import { expect, test } from "@playwright/test";
import { installMockApi } from "./mock-api.js";

test("恢复辅导、保存纠正、再次提问和查看实际查询轨迹", async ({ page }) => {
    await installMockApi(page);
    let chatCalls = 0;
    let lesson = { id: "12345678-1111-4111-8111-123456789abc", question: "如何理解隔离性？",
        answer: "先比较不同隔离级别的可见范围。", teaching: "COMPARE", evidence: [
            { kind: "SOURCE", materialId: 183, id: "8", label: "事务原文" }
        ], trace: [{ tool: "search_material", count: 1 }], feedback: "PENDING", detail: "", materialId: null };
    await page.route("**/api/tutor/**", async route => {
        const path = new URL(route.request().url()).pathname;
        if (path.endsWith("/suggestion")) return route.fulfill({ status: 204 });
        if (path.endsWith("/lessons")) return route.fulfill({ json: [lesson] });
        if (path.endsWith("/feedback")) {
            const body = route.request().postDataJSON();
            lesson = { ...lesson, feedback: body.status, detail: body.detail };
            return route.fulfill({ json: lesson });
        }
        if (path.endsWith("/chat")) {
            chatCalls++;
            expect(route.request().postDataJSON().requestId).toMatch(/^[a-f0-9-]{36}$/);
            expect(route.request().postDataJSON()).not.toHaveProperty("history");
            return route.fulfill({ json: { ...lesson, id: "22345678-1111-4111-8111-123456789abc", answer: "换成两个事务的具体例子。", feedback: "PENDING", detail: "" } });
        }
        return route.fulfill({ status: 404 });
    });
    await page.goto("/workspace");
    await page.getByRole("button", { name: "小栗老师", exact: true }).hover();
    const panel = page.getByRole("region", { name: "和小栗聊天" });
    await expect(panel.getByText(lesson.answer)).toBeVisible();
    expect(chatCalls).toBe(0);
    await panel.getByText("本次查询", { exact: true }).click();
    await expect(panel.getByText("资料原文 · 1 条")).toBeVisible();
    await panel.getByRole("button", { name: "判断不对", exact: true }).click();
    await expect(panel.getByRole("button", { name: "判断不对", exact: true })).toHaveAttribute("aria-pressed", "true");
    await panel.getByLabel("补充说明（可选）").fill("我想问可重复读，不是读未提交");
    await panel.getByRole("button", { name: "保存说明", exact: true }).click();
    await expect.poll(() => lesson.detail).toContain("可重复读");
    expect(chatCalls).toBe(0);
    await panel.getByRole("button", { name: "换个讲法", exact: true }).click();
    await expect(panel.getByLabel("问小栗")).toHaveValue(/请换一种讲法/);
    expect(chatCalls).toBe(0); // 填问题和反馈不会悄悄调用付费模型。
    await panel.getByRole("button", { name: "发送", exact: true }).click();
    await expect(panel.getByText("换成两个事务的具体例子。")).toBeVisible();
    expect(chatCalls).toBe(1);
});

test("换资料时切换辅导范围，发送只带当前资料", async ({ page }) => {
    await installMockApi(page);
    const scopes = [];
    await page.route("**/api/tutor/**", async route => {
        const url = new URL(route.request().url());
        if (url.pathname.endsWith("/suggestion")) return route.fulfill({ status: 204 });
        if (url.pathname.endsWith("/lessons")) { scopes.push(url.searchParams.get("materialId"));return route.fulfill({ json: [] }); }
        if (url.pathname.endsWith("/chat")) {
            expect(route.request().postDataJSON().materialId).toBe(183);
            return route.fulfill({ json: { answer: "当前资料的说明", evidence: [], trace: [] } });
        }
        return route.fulfill({ status: 404 });
    });
    await page.goto("/workspace?materialId=183");
    await page.getByRole("button", { name: "小栗老师", exact: true }).hover();
    const panel = page.getByRole("region", { name: "和小栗聊天" });
    await expect.poll(() => scopes).toContain("183");
    await panel.getByLabel("问小栗").fill("我该关注什么？");
    await panel.getByRole("button", { name: "发送", exact: true }).click();
    await expect(panel.getByText("当前资料的说明")).toBeVisible();
    await expect(panel.getByRole("link", { name: "安排练习" })).toHaveAttribute("href", "/tutor-practice?materialId=183");
});
