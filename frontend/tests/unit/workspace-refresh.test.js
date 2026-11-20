import test from "node:test";
import assert from "node:assert/strict";
import { validPendingRequest } from "../../lib/workspace-model.js";

test("恢复待确认请求时保留重新提炼标志，避免同一请求键变更语义", () => {
    const original = { materialId: 8, blockIds: [2, 3], count: 3, difficulty: "HARD",
        granularity: "FINE", mode: "COVERAGE", refreshCatalog: true, requestKey: "same-request" };
    assert.deepEqual(validPendingRequest(JSON.parse(JSON.stringify(original))), original);
    assert.equal(validPendingRequest({ ...original, refreshCatalog: "true" }), null);
    assert.equal(validPendingRequest({ ...original, mode: undefined }), null);
});
