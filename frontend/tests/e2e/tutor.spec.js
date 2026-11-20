import { expect, test } from "@playwright/test";
import { installMockApi } from "./mock-api.js";

test("小栗鼠标移入即展开，进入面板及输入时保持可用", async ({ page }) => {
    await installMockApi(page);
    await page.route("**/api/tutor/suggestion", route => route.fulfill({ status: 204 }));
    await page.goto("/workspace");
    const cat = page.getByRole("button", { name: "小栗老师", exact: true });
    const panel = page.getByRole("region", { name: "和小栗聊天" });
    const input = page.getByLabel("问小栗");
    await expect(panel).toBeHidden();
    await expect(cat).toHaveCSS("border-top-width", "0px");
    await expect(cat).toHaveCSS("background-color", "rgba(0, 0, 0, 0)");
    await expect(cat.locator(".tutor-sprite-tail")).toHaveCSS("animation-name", "tutor-tail");

    await cat.hover();
    await expect(panel).toBeVisible();
    await expect(panel).toHaveCSS("background-color", "rgb(237, 247, 240)");
    await expect(input).toHaveCSS("background-color", "rgb(255, 255, 255)");
    const practice = panel.getByRole("link", { name: "安排练习" });
    await expect(practice).toHaveCSS("text-decoration-line", "none");
    await expect(practice).toHaveCSS("background-color", "rgb(220, 239, 228)");
    await input.click();
    await page.mouse.move(10, 10);
    await input.fill("这是测试输入");
    await expect(panel).toBeVisible();
    await page.keyboard.press("Escape");
    await expect(panel).toBeHidden();
    await expect(cat).toBeFocused();
    await cat.press("Enter");
    await expect(input).toBeFocused();
    await expect(input).toHaveValue("这是测试输入");
    await page.keyboard.press("Escape");
    await cat.hover();
    await page.mouse.move(10, 10);
    await expect(panel).toBeHidden();
});

test.describe("触屏与减少动态效果", () => {
    test.use({ hasTouch: true, isMobile: true, reducedMotion: "reduce", viewport: { width: 390, height: 844 } });
    test("轻触打开小栗，猫咪动画遵循系统设置", async ({ page }) => {
        await installMockApi(page);
        await page.route("**/api/tutor/suggestion", route => route.fulfill({ status: 204 }));
        await page.goto("/workspace");
        const cat = page.getByRole("button", { name: "小栗老师", exact: true });
        await expect(cat.locator(".tutor-sprite-tail")).toHaveCSS("animation-name", "none");
        await cat.tap();
        await expect(page.getByRole("region", { name: "和小栗聊天" })).toBeVisible();
        await page.getByRole("button", { name: "收起" }).tap();
        await expect(page.getByRole("region", { name: "和小栗聊天" })).toBeHidden();
    });
});

test("答错后填写其他原因，猫咪可以打开对话并读取相关学习记录", async ({ page }) => {
    await installMockApi(page);
    const card = { topicId: "topic-1", versionId: "v1", dueAt: "2026-11-05T02:00:00",
        materialName: "数据库", topicName: "事务", content: "提交后数据是否保留？",
        optionA: "保留", optionB: "丢失", optionC: "不确定", optionD: "回滚", remaining: 1 };
    await page.route("**/api/review/**", async route => {
        if (route.request().url().endsWith("/today")) return route.fulfill({ json: { card } });
        return route.fulfill({ json: { attemptId: "attempt-1", selected: "B", correctAnswer: "A" } });
    });
    let saved = null;
    await page.route("**/api/tutor/**", async route => {
        const path = new URL(route.request().url()).pathname;
        if (path.endsWith("/suggestion")) return route.fulfill({ status: 204 });
        if (path.endsWith("/chat")) {
            expect(route.request().postDataJSON().message).toBe("怎么区分提交和回滚？");
            return route.fulfill({ json: { answer: "先看这次作答，再对比提交和回滚。", evidence: [
                { kind: "NOTE", id: "v1", label: "数据库 · 答题笔记" }
            ] } });
        }
        if (route.request().method() === "PUT") {
            saved = route.request().postDataJSON();
            return route.fulfill({ json: saved });
        }
        return saved ? route.fulfill({ json: saved }) : route.fulfill({ status: 204 });
    });
    await page.goto("/review");
    await page.getByRole("radio", { name: "B. 丢失" }).check();
    await page.getByRole("button", { name: "提交答案" }).click();
    await page.getByRole("radio", { name: "其他", exact: true }).check();
    await page.getByLabel("补充原因").fill("把提交看成了回滚");
    await page.getByRole("button", { name: "告诉老师" }).click();
    await expect(page.getByText("错因已保存")).toBeVisible();
    expect(saved).toEqual({ reason: "OTHER", detail: "把提交看成了回滚" });
    await expect(page.getByRole("button", { name: "记得" })).toBeEnabled();
    await page.getByRole("button", { name: "小栗老师" }).click();
    await page.getByLabel("问小栗").fill("怎么区分提交和回滚？");
    await page.getByRole("button", { name: "发送" }).click();
    await expect(page.getByText("先看这次作答，再对比提交和回滚。")).toBeVisible();
    await page.getByText("查看学习记录").click();
    await expect(page.getByRole("link", { name: "数据库 · 答题笔记" })).toBeVisible();
});
