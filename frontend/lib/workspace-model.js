import { sourceLabel } from "./materials-model.js";

export function codePoints(text) {
    return Array.from(text ?? "").length;
}

export function blockPage(block) {
    const value = block?.sourceLocator?.pageNumber;
    return Number.isSafeInteger(value) && value > 0 ? value : null;
}

export function pageRangeLabel(blocks) {
    const pages = blocks.map(blockPage).filter(page => page !== null);
    if (pages.length) return `第 ${Math.min(...pages)}—${Math.max(...pages)} 页`;
    return `正文块 ${blocks[0]?.blockNo ?? "?"}—${blocks.at(-1)?.blockNo ?? "?"}`;
}

// 单个正文块超过上限时返回 null：不能靠拆批绕过服务端校验，也不能悄悄截断。
export function splitBatches(blocks) {
    const batches = [];
    let current = [], characters = 0;
    for (const block of blocks) {
        const size = codePoints(block.text);
        if (size > 12000) return null;
        if (current.length > 0 && (current.length >= 10 || characters + size > 12000)) {
            batches.push({ blocks: current, chars: characters });
            current = []; characters = 0;
        }
        current.push(block); characters += size;
    }
    if (current.length > 0) batches.push({ blocks: current, chars: characters });
    return batches;
}

export function blockLocator(block) {
    return sourceLabel(block?.sourceLocator);
}

export function queryMaterialId(search) {
    const value = Number(new URLSearchParams(search ?? "").get("materialId"));
    return Number.isSafeInteger(value) && value > 0 ? String(value) : "";
}

export function queryVersionId(search) {
    const value = new URLSearchParams(search ?? "").get("versionId");
    return typeof value === "string" && /^[0-9a-fA-F-]{36}$/.test(value) ? value : "";
}

export function validPendingRequest(value) {
    if (!value || !Number.isSafeInteger(value.materialId) || value.materialId <= 0 ||
        !Array.isArray(value.blockIds) || value.blockIds.length === 0 || value.blockIds.length > 10 ||
        !value.blockIds.every(id => Number.isSafeInteger(id) && id > 0) ||
        new Set(value.blockIds).size !== value.blockIds.length ||
        !["EASY", "MEDIUM", "HARD"].includes(value.difficulty) ||
        !["COARSE", "MEDIUM", "FINE"].includes(value.granularity) ||
        ![3, 5, 10].includes(value.count) || typeof value.requestKey !== "string" || !value.requestKey ||
        (value.mode != null && !["COVERAGE", "WEAKNESS"].includes(value.mode)) ||
        (value.refreshCatalog != null && typeof value.refreshCatalog !== "boolean") ||
        (value.refreshCatalog === true && !["COVERAGE", "WEAKNESS"].includes(value.mode))) return null;
    return {
        materialId: value.materialId,
        blockIds: [...value.blockIds].sort((a, b) => a - b),
        count: value.count,
        difficulty: value.difficulty,
        granularity: value.granularity,
        requestKey: value.requestKey,
        ...(value.refreshCatalog === true ? { refreshCatalog: true } : {}),
        ...(value.mode ? { mode: value.mode } : {})
    };
}

export function normalizeRecovery(value) {
    if (!value || !Number.isSafeInteger(value.materialId) || value.materialId <= 0) return null;
    const pendingRequest = validPendingRequest(value.pendingRequest);
    return {
        materialId: value.materialId,
        jobId: typeof value.jobId === "string" ? value.jobId : null,
        pendingRequest,
        questionVersionId: typeof value.questionVersionId === "string" && /^[0-9a-fA-F-]{36}$/.test(value.questionVersionId)
            ? value.questionVersionId : null
    };
}
