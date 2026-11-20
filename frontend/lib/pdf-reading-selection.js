// PDF.js 与解析器的换行和空格可能不同。只忽略空白，仍用解析正文的原样引文和 UTF-16 偏移。
export function matchPdfAnchor(records, pageNumber, selectedText, materialId, parseAttempt) {
    const needle = selectedText.replace(/\s/gu, "");
    if (!needle) return null;
    let anchor = null;
    for (const block of records) {
        if (block.sourceLocator?.pageNumber !== pageNumber) continue;
        const offsets = [];
        let compact = "";
        for (let i = 0; i < block.text.length; i++) {
            if (/\s/u.test(block.text[i])) continue;
            compact += block.text[i];
            offsets.push(i);
        }
        const index = compact.indexOf(needle);
        if (index < 0) continue;
        // 水印、重复页眉和重复词可能有多个位置。无法唯一对应时请用户调整选区。
        if (anchor || compact.indexOf(needle, index + 1) !== -1) return null;
        const start = offsets[index];
        const end = offsets[index + needle.length - 1] + 1;
        const quote = block.text.slice(start, end);
        if ([...quote].length > 1200) return null;
        anchor = { materialId, parseAttempt, blockId: block.blockId, start, end, quote };
    }
    return anchor;
}

export async function resolvePdfAnchor(load, materialId, selection) {
    const { pageNumber, quote } = selection;
    const first = await load(1);
    const attempt = first.parseAttempt;
    async function batch(page) {
        const data = page === 1 ? first : await load(page);
        if (data.parseAttempt !== attempt) throw new Error("资料已重新解析，请重新选择原文。");
        return data.blocks.records;
    }
    let left = 1;
    let right = first.blocks.totalPages;
    // 解析器按物理页顺序存块。先找到可能包含所选页的首批，再检查相邻批次。
    while (left < right) {
        const middle = Math.floor((left + right) / 2);
        const records = await batch(middle);
        if (records.at(-1)?.sourceLocator?.pageNumber >= pageNumber) right = middle;
        else left = middle + 1;
    }
    const matches = [];
    for (let page = left; page <= first.blocks.totalPages; page++) {
        const records = await batch(page);
        matches.push(...records.filter(row => row.sourceLocator?.pageNumber === pageNumber));
        if (records.at(-1)?.sourceLocator?.pageNumber > pageNumber) break;
    }
    return matchPdfAnchor(matches, pageNumber, quote, materialId, attempt);
}
