import { expect, test } from "@playwright/test";
import { installMockApi } from "./mock-api.js";

test("从导航管理提醒和已保存的教学反馈", async ({ page }) => {
    const state = await installMockApi(page);
    let lesson = { id: "lesson-1", question: "为什么需要分页？", answer: "一次只取需要的数据。", feedback: "PENDING", detail: "" };
    await page.route("**/api/tutor/lessons", route => route.fulfill({ json: [lesson] }));
    await page.route("**/api/tutor/lessons/lesson-1/feedback", route => {
        expect(route.request().method()).toBe("PUT");
        const body = route.request().postDataJSON();
        lesson = { ...lesson, feedback: body.status, detail: body.detail };
        return route.fulfill({ json: lesson });
    });
    await page.goto("/workspace");
    const link = page.getByRole("navigation", { name: "主要导航" }).getByRole("link", { name: "小栗老师" });
    await link.click();
    await expect(page).toHaveURL("/tutor");
    await expect(link).toHaveAttribute("aria-current", "page");
    await expect(page.getByRole("heading", { name: "小栗老师管理" })).toBeVisible();
    await page.getByRole("checkbox", { name: "在此浏览器显示学习提醒" }).uncheck();
    await page.getByText(lesson.question, { exact: true }).click();
    await expect(page.getByText(lesson.answer, { exact: true })).toBeVisible();
    await page.getByRole("button", { name: "有帮助" }).click();
    await expect(page.getByRole("button", { name: "有帮助" })).toHaveAttribute("aria-pressed", "true");
    await page.reload();
    await expect(page.getByRole("checkbox")).not.toBeChecked();
    await page.getByText(lesson.question, { exact: true }).click();
    await expect(page.getByRole("button", { name: "有帮助" })).toHaveAttribute("aria-pressed", "true");
    await page.getByRole("navigation", { name: "主要导航" }).getByRole("link", { name: "个人中心" }).click();
    await page.getByRole("button", { name: "小栗老师", exact: true }).click();
    await expect(page.getByRole("button", { name: "开启提醒" })).toBeVisible();
    expect(state.calls.some(call => call.pathname === "/api/tutor/chat")).toBe(false);
});
