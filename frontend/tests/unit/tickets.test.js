import assert from "node:assert/strict";
import test from "node:test";
import {
    clearQuestionVariantTicket, loadQuestionPracticeTicket, loadQuestionVariantTicket,
    loadWorkspaceRecovery, loadWorkspaceTicket, saveQuestionPracticeTicket,
    saveQuestionVariantTicket, saveWorkspaceRecovery, saveWorkspaceTicket
} from "../../lib/tickets.js";

const VERSION = "4b7c7b7e-5d8a-4f2b-aabf-4b7e3e0f8a11";
class MemoryStorage {
    constructor() { this.values = new Map(); }
    getItem(key) { return this.values.has(key) ? this.values.get(key) : null; }
    setItem(key, value) { this.values.set(key, value); }
    removeItem(key) { this.values.delete(key); }
}

test("uses exact user-scoped key names and round-trips recovery", () => {
    const storage = new MemoryStorage();
    assert.equal(saveWorkspaceRecovery(7, { materialId: 3, jobId: "j" }, storage), undefined);
    assert.deepEqual(loadWorkspaceRecovery(7, storage), { materialId: 3, jobId: "j" });
    assert.equal(storage.values.has("learnloop:7:workspace"), true);
    assert.equal(storage.values.has("learnloop:6:workspace"), false);
});

test("workspace ticket keeps only submit payload and reveal flag", () => {
    const storage = new MemoryStorage();
    saveWorkspaceTicket(7, 3, {
        questionVersionId: VERSION, requestKey: "request", attemptId: "attempt",
        revealRequested: true,
        submit: { selectedOption: "B", submitKey: "submit", confidence: "SOMEWHAT_SURE", extra: "not stored" }
    }, storage);
    assert.deepEqual(loadWorkspaceTicket(7, 3, VERSION, storage), {
        questionVersionId: VERSION, requestKey: "request", attemptId: "attempt",
        revealRequested: true,
        submit: { selectedOption: "B", submitKey: "submit", confidence: "SOMEWHAT_SURE" }
    });
    assert.equal(loadWorkspaceTicket(8, 3, VERSION, storage), null);
});

test("rejects malformed tickets and supports question tickets", () => {
    const storage = new MemoryStorage();
    storage.setItem(`learnloop:7:question-practice:${VERSION}`, JSON.stringify({
        questionVersionId: VERSION, requestKey: "request",
        submit: { selectedOption: "Z", submitKey: "x", confidence: "UNSURE" }
    }));
    assert.deepEqual(loadQuestionPracticeTicket(7, VERSION, storage), {
        questionVersionId: VERSION, requestKey: "request", attemptId: null, submit: null
    });
    saveQuestionPracticeTicket(7, { questionVersionId: VERSION, requestKey: "r2", submit: null }, storage);
    assert.equal(loadQuestionPracticeTicket(7, VERSION, storage).requestKey, "r2");

    saveQuestionVariantTicket(7, { questionVersionId: VERSION, requestKey: "variant", jobId: "job" }, storage);
    assert.deepEqual(loadQuestionVariantTicket(7, VERSION, storage), {
        questionVersionId: VERSION, requestKey: "variant", jobId: "job"
    });
    clearQuestionVariantTicket(7, VERSION, storage);
    assert.equal(loadQuestionVariantTicket(7, VERSION, storage), null);
});
