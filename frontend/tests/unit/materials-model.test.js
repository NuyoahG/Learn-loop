import assert from "node:assert/strict";
import test from "node:test";
import { failureAdvice, fileSize, sourceLabel, stateLabels, validateFile } from "../../lib/materials-model.js";

test("validates supported extensions and size limits", () => {
    assert.equal(validateFile(null), "请先选择一份文件。");
    assert.match(validateFile({ name: "a.exe", size: 10 }), /仅支持/);
    assert.match(validateFile({ name: "a.txt", size: 0 }), /文件为空/);
    assert.equal(validateFile({ name: "a.md", size: 2 * 1024 * 1024 }), "");
    assert.match(validateFile({ name: "a.txt", size: 2 * 1024 * 1024 + 1 }), /2 MiB/);
    for (const extension of ["pdf", "docx"]) {
        assert.equal(validateFile({ name: `a.${extension}`, size: 100 * 1024 * 1024 }), "");
        assert.match(validateFile({ name: `a.${extension}`, size: 100 * 1024 * 1024 + 1 }), /100 MiB/);
    }
});

test("formats sizes and source labels", () => {
    assert.equal(fileSize(512), "512 B");
    assert.equal(fileSize(2048), "2.0 KiB");
    assert.equal(fileSize(2 * 1024 * 1024), "2.0 MiB");
    assert.equal(stateLabels.PARSED, "解析完成");
    assert.equal(sourceLabel({ kind: "pdf", pageNumber: 3, partIndex: 1 }), "文件第 3 页 · 分片 1");
    assert.equal(sourceLabel({ kind: "text", lineStart: 2, lineEnd: 4, partIndex: 1 }), "原文第 2—4 行 · 分片 1");
});

test("separates content errors from retryable infrastructure errors", () => {
    assert.equal(failureAdvice("INVALID_ENCODING").retry, false);
    assert.equal(failureAdvice("NO_EXTRACTABLE_TEXT").retry, false);
    assert.equal(failureAdvice("PARSER_TIMEOUT").retry, true);
});
