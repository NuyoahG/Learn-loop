// 只存在于当前页面的模块内存中；刷新页面后会重新获取。
// 不把密码、会话 Cookie 或 CSRF 令牌写入 localStorage。
let csrfPair = null;

// 页面确认 /api/auth/me 后设置的账号编号。它不是认证凭据，只是写请求的上下文栅栏：
// 另一个标签页切换账号后，旧页面不能把操作记到新账号上。
let expectedUser = null;

export function setExpectedUser(userId) {
    expectedUser = Number.isSafeInteger(userId) && userId > 0 ? userId : null;
}

export class ApiError extends Error {
    constructor(message, status, errorCode = null) {
        super(message);
        this.name = "ApiError";
        this.status = status;
        this.errorCode = errorCode;
    }
}

export function clearCsrf() {
    csrfPair = null;
}

export async function refreshCsrf() {
    // 这里直接使用 fetch，避免 apiRequest 和 refreshCsrf 互相递归调用。
    const response = await fetch("/api/auth/csrf", {
        method: "GET",
        credentials: "same-origin",
        cache: "no-store",
        headers: { "Accept": "application/json" }
    });

    if (!response.ok) {
        throw new ApiError("暂时无法准备请求，请稍后重试。", response.status);
    }

    const data = await response.json();
    if (typeof data.headerName !== "string" || !data.headerName ||
        typeof data.token !== "string" || !data.token) {
        throw new Error("服务返回的请求校验信息不完整。");
    }

    csrfPair = data;
    return data;
}

export async function apiRequest(path, options = {}) {
    // 本模块只调用本站业务 API，减少误把令牌发往其他地址的机会。
    if (typeof path !== "string" || !path.startsWith("/api/")) {
        throw new Error("apiRequest 只接受本站 /api/ 开头的地址。");
    }

    const { responseType, ...fetchOptions } = options;
    const method = (options.method ?? "GET").toUpperCase();
    const headers = new Headers(options.headers);
    headers.set("Accept", responseType === "blob" ? "*/*" : "application/json");
    // 先冻结页面所属账号，再异步拿 CSRF。新页面的私人读请求也带上下文。
    const userAtStart = expectedUser;

    // GET 等读取请求不需要 CSRF；POST 等写请求需要携带。
    const needsCsrf = !["GET", "HEAD", "OPTIONS"].includes(method);
    if (needsCsrf) {
        const token = csrfPair ?? await refreshCsrf();
        headers.set(token.headerName, token.token);
    }
    if (userAtStart !== null && (needsCsrf || /^\/api\/(?:answers|notes|reading|review|tutor)(?:[/?]|$)/.test(path) || responseType === "blob")) {
        headers.set("X-LearnLoop-Expected-User", String(userAtStart));
    }

    const response = await fetch(path, {
        ...fetchOptions,
        method,
        headers,
        credentials: "same-origin", // 同源请求携带浏览器中的会话 Cookie。
        cache: "no-store"
    });

    if (!response.ok) {
        // 令牌可能随会话变化而失效；下次主动请求时重新获取。
        if (response.status === 401 || response.status === 403) {
            clearCsrf();
        }

        const messages = {
            400: "请求内容不符合要求，请检查输入。",
            401: "登录状态已失效，请重新登录。",
            403: "请求校验未通过，请刷新页面后重试。",
            404: "没有找到可访问的数据。",
            409: "当前操作与已有数据冲突。"
        };

        let message = messages[response.status] ??
            "服务暂时无法完成请求，请稍后重试。";
        let errorCode = null;
        // 只扩展受控业务模块的错误；登录/注册/旧答题接口保持原有状态码语义。
        // 错误体损坏时仍保留 HTTP 状态，尤其不能把 401 丢成普通 JSON 异常。
        if (/^\/api\/(?:materials|settings|generation|learning|answers|notes|reading|review|tutor)(?:[/?]|$)/.test(path)) {
            try {
                const detail = await response.json();
                if (typeof detail.errorCode === "string" && /^[A-Z][A-Z0-9_]{0,63}$/.test(detail.errorCode)) {
                    errorCode = detail.errorCode;
                    if (typeof detail.message === "string" && detail.message.length <= 255 && detail.message) message = detail.message;
                }
            } catch { /* 使用上面的安全回退，不重发请求。 */ }
        }
        throw new ApiError(message, response.status, errorCode);
    }

    // 登录与退出成功都返回 204；此时不能执行 response.json()。
    if (response.status === 204) {
        return null;
    }

    // 其余当前业务接口成功时返回 JSON。
    // 若解析失败，让页面提示失败；不要因此自动重发刚才的 POST。
    return responseType === "blob" ? response.blob() : response.json();
}
