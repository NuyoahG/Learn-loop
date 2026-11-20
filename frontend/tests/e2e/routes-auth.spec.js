import { expect, test } from "@playwright/test";
import { installMockApi } from "./mock-api.js";
import { MASTERY, material, pdfBlocks } from "./fixtures.js";

function workspaceMocks() {
    return {
        materials: [material()],
        details: { 10: material() },
        blocks: { 10: pdfBlocks() },
        mastery: MASTERY
    };
}

test("未登录访问受保护路由时保留原路径和查询串", async ({ page }) => {
    const api = await installMockApi(page, { authenticated: false });
    await page.goto("/workspace?materialId=7");

    await expect(page).toHaveURL(/\/login\?next=%2Fworkspace%3FmaterialId%3D7$/);
    await expect(page.getByText("请输入账号和密码。")).toBeVisible();
    expect(api.unmatched).toEqual([]);
});

test("旧 .html 入口重定向到新路由并保留查询串", async ({ page }) => {
    const api = await installMockApi(page, {
        ...workspaceMocks(),
        answers: [],
        library: []
    });

    await page.goto("/workspace.html?materialId=10");
    await expect(page).toHaveURL("/workspace?materialId=10");
    await expect(page.getByRole("heading", { name: "学习工作台" })).toBeVisible();

    await page.goto("/web-basics.html?materialId=10");
    await expect(page).toHaveURL("/questions?materialId=10");
    await expect(page.getByRole("heading", { name: "题目中心与答题练习" })).toBeVisible();

    await page.goto("/answer-table");
    await expect(page).toHaveURL("/history");
    await expect(page.getByRole("heading", { name: "答题记录" })).toBeVisible();
    expect(api.unmatched).toEqual([]);
});

test("登录使用表单编码、先取 CSRF，成功后按 next 白名单跳转", async ({ page }) => {
    const api = await installMockApi(page, {
        authenticated: false,
        answers: []
    });
    await page.goto("/login?next=%2Fhistory%3Fpage%3D2");
    await expect(page.locator("#usernameInput")).toBeEnabled();

    await page.locator("#usernameInput").fill("demo_learner");
    await page.locator("#passwordInput").fill("correct-horse");
    await page.getByRole("button", { name: "登录" }).click();

    await page.waitForURL("/history?page=2");
    await expect(page.getByRole("heading", { name: "答题记录" })).toBeVisible();

    const login = api.calls.find(call => call.method === "POST" && call.pathname === "/api/auth/login");
    expect(login).toBeTruthy();
    expect(login.headers["content-type"]).toContain("application/x-www-form-urlencoded");
    expect(login.headers["x-csrf-token"]).toBe("e2e-token");
    expect(login.headers["x-learnloop-expected-user"]).toBeUndefined();
    expect(login.body).toBe("username=demo_learner&password=correct-horse");
    expect(api.calls.filter(call => call.pathname === "/api/auth/csrf").length).toBeGreaterThanOrEqual(2);
    expect(api.unmatched).toEqual([]);
});

test("登录 next 指向站外时回落到题目中心", async ({ page }) => {
    const api = await installMockApi(page, { authenticated: false, library: [] });
    await page.goto("/login?next=https%3A%2F%2Fevil.example%2Fsteal");
    await expect(page.locator("#usernameInput")).toBeEnabled();

    await page.locator("#usernameInput").fill("demo_learner");
    await page.locator("#passwordInput").fill("correct-horse");
    await page.getByRole("button", { name: "登录" }).click();

    await page.waitForURL("/questions");
    await expect(page.getByRole("heading", { name: "题目中心与答题练习" })).toBeVisible();
    expect(api.unmatched).toEqual([]);
});

test("注册发送 JSON、成功后不自动登录", async ({ page }) => {
    const api = await installMockApi(page, { authenticated: false });
    await page.goto("/register");
    await expect(page.locator("#registerUsernameInput")).toBeEnabled();

    await page.locator("#registerUsernameInput").fill("new_user");
    await page.locator("#registerPasswordInput").fill("password123");
    await page.locator("#confirmPasswordInput").fill("password123");
    await page.getByRole("button", { name: "注册" }).click();

    await expect(page.getByText("账号 new_user 注册成功")).toBeVisible();
    await expect(page.locator("#registerForm")).toBeHidden();
    await expect(page).toHaveURL("/register");

    const register = api.calls.find(call => call.method === "POST" && call.pathname === "/api/auth/register");
    expect(register).toBeTruthy();
    expect(register.headers["content-type"]).toContain("application/json");
    expect(register.headers["x-csrf-token"]).toBe("e2e-token");
    expect(register.headers["x-learnloop-expected-user"]).toBeUndefined();
    expect(JSON.parse(register.body)).toEqual({ username: "new_user", password: "password123" });
    expect(api.unmatched).toEqual([]);
});

test("退出先刷新 CSRF，POST 带当前账号上下文，然后回登录页", async ({ page }) => {
    const api = await installMockApi(page, workspaceMocks());
    await page.goto("/workspace");
    await expect(page.getByRole("heading", { name: "学习工作台" })).toBeVisible();

    await page.getByRole("button", { name: "退出登录" }).click();
    await page.waitForURL("/login");

    const logout = api.calls.find(call => call.method === "POST" && call.pathname === "/api/auth/logout");
    expect(logout).toBeTruthy();
    expect(logout.headers["x-csrf-token"]).toBe("e2e-token");
    expect(logout.headers["x-learnloop-expected-user"]).toBe("1");
    expect(api.unmatched).toEqual([]);
});
