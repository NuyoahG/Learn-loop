// 登录后的 next 只接受固定业务页面；解析为同源 URL 后保留查询参数，拒绝任意站点和未知路径。
const allowedTargets = new Set(["/workspace", "/questions", "/materials", "/history", "/notes", "/reading", "/review", "/tutor-practice", "/tutor"]);
const aliases = {
    "/login.html": "/login",
    "/register.html": "/register",
    "/materials.html": "/materials",
    "/workspace.html": "/workspace",
    "/questions.html": "/questions",
    "/history.html": "/history",
    "/web-basics.html": "/questions",
    "/web-basics": "/questions",
    "/answer-table.html": "/history",
    "/answer-table": "/history"
};

export function safeTarget(raw, origin) {
    if (!raw) return "/questions";
    try {
        if (raw.includes("\\")) return "/questions";
        const base = origin || (typeof window !== "undefined" ? window.location.origin : "http://localhost");
        const url = new URL(raw, base);
        if (url.origin !== base) return "/questions";
        const pathname = url.pathname.length > 1 ? url.pathname.replace(/\/+$/, "") : url.pathname;
        const normalized = aliases[pathname] ?? pathname;
        return allowedTargets.has(normalized) ? normalized + url.search : "/questions";
    } catch {
        return "/questions";
    }
}
