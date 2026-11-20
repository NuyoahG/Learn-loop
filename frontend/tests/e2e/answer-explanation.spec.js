import { expect, test } from "@playwright/test";
import { installMockApi } from "./mock-api.js";
import { QUESTION, VARIANT_QUESTION, material } from "./fixtures.js";

for (const correct of [true, false]) {
    test(`${correct ? "答对" : "答错"}显示简单讲解，隐藏把握程度，只有点击才生成详解`, async ({ page }) => {
        const state = await installMockApi(page, {
            materials: [material()], library: [QUESTION],
            briefExplanation: "幂等是同一操作执行一次和多次具有相同效果。",
            detailedExplanation: "GET 读取资源。重复读取不会要求修改资源状态，因此符合幂等定义。",
            submission: { attemptId: "attempt-one", correct, correctOption: "A", eligible: false }
        });
        await page.goto("/questions");
        await page.locator(".questions-card").first().click();
        await expect(page.getByText("这道题，你有多确定？", { exact: true })).toBeVisible();
        await page.locator(`input[name="practiceAnswer"][value="${correct ? "A" : "B"}"]`).check();
        await page.getByRole("button", { name: "提交答案", exact: true }).click();
        const explanation = page.getByRole("region", { name: "答案讲解" });
        await expect(explanation.getByText("幂等是同一操作执行一次和多次具有相同效果。", { exact: true })).toBeVisible();
        await expect(page.getByText("这道题，你有多确定？", { exact: true })).toHaveCount(0);
        await expect(page.locator("#practiceHint")).toHaveCount(0);
        const calls = () => state.calls.filter(call => call.pathname.includes("/explanations/") && call.method === "POST");
        expect(calls()).toHaveLength(0);
        await explanation.getByRole("button", { name: "我需要详解", exact: true }).click();
        await expect(explanation.getByRole("heading", { name: "详细讲解" })).toBeVisible();
        await expect(explanation.getByText("GET 读取资源。重复读取不会要求修改资源状态，因此符合幂等定义。")).toBeVisible();
        await explanation.getByRole("button", { name: "收起详解", exact: true }).click();
        await explanation.getByRole("button", { name: "查看详解", exact: true }).click();
        expect(calls()).toHaveLength(1);
    });
}

test("详解失败可重试，切题后旧答案不会出现在新题", async ({ page }) => {
    await installMockApi(page, { materials: [material()], library: [QUESTION, VARIANT_QUESTION],
        submission: { attemptId: "attempt-one", correct: true, correctOption: "A", eligible: false } });
    let calls = 0;
    let release;
    await page.route("**/api/learning/explanations/**", async route => {
        if (route.request().method() === "GET") return route.fulfill({ json: { brief: "本题的简短解析。" } });
        calls++;
        if (calls === 1) return route.fulfill({ status: 503, json: { errorCode: "EXPLANATION_UNAVAILABLE", message: "模型暂时不可用。" } });
        await new Promise(resolve => { release = resolve; });
        await route.fulfill({ json: { answer: "第一道题的迟到详解。" } }).catch(() => {});
    });
    await page.goto("/questions");
    await page.locator(".questions-card").first().click();
    await page.locator('input[name="practiceAnswer"][value="A"]').check();
    await page.getByRole("button", { name: "提交答案", exact: true }).click();
    await page.getByRole("button", { name: "我需要详解", exact: true }).click();
    await expect(page.getByText("模型暂时不可用。", { exact: true })).toBeVisible();
    await page.getByRole("button", { name: "我需要详解", exact: true }).click();
    await expect(page.getByRole("button", { name: "正在生成详解…", exact: true })).toBeDisabled();
    await page.locator(".questions-card").nth(1).click();
    await expect(page.locator("#practiceQuestion")).toHaveText(VARIANT_QUESTION.content);
    release();
    await expect(page.getByText("第一道题的迟到详解。", { exact: true })).toHaveCount(0);
    await expect(page.getByRole("region", { name: "答案讲解" })).toHaveCount(0);
});

test("今日复习复用已有简单解析并提供详解按钮", async ({ page }) => {
    const state = await installMockApi(page);
    const card = { topicId: "topic-1", versionId: "version-1", dueAt: "2026-11-19T00:00:00Z", materialName: "数据库",
        topicName: "事务", content: "提交后是否持久保存？", optionA: "是", optionB: "否", optionC: "无关", optionD: "不知道", remaining: 1 };
    await page.route("**/api/review/**", route => route.fulfill({ json: route.request().url().endsWith("/today")
        ? { card } : { attemptId: "review-one", selected: "A", correctAnswer: "A", explanation: "持久性保证提交的数据得以保存。" } }));
    await page.goto("/review");
    await page.getByRole("radio", { name: "A. 是", exact: true }).check();
    await page.getByRole("button", { name: "提交答案", exact: true }).click();
    await expect(page.getByText("持久性保证提交的数据得以保存。", { exact: true })).toBeVisible();
    await page.getByRole("button", { name: "我需要详解", exact: true }).click();
    await expect(page.getByRole("heading", { name: "详细讲解" })).toBeVisible();
    expect(state.calls.some(call => call.method === "POST" && call.pathname === "/api/learning/explanations/REVIEW/review-one")).toBe(true);
});
