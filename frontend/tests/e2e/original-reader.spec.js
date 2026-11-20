import { readFileSync } from "node:fs";
import { expect, test } from "@playwright/test";
import { installMockApi } from "./mock-api.js";
import { material } from "./fixtures.js";

const fixture = name => readFileSync(new URL(`./fixtures/${name}`, import.meta.url));

for (const width of [720, 1440]) {
    test(`长回答只滚动内容，紧凑标题和收起按钮保持可见（${width}px）`, async ({ page }) => {
        await page.setViewportSize({ width, height: 680 });
        await installMockApi(page, {
            details: { 10: material({ blockCount: 1 }) }, originals: { 10: fixture("outline.pdf") },
            blocks: { 10: [{ blockId: 101, blockNo: 1, text: "First page",
                sourceLocator: { kind: "pdf", pageNumber: 1 } }] }
        });
        await page.route("**/api/reading/10/ask", route => route.fulfill({ json: {
            ...route.request().postDataJSON(), answer: "一句通俗解释。\n".repeat(120),
            expiresAt: 9999999999, receipt: "long-answer"
        } }));
        await page.goto("/reading?materialId=10");
        const text = page.locator('.pdfViewer [data-page-number="1"] .textLayer span').filter({ hasText: "First page" });
        await expect(text).toBeVisible();
        await text.evaluate(node => {
            const range = document.createRange();
            range.selectNodeContents(node);
            const selection = window.getSelection();
            selection.removeAllRanges();
            selection.addRange(range);
            node.dispatchEvent(new PointerEvent("pointerup", { bubbles: true }));
        });
        await page.getByRole("button", { name: /问问 AI/ }).click();
        const panel = page.getByRole("complementary", { name: "AI 问答" });
        await panel.getByRole("button", { name: "发送问题" }).click();
        await expect(panel.locator(".reading-answer-text")).toBeVisible();
        const heading = panel.locator(":scope > .journal-heading");
        const before = await heading.boundingBox();
        expect(before.height).toBeLessThanOrEqual(44);
        const body = panel.locator(".pdf-question-content");
        await body.evaluate(node => { node.scrollTop = node.scrollHeight; });
        expect(await body.evaluate(node => node.scrollTop)).toBeGreaterThan(100);
        const after = await heading.boundingBox();
        expect(Math.abs(before.y - after.y)).toBeLessThan(1);
        await expect(panel.getByRole("button", { name: "收起", exact: true })).toBeInViewport();
        await expect(panel.getByRole("link", { name: "打开文字版", exact: true })).toHaveCount(0);
        await page.screenshot({ path: test.info().outputPath(`compact-reader-${width}.png`) });
    });
}

test("AI 问答默认占右侧四分之一，收起后阅读区扩展", async ({ page }) => {
    await page.setViewportSize({ width: 1440, height: 900 });
    const state = await installMockApi(page, {
        details: { 10: material() }, originals: { 10: fixture("outline.pdf") }
    });
    await page.goto("/reading?materialId=10");
    const panel = page.getByRole("complementary", { name: "AI 问答" });
    await expect(panel).toBeVisible();
    const layout = await page.locator(".original-layout").boundingBox();
    const pane = await page.locator(".pdf-ai-pane").boundingBox();
    expect(pane.width / layout.width).toBeGreaterThan(.23);
    expect(pane.width / layout.width).toBeLessThan(.27);
    const originalWidth = (await page.locator(".pdf-original").boundingBox()).width;
    await panel.getByRole("button", { name: "收起", exact: true }).click();
    await expect(panel).toBeHidden();
    expect((await page.locator(".pdf-original").boundingBox()).width).toBeGreaterThan(originalWidth * 1.2);
    await page.getByRole("button", { name: "AI 问答", exact: true }).click();
    await expect(panel).toBeVisible();
    expect(state.calls.some(call => call.pathname.endsWith("/ask"))).toBe(false);
});

test("PDF 缩放复用原件，适合宽度随阅读区变化", async ({ page }) => {
    const state = await installMockApi(page, {
        details: { 10: material() }, originals: { 10: fixture("outline.pdf") }
    });
    await page.goto("/reading?materialId=10");
    const firstPage = page.locator('.pdfViewer [data-page-number="1"]');
    const percentage = page.getByLabel("PDF 缩放比例");
    const fit = page.getByRole("button", { name: "适合宽度" });
    await expect(page.locator(".pdfViewer canvas").first()).toBeVisible();
    await expect(fit).toHaveAttribute("aria-pressed", "true");
    const originalPercent = Number((await percentage.textContent()).replace("%", ""));
    const originalWidth = (await firstPage.boundingBox()).width;
    await page.getByRole("button", { name: "放大 PDF" }).click();
    await expect.poll(async () => Number((await percentage.textContent()).replace("%", ""))).toBeGreaterThan(originalPercent);
    await expect(fit).toHaveAttribute("aria-pressed", "false");
    await expect.poll(async () => (await firstPage.boundingBox()).width).toBeGreaterThan(originalWidth);
    const manualPercent = await percentage.textContent();
    await page.setViewportSize({ width: 920, height: 720 });
    await expect(percentage).toHaveText(manualPercent);
    await fit.click();
    await expect(fit).toHaveAttribute("aria-pressed", "true");
    await expect.poll(async () => firstPage.evaluate(element => {
        const reader = element.closest(".pdf-scroll");
        return element.getBoundingClientRect().width <= reader.clientWidth;
    })).toBe(true);
    const fitWidth = (await firstPage.boundingBox()).width;
    await page.setViewportSize({ width: 1100, height: 720 });
    await expect.poll(async () => (await firstPage.boundingBox()).width).toBeGreaterThan(fitWidth);
    await page.getByRole("button", { name: "缩小 PDF" }).click();
    await expect(fit).toHaveAttribute("aria-pressed", "false");
    expect(state.calls.filter(call => call.pathname.endsWith("/original"))).toHaveLength(1);
});

for (const deviceScaleFactor of [1, 1.25, 2]) {
    test.describe(`PDF 画布与页框对齐（像素比例 ${deviceScaleFactor}）`, () => {
        test.use({ deviceScaleFactor });
        test("高分屏、缩放和侧栏切换不会把画布裁成半页", async ({ page }) => {
            await page.setViewportSize({ width: 1440, height: 900 });
            await installMockApi(page, { details: { 10: material() }, originals: { 10: fixture("outline.pdf") } });
            await page.goto("/reading?materialId=10");
            const first = page.locator('.pdfViewer [data-page-number="1"]');
            const fit = page.getByRole("button", { name: "适合宽度" });
            // 只检查“画布可见”无法发现高分屏裁切；必须核对渲染像素与 CSS 显示尺寸的关系。
            async function aligned() {
                await expect.poll(() => first.evaluate(element => {
                    const wrapper = element.querySelector(".canvasWrapper")?.getBoundingClientRect();
                    const canvas = element.querySelector("canvas");
                    const rendered = canvas?.getBoundingClientRect();
                    const text = element.querySelector(".textLayer")?.getBoundingClientRect();
                    if (!wrapper?.width || !rendered?.width || !text?.width) return false;
                    return Math.abs(rendered.width - wrapper.width) < 1
                        && Math.abs(rendered.height - wrapper.height) < 1
                        && Math.abs(rendered.x - text.x) < 1 && Math.abs(rendered.y - text.y) < 1
                        && Math.abs(rendered.width - text.width) < 1 && Math.abs(rendered.height - text.height) < 1
                        && Math.abs(canvas.width / rendered.width - devicePixelRatio) < .03;
                })).toBe(true);
            }
            await expect(first.locator("canvas")).toBeVisible();
            await aligned();
            await page.getByRole("button", { name: "放大 PDF" }).click();
            await aligned();
            await page.getByRole("button", { name: "缩小 PDF" }).click();
            await aligned();
            await page.setViewportSize({ width: 1000, height: 760 });
            await fit.click();
            await aligned();
            await page.getByRole("complementary", { name: "AI 问答" }).getByRole("button", { name: "收起", exact: true }).click();
            await aligned();
            await expect.poll(() => first.evaluate(element => element.getBoundingClientRect().width
                <= element.closest(".pdf-scroll").clientWidth)).toBe(true);
            await page.getByRole("button", { name: "AI 问答", exact: true }).click();
            await aligned();
            await expect.poll(() => first.evaluate(element => element.getBoundingClientRect().width
                <= element.closest(".pdf-scroll").clientWidth)).toBe(true);
        });
    });
}

test("原件 PDF 使用书签跳转，收起目录并恢复页码", async ({ page }) => {
    const doc = material({ parseStatus: "UPLOADED", blockCount: 0 });
    const state = await installMockApi(page, {
        materials: [doc], details: { 10: doc }, originals: { 10: fixture("outline.pdf") }
    });
    await page.goto("/materials");
    await page.locator(".material-card").click();
    await expect(page.locator(".pdfViewer canvas").first()).toBeVisible();
    await page.getByRole("button", { name: "目录", exact: true }).click();
    const outline = page.getByRole("complementary", { name: "文档目录" });
    const chapter = outline.getByRole("button", { name: "First page 第 1 页" });
    const subsection = outline.getByRole("button", { name: "Second page 第 2 页" });
    await expect(chapter).toHaveAttribute("aria-expanded", "false");
    await expect(subsection).toHaveCount(0);
    await expect(outline.getByRole("button", { name: "跳转到 First page" })).toBeVisible();
    await chapter.click();
    await expect(chapter).toHaveAttribute("aria-expanded", "true");
    await subsection.click();
    await expect(page.getByRole("complementary", { name: "文档目录" })).toHaveCount(0);
    const currentPage = () => page.evaluate(() => JSON.parse(localStorage.getItem("learnloop:last-reading:1"))?.position?.page);
    await expect.poll(currentPage).toBe(2);
    await page.goto("/reading");
    await expect(page.locator('.pdfViewer [data-page-number="2"] canvas')).toBeInViewport();
    await page.getByRole("button", { name: "目录", exact: true }).click();
    await expect(chapter).toHaveAttribute("aria-expanded", "false");
    await outline.getByRole("button", { name: "跳转到 First page" }).click();
    await expect.poll(currentPage).toBe(1);
    expect(state.calls.find(call => call.pathname.endsWith("/original")).headers["x-learnloop-expected-user"]).toBe("1");
    expect(state.calls.some(call => call.pathname.endsWith("/blocks"))).toBe(false);
});

test("Word 标题可跳转，纯文本没有目录时显示失败提示", async ({ page }) => {
    const word = material({ originalName: "课程.docx", fileType: "docx" });
    const text = material({ materialId: 11, originalName: "课程.txt", fileType: "txt" });
    await installMockApi(page, { details: { 10: word, 11: text },
        originals: { 10: fixture("outline.docx"), 11: "原始文字\n不含目录" } });
    await page.goto("/reading?materialId=10");
    const frame = page.frameLocator('iframe[title="Word 原件预览"]');
    await expect(frame.getByText("First chapter")).toBeVisible();
    await page.getByRole("button", { name: "目录", exact: true }).click();
    await page.getByRole("button", { name: "Second chapter" }).click();
    await expect(frame.getByText("Second chapter")).toBeInViewport();
    await expect(page.getByRole("complementary", { name: "文档目录" })).toHaveCount(0);

    await page.goto("/reading?materialId=11");
    await expect(page.getByLabel("文本原件")).toContainText("原始文字");
    await page.getByRole("button", { name: "目录", exact: true }).click();
    await expect(page.getByText("无法识别目录", { exact: true })).toBeVisible();
});

test("在 PDF 原件中划词问答并按需保存，工具栏按钮同宽同高", async ({ page }) => {
    const doc = material({ blockCount: 2 });
    const blocks = [{ blockId: 101, blockNo: 1, text: "First\npage", sourceLocator: { kind: "pdf", pageNumber: 1 } },
        { blockId: 102, blockNo: 2, text: "Second page", sourceLocator: { kind: "pdf", pageNumber: 2 } }];
    const state = await installMockApi(page, {
        details: { 10: doc }, blocks: { 10: blocks }, originals: { 10: fixture("outline.pdf") }
    });
    await page.goto("/reading?materialId=10");
    const toolbar = page.locator(".original-toolbar");
    const sizes = await Promise.all(["目录", "AI 问答", "下载原件"].map(async name => {
        const button = toolbar.getByRole("button", { name, exact: true });
        await expect(button).toBeVisible();
        return button.evaluate(element => [element.offsetWidth, element.offsetHeight, getComputedStyle(element).fontSize]);
    }));
    expect(sizes[0]).toEqual(sizes[1]);
    expect(sizes[0]).toEqual(sizes[2]);
    expect(sizes[0].slice(0, 2)).toEqual([69, 29]);

    const textLayer = page.locator('.pdfViewer [data-page-number="1"] .textLayer span').filter({ hasText: "First page" });
    await expect(textLayer).toBeInViewport();
    await textLayer.evaluate(node => {
        const range = document.createRange();
        range.selectNodeContents(node);
        const selection = window.getSelection();
        selection.removeAllRanges();
        selection.addRange(range);
        node.dispatchEvent(new PointerEvent("pointerup", { bubbles: true }));
    });
    await page.getByRole("button", { name: /问问 AI/ }).click();
    const panel = page.getByRole("complementary", { name: "AI 问答" });
    await expect(panel.locator("blockquote")).toHaveText("First page");
    await expect(panel.getByRole("link", { name: "打开文字版", exact: true })).toHaveCount(0);
    expect(state.calls.some(call => call.pathname.endsWith("/ask"))).toBe(false);
    await panel.getByRole("button", { name: "解释一下", exact: true }).click();
    await expect(panel.getByRole("textbox", { name: "你想问什么？" })).toHaveValue("解释一下");
    await panel.getByRole("button", { name: "分析一下", exact: true }).click();
    await expect(panel.getByRole("textbox", { name: "你想问什么？" })).toHaveValue("分析一下");
    expect(state.calls.some(call => call.pathname.endsWith("/ask"))).toBe(false);
    await panel.getByRole("radio", { name: "详细" }).check();
    await panel.getByRole("button", { name: "发送问题" }).click();
    await expect(panel.getByText("这是阅读答案。")).toBeVisible();
    await panel.getByRole("button", { name: "收起", exact: true }).click();
    await expect(panel).toBeHidden();
    await toolbar.getByRole("button", { name: "AI 问答", exact: true }).click();
    await expect(panel.getByText("这是阅读答案。")).toBeVisible();
    await expect(panel.getByRole("textbox", { name: "你想问什么？" })).toHaveValue("分析一下");
    await expect(page).toHaveURL(/reading\?materialId=10$/);
    expect(JSON.parse(state.calls.find(call => call.pathname.endsWith("/ask")).body)).toMatchObject({
        anchor: { blockId: 101, start: 0, end: 10, quote: "First\npage" }, detail: "DETAILED", question: "分析一下"
    });
    expect(state.highlights).toHaveLength(0);
    await panel.getByRole("radio", { name: "薄荷绿" }).check();
    await panel.getByRole("button", { name: "保存高亮和笔记" }).click();
    await expect(panel.getByRole("link", { name: "查看已保存问答", exact: true })).toHaveCount(0);
    await expect(panel.getByRole("link", { name: "查看笔记" })).toHaveAttribute("href", "/notes");
    const savedRequest = state.calls.find(call => call.method === "POST" && call.pathname.endsWith("/highlights"));
    expect(JSON.parse(savedRequest.body).saveAsNote).toBe(true);
    expect(JSON.parse(savedRequest.body).pdfPosition).toMatchObject({ pageNumber: 1, rects: expect.any(Array) });
    await expect(page.getByRole("button", { name: "编辑阅读笔记", exact: true })).toBeVisible();
    expect(state.highlights[0].color).toBe("MINT");
});
