import assert from "node:assert/strict";
import { test } from "node:test";
import { matchPdfAnchor, resolvePdfAnchor } from "../../lib/pdf-reading-selection.js";

function block(blockId, pageNumber, text) {
    return { blockId, sourceLocator: { pageNumber }, text };
}

test("PDF 引文忽略布局空白，提交解析正文的精确 UTF-16 选区", () => {
    const text = "前缀😀\n 目标 内容。";
    const records = [block(10, 1, text), block(20, 2, text)];
    assert.deepEqual(matchPdfAnchor(records, 2, "😀目标内容", 9, 4),
        { materialId: 9, parseAttempt: 4, blockId: 20, start: 2, end: 11, quote: "😀\n 目标 内容" });
    assert.equal(matchPdfAnchor([...records, block(21, 2, text)], 2, "目标内容", 9, 4), null);
    assert.equal(matchPdfAnchor([block(22, 2, "目标\n目标")], 2, "目标", 9, 4), null);
});

test("PDF 原文定位按物理页查找，跨批次检查同一页的重复引文", async () => {
    const pages = [
        [block(11, 2, "目标")],
        [block(12, 3, "甲")],
        [block(13, 7, "乙")],
        [block(14, 12, "目标")],
        [block(15, 12, "另一段"), block(16, 13, "丙")],
        [block(17, 15, "丁")]
    ];
    const calls = [];
    async function load(page) {
        calls.push(page);
        return { parseAttempt: 8, blocks: { totalPages: pages.length, records: pages[page - 1] } };
    }
    assert.equal((await resolvePdfAnchor(load, 10, { pageNumber: 12, quote: "目标" })).blockId, 14);
    assert.equal(calls.includes(2) || calls.includes(6), false);
    pages[4][0].text = "目标";
    assert.equal(await resolvePdfAnchor(load, 10, { pageNumber: 12, quote: "目标" }), null);
});
