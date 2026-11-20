export const USER = { userId: 1, username: "demo_learner" };

export const QUESTION_VERSION = "11111111-1111-4111-8111-111111111111";
export const VARIANT_VERSION = "22222222-2222-4222-8222-222222222222";

export function material(overrides = {}) {
    return {
        materialId: 10,
        originalName: "网络基础.pdf",
        fileType: "pdf",
        sizeBytes: 1048576,
        parseStatus: "PARSED",
        blockCount: 11,
        textCharCount: 220,
        createdAt: "2026-11-01T08:00:00Z",
        warnings: [],
        parseAttempt: 1,
        errorMessage: null,
        errorCode: null,
        ...overrides
    };
}

export function pdfBlocks() {
    return Array.from({ length: 11 }, (_, index) => ({
        blockId: 101 + index,
        blockNo: index + 1,
        text: `第 ${index + 1} 段正文，用于确认页码范围与来源。`,
        sourceLocator: { kind: "pdf", pageNumber: index < 6 ? 1 : 2, partIndex: 1 }
    }));
}

export const QUESTION = {
    versionId: QUESTION_VERSION,
    materialId: 10,
    materialName: "网络基础.pdf",
    jobId: "job-1",
    jobCreatedAt: "2026-11-01T09:00:00Z",
    createdAt: "2026-11-01T09:00:00Z",
    conceptId: "concept-1",
    conceptName: "HTTP 方法",
    sourceLocator: { kind: "pdf", pageNumber: 1, partIndex: 1 },
    difficulty: "MEDIUM",
    content: "以下哪一个是 HTTP 的幂等方法？",
    optionA: "GET",
    optionB: "POST",
    optionC: "PATCH",
    optionD: "CONNECT",
    answered: false,
    latestCorrect: null
};

export const VARIANT_QUESTION = {
    ...QUESTION,
    versionId: VARIANT_VERSION,
    jobId: "variant-job",
    content: "HTTP 状态码 304 表示什么？",
    optionA: "资源未修改，可使用缓存",
    optionB: "资源永久移动",
    optionC: "请求方法不允许",
    optionD: "服务器内部错误"
};

export function job(question = QUESTION, overrides = {}) {
    return {
        jobId: "job-1",
        status: "SUCCEEDED",
        requestedCount: 3,
        generatedCount: 1,
        difficulty: "MEDIUM",
        granularity: "MEDIUM",
        attemptCount: 1,
        errorCode: null,
        shortfallReason: "可用正文有限。",
        questions: [{ question, sourceLocator: question.sourceLocator }],
        ...overrides
    };
}

export const MASTERY = [{
    conceptId: "concept-1",
    materialId: 10,
    name: "HTTP 方法",
    probability: 0.4,
    evidenceCount: 1,
    evidenceStatus: "VALID",
    updating: false
}];

export function historyRecords(length = 12) {
    return Array.from({ length }, (_, index) => ({
        recordId: index + 1,
        questionId: 900 + index,
        content: `第${index + 1}道题目：事务提交之后能否持久保存？`,
        selectedOption: "ABCD"[index % 4],
        correct: index % 2 === 0,
        submittedAt: `2026-11-${String(index + 1).padStart(2, "0")}T08:00:00Z`
    }));
}
