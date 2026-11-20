import { readFile } from "node:fs/promises";
import path from "node:path";

const types = {
    build: ["application/javascript", /^pdf\.worker\.min\.mjs$/],
    legacy: ["application/javascript", /^pdf\.worker\.min\.mjs$/],
    cmaps: ["application/octet-stream", /^[a-z0-9_-]+\.bcmap$/i],
    standard_fonts: ["application/octet-stream", /^[a-z0-9_-]+\.(?:pfb|ttf)$/i],
    iccs: ["application/octet-stream", /^[a-z0-9_-]+\.icc$/i],
    wasm: ["application/wasm", /^[a-z0-9_-]+\.wasm$/i]
};

export async function GET(_, { params }) {
    const { asset } = await params;
    const [folder, name] = asset;
    const type = Object.hasOwn(types, folder) ? types[folder] : null;
    if (asset.length !== 2 || !type || !type[1].test(name)) return new Response(null, { status: 404 });
    // 只开放 PDF.js 的固定静态资源目录，与用户原件接口分开。
    const directory = folder === "legacy" ? "legacy/build" : folder;
    try {
        const body = await readFile(path.join(process.cwd(), "node_modules", "pdfjs-dist", directory, name));
        return new Response(body, { headers: {
            "Content-Type": type[0], "Cache-Control": "public, max-age=3600",
            "X-Content-Type-Options": "nosniff"
        } });
    } catch {
        return new Response(null, { status: 404 });
    }
}
