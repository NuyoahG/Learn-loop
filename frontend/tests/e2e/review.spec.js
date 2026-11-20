import { expect, test } from "@playwright/test";
import { installMockApi } from "./mock-api.js";

test("到期题先作答再评分，失败时保留反馈并可重试", async ({ page }) => {
    await installMockApi(page);
    const card = { topicId: "topic-1", versionId: "v1", dueAt: "2026-11-05T02:00:00",
        materialName: "数据库", topicName: "事务", content: "提交后数据是否保留？",
        optionA: "保留", optionB: "丢失", optionC: "不确定", optionD: "回滚", remaining: 1 };
    let requests = 0;
    await page.route("**/api/review/**", async route => {
        const path = new URL(route.request().url()).pathname;
        if (path.endsWith("/today")) return route.fulfill({ json: { card: requests >= 2 ? null : card } });
        if (path.endsWith("/answer")) {
            expect(route.request().postDataJSON()).toMatchObject({ selected: "A", dueAt: card.dueAt });
            return route.fulfill({ json: { attemptId: "attempt-1", selected: "A", correctAnswer: "A", explanation: "事务持久性" } });
        }
        expect(path).toBe("/api/review/attempt-1/rating");
        expect(route.request().postDataJSON()).toEqual({ rating: 3 });
        requests++;
        if (requests === 1) return route.fulfill({ status: 503, json: { errorCode: "REVIEW_REQUEST_FAILED", message: "排期暂未完成" } });
        return route.fulfill({ json: { dueAt: "2026-11-06T03:30:00Z", rating: 3 } });
    });
    await page.goto("/review");
    await expect(page.getByRole("heading", { name: "今日复习" })).toBeVisible();
    await expect(page.getByRole("button", { name: "记得" })).toHaveCount(0);
    await page.getByRole("radio", { name: "A. 保留" }).check();
    await page.getByRole("button", { name: "提交答案" }).click();
    await expect(page.getByText("答对了 · 正确答案 A")).toBeVisible();
    await page.getByRole("button", { name: "记得" }).click();
    await expect(page.getByText("排期暂未完成", { exact: true })).toBeVisible();
    await expect(page.getByRole("button", { name: "忘了" })).toBeDisabled();
    await page.getByRole("button", { name: "记得" }).click();
    await expect(page.getByText(/下次复习/)).toBeVisible();
    await page.getByRole("button", { name: "下一题" }).click();
    await expect(page.getByText("今日已完成")).toBeVisible();
});
