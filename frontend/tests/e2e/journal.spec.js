import { expect, test } from "@playwright/test";
import { installMockApi } from "./mock-api.js";
import { material, pdfBlocks, QUESTION } from "./fixtures.js";

test("题目下保存笔记，独立笔记页可查找和编辑", async ({ page }) => {
    await installMockApi(page, { materials: [material()], library: [QUESTION] });
    await page.goto("/questions");
    await page.getByRole("button", { name: /HTTP 的幂等方法/ }).click();
    const field = page.getByRole("textbox", { name: "学习笔记" });
    await field.fill("GET 不修改资源状态。");
    await page.getByRole("button", { name: "保存笔记" }).click();
    await expect(page.getByText("笔记已保存")).toBeVisible();
    await page.getByRole("link", { name: "我的笔记" }).click();
    await page.getByRole("searchbox", { name: "搜索笔记" }).fill("GET");
    await page.getByRole("button", { name: "搜索" }).click();
    await page.getByRole("button", { name: /HTTP 的幂等方法/ }).click();
    await expect(field).toHaveValue("GET 不修改资源状态。");
    await field.fill("GET 用来读取资源。");
    await page.getByRole("button", { name: "保存笔记" }).click();
    await expect(page.getByText("笔记已保存")).toBeVisible();
    await expect(page.getByRole("link", { name: /查看资料题库/ })).toHaveAttribute("href", /versionId=/);
    await page.reload();
    await page.getByRole("button", { name: /HTTP 的幂等方法/ }).click();
    await expect(field).toHaveValue("GET 用来读取资源。");
});

test("选中文字后提问，主动保存马卡龙高亮并刷新回看", async ({ page }) => {
    const doc = material({ originalName: "数据库事务" });
    const blocks = pdfBlocks();
    blocks[0].text = "事务提交😀表示本次操作已经完成。";
    const state = await installMockApi(page, { materials: [doc], details: { 10: doc }, blocks: { 10: blocks } });
    await page.goto("/reading?materialId=10&view=text");
    await expect(page.getByRole("heading", { name: "数据库事务" })).toBeVisible();
    await page.locator("[data-reader-text='101'] span").evaluate(node => {
        const selection = window.getSelection();
        const range = document.createRange();
        range.setStart(node.firstChild, 2);
        range.setEnd(node.firstChild, 6);
        selection.removeAllRanges();
        selection.addRange(range);
        node.dispatchEvent(new PointerEvent("pointerup", { bubbles: true }));
    });
    const askOption = page.getByRole("button", { name: /问问 AI/ });
    await expect(askOption).toBeVisible();
    const position = await askOption.evaluate(button => {
        const selection = window.getSelection().getRangeAt(0).getBoundingClientRect();
        const popup = button.getBoundingClientRect();
        return { gap: Math.min(Math.abs(popup.top - selection.bottom), Math.abs(selection.top - popup.bottom)),
            fixed: getComputedStyle(button).position };
    });
    expect(position.fixed).toBe("fixed");
    expect(position.gap).toBeLessThan(12);
    await askOption.click();
    await page.getByRole("textbox", { name: "你想问什么？" }).fill("这里提交意味着什么？");
    await page.getByRole("radio", { name: "详细", exact: true }).check();
    await page.getByRole("button", { name: "发送问题" }).click();
    await expect(page.getByText("这是阅读答案。")).toBeVisible();
    expect(state.highlights).toHaveLength(0);
    const asked = state.calls.find(call => call.pathname.endsWith("/ask"));
    expect(JSON.parse(asked.body)).toMatchObject({
        anchor: { start: 2, end: 6, quote: "提交😀", parseAttempt: 1 },
        question: "这里提交意味着什么？", detail: "DETAILED"
    });
    await page.getByRole("radio", { name: "香芋紫" }).check();
    await page.getByRole("button", { name: "保存回答并高亮" }).click();
    const highlight = page.getByRole("button", { name: /查看已保存问答/ });
    await expect(highlight).toHaveAttribute("data-color", "LILAC");
    await page.getByRole("button", { name: "编辑学习标记" }).click();
    await page.getByLabel("标记意图").selectOption("CONFUSED");
    await page.getByLabel("关联考点").selectOption("topic-1");
    await page.getByRole("button", { name: "保存标记" }).click();
    await expect(page.getByText("容易混淆 · 持久性")).toBeVisible();
    await page.reload();
    await highlight.click();
    await expect(page.getByText("这是阅读答案。")).toBeVisible();
    await expect(page.getByText("容易混淆 · 持久性")).toBeVisible();
    await page.getByRole("button", { name: "编辑学习标记" }).click();
    await page.getByLabel("标记意图").selectOption("HIGHLIGHT");
    await page.getByLabel("关联考点").selectOption("");
    await page.getByRole("button", { name: "保存标记" }).click();
    await expect(page.getByText("只高亮", { exact: true })).toBeVisible();
    expect(state.highlights[0].topicId).toBeNull();
    await page.goto("/reading?materialId=10&view=text&highlight=" + state.highlights[0].id);
    await expect(page.getByRole("heading", { name: "已保存的问答" })).toBeVisible();
    expect(state.calls.filter(call => call.pathname.endsWith("/ask"))).toHaveLength(1);
});

test("连续读到后文，默认重新登录后回到同一账号的段落", async ({ page }) => {
    const doc = material({ blockCount: 26 });
    const blocks = Array.from({ length: 26 }, (_, index) => ({
        ...pdfBlocks()[0], blockId: 101 + index, blockNo: index + 1,
        text: `段落 ${index + 1}。` + "这是一段用于验证连续阅读和位置恢复的资料。".repeat(24)
    }));
    const state = await installMockApi(page, { materials: [doc], details: { 10: doc }, blocks: { 10: blocks } });
    await page.setViewportSize({ width: 1440, height: 900 });
    await page.goto("/reading?materialId=10&view=text");
    const first = page.locator("[data-block-no='1']");
    await expect(first).toBeVisible();
    await page.locator("[data-block-no='12']").scrollIntoViewIfNeeded();
    const target = page.locator("[data-block-no='18']");
    await expect(target).toBeVisible();
    await expect(first).toBeAttached();
    await expect(page.getByRole("button", { name: "下一页", exact: true })).toHaveCount(0);
    await target.evaluate(node => window.scrollBy(0, node.getBoundingClientRect().top - 85));
    await expect.poll(() => page.evaluate(() => JSON.parse(localStorage.getItem("learnloop:last-reading:1"))))
        .toMatchObject({ blockId: 118, page: 2 });
    await page.getByRole("button", { name: "退出登录" }).click();
    await page.waitForURL("/login");
    await page.getByLabel("用户名").fill("demo_learner");
    await page.getByLabel("密码", { exact: true }).fill("fixture-password");
    await page.getByRole("button", { name: "登录", exact: true }).click();
    await page.waitForURL("/reading");
    await expect(target).toBeInViewport();
    await expect.poll(() => target.evaluate(node => Math.round(node.getBoundingClientRect().top))).toBe(85);
    await expect(page.getByRole("button", { name: "加载前文" })).toBeVisible();
    await page.getByRole("button", { name: "加载前文" }).evaluate(button => button.click());
    await expect(first).toBeAttached();
    await expect(target).toBeInViewport();

    state.user = { userId: 2, username: "bob" };
    await page.goto("/login");
    await page.waitForURL("/workspace");
    await page.getByRole("link", { name: "资料阅读" }).click();
    await expect(page.getByRole("link", { name: /从资料列表选择一份资料/ })).toBeVisible();
});

test("已登录页面使用视口宽度且小屏无横向溢出", async ({ page }) => {
    const doc = material();
    await installMockApi(page, { details: { 10: doc }, blocks: { 10: pdfBlocks() } });
    for (const width of [1440, 390]) {
        await page.setViewportSize({ width, height: 900 });
        for (const path of ["/workspace", "/questions", "/history", "/materials", "/notes", "/reading?materialId=10"]) {
            await page.goto(path);
            await expect(page.getByRole("navigation", { name: "主要导航" })).toBeVisible();
            expect(await page.locator("main.practice-page").evaluate(node => node.getBoundingClientRect().width)).toBe(width);
            expect(await page.evaluate(() => document.documentElement.scrollWidth)).toBe(width);
        }
    }
});

test.describe("触屏阅读", () => {
    test.use({ hasTouch: true, isMobile: true, viewport: { width: 390, height: 844 } });

    test("正文选区变化后可以打开问答", async ({ page }) => {
        const doc = material();
        await installMockApi(page, { details: { 10: doc }, blocks: { 10: pdfBlocks() } });
        await page.goto("/reading?materialId=10&view=text");
        await page.locator("[data-reader-text='101'] span").evaluate(node => {
            const range = document.createRange();
            range.setStart(node.firstChild, 0);
            range.setEnd(node.firstChild, 7);
            window.getSelection().removeAllRanges();
            window.getSelection().addRange(range);
            document.dispatchEvent(new Event("selectionchange"));
        });
        await page.getByRole("button", { name: /问问 AI/ }).tap();
        await expect(page.getByRole("textbox", { name: "你想问什么？" })).toBeVisible();
    });
});

test("登录与注册铺满窗口", async ({ page }) => {
    await installMockApi(page, { authenticated: false });
    for (const width of [1440, 390]) {
        await page.setViewportSize({ width, height: 900 });
        for (const path of ["/login", "/register"]) {
            await page.goto(path);
            const screen = page.locator(".auth-screen");
            await expect(screen).toBeVisible();
            expect(await screen.evaluate(node => node.getBoundingClientRect().width)).toBe(width);
            expect(await page.evaluate(() => document.documentElement.scrollWidth)).toBe(width);
        }
    }
});
