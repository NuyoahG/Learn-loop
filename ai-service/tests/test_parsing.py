"""独立测试不依赖 MySQL；真实文档样例另由集成验收检查。"""
from io import BytesIO
import multiprocessing as mp
import time
from zipfile import ZipFile
import pytest
from docx import Document
from pypdf import PdfWriter
from fastapi.testclient import TestClient
from app.main import PARSE_BODY_LIMIT, create_app
from app.models import PDF_PAGE_LIMIT, ParseError
from app.parsers import parse
from app.runner import run_parse


def document_bytes():
    doc = Document()
    doc.add_paragraph("第一段 A")
    table = doc.add_table(rows=1, cols=2)
    table.cell(0, 0).text = "左格"
    table.cell(0, 1).text = "右格"
    doc.add_paragraph("末段 B")
    buffer = BytesIO()
    doc.save(buffer)
    return buffer.getvalue()


def test_text_bom_lines_and_literal_script():
    value = parse(b"\xef\xbb\xbfA\r\nB\r\n\r\n<script>literal</script>", "md", "1:1")
    assert [b.text for b in value.blocks] == ["A\nB", "<script>literal</script>"]
    assert value.blocks[0].sourceLocator == {"kind": "text", "lineStart": 1, "lineEnd": 2, "partIndex": 1}
    assert value.blocks[1].sourceLocator["lineStart"] == 4


def test_unicode_splits_count_codepoints():
    value = parse(("😀" * 4001).encode(), "txt", "1:2")
    assert [len(b.text) for b in value.blocks] == [4000, 1]
    assert value.textCharCount == 4001
    assert value.blocks[1].sourceLocator["partIndex"] == 2


@pytest.mark.parametrize("data,kind,code", [
    (b"\xff", "txt", "INVALID_ENCODING"), (b" \n\t\x00", "txt", "EMPTY_TEXT"),
    (b"", "txt", "EMPTY_FILE"), (b"%PDF-pretending", "txt", "INVALID_TEXT"),
    (b"A" * 200001, "txt", "TEXT_LIMIT_EXCEEDED"),
    (b"A\n\n" * 2001, "txt", "BLOCK_LIMIT_EXCEEDED"),
    (b"A" * (2 * 1024 * 1024 + 1), "md", "FILE_TOO_LARGE"),
    (b"bad", "docx", "INVALID_DOCX"), (b"%PDF-broken", "pdf", "INVALID_PDF"),
], ids=["encoding", "blank", "empty", "disguised", "chars", "blocks", "bytes", "docx", "pdf"])
def test_failure(data, kind, code):
    # 参数化覆盖不同业务原因，不能把任何异常都当成预期失败。
    with pytest.raises(ParseError) as captured:
        parse(data, kind, "1:1")
    assert captured.value.code == code


def test_docx_preserves_mixed_order_and_source():
    value = parse(document_bytes(), "docx", "2:1")
    assert [b.text for b in value.blocks] == ["第一段 A", "左格\t右格", "末段 B"]
    assert value.blocks[1].sourceLocator == {"kind": "docx", "bodyBlockIndex": 2, "tableRowIndex": 1, "partIndex": 1}


@pytest.mark.parametrize("encrypted,pages,expected", [
    (True, 1, "PDF_ENCRYPTED"),
    (False, PDF_PAGE_LIMIT + 1, "PDF_PAGE_LIMIT_EXCEEDED"),
    (False, 1, "NO_EXTRACTABLE_TEXT"),
])
def test_pdf_rejections(encrypted, pages, expected):
    writer = PdfWriter()
    for _ in range(pages):
        writer.add_blank_page(width=200, height=200)
    if encrypted:
        writer.encrypt("fixture-only")
    buffer = BytesIO()
    writer.write(buffer)
    with pytest.raises(ParseError) as captured:
        parse(buffer.getvalue(), "pdf", "3:1")
    assert captured.value.code == expected


def test_docx_entry_limit():
    buffer = BytesIO()
    with ZipFile(buffer, "w") as package:
        for i in range(1001):
            package.writestr(str(i), b"x")
    with pytest.raises(ParseError) as captured:
        parse(buffer.getvalue(), "docx", "4:1")
    assert captured.value.code == "DOCX_LIMIT_EXCEEDED"


def slow_worker(connection, data, kind, attempt):
    time.sleep(10)


def test_timeout_reaps_process_and_next_parse_succeeds():
    before = {p.pid for p in mp.active_children()}
    with pytest.raises(ParseError) as captured:
        run_parse(b"abc", "txt", "1:1", timeout=0.3, worker=slow_worker)
    assert captured.value.code == "PARSER_TIMEOUT"
    assert {p.pid for p in mp.active_children()} == before
    assert run_parse(b"next valid document", "txt", "1:2")["blocks"][0]["text"] == "next valid document"


def test_large_pipe_result_does_not_deadlock():
    result = run_parse(("中" * 180000).encode(), "txt", "1:3")
    assert result["textCharCount"] == 180000


def test_http_auth_and_valid_form():
    with TestClient(create_app("k" * 40)) as client:
        assert client.get("/health").json()["status"] == "UP"
        assert client.post("/internal/parse", content=b"not multipart").status_code == 401
        response = client.post("/internal/parse", headers={"X-LearnLoop-Service-Key": "k" * 40},
            data={"fileType": "txt", "attemptId": "8:1"}, files={"file": ("test.txt", b"literal text")})
        assert response.status_code == 200
        assert response.json()["attemptId"] == "8:1"
        duplicate = client.post("/internal/parse", headers={"X-LearnLoop-Service-Key": "k" * 40},
            data={"fileType": "txt", "attemptId": "8:1"}, files=[("file", ("a.txt", b"a")), ("file", ("b.txt", b"b"))])
        assert duplicate.status_code == 400


def test_missing_server_key_rejects_startup():
    with pytest.raises(RuntimeError):
        with TestClient(create_app("")):
            pass


def test_http_wrong_key_and_request_size_limit():
    with TestClient(create_app("k" * 40)) as client:
        assert client.post("/internal/parse", headers={"X-LearnLoop-Service-Key": "wrong"}, content=b"bad").status_code == 401
        response = client.post("/internal/parse", headers={
            "X-LearnLoop-Service-Key": "k" * 40, "Content-Length": str(PARSE_BODY_LIMIT + 1)
        }, content=b"X")
        assert response.status_code == 413
        assert response.json()["errorCode"] == "REQUEST_TOO_LARGE"


def test_chunked_multipart_still_counts_actual_bytes(monkeypatch):
    import app.main as main

    monkeypatch.setattr(main, "PARSE_BODY_LIMIT", 1024)
    with TestClient(create_app("k" * 40)) as client:
        response = client.post("/internal/parse", headers={
            "X-LearnLoop-Service-Key": "k" * 40,
            "Content-Type": "multipart/form-data; boundary=fixture"
        }, content=iter([
            b'--fixture\r\nContent-Disposition: form-data; name="file"; filename="test.pdf"\r\n\r\n',
            b"x" * 1024, b"\r\n--fixture--\r\n"
        ]))
        assert response.status_code == 413
        assert response.json()["errorCode"] == "REQUEST_TOO_LARGE"


def test_busy_service_refuses_second_job_and_recovers(monkeypatch):
    import threading
    from concurrent.futures import ThreadPoolExecutor
    import app.main as main
    entered, release = threading.Event(), threading.Event()

    def controlled_parse(data, kind, attempt):
        entered.set()
        assert release.wait(5)
        return parse(data, kind, attempt).model_dump()

    monkeypatch.setattr(main, "run_uploaded_parse", controlled_parse)
    with TestClient(create_app("k" * 40)) as client, ThreadPoolExecutor(max_workers=1) as threads:
        def send():
            return client.post("/internal/parse", headers={"X-LearnLoop-Service-Key": "k" * 40},
                data={"fileType": "txt", "attemptId": "8:2"}, files={"file": ("a.txt", b"valid")})
        first = threads.submit(send)
        try:
            assert entered.wait(3)
            assert send().status_code == 503
        finally:
            release.set()
        assert first.result(timeout=3).status_code == 200
        assert send().status_code == 200
