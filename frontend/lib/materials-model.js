// 与 DOM 分离的小规则，供页面和 Node 测试共用；服务端仍独立做全部校验。
export const stateLabels = { UPLOADED: "已上传，待解析", PARSING: "正在解析", PARSED: "解析完成", FAILED: "解析失败" };
export const TEXT_FILE_MIB = 2;
export const DOCUMENT_FILE_MIB = 100;

export function validateFile(file) {
    if (!file) return "请先选择一份文件。";
    const extension = file.name.split(".").pop().toLowerCase();
    if (!["txt", "md", "docx", "pdf"].includes(extension)) return "仅支持 TXT、Markdown、Word DOCX 和 PDF。";
    if (file.size === 0) return "文件为空，请选择有内容的资料。";
    const limit = ["txt", "md"].includes(extension) ? TEXT_FILE_MIB : DOCUMENT_FILE_MIB;
    if (file.size > limit * 1024 * 1024) return `该格式最多 ${limit} MiB，请拆分或压缩资料。`;
    return "";
}

export function fileSize(bytes) {
    if (bytes < 1024) return `${bytes} B`;
    if (bytes < 1024 * 1024) return `${(bytes / 1024).toFixed(1)} KiB`;
    return `${(bytes / 1024 / 1024).toFixed(1)} MiB`;
}

const contentErrors = new Set(["INVALID_ENCODING", "EMPTY_TEXT", "NO_EXTRACTABLE_TEXT", "PDF_ENCRYPTED", "INVALID_PDF", "INVALID_DOCX", "INVALID_TEXT", "TEXT_LIMIT_EXCEEDED", "BLOCK_LIMIT_EXCEEDED", "DOCX_LIMIT_EXCEEDED", "PDF_PAGE_LIMIT_EXCEEDED", "RESULT_TOO_LARGE", "ORIGINAL_UNAVAILABLE"]);
export function failureAdvice(code) {
    if (code === "INVALID_ENCODING") return { retry: false, text: "请将文本另存为 UTF-8，再上传新资料。" };
    if (["NO_EXTRACTABLE_TEXT", "EMPTY_TEXT"].includes(code)) return { retry: false, text: "请检查原文件是否有正文，或上传文字版资料；当前不支持 OCR。" };
    if (code === "PDF_ENCRYPTED") return { retry: false, text: "请上传未加密且有权使用的文字型 PDF。" };
    if (contentErrors.has(code)) return { retry: false, text: "请检查格式、内容或大小限制，修正后上传新资料。" };
    return { retry: true, text: "原件已保留。服务恢复后，可以点击“重新解析”，不必再次上传。" };
}

export function sourceLabel(source) {
    if (source.kind === "pdf") return `文件第 ${source.pageNumber} 页 · 分片 ${source.partIndex}`;
    if (source.kind === "text") return `原文第 ${source.lineStart}—${source.lineEnd} 行 · 分片 ${source.partIndex}`;
    if (source.kind === "docx") return `Word 正文位置 ${source.bodyBlockIndex}` +
        (source.tableRowIndex ? ` · 表格第 ${source.tableRowIndex} 行` : "") + ` · 分片 ${source.partIndex}`;
    return "来源位置暂不可用";
}

// 同页重新选中同字节+同类型，沿用原请求键。File 对象身份/文件名/修改时间均不能证明内容相同。
export class UploadTickets {
    constructor() { this.items = new Map(); }
    async select(file) {
        const bytes = await file.arrayBuffer(); // 大小已先校验，最多 100 MiB。
        const hash = Array.from(new Uint8Array(await crypto.subtle.digest("SHA-256", bytes)), x => x.toString(16).padStart(2, "0")).join("");
        const signature = file.name.split(".").pop().toLowerCase() + ":" + hash;
        if (!this.items.has(signature)) this.items.set(signature, { requestId: crypto.randomUUID(), materialId: null, uncertain: false });
        return this.items.get(signature);
    }
}
