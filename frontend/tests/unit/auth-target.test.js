import assert from "node:assert/strict";
import test from "node:test";
import { safeTarget } from "../../lib/auth-target.js";

const origin = "http://127.0.0.1:3000";

test("accepts known Next routes and preserves query", () => {
    assert.equal(safeTarget("/workspace?materialId=7", origin), "/workspace?materialId=7");
    assert.equal(safeTarget("/questions?materialId=7&versionId=x", origin), "/questions?materialId=7&versionId=x");
    assert.equal(safeTarget("/materials/", origin), "/materials");
});

test("normalizes legacy page aliases", () => {
    assert.equal(safeTarget("/web-basics.html?materialId=9", origin), "/questions?materialId=9");
    assert.equal(safeTarget("/history.html", origin), "/history");
    assert.equal(safeTarget("/answer-table.html?page=2", origin), "/history?page=2");
});

test("rejects external, protocol-relative, backslash and unknown targets", () => {
    assert.equal(safeTarget("https://evil.example/workspace", origin), "/questions");
    assert.equal(safeTarget("//evil.example/workspace", origin), "/questions");
    assert.equal(safeTarget("/\\evil.example/workspace", origin), "/questions");
    assert.equal(safeTarget("/admin", origin), "/questions");
    assert.equal(safeTarget("javascript:alert(1)", origin), "/questions");
    assert.equal(safeTarget(null, origin), "/questions");
});
