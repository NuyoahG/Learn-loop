import { confidenceValues } from "./format.js";

// 只保存恢复标识和待确认提交载荷；不保存题干、选项答案、Cookie、CSRF 或模型 Key。
function defaultStorage() {
    try { return typeof window !== "undefined" ? window.sessionStorage : null; } catch { return null; }
}

function read(key, storage) {
    if (!key) return null;
    try {
        const value = (storage ?? defaultStorage())?.getItem(key);
        return value ? JSON.parse(value) : null;
    } catch {
        return null;
    }
}

function write(key, value, storage) {
    if (!key) return;
    try {
        const target = storage ?? defaultStorage();
        if (!target) return;
        if (value == null) target.removeItem(key);
        else target.setItem(key, JSON.stringify(value));
    } catch { /* 存储不可用时不阻断当前页面流程。 */ }
}

function remove(key, storage) {
    if (!key) return;
    try { (storage ?? defaultStorage())?.removeItem(key); } catch { /* 忽略存储不可用。 */ }
}

function userId(value) {
    return Number.isSafeInteger(value) && value > 0 ? value : null;
}

function submitPayload(value) {
    if (!value || !["A", "B", "C", "D"].includes(value.selectedOption) ||
        typeof value.submitKey !== "string" || !value.submitKey ||
        !confidenceValues.has(value.confidence)) return null;
    return { selectedOption: value.selectedOption, submitKey: value.submitKey, confidence: value.confidence };
}

export function workspaceRecoveryKey(id) {
    const user = userId(id);
    return user ? `learnloop:${user}:workspace` : null;
}

export function loadWorkspaceRecovery(id, storage) {
    return read(workspaceRecoveryKey(id), storage);
}

export function saveWorkspaceRecovery(id, value, storage) {
    write(workspaceRecoveryKey(id), value, storage);
}

export function workspaceTicketKey(id, materialId, versionId) {
    const user = userId(id);
    return user && Number.isSafeInteger(materialId) && materialId > 0 && versionId
        ? `learnloop:${user}:practice:${materialId}:${versionId}` : null;
}

export function loadWorkspaceTicket(id, materialId, versionId, storage) {
    const value = read(workspaceTicketKey(id, materialId, versionId), storage);
    if (!value || value.questionVersionId !== versionId || typeof value.requestKey !== "string" || !value.requestKey) return null;
    return {
        questionVersionId: versionId,
        requestKey: value.requestKey,
        attemptId: typeof value.attemptId === "string" ? value.attemptId : null,
        revealRequested: value.revealRequested === true,
        submit: submitPayload(value.submit)
    };
}

export function saveWorkspaceTicket(id, materialId, ticket, storage) {
    if (!ticket?.questionVersionId || typeof ticket.requestKey !== "string" || !ticket.requestKey) return;
    write(workspaceTicketKey(id, materialId, ticket.questionVersionId), {
        questionVersionId: ticket.questionVersionId,
        requestKey: ticket.requestKey,
        attemptId: typeof ticket.attemptId === "string" ? ticket.attemptId : null,
        revealRequested: ticket.revealRequested === true,
        submit: submitPayload(ticket.submit)
    }, storage);
}

export function questionPracticeTicketKey(id, versionId) {
    const user = userId(id);
    return user && versionId ? `learnloop:${user}:question-practice:${versionId}` : null;
}

export function loadQuestionPracticeTicket(id, versionId, storage) {
    const value = read(questionPracticeTicketKey(id, versionId), storage);
    if (!value || value.questionVersionId !== versionId || typeof value.requestKey !== "string" || !value.requestKey) return null;
    return {
        questionVersionId: versionId,
        requestKey: value.requestKey,
        attemptId: typeof value.attemptId === "string" ? value.attemptId : null,
        submit: submitPayload(value.submit)
    };
}

export function saveQuestionPracticeTicket(id, ticket, storage) {
    if (!ticket?.questionVersionId || typeof ticket.requestKey !== "string" || !ticket.requestKey) return;
    write(questionPracticeTicketKey(id, ticket.questionVersionId), {
        questionVersionId: ticket.questionVersionId,
        requestKey: ticket.requestKey,
        attemptId: typeof ticket.attemptId === "string" ? ticket.attemptId : null,
        submit: submitPayload(ticket.submit)
    }, storage);
}

export function questionVariantTicketKey(id, versionId) {
    const user = userId(id);
    return user && versionId ? `learnloop:${user}:question-variant:${versionId}` : null;
}

export function loadQuestionVariantTicket(id, versionId, storage) {
    const value = read(questionVariantTicketKey(id, versionId), storage);
    if (!value || value.questionVersionId !== versionId || typeof value.requestKey !== "string" || !value.requestKey) return null;
    return {
        questionVersionId: versionId,
        requestKey: value.requestKey,
        jobId: typeof value.jobId === "string" ? value.jobId : null
    };
}

export function saveQuestionVariantTicket(id, ticket, storage) {
    if (!ticket?.questionVersionId || typeof ticket.requestKey !== "string" || !ticket.requestKey) return;
    write(questionVariantTicketKey(id, ticket.questionVersionId), {
        questionVersionId: ticket.questionVersionId,
        requestKey: ticket.requestKey,
        jobId: typeof ticket.jobId === "string" ? ticket.jobId : null
    }, storage);
}

export function clearQuestionVariantTicket(id, versionId, storage) {
    remove(questionVariantTicketKey(id, versionId), storage);
}
