import { readFileSync } from "node:fs";
import { expect, test } from "@playwright/test";
import { installMockApi } from "./mock-api.js";
import { material } from "./fixtures.js";

async function selectFirstLine(page) {
    const text = page.locator('.pdfViewer [data-page-number="1"] .textLayer span').filter({ hasText: "First page" }).first();
    await expect(text).toBeVisible();
    await text.evaluate(element => {
        const range = document.createRange();
        range.selectNodeContents(element);
        const selection = window.getSelection();
        selection.removeAllRanges();
        selection.addRange(range);
        element.dispatchEvent(new PointerEvent("pointerup", { bubbles: true }));
    });
    await expect(page.getByRole("button", { name: "添加笔记", exact: true })).toBeVisible();
}

async function installAnnotations(page) {
    const document = material({ parseStatus: "UPLOADED", blockCount: 0 });
    const state = await installMockApi(page, { details: { 10: document },
        originals: { 10: readFileSync(new URL("./fixtures/outline.pdf", import.meta.url)) } });
    const calls = [];
    const marks = [];
    let conflict = false;
    await page.route("**/api/reading/10/annotations**", async route => {
        const request = route.request();
        const url = new URL(request.url());
        const method = request.method();
        const body = method === "POST" || method === "PUT" ? request.postDataJSON() : null;
        calls.push({ method, body });
        if (method === "GET") return route.fulfill({ json: { records: marks } });
        expect(request.headers()["x-learnloop-expected-user"]).toBe("1");
        expect(request.headers()["x-csrf-token"]).toBe("e2e-token");
        if (method === "POST") {
            expect(body.quote).toBe("First page");
            expect(body.pageNumber).toBe(1);
            expect(body.rects.length).toBeGreaterThan(0);
            for (const rect of body.rects) {
                expect(rect.x).toBeGreaterThanOrEqual(0);
                expect(rect.y).toBeGreaterThanOrEqual(0);
                expect(rect.x + rect.width).toBeLessThanOrEqual(1.000001);
                expect(rect.y + rect.height).toBeLessThanOrEqual(1.000001);
            }
            const saved = { ...body, revision: "v1", noteId: body.note ? "a".repeat(64) : null,
                noteRevision: body.note ? "n1" : null };
            marks.push(saved);
            return route.fulfill({ status: 201, json: saved });
        }
        const index = marks.findIndex(mark => url.pathname.endsWith("/" + mark.id));
        if (method === "DELETE") {
            marks.splice(index, 1);
            return route.fulfill({ status: 204 });
        }
        if (conflict) return route.fulfill({ status: 409, json: { errorCode: "PDF_ANNOTATION_CHANGED", message: "标注已在其他页面更新，请保留草稿并刷新。" } });
        marks[index] = { ...marks[index], ...body, revision: "v2", noteRevision: "n2" };
        return route.fulfill({ json: marks[index] });
    });
    return { state, marks, calls, setConflict: value => { conflict = value; } };
}

test("原件笔记在首行旁回显，悬停预览、修改、刷新和缩放保持定位", async ({ page }) => {
    const api = await installAnnotations(page);
    await page.goto("/reading?materialId=10");
    await selectFirstLine(page);
    await page.locator(".pdf-selection-menu").getByRole("radio", { name: "薄荷绿" }).check();
    await page.getByRole("button", { name: "添加笔记", exact: true }).click();
    const editor = page.getByRole("complementary", { name: "阅读笔记" });
    const input = editor.getByRole("textbox", { name: "笔记", exact: true });
    await input.fill("这是我对这段话的理解。");
    await editor.getByRole("button", { name: "保存笔记", exact: true }).click();
    await expect(editor).toHaveCount(0);
    expect(api.marks).toHaveLength(1);
    expect(api.marks[0].color).toBe("MINT");
    expect(api.state.calls.some(call => call.pathname.endsWith("/ask"))).toBe(false);

    const marker = page.getByRole("button", { name: "编辑阅读笔记" });
    await expect(marker).toBeVisible();
    const firstText = page.locator('.pdfViewer [data-page-number="1"] .textLayer span').filter({ hasText: "First page" }).first();
    await expect.poll(async () => {
        const text = await firstText.evaluate(element => {
            const range = document.createRange();
            range.selectNodeContents(element);
            const bounds = range.getClientRects()[0];
            return { x: bounds.x, y: bounds.y };
        });
        const fill = await page.locator(".pdf-mark-fill").first().boundingBox();
        return Math.abs(text.x - fill.x) + Math.abs(text.y - fill.y);
    }).toBeLessThan(4);
    await marker.hover();
    const preview = page.getByRole("tooltip");
    await expect(preview).toHaveText("这是我对这段话的理解。");
    await preview.hover();
    await expect(preview).toBeVisible();
    const ai = page.getByRole("complementary", { name: "AI 问答" });
    await expect(ai).toBeVisible();
    await marker.click();
    await expect(ai).toBeVisible();
    await expect(input).toHaveValue("这是我对这段话的理解。");
    await input.fill("修订后的理解。");
    await editor.getByRole("button", { name: "保存笔记", exact: true }).click();
    await page.reload();
    await expect(marker).toBeVisible();
    await marker.hover();
    await expect(preview).toHaveText("修订后的理解。");
    await page.getByRole("button", { name: "放大 PDF", exact: true }).click();
    await expect(marker).toBeVisible();
    const ratios = await page.locator(".pdf-mark-layer").evaluate(layer => {
        const canvas = layer.parentElement.querySelector(".canvasWrapper");
        const highlight = layer.querySelector(".pdf-mark-fill");
        return [layer.getBoundingClientRect().width / canvas.getBoundingClientRect().width,
            highlight.getBoundingClientRect().width / canvas.getBoundingClientRect().width];
    });
    expect(ratios[0]).toBeCloseTo(1, 2);
    expect(ratios[1]).toBeCloseTo(api.marks[0].rects[0].width, 2);
    expect(api.calls.filter(call => call.method === "POST")).toHaveLength(1);
    await page.goto("/reading?materialId=10&annotation=" + api.marks[0].id);
    await expect(editor).toBeVisible();
    await expect(input).toHaveValue("修订后的理解。");
});

test("纯高亮不调用模型，冲突保留笔记草稿，删除后刷新不再回显", async ({ page }) => {
    const api = await installAnnotations(page);
    await page.goto("/reading?materialId=10");
    await selectFirstLine(page);
    await page.getByRole("button", { name: "高亮", exact: true }).click();
    await page.getByRole("button", { name: "编辑高亮", exact: true }).click();
    const editor = page.getByRole("complementary", { name: "阅读笔记" });
    const input = editor.getByRole("textbox", { name: "笔记", exact: true });
    await input.fill("不能丢失的草稿");
    api.setConflict(true);
    await editor.getByRole("button", { name: "保存笔记", exact: true }).click();
    await expect(editor.getByRole("status")).toContainText("已在其他页面更新");
    await expect(input).toHaveValue("不能丢失的草稿");
    api.setConflict(false);
    await editor.getByRole("button", { name: "保存笔记", exact: true }).click();
    await page.getByRole("button", { name: "编辑阅读笔记", exact: true }).click();
    page.once("dialog", dialog => dialog.accept());
    await editor.getByRole("button", { name: "删除高亮", exact: true }).click();
    await expect(page.locator(".pdf-mark-fill")).toHaveCount(0);
    await page.reload();
    await expect(page.locator(".pdf-mark-fill")).toHaveCount(0);
    expect(api.state.calls.some(call => call.pathname.endsWith("/ask"))).toBe(false);
});

test("旧批注的重复矩形与不同批注重叠时不会叠加颜色浓度", async ({ page }) => {
    const api = await installAnnotations(page);
    const rectangle = { x: .2, y: .3, width: .4, height: .04 };
    // 模拟 PDF 文字层返回父子嵌套矩形，以及用户在相同原文上另建了一条批注。
    // 保留原有数据和笔记，修复只影响绘制，不靠删除重复批注隐藏问题。
    api.marks.push({ id: "legacy-nested", pageNumber: 1, quote: "原文", color: "LILAC", revision: "v1",
        note: "第一条笔记", noteId: "note-a", noteRevision: "n1", rects: [rectangle,
            { x: .3, y: .3, width: .15, height: .04 }, { x: .3, y: .3, width: .15, height: .04 }] },
        { id: "overlapping-mark", pageNumber: 1, quote: "原文片段", color: "LILAC", revision: "v1",
            note: "第二条笔记", noteId: "note-b", noteRevision: "n1", rects: [{ x: .4, y: .3, width: .15, height: .04 }] });
    await page.goto("/reading?materialId=10");
    const fills = page.locator(".pdf-mark-fill");
    await expect(fills).toHaveCount(4);
    async function expectUniformTint(fill = fills.first(), points = [.0625, .3125, .625]) {
        // 读取真实浏览器合成后的像素，而不是只断言 CSS 值：单层区、嵌套区和跨批注重叠区应同色。
        const png = (await fill.screenshot({ scale: "css", animations: "disabled" })).toString("base64");
        const samples = await page.evaluate(async ({ base64, points }) => {
            const image = new Image();
            await new Promise((resolve, reject) => {
                image.onload = resolve; image.onerror = reject;
                image.src = "data:image/png;base64," + base64;
            });
            const canvas = document.createElement("canvas");
            canvas.width = image.width; canvas.height = image.height;
            const context = canvas.getContext("2d");
            context.drawImage(image, 0, 0);
            return points.map(x => Array.from(context.getImageData(
                Math.round(x * (image.width - 1)), Math.round((image.height - 1) / 2), 1, 1).data));
        }, { base64: png, points });
        expect(Math.min(...samples[0].slice(0, 3))).toBeGreaterThan(210); // 保持浅色，而非不透明深色块。
        for (const pixel of samples.slice(1)) for (let channel = 0; channel < 4; channel++) {
            expect(Math.abs(pixel[channel] - samples[0][channel])).toBeLessThanOrEqual(1);
        }
    }
    await expectUniformTint();
    await page.getByRole("button", { name: "放大 PDF", exact: true }).click();
    await expectUniformTint();
    await page.reload();
    await expect(fills).toHaveCount(4);
    await expectUniformTint();
    // 不同颜色交叉时，顶层颜色以同样浓度覆盖，不会和底色混成更深的第三种颜色。
    api.marks[1].color = "MINT";
    api.marks[1].rects[0].width = .3;
    await page.reload();
    await expect(fills).toHaveCount(4);
    await expectUniformTint(fills.last(), [.1, .9]);
    await expect(page.getByRole("button", { name: "编辑阅读笔记", exact: true })).toHaveCount(2);
    await page.locator('[data-annotation-id="overlapping-mark"] button').click();
    await expect(page.getByRole("textbox", { name: "笔记", exact: true })).toHaveValue("第二条笔记");
    expect(api.marks).toHaveLength(2);
    expect(api.calls.every(call => call.method === "GET")).toBe(true);
});
