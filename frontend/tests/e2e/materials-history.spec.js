import { expect, test } from "@playwright/test";
import { installMockApi } from "./mock-api.js";
import { historyRecords, material, pdfBlocks } from "./fixtures.js";

test("资料列表、解析详情和正文分页", async ({ page }) => {
    const api = await installMockApi(page, {
        materials: [
            material(),
            material({ materialId: 11, originalName: "课堂笔记.md", fileType: "md", parseStatus: "PARSING", blockCount: 0 })
        ],
        details: {
            10: material(),
            11: material({ materialId: 11, originalName: "课堂笔记.md", fileType: "md", parseStatus: "PARSING", blockCount: 0 })
        },
        blocks: { 10: pdfBlocks() }
    });

    await page.goto("/materials");
    await expect(page.getByRole("heading", { name: "我的资料" })).toBeVisible();
    await expect(page.locator(".material-card")).toHaveCount(2);
    await expect(page.locator("#materialsPageInfo")).toHaveText("第 1 / 1 页，共 2 份");

    await page.locator(".material-card").filter({ hasText: "网络基础.pdf" }).click();
    await expect(page).toHaveURL("/reading?materialId=10");
    await expect(page.getByRole("heading", { name: "网络基础.pdf" })).toBeVisible();
    await expect(page.getByRole("link", { name: "资料阅读" })).toHaveAttribute("aria-current", "page");
    await page.getByRole("link", { name: "← 我的资料" }).click();
    await page.getByRole("button", { name: "网络基础.pdf 的详情与解析" }).click();
    await expect(page.locator("#materialName")).toHaveText("网络基础.pdf");
    await expect(page.locator("#materialState")).toHaveText("解析完成");
    await expect(page.locator(".material-block").first()).toContainText("第 1 段正文");
    await expect(page.getByRole("link", { name: "打开阅读与问答 →" })).toHaveCSS("color", "rgb(255, 255, 255)");
    await expect(page.locator("#detailStatus")).toHaveCount(0);
    await expect(page.locator("#blocksPageInfo")).toHaveText("第 1 / 3 页，共 11 块");

    const detail = api.calls.find(call => call.pathname === "/api/materials/10" && call.method === "GET");
    expect(detail).toBeTruthy();
    await page.locator(".material-card").filter({ hasText: "课堂笔记.md" }).click();
    await expect(page).toHaveURL("/reading?materialId=11");
    await page.getByRole("link", { name: "文字问答" }).click();
    await page.getByRole("link", { name: "查看资料详情与解析" }).click();
    await expect(page.locator("#materialName")).toHaveText("课堂笔记.md");
    expect(api.unmatched).toEqual([]);
});

test("上传使用 multipart 与幂等 requestId，随后解析并显示正文", async ({ page }) => {
    const uploaded = material({
        materialId: 9,
        originalName: "笔记.txt",
        fileType: "txt",
        sizeBytes: 16,
        parseStatus: "UPLOADED",
        blockCount: 0,
        textCharCount: 0
    });
    const api = await installMockApi(page, {
        materials: [],
        details: { 9: uploaded },
        parseResult: { ...uploaded, parseStatus: "PARSED", blockCount: 1, textCharCount: 16 },
        blocks: {
            9: [{
                blockId: 901,
                blockNo: 1,
                text: "hello learnloop",
                sourceLocator: { kind: "text", lineStart: 1, lineEnd: 1, partIndex: 1 }
            }]
        },
        uploadedMaterial: uploaded
    });

    await page.goto("/materials");
    await expect(page.getByRole("heading", { name: "我的资料" })).toBeVisible();
    await page.locator("#materialFile").setInputFiles({
        name: "notes.txt",
        mimeType: "text/plain",
        buffer: Buffer.from("hello learnloop")
    });
    await expect(page.getByRole("button", { name: "上传并解析" })).toBeEnabled();
    await page.getByRole("button", { name: "上传并解析" }).click();

    await expect(page.locator("#materialName")).toHaveText("笔记.txt");
    await expect(page.locator("#materialState")).toHaveText("解析完成");
    await expect(page.locator(".material-block")).toContainText("hello learnloop");

    const upload = api.calls.find(call => call.pathname === "/api/materials" && call.method === "POST");
    expect(upload).toBeTruthy();
    expect(upload.headers["content-type"]).toContain("multipart/form-data; boundary=");
    expect(upload.body).toContain('name="requestId"');
    expect(upload.body).toContain('filename="notes.txt"');
    expect(upload.body).toContain("hello learnloop");
    expect(upload.headers["x-csrf-token"]).toBe("e2e-token");
    expect(upload.headers["x-learnloop-expected-user"]).toBe("1");

    const parse = api.calls.find(call => call.pathname === "/api/materials/9/parse" && call.method === "POST");
    expect(parse).toBeTruthy();
    expect(parse.headers["x-learnloop-expected-user"]).toBe("1");
    expect(api.unmatched).toEqual([]);
});

test("答题记录分页、对错筛选与排序使用服务端参数", async ({ page }) => {
    const api = await installMockApi(page, { answers: historyRecords(17) });
    await page.goto("/history");
    await expect(page.getByRole("heading", { name: "答题记录" })).toBeVisible();
    await expect(page.locator("#historyBody tr")).toHaveCount(15);
    await expect(page.locator("#historyPageInfo")).toHaveText("第 1 / 2 页，共 17 条");

    await page.getByRole("button", { name: "下一页" }).click();
    await expect(page.locator("#historyBody tr")).toHaveCount(2);
    await expect(page.locator("#historyPageInfo")).toHaveText("第 2 / 2 页，共 17 条");

    await page.getByRole("button", { name: "答错记录" }).click();
    await expect(page.locator("#historyBody tr")).toHaveCount(8);
    await expect(page.getByRole("button", { name: "答错记录" })).toHaveAttribute("aria-pressed", "true");
    await expect(page.locator("#historyBody tr").first().locator("td").first()).toHaveText("第16道题目：事务提…");

    await page.locator("#historySortButton").hover();
    await page.locator('[data-sort="asc"]').click();
    await expect(page.locator("#historyBody tr").first().locator("td").first()).toHaveText("第2道题目：事务提交…");

    expect(api.calls.some(call =>
        call.pathname === "/api/answers" && call.search.includes("correct=false"))).toBe(true);
    expect(api.calls.some(call =>
        call.pathname === "/api/answers" && call.search.includes("sort=asc") &&
        call.search.includes("correct=false"))).toBe(true);
    expect(api.unmatched).toEqual([]);
});
