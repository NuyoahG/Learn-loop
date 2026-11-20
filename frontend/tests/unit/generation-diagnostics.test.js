import test from "node:test";
import assert from "node:assert/strict";
import { generationFailureHint } from "../../lib/generation-diagnostics.js";

test("new failures identify their stage while old jobs remain readable", () => {
    assert.match(generationFailureHint("CATALOG_MODEL_OUTPUT_INVALID"), /提炼考点阶段/);
    assert.match(generationFailureHint("QUESTIONS_MODEL_TIMEOUT"), /生成题目阶段.*超时/);
    assert.match(generationFailureHint("MODEL_OUTPUT_INVALID"), /格式或来源校验/);
    assert.doesNotMatch(generationFailureHint("secret<raw response>"), /secret/);
});
