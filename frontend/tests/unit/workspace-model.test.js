import assert from "node:assert/strict";
import test from "node:test";
import {
    normalizeRecovery, pageRangeLabel, queryMaterialId, queryVersionId, splitBatches,
    validPendingRequest
} from "../../lib/workspace-model.js";

const block = (blockId, text, pageNumber = blockId) => ({
    blockId, blockNo: blockId, text, sourceLocator: { kind: "pdf", pageNumber, partIndex: 1 }
});

test("splits by 10 blocks or 12000 code points", () => {
    const batches = splitBatches(Array.from({ length: 12 }, (_, i) => block(i + 1, "a")));
    assert.equal(batches.length, 2);
    assert.equal(batches[0].blocks.length, 10);
    assert.equal(batches[1].blocks.length, 2);

    const big = splitBatches([block(1, "a".repeat(12000)), block(2, "b")]);
    assert.equal(big.length, 2);
    assert.equal(splitBatches([block(1, "a".repeat(12001))]), null);
});

test("counts Unicode code points, not UTF-16 units", () => {
    const split = splitBatches([block(1, "😀".repeat(12000)), block(2, "x")]);
    assert.equal(split.length, 2);
});

test("labels page ranges and falls back to block numbers", () => {
    assert.equal(pageRangeLabel([block(1, "a", 3), block(2, "b", 5)]), "第 3—5 页");
    assert.equal(pageRangeLabel([{ blockNo: 2, text: "a", sourceLocator: {} }]), "正文块 2—2");
});

test("validates deep-link ids and pending requests", () => {
    assert.equal(queryMaterialId("?materialId=12"), "12");
    assert.equal(queryMaterialId("?materialId=0"), "");
    assert.equal(queryVersionId("?versionId=4b7c7b7e-5d8a-4f2b-aabf-4b7e3e0f8a11"), "4b7c7b7e-5d8a-4f2b-aabf-4b7e3e0f8a11");
    assert.equal(queryVersionId("?versionId=bad"), "");

    const pending = validPendingRequest({
        materialId: 1, blockIds: [3, 1], count: 3, difficulty: "MEDIUM",
        granularity: "FINE", requestKey: "key"
    });
    assert.deepEqual(pending.blockIds, [1, 3]);
    assert.equal(validPendingRequest({ ...pending, blockIds: [1, 1] }), null);
    assert.equal(validPendingRequest({ ...pending, requestKey: "" }), null);
});

test("normalizes recovery and drops malformed fields", () => {
    assert.equal(normalizeRecovery(null), null);
    assert.equal(normalizeRecovery({ materialId: 0 }), null);
    assert.deepEqual(normalizeRecovery({
        materialId: 2, jobId: "job", questionVersionId: "4b7c7b7e-5d8a-4f2b-aabf-4b7e3e0f8a11"
    }), {
        materialId: 2, jobId: "job", pendingRequest: null,
        questionVersionId: "4b7c7b7e-5d8a-4f2b-aabf-4b7e3e0f8a11"
    });
});
