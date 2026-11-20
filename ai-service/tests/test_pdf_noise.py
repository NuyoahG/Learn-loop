from io import BytesIO
import json

import pytest
from pypdf import PdfReader, PdfWriter
from pypdf.generic import ArrayObject, DictionaryObject, NameObject, DecodedStreamObject, FloatObject, NumberObject, TextStringObject

from app.generation import Source
from app.model_sources import evidence_sources
from app.parsers import parse
from app.pdf_noise import pdf_noise_ranges


def sample(opacity=.15, diagonal=True, rendering=0):
    writer = PdfWriter()
    page = writer.add_blank_page(width=600, height=800)
    font = DictionaryObject({NameObject("/Type"): NameObject("/Font"), NameObject("/Subtype"): NameObject("/Type1"),
                             NameObject("/BaseFont"): NameObject("/Helvetica")})
    gs = DictionaryObject({NameObject("/ca"): FloatObject(opacity), NameObject("/CA"): FloatObject(opacity)})
    page[NameObject("/Resources")] = DictionaryObject({NameObject("/Font"): DictionaryObject({NameObject("/F1"): font}),
        NameObject("/ExtGState"): DictionaryObject({NameObject("/G1"): gs})})
    matrix = ".707 .707 -.707 .707" if diagonal else "1 0 0 1"
    stream = DecodedStreamObject()
    stream.set_data(("BT /F1 12 Tf 1 0 0 1 20 700 Tm (The original body is always retained.) Tj ET\n"
                     f"q /G1 gs BT /F1 20 Tf {rendering} Tr {matrix} 60 200 Tm (CONFIDENTIAL WATERMARK) Tj ET Q\n"
                     "BT /F1 12 Tf 1 0 0 1 20 600 Tm (A second body paragraph remains unchanged.) Tj ET").encode())
    page[NameObject("/Contents")] = stream
    output = BytesIO()
    writer.write(output)
    return output.getvalue()


def test_transparent_watermark_only_disappears_from_model_view():
    data = sample()
    page = PdfReader(BytesIO(data)).pages[0]
    before = page.get_contents().get_data()
    raw = page.extract_text()
    ranges = pdf_noise_ranges(page, raw)
    assert ranges
    views, _ = evidence_sources([Source(blockId=1, text=raw, noiseRanges=json.dumps(ranges))])
    model_text = "".join(unit["text"] for unit in views[0]["evidence"])
    assert "WATERMARK" not in model_text
    assert "original body" in model_text and "second body" in model_text
    assert page.extract_text() == raw and page.get_contents().get_data() == before
    result = parse(data, "pdf", "noise:test")
    assert "WATERMARK" in result.blocks[0].text and result.blocks[0].noiseRanges
    assert result.blocks[0].sourceLocator["pageNumber"] == 1


@pytest.mark.parametrize("opacity,rendering", [(1, 0), (.15, 3)])
def test_opaque_text_and_invisible_ocr_are_not_removed(opacity, rendering):
    page = PdfReader(BytesIO(sample(opacity=opacity, rendering=rendering))).pages[0]
    assert pdf_noise_ranges(page, page.extract_text()) == []


def test_noise_spans_are_codepoint_based_and_cannot_join_false_quotes():
    raw = "😀原始正文保持不变。水印内容。后面的另一段正文也不改变。"
    start, end = raw.index("水印"), raw.index("后面")
    views, evidence = evidence_sources([Source(blockId=9, text=raw, noiseRanges=json.dumps([[start, end]]))])
    assert len(views[0]["evidence"]) == 2
    assert all("水印" not in quote for _, quote in evidence.values())
    assert evidence["B9E1"][1] == raw[:start]


def test_filter_preserves_original_font_bytes_for_chinese_text():
    page = PdfReader(BytesIO(sample())).pages[0]
    cmap = DecodedStreamObject()
    cmap.set_data(b"/CIDInit /ProcSet findresource begin 12 dict begin begincmap /CMapType 2 def "
                  b"1 begincodespacerange <0000> <FFFF> endcodespacerange "
                  b"2 beginbfchar <0001> <6B63> <0002> <6587> endbfchar endcmap end end")
    cid = DictionaryObject({NameObject("/Type"): NameObject("/Font"), NameObject("/Subtype"): NameObject("/CIDFontType2"),
                            NameObject("/BaseFont"): NameObject("/TestChinese"), NameObject("/DW"): NumberObject(1000),
                            NameObject("/CIDSystemInfo"): DictionaryObject({NameObject("/Registry"): TextStringObject("Adobe"),
                                NameObject("/Ordering"): TextStringObject("Identity"), NameObject("/Supplement"): NumberObject(0)})})
    page["/Resources"]["/Font"][NameObject("/F2")] = DictionaryObject({NameObject("/Type"): NameObject("/Font"),
        NameObject("/Subtype"): NameObject("/Type0"), NameObject("/BaseFont"): NameObject("/TestChinese"),
        NameObject("/Encoding"): NameObject("/Identity-H"), NameObject("/DescendantFonts"): ArrayObject([cid]),
        NameObject("/ToUnicode"): cmap})
    content = DecodedStreamObject()
    content.set_data(page.get_contents().get_data() + b"\nBT /F2 12 Tf 1 0 0 1 20 400 Tm <0001000200010002> Tj ET")
    page[NameObject("/Contents")] = content
    raw = page.extract_text()
    assert "正文正文" in raw
    ranges = pdf_noise_ranges(page, raw)
    assert ranges
    views, _ = evidence_sources([Source(blockId=1, text=raw, noiseRanges=json.dumps(ranges))])
    visible = "".join(unit["text"] for unit in views[0]["evidence"])
    assert "WATERMARK" not in visible and "正文正文" in visible
    assert page.extract_text() == raw
