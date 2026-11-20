import assert from "node:assert/strict";
import { createHash } from "node:crypto";
import http from "node:http";
import { spawn } from "node:child_process";
import { once } from "node:events";
import { fileURLToPath } from "node:url";
import test from "node:test";

const NEXT_PORT = 3101;
const NEXT_URL = `http://127.0.0.1:${NEXT_PORT}`;
const root = fileURLToPath(new URL("../..", import.meta.url));
const nextBin = fileURLToPath(new URL("../../node_modules/next/dist/bin/next", import.meta.url));

function listen(server) {
    return new Promise((resolve, reject) => {
        server.once("error", reject);
        server.listen(0, "127.0.0.1", () => resolve(server.address().port));
    });
}

async function waitForNext() {
    const deadline = Date.now() + 90_000;
    while (Date.now() < deadline) {
        try {
            const response = await fetch(`${NEXT_URL}/`, { redirect: "manual" });
            if (response.status > 0) return;
        } catch {
            // 启动中的连接失败是预期情况。
        }
        await new Promise(resolve => setTimeout(resolve, 500));
    }
    throw new Error("Next.js dev server did not start");
}

let backend;
let child;
let upstreamPort;

test.before(async () => {
    backend = http.createServer(async (request, response) => {
        if (request.url === "/api/materials") {
            // 只返回摘要，以验证大文件没有被 Next 的请求体缓存截断。
            const digest = createHash("sha256");
            let bytes = 0;
            for await (const chunk of request) {
                digest.update(chunk);
                bytes += chunk.length;
            }
            response.writeHead(201, { "Content-Type": "application/json" });
            response.end(JSON.stringify({ bytes, digest: digest.digest("hex") }));
            return;
        }
        const chunks = [];
        for await (const chunk of request) chunks.push(chunk);
        const body = Buffer.concat(chunks);
        if (request.url === "/api/cookie") {
            response.writeHead(204, {
                "Set-Cookie": ["first=1; Path=/; HttpOnly", "second=2; Path=/"],
                "Cache-Control": "no-store"
            });
            response.end();
            return;
        }
        if (request.url === "/api/unauthorized") {
            response.writeHead(401, { "Content-Type": "application/json" });
            response.end(JSON.stringify({ errorCode: "TEST_UNAUTHORIZED", message: "登录已失效" }));
            return;
        }
        response.writeHead(201, {
            "Content-Type": "application/json",
            "Set-Cookie": "session=upstream; Path=/; HttpOnly",
            "Cache-Control": "no-store"
        });
        response.end(JSON.stringify({
            method: request.method,
            url: request.url,
            cookie: request.headers.cookie ?? null,
            csrf: request.headers["x-csrf-token"] ?? null,
            expectedUser: request.headers["x-learnloop-expected-user"] ?? null,
            contentType: request.headers["content-type"] ?? null,
            body: body.toString("utf8")
        }));
    });
    upstreamPort = await listen(backend);

    child = spawn(process.execPath, [nextBin, "dev", "-H", "127.0.0.1", "-p", String(NEXT_PORT)], {
        cwd: root,
        env: { ...process.env, LEARNLOOP_BACKEND_URL: `http://127.0.0.1:${upstreamPort}` },
        stdio: ["ignore", "pipe", "pipe"]
    });
    child.stderr.on("data", data => process.stderr.write(data));
    await waitForNext();
});

test.after(async () => {
    if (child && child.exitCode === null) {
        child.kill();
        await Promise.race([once(child, "exit"), new Promise(resolve => setTimeout(resolve, 5000))]);
    }
    if (backend) await new Promise(resolve => backend.close(resolve));
});

test("forwards cookies, CSRF, expected-user, multipart and upstream status", async () => {
    const form = new FormData();
    form.append("requestId", "4b7c7b7e-5d8a-4f2b-aabf-4b7e3e0f8a11");
    form.append("file", new Blob(["source text"], { type: "text/plain" }), "source.txt");
    const response = await fetch(`${NEXT_URL}/api/echo`, {
        method: "POST",
        headers: {
            Cookie: "JSESSIONID=test-session",
            "X-CSRF-Token": "test-csrf",
            "X-LearnLoop-Expected-User": "7"
        },
        body: form
    });

    assert.equal(response.status, 201);
    assert.match(response.headers.get("set-cookie") ?? "", /session=upstream/);
    const received = await response.json();
    assert.equal(received.method, "POST");
    assert.equal(received.cookie, "JSESSIONID=test-session");
    assert.equal(received.csrf, "test-csrf");
    assert.equal(received.expectedUser, "7");
    assert.match(received.contentType, /^multipart\/form-data; boundary=/);
    assert.match(received.body, /name="requestId"/);
    assert.match(received.body, /source text/);
    assert.match(received.body, /filename="source.txt"/);
});

test("preserves 204 and multiple Set-Cookie headers", async () => {
    const response = await fetch(`${NEXT_URL}/api/cookie`);
    assert.equal(response.status, 204);
    assert.equal(await response.text(), "");
    assert.deepEqual(response.headers.getSetCookie(), [
        "first=1; Path=/; HttpOnly",
        "second=2; Path=/"
    ]);
});

test("forwards all bytes of a 100 MiB multipart file", async () => {
    const boundary = "learnloop-large-file-fixture";
    const prefix = Buffer.from(`--${boundary}\r\nContent-Disposition: form-data; name="requestId"\r\n\r\nfixture\r\n` +
        `--${boundary}\r\nContent-Disposition: form-data; name="file"; filename="large.pdf"\r\n` +
        "Content-Type: application/pdf\r\n\r\n");
    const chunk = Buffer.alloc(1024 * 1024, 65);
    const suffix = Buffer.from(`\r\n--${boundary}--\r\n`);
    const bytes = prefix.length + 100 * chunk.length + suffix.length;
    const digest = createHash("sha256");
    async function* body() {
        for (const part of [prefix, ...Array(100).fill(chunk), suffix]) {
            digest.update(part);
            yield part;
        }
    }
    const response = await fetch(`${NEXT_URL}/api/materials`, {
        method: "POST", duplex: "half", body: body(), headers: {
            "Content-Type": `multipart/form-data; boundary=${boundary}`,
            "Content-Length": String(bytes)
        }
    });

    assert.equal(response.status, 201);
    assert.deepEqual(await response.json(), { bytes, digest: digest.digest("hex") });
});

test("preserves error status and JSON body", async () => {
    const response = await fetch(`${NEXT_URL}/api/unauthorized`);
    assert.equal(response.status, 401);
    assert.deepEqual(await response.json(), {
        errorCode: "TEST_UNAUTHORIZED",
        message: "登录已失效"
    });
});
