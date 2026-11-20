"""格式提取仅在可终止的子进程里执行。不会下载链接、渲染 HTML 或执行宏。"""
from io import BytesIO, SEEK_END
from typing import BinaryIO
from zipfile import ZipFile, BadZipFile
import logging
import re
from docx import Document
from docx.table import Table
from docx.text.paragraph import Paragraph
from pypdf import PdfReader
from app.pdf_noise import pdf_noise_ranges
from app.models import (
    Block, DOCUMENT_FILE_LIMIT, DOCX_EXPANDED_LIMIT, PDF_CHARACTER_LIMIT, PDF_PAGE_LIMIT,
    ParseError, ParseResult, TEXT_CHARACTER_LIMIT, TEXT_FILE_LIMIT, Warning,
)

logging.getLogger("pypdf").setLevel(logging.CRITICAL)


class UnencryptedPdfReader(PdfReader):
    def _handle_encryption(self, password):
        # pypdf 6.21.2 构造器默认也会尝试空密码。项目统一拒绝加密资料，
        # 在此钩子直接退出，既不尝试解密，也不为拒绝 AES 文件额外安装密码库。
        # 这是受锁定版本约束的内部钩子；升级 pypdf 必须回归 RC4/AES 加密样例。
        raise ParseError(422, "PDF_ENCRYPTED", "当前不支持加密 PDF，请上传未加密的自有资料。")


class Builder:
    def __init__(self, character_limit: int):
        self.character_limit = character_limit
        self.blocks: list[Block] = []
        self.count = 0
        self.warnings: list[Warning] = []

    def add(self, text: str, kind: str, locator: dict, noise_ranges=None):
        text = text.replace("\r\n", "\n").replace("\r", "\n").replace("\x00", "")
        if not text.strip():
            return
        self.count += len(text)
        if self.count > self.character_limit:
            raise ParseError(413, "TEXT_LIMIT_EXCEEDED", f"提取文字超过 {self.character_limit} 字符，请拆分资料。")
        # 机械拆分保持原文及定位，不在这里改写或做语义切分。
        for part, start in enumerate(range(0, len(text), 4000), 1):
            piece = text[start:start + 4000]
            if not piece.strip():
                self.count -= len(piece)
                continue
            if len(self.blocks) >= 2000:
                raise ParseError(413, "BLOCK_LIMIT_EXCEEDED", "正文块过多，请拆分资料。")
            ranges = [[max(left, start) - start, min(right, start + len(piece)) - start]
                      for left, right in (noise_ranges or []) if left < start + len(piece) and right > start]
            self.blocks.append(Block(blockNo=len(self.blocks) + 1, blockType=kind, noiseRanges=ranges,
                                     text=piece, sourceLocator={**locator, "partIndex": part}))


def text_blocks(data: bytes, out: Builder):
    try:
        text = data.decode("utf-8-sig", errors="strict")
    except UnicodeDecodeError:
        raise ParseError(422, "INVALID_ENCODING", "文本必须为 UTF-8 编码，请转换后重新上传。") from None
    text = text.replace("\r\n", "\n").replace("\r", "\n").replace("\x00", "")
    lines = text.split("\n")
    start = 0
    # 空行分段，代码缩进、Markdown 标记与 <script> 字样均作为原文保留。
    for end in range(len(lines) + 1):
        if end == len(lines) or not lines[end].strip():
            if start < end:
                out.add("\n".join(lines[start:end]), "text",
                        {"kind": "text", "lineStart": start + 1, "lineEnd": end})
            start = end + 1


def docx_blocks(source: BinaryIO, out: Builder):
    try:
        # 不解压到磁盘；除声明值外，逐条实际读取也计入展开上限。
        with ZipFile(source) as package:
            entries = package.infolist()
            names = [x.filename for x in entries]
            if len(entries) > 1000 or len(names) != len(set(names)):
                raise ParseError(413, "DOCX_LIMIT_EXCEEDED", "Word 包含过多或重复条目。")
            if not {"[Content_Types].xml", "word/document.xml", "_rels/.rels"}.issubset(names):
                raise ParseError(422, "INVALID_DOCX", "文件不是有效的 DOCX 正文文档。")
            total = 0
            for info in entries:
                if info.flag_bits & 1 or info.file_size > DOCX_EXPANDED_LIMIT - total:
                    raise ParseError(413, "DOCX_LIMIT_EXCEEDED", "Word 展开内容超出限制。")
                with package.open(info) as stream:
                    prefix = b""
                    while chunk := stream.read(65536):
                        total += len(chunk)
                        if total > DOCX_EXPANDED_LIMIT:
                            raise ParseError(413, "DOCX_LIMIT_EXCEEDED", "Word 展开内容超过 128 MiB。")
                        # XML 是有限大小的，检测跨块 DOCTYPE/ENTITY 声明。
                        if info.filename.endswith((".xml", ".rels")):
                            segment = prefix + chunk
                            if re.search(br"<!\s*(DOCTYPE|ENTITY)", segment, re.I):
                                raise ParseError(422, "INVALID_DOCX", "不支持包含外部实体声明的 Word。")
                            prefix = segment[-32:]
        source.seek(0)
        doc = Document(source)
        for index, item in enumerate(doc.iter_inner_content(), 1):
            if isinstance(item, Paragraph):
                out.add(item.text, "paragraph", {"kind": "docx", "bodyBlockIndex": index})
            elif isinstance(item, Table):
                for row_index, row in enumerate(item.rows, 1):
                    cells = [cell.text for cell in row.cells]
                    if not any(cell.strip() for cell in cells):
                        continue
                    out.add("\t".join(cells), "table_row",
                            {"kind": "docx", "bodyBlockIndex": index, "tableRowIndex": row_index})
        out.warnings.append(Warning(code="DOCX_LAYOUT_LIMITED", message="仅提取正文和基础表格；图片、文本框、公式、脚注和修订可能未提取。"))
    except ParseError:
        raise
    except Exception:
        raise ParseError(422, "INVALID_DOCX", "Word 结构损坏或格式不受支持，请另存为 DOCX 后上传。") from None


def pdf_blocks(source: BinaryIO, out: Builder):
    try:
        if source.read(5) != b"%PDF-":
            raise ParseError(422, "INVALID_PDF", "文件不是有效的 PDF。")
        source.seek(0)
        reader = UnencryptedPdfReader(source)
        if reader.is_encrypted:
            raise ParseError(422, "PDF_ENCRYPTED", "当前不支持加密 PDF，请上传未加密的自有资料。")
        if len(reader.pages) > PDF_PAGE_LIMIT:
            raise ParseError(413, "PDF_PAGE_LIMIT_EXCEEDED", f"PDF 超过 {PDF_PAGE_LIMIT} 页，请拆分上传。")
        missing = []
        for number, page in enumerate(reader.pages, 1):
            text = page.extract_text() or ""
            if not text.strip():
                missing.append(number)
            out.add(text, "pdf_text", {"kind": "pdf", "pageNumber": number}, pdf_noise_ranges(page, text))
        if missing:
            out.warnings.append(Warning(code="PDF_PAGE_WITHOUT_TEXT",
                message="部分页面没有可提取文字，可能含扫描内容；当前未启用 OCR。", pageNumbers=missing))
        out.warnings.append(Warning(code="PDF_LAYOUT_LIMITED", message="提取的是现有文字；多栏、表格与公式的顺序及完整性需要人工核对。"))
    except ParseError:
        raise
    except Exception:
        raise ParseError(422, "INVALID_PDF", "PDF 损坏或无法提取，请检查原文件。") from None


def parse(data: bytes | BinaryIO, file_type: str, attempt: str) -> ParseResult:
    # HTTP 大文件从临时原件按需读取；小型测试和直接调用仍可传 bytes。
    source = BytesIO(data) if isinstance(data, bytes) else data
    size = source.seek(0, SEEK_END)
    source.seek(0)
    limit = TEXT_FILE_LIMIT if file_type in ("txt", "md") else DOCUMENT_FILE_LIMIT
    if size == 0:
        raise ParseError(422, "EMPTY_FILE", "文件为空。")
    if size > limit:
        raise ParseError(413, "FILE_TOO_LARGE", "文件超过此格式的大小限制。")
    # PDF 支持更长的正文；正文块、单次返回字节数和解析超时仍由独立限制保护。
    out = Builder(PDF_CHARACTER_LIMIT if file_type == "pdf" else TEXT_CHARACTER_LIMIT)
    if file_type in ("txt", "md"):
        text_bytes = source.read()
        if text_bytes.startswith((b"PK\x03\x04", b"%PDF-", b"\xd0\xcf\x11\xe0")):
            raise ParseError(422, "INVALID_TEXT", "内容与文本格式不符。")
        text_blocks(text_bytes, out)
    elif file_type == "docx":
        docx_blocks(source, out)
    elif file_type == "pdf":
        pdf_blocks(source, out)
    else:
        raise ParseError(415, "UNSUPPORTED_TYPE", "仅支持 txt、md、docx、pdf。")
    if not out.blocks:
        code = "EMPTY_TEXT" if file_type in ("txt", "md") else "NO_EXTRACTABLE_TEXT"
        raise ParseError(422, code, "未提取到可用文字，可能含图片或扫描内容；当前未启用 OCR。")
    if out.count < 30:
        out.warnings.append(Warning(code="LITTLE_TEXT", message="提取文字较少，请人工核对是否完整。"))
    return ParseResult(attemptId=attempt, fileType=file_type, blocks=out.blocks,
                       textCharCount=out.count, warnings=out.warnings)
