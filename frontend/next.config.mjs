import { DOCUMENT_FILE_MIB } from "./lib/materials-model.js";

const backend = (process.env.LEARNLOOP_BACKEND_URL ?? "http://127.0.0.1:8080")
    .replace(/\/+$/, "");

/** @type {import('next').NextConfig} */
const nextConfig = {
    reactStrictMode: true,
    experimental: {
        // 外部 rewrite 会使用 Next 缓冲的请求体。为单文件及 multipart 边界、文件名和表单字段留空间。
        proxyClientMaxBodySize: (DOCUMENT_FILE_MIB + 1) * 1024 * 1024
    },
    async rewrites() {
        // 浏览器只访问 Next 源；Cookie、CSRF、multipart 和状态码由同源代理原样转发。
        return [{ source: "/api/:path*", destination: `${backend}/api/:path*` }];
    },
    async redirects() {
        return [
            { source: "/login.html", destination: "/login", permanent: false },
            { source: "/register.html", destination: "/register", permanent: false },
            { source: "/materials.html", destination: "/materials", permanent: false },
            { source: "/workspace.html", destination: "/workspace", permanent: false },
            { source: "/questions.html", destination: "/questions", permanent: false },
            { source: "/history.html", destination: "/history", permanent: false },
            { source: "/web-basics.html", destination: "/questions", permanent: false },
            { source: "/web-basics", destination: "/questions", permanent: false },
            { source: "/answer-table.html", destination: "/history", permanent: false },
            { source: "/answer-table", destination: "/history", permanent: false }
        ];
    }
};

export default nextConfig;
