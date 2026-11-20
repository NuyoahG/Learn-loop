"""Java/Python 的版本化契约；Unicode 字数均按码点计算。"""
from typing import Literal
from pydantic import BaseModel, ConfigDict, Field

VERSION = "learnloop-parser/0.1.0"
MAX_RESPONSE = 2 * 1024 * 1024
TEXT_FILE_LIMIT = 2 * 1024 * 1024
DOCUMENT_FILE_LIMIT = 100 * 1024 * 1024
DOCX_EXPANDED_LIMIT = 128 * 1024 * 1024
PDF_PAGE_LIMIT = 1000
TEXT_CHARACTER_LIMIT = 200_000
PDF_CHARACTER_LIMIT = 2_000_000


class StrictModel(BaseModel):
    model_config = ConfigDict(extra="forbid", strict=True)


class Warning(StrictModel):
    code: str = Field(min_length=1, max_length=64)
    message: str = Field(min_length=1, max_length=255)
    pageNumbers: list[int] = Field(default_factory=list, max_length=PDF_PAGE_LIMIT)


class Block(StrictModel):
    blockNo: int = Field(ge=1, le=2000)
    blockType: Literal["text", "paragraph", "table_row", "pdf_text"]
    text: str = Field(min_length=1, max_length=4000)
    sourceLocator: dict[str, str | int]
    noiseRanges: list[list[int]] = Field(default_factory=list, max_length=1000)


class ParseResult(StrictModel):
    schemaVersion: Literal[1] = 1
    attemptId: str = Field(min_length=1, max_length=64)
    fileType: Literal["txt", "md", "docx", "pdf"]
    parserVersion: str = VERSION
    blocks: list[Block] = Field(min_length=1, max_length=2000)
    textCharCount: int = Field(ge=1, le=PDF_CHARACTER_LIMIT)
    warnings: list[Warning] = Field(default_factory=list, max_length=20)


class ParseError(Exception):
    """只让受控文案穿过进程和 HTTP 边界，不返回库异常或磁盘路径。"""
    def __init__(self, status: int, code: str, message: str):
        self.status, self.code, self.message = status, code, message
        super().__init__(code)
