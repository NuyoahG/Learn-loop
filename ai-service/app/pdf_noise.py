"""从 PDF 图形状态识别低透明度水印，只返回原文区间，不改原件或阅读正文。"""
import copy
import math
import re
from collections import Counter
from difflib import SequenceMatcher

from pypdf.generic import ArrayObject, ByteStringObject, ContentStream, DecodedStreamObject, DictionaryObject, NameObject, TextStringObject


def _original_bytes(value):
    # PDF 字体编码并非 Unicode。重建临时内容流时保留字符串原字节，避免中文被再次编码损坏。
    if isinstance(value, TextStringObject):
        try:
            return ByteStringObject(value.original_bytes)
        except Exception:
            return value
    if isinstance(value, ArrayObject):
        return ArrayObject(_original_bytes(item) for item in value)
    return value


def _signature(operator, operands):
    values = operands[0] if operator == b"TJ" else operands[-1:]
    return "".join(str(value) for value in values if isinstance(value, (str, bytes)))


def _filter(stream, resources, pdf, inherited=(1.0, 1.0, 0, False), depth=0):
    if depth > 8:
        return stream, resources, 0
    resources = resources.get_object() if hasattr(resources, "get_object") else resources
    detached = DecodedStreamObject()
    detached.set_data(stream.get_data())
    content = ContentStream(detached, pdf)
    counts = Counter(_signature(op, args) for args, op in content.operations if op in (b"Tj", b"TJ", b"'", b'"'))
    forms = Counter(str(args[0]) for args, op in content.operations if op == b"Do")
    local_resources = DictionaryObject(dict(resources))
    original_xobjects = resources.get("/XObject", {})
    original_xobjects = original_xobjects.get_object() if hasattr(original_xobjects, "get_object") else original_xobjects
    xobjects = DictionaryObject(dict(original_xobjects))
    if xobjects:
        local_resources[NameObject("/XObject")] = xobjects
    fill, stroke, mode, diagonal = inherited
    stack, operations, removed = [], [], 0
    for operands, operator in content.operations:
        if operator == b"q":
            stack.append((fill, stroke, mode, diagonal))
        elif operator == b"Q" and stack:
            fill, stroke, mode, diagonal = stack.pop()
        elif operator == b"gs":
            states = resources.get("/ExtGState", {})
            states = states.get_object() if hasattr(states, "get_object") else states
            state = states.get(operands[0], {})
            state = state.get_object() if hasattr(state, "get_object") else state
            fill = float(state.get("/ca", fill))
            stroke = float(state.get("/CA", stroke))
        elif operator == b"Tr":
            mode = int(operands[0])
        elif operator in (b"cm", b"Tm"):
            angle = abs(math.degrees(math.atan2(float(operands[1]), float(operands[0])))) % 90
            diagonal = diagonal or 10 < angle < 80
        elif operator == b"Do" and operands[0] in xobjects:
            original = xobjects[operands[0]].get_object()
            if original.get("/Subtype") == "/Form":
                matrix = original.get("/Matrix", [1, 0, 0, 1, 0, 0])
                angle = abs(math.degrees(math.atan2(float(matrix[1]), float(matrix[0])))) % 90
                form_watermark = diagonal or 10 < angle < 80 or forms[str(operands[0])] >= 3
                filtered, form_resources, count = _filter(original, original.get("/Resources", resources), pdf,
                                                         (fill, stroke, mode, form_watermark), depth + 1)
                if count:
                    form = DecodedStreamObject()
                    form.update({key: value for key, value in original.items()
                                 if key not in ("/Length", "/Filter", "/DecodeParms", "/Resources")})
                    form.set_data(filtered.get_data())
                    form[NameObject("/Resources")] = form_resources
                    xobjects[operands[0]] = form
                    removed += count
        if operator in (b"Tj", b"TJ", b"'", b'"'):
            # 透明度是辅助信号；仅低透明且倾斜、重复或带水印提示的文字才进入噪声视图。
            # Tr=3 常是扫描件的隐藏 OCR 正文，必须保留。
            alpha = stroke if mode == 1 else max(fill, stroke) if mode == 2 else fill
            signature = _signature(operator, operands)
            watermark = (diagonal or counts[signature] >= 3
                         or re.search(r"watermark|confidential|draft|水印|仅供|内部资料", signature, re.I))
            if mode != 3 and 0 <= alpha <= .35 and watermark:
                removed += 1
                # 保留文字换行/间距操作，只清空显示文字。此流仅在内存里用于找噪声区间。
                if operator == b"TJ":
                    operands = [ArrayObject()]
                else:
                    operands = [*operands[:-1], TextStringObject("")]
        operations.append(([_original_bytes(item) for item in operands], operator))
    content.operations = operations
    return content, local_resources, removed


def pdf_noise_ranges(page, original):
    """过滤副本和原文对齐，只接受删除区间；复杂版式无法可靠对齐时保守保留。"""
    try:
        stream = page.get_contents()
        if stream is None:
            return []
        filtered, resources, count = _filter(stream, page.get("/Resources", {}), page.pdf)
        if not count:
            return []
        preview = copy.copy(page)
        preview[NameObject("/Contents")] = filtered
        preview[NameObject("/Resources")] = resources
        clean = (preview.extract_text() or "").replace("\r\n", "\n").replace("\r", "\n").replace("\x00", "")
        raw = original.replace("\r\n", "\n").replace("\r", "\n").replace("\x00", "")
        compact_raw = iter(char for char in raw if not char.isspace())
        # 过滤副本只能删除原有文字，不能替换字形或引入新字；异常编码/重排时保守保留全文。
        if not all(any(found == char for found in compact_raw) for char in clean if not char.isspace()):
            return []
        ranges = []
        # 中文常用字和重复水印不应被当作 junk，否则正文对齐会丢失可靠匹配。
        for tag, start, end, new_start, new_end in SequenceMatcher(None, raw, clean, autojunk=False).get_opcodes():
            if tag in ("delete", "replace") and raw[start:end].strip() and not clean[new_start:new_end].strip():
                ranges.append([start, end])
        return ranges[:1000]
    except (KeyError, ValueError, TypeError, AttributeError, IndexError, RecursionError):
        # shortcut: 复杂 Form/坐标解析失败时不删正文；有样本证据后再扩展过滤规则。
        return []
