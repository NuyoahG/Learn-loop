// Range 的 DOM 偏移和 String.slice 都采用 UTF-16；利用段落起点测量，包含已渲染高亮中的字符。
export function captureAnchor(selection, root, reader) {
    if (!selection || selection.isCollapsed || selection.rangeCount !== 1) return null;
    const range = selection.getRangeAt(0);
    const textBlock = node => (node.nodeType === 1 ? node : node.parentElement)?.closest("[data-reader-text]");
    const startBlock = textBlock(range.startContainer);
    const endBlock = textBlock(range.endContainer);
    if (!startBlock || startBlock !== endBlock || !root.contains(startBlock)) return null;
    const block = reader.blocks.records.find(item => String(item.blockId) === startBlock.dataset.readerText);
    if (!block) return null;
    const measure = range.cloneRange();
    measure.selectNodeContents(startBlock);
    measure.setEnd(range.startContainer, range.startOffset);
    const start = measure.toString().length;
    const quote = range.toString();
    const end = start + quote.length;
    if (!quote.trim() || [...quote].length > 1200 || block.text.slice(start, end) !== quote) return null;
    return { materialId: reader.materialId, parseAttempt: reader.parseAttempt, blockId: block.blockId, start, end, quote };
}

export function selectionMenuPosition(selection) {
    // 选择可见的末行，避免多行选区的外接框把按钮放到远离文字的位置。
    const rect = Array.from(selection.getRangeAt(0).getClientRects()).findLast(line =>
        line.width > 0 && line.height > 0 && line.bottom >= 0 && line.top <= window.innerHeight);
    if (!rect) return null;
    const above = rect.bottom + 48 > window.innerHeight && rect.top >= 48;
    return {
        left: Math.max(8, Math.min(rect.left, document.documentElement.clientWidth - 134)),
        top: above ? rect.top - 7 : rect.bottom + 7,
        above
    };
}

export function highlightParts(text, highlights) {
    const parts = [];
    let offset = 0;
    for (const h of highlights) {
        if (h.start < offset || h.end <= h.start || text.slice(h.start, h.end) !== h.quote) continue;
        parts.push({ text: text.slice(offset, h.start) }, { text: h.quote, highlight: h });
        offset = h.end;
    }
    parts.push({ text: text.slice(offset) });
    return parts;
}
