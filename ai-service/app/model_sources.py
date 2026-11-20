"""只准备模型视图；原始正文、字符偏移和阅读高亮始终保持不变。"""
import json
import re
from collections import Counter


def noise_ranges(source):
    """解析器记录的是 Unicode 码点区间；旧资料仅保守过滤高度重复的水印提示行。"""
    text = source.text
    ranges = json.loads(source.noiseRanges) if source.noiseRanges else []
    previous = 0
    if not isinstance(ranges, list) or len(ranges) > 1000:
        raise ValueError("Invalid model-view ranges")
    for pair in ranges:
        if (not isinstance(pair, list) or len(pair) != 2 or any(type(n) is not int for n in pair)
                or not previous <= pair[0] < pair[1] <= len(text)):
            raise ValueError("Invalid model-view range")
        previous = pair[1]
    lines = list(re.finditer(r"[^\n]+(?:\n|$)", text))
    counts = Counter(line.group().strip() for line in lines)
    for line in lines:
        value = line.group().strip()
        if (4 <= len(value) <= 80 and counts[value] >= 6
                and re.search(r"水印|仅供|内部资料|禁止传播|confidential|watermark|\bdraft\b", value, re.I)):
            ranges.append([line.start(), line.end()])
    merged = []
    for start, end in sorted(ranges):
        if merged and start <= merged[-1][1]:
            merged[-1][1] = max(end, merged[-1][1])
        else:
            merged.append([start, end])
    return merged


def evidence_sources(sources):
    """每个编号对应连续原文，跨水印或分片边界不能拼成虚假的连续引文。"""
    views, evidence = [], {}
    for source in sorted(sources, key=lambda item: item.blockId):
        cursor, parts = 0, []
        for start, end in [*noise_ranges(source), [len(source.text), len(source.text)]]:
            clean = source.text[cursor:start]
            # 小证据片保留原始空白；模型可选片内更短的 quote，不能改变正文。
            for offset in range(0, len(clean), 300):
                quote = clean[offset:offset + 300]
                if not quote.strip() or len(quote) < 8:
                    continue
                ref = f"B{source.blockId}E{len(parts) + 1}"
                evidence[ref] = (source.blockId, quote)
                parts.append({"evidenceRef": ref, "text": quote})
            cursor = end
        views.append({"blockId": source.blockId, "evidence": parts})
    return views, evidence


def restore_evidence(item, evidence, quote_field):
    ref = item.pop("evidenceRef", None)
    if ref is None:
        return  # 兼容旧模型输出，仍由原有严格来源校验兜底。
    if ref not in evidence:
        raise ValueError("Unknown evidence reference")
    block, quote = evidence[ref]
    if "blockId" in item and item["blockId"] != block:
        raise ValueError("Evidence block mismatch")
    supplied = item.get(quote_field)
    if supplied is not None and (not isinstance(supplied, str) or supplied not in quote):
        raise ValueError("Quote outside evidence")
    item["blockId"] = block
    item[quote_field] = quote if supplied is None else supplied
