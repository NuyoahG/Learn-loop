"""硬截止：超时终止的是解析进程，而不只是停止 HTTP 等待。"""
import json
import multiprocessing as mp
from pathlib import Path
from shutil import copyfileobj
from tempfile import TemporaryDirectory
import time
from typing import BinaryIO
from app.models import MAX_RESPONSE, ParseError


def child_parse(connection, data: bytes | Path, file_type: str, attempt: str):
    try:
        from app.parsers import parse
        if isinstance(data, Path):
            with data.open("rb") as source:
                result = parse(source, file_type, attempt)
        else:
            result = parse(data, file_type, attempt)
        payload = {"ok": True, "result": result.model_dump()}
    except ParseError as exc:
        payload = {"ok": False, "status": exc.status, "errorCode": exc.code, "message": exc.message}
    except BaseException:
        payload = {"ok": False, "status": 500, "errorCode": "PARSER_FAILED", "message": "解析未完成，请检查资料后重试。"}
    try:
        encoded = json.dumps(payload, ensure_ascii=False).encode("utf-8")
        if len(encoded) > MAX_RESPONSE:
            encoded = b'{"ok":false,"status":413,"errorCode":"RESULT_TOO_LARGE","message":"Result exceeds limit."}'
        connection.send_bytes(encoded)
    finally:
        connection.close()


def run_uploaded_parse(source: BinaryIO, file_type: str, attempt: str):
    # Windows spawn 无法复用上传流。复制到由本次请求管理的临时原件，只把路径传给子进程。
    # run_parse 会先回收子进程，退出目录上下文时才删除文件。
    with TemporaryDirectory(prefix="learnloop-parse-") as directory:
        path = Path(directory) / "material.bin"
        with path.open("xb") as target:
            copyfileobj(source, target, length=64 * 1024)
        return run_parse(path, file_type, attempt)


def run_parse(data: bytes | Path, file_type: str, attempt: str, *, timeout: float = 30.0, worker=child_parse):
    # Windows 使用 spawn，不继承线程状态；worker 参数只用于单元测试故障注入，不是 HTTP 开关。
    context = mp.get_context("spawn")
    receive, send = context.Pipe(duplex=False)
    process = context.Process(target=worker, args=(send, data, file_type, attempt), daemon=True)
    deadline = time.monotonic() + timeout
    try:
        process.start()
        send.close()
        if not receive.poll(max(0, deadline - time.monotonic())):
            raise ParseError(504, "PARSER_TIMEOUT", "解析超过 30 秒，请拆分资料后重试。")
        # 先读管道再 join，避免大结果塞满管道而父子互相等待。
        payload = json.loads(receive.recv_bytes(MAX_RESPONSE))
        if not payload["ok"]:
            raise ParseError(payload["status"], payload["errorCode"], payload["message"])
        return payload["result"]
    except ParseError:
        raise
    except Exception:
        raise ParseError(500, "PARSER_FAILED", "解析进程未正常完成，请重试。") from None
    finally:
        send.close()
        receive.close()
        if process.pid is not None:
            process.join(timeout=0.2)
            if process.is_alive():
                process.terminate()
                process.join(timeout=1)
            if process.is_alive():
                process.kill()
                process.join(timeout=1)
            process.close()
