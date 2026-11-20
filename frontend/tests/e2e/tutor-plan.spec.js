import { expect, test } from "@playwright/test";
import { installMockApi } from "./mock-api.js";

test("确认前展示依据，刷新后继续真实练习入口", async ({ page }) => {
    await installMockApi(page);
    const item = { versionId: "q1", topicId: "t1", materialId: 3, material: "数据库",
        topic: "持久性", dueAt: null, recentErrors: 2 };
    const draft = { id: "plan-1", status: "DRAFT", minutes: 10, reason: "近期错答两次",
        planner: "AGENT", trace: ["PRACTICE"], items: [item], position: 0 };
    let saved = null;
    let submitted = false;
    await page.route("**/api/tutor/plans**", route => {
        const path = new URL(route.request().url()).pathname;
        if (path.endsWith("/latest")) return saved ? route.fulfill({ json: saved }) : route.fulfill({ status: 204 });
        if (path.endsWith("/plans")) {
            expect(route.request().postDataJSON().minutes).toBe(10);
            saved = draft;
        } else if (path.endsWith("/confirm")) {
            saved = { ...draft, status: "ACTIVE" };
        } else if (path.endsWith("/open")) {
            expect(saved.status).toBe("ACTIVE");
            return route.fulfill({ json: { position: 0, kind: "PRACTICE", attemptId: "attempt-1",
                item, content: "提交后数据是否保留？", optionA: "保留", optionB: "丢失",
                optionC: "不确定", optionD: "回滚", finished: submitted } });
        } else if (path.endsWith("/advance")) {
            expect(submitted).toBe(true);
            expect(route.request().postDataJSON()).toEqual({ position: 0 });
            saved = { ...draft, status: "COMPLETED", position: 1 };
        }
        return route.fulfill({ json: saved });
    });
    await page.route("**/api/learning/attempts/attempt-1/answers", route => {
        expect(route.request().postDataJSON().selectedOption).toBe("A");
        submitted = true;
        return route.fulfill({ json: { attemptId: "attempt-1", correct: true, correctOption: "A" } });
    });
    await page.goto("/tutor-practice");
    await page.getByRole("button", { name: "安排练习", exact: true }).click();
    await expect(page.getByText("近期错答两次")).toBeVisible();
    await expect(page.getByText("提交后数据是否保留？")).toHaveCount(0);
    await page.reload();
    await page.getByRole("button", { name: "确认并开始" }).click();
    await page.getByRole("radio", { name: /A.*保留/ }).check();
    await page.getByRole("button", { name: "提交答案" }).click();
    await expect(page.getByText("答对了 · 正确选项 A")).toBeVisible();
    await page.reload();
    await page.getByRole("button", { name: "继续练习" }).click();
    await page.getByRole("button", { name: "完成练习" }).click();
    await expect(page.getByRole("heading", { name: "练习已完成" })).toBeVisible();
});
