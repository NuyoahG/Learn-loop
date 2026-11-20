import assert from "node:assert/strict";
import test from "node:test";
import { ApiError, apiRequest, clearCsrf, setExpectedUser } from "../../lib/api.js";

const originalFetch = globalThis.fetch;

function installFetch(handler) {
    const calls = [];
    globalThis.fetch = async (url, options = {}) => {
        calls.push({ url, options });
        return handler(url, options, calls);
    };
    return calls;
}

test.afterEach(() => {
    globalThis.fetch = originalFetch;
    clearCsrf();
    setExpectedUser(null);
});

test("GET does not request CSRF and uses same-origin no-store", async () => {
    const calls = installFetch(() => new Response(JSON.stringify({ userId: 7 }), {
        status: 200, headers: { "Content-Type": "application/json" }
    }));
    assert.deepEqual(await apiRequest("/api/auth/me"), { userId: 7 });
    assert.equal(calls.length, 1);
    assert.equal(calls[0].options.credentials, "same-origin");
    assert.equal(calls[0].options.cache, "no-store");
});

test("POST fetches CSRF and sends expected-user", async () => {
    const calls = installFetch((url) => {
        if (url === "/api/auth/csrf") {
            return new Response(JSON.stringify({ headerName: "X-CSRF-TOKEN", token: "token" }), {
                status: 200, headers: { "Content-Type": "application/json" }
            });
        }
        return new Response(null, { status: 204 });
    });
    setExpectedUser(7);
    assert.equal(await apiRequest("/api/generation/jobs", { method: "POST", body: "{}" }), null);
    assert.equal(calls.length, 2);
    assert.equal(calls[0].url, "/api/auth/csrf");
    assert.equal(calls[1].options.headers.get("X-CSRF-TOKEN"), "token");
    assert.equal(calls[1].options.headers.get("X-LearnLoop-Expected-User"), "7");
});

test("keeps HTTP status when a 401 body is not JSON", async () => {
    installFetch(() => new Response("<html>", { status: 401, headers: { "Content-Type": "text/html" } }));
    await assert.rejects(apiRequest("/api/learning/question-library"), error => {
        assert.equal(error instanceof ApiError, true);
        assert.equal(error.status, 401);
        return true;
    });
});

test("refetches CSRF after a 401 and never retries the failed POST", async () => {
    let csrfCount = 0, postCount = 0;
    installFetch((url) => {
        if (url === "/api/auth/csrf") {
            csrfCount++;
            return new Response(JSON.stringify({ headerName: "X-CSRF-TOKEN", token: "t" + csrfCount }), {
                status: 200, headers: { "Content-Type": "application/json" }
            });
        }
        postCount++;
        return new Response(JSON.stringify({ errorCode: "LOGIN_REQUIRED", message: "请先登录。" }), {
            status: 401, headers: { "Content-Type": "application/json" }
        });
    });
    await assert.rejects(apiRequest("/api/learning/attempts", { method: "POST", body: "{}" }), error => {
        assert.equal(error.status, 401);
        assert.equal(error.errorCode, "LOGIN_REQUIRED");
        return true;
    });
    assert.equal(postCount, 1);
    assert.equal(csrfCount, 1);
});
