import { expect, test } from "@playwright/test";
import { installMockApi } from "./mock-api.js";

test("笔记每页显示 12 条，翻页后仍可打开和删除笔记", async ({ page }) => {
    await installMockApi(page);
    let notes = Array.from({ length: 13 }, (_, index) => ({
        questionVersionId: "note-" + (index + 1),
        materialId: 10,
        materialName: "测试资料",
        question: "题目 " + (index + 1),
        content: "笔记 " + (index + 1),
        revision: "rev"
    }));
    const readingNoteId = "a".repeat(64);
    notes[1] = {
        ...notes[1], questionVersionId: null, readingNoteId,
        question: "阅读问答 2", quote: "原文引句", content: "原来的回答"
    };
    await page.route("**/api/notes?*", async route => {
        const params = new URL(route.request().url()).searchParams;
        const number = Number(params.get("page"));
        const size = Number(params.get("size"));
        const offset = (number - 1) * size;
        await route.fulfill({ json: {
            page: number,
            records: notes.slice(offset, offset + size),
            hasMore: notes.length > offset + size
        } });
    });
    await page.route("**/api/notes/note-13?*", async route => {
        expect(route.request().method()).toBe("DELETE");
        notes = notes.filter(note => note.questionVersionId !== "note-13");
        await route.fulfill({ status: 204 });
    });
    await page.route("**/api/notes/reading/" + readingNoteId, async route => {
        expect(route.request().method()).toBe("PUT");
        notes[1] = { ...notes[1], content: route.request().postDataJSON().content, revision: "next" };
        await route.fulfill({ json: notes[1] });
    });
    await page.goto("/notes");
    const list = page.getByRole("region", { name: "笔记列表" });
    const rows = list.getByRole("row");
    const detail = page.getByRole("region", { name: "笔记详情" });
    await expect(rows).toHaveCount(13); // 一行表头加 12 条笔记。
    await expect(detail).toHaveCount(0);

    const first = list.getByRole("button", { name: "题目 1", exact: true });
    const second = list.getByRole("button", { name: "原文引句" });
    await first.click();
    await expect(first).toHaveAttribute("aria-expanded", "true");
    await expect(first.locator("xpath=ancestor::tr/following-sibling::tr[1]").locator("#noteDetails")).toBeVisible();
    await second.click();
    await expect(first).toHaveAttribute("aria-expanded", "false");
    await expect(second.locator("xpath=ancestor::tr/following-sibling::tr[1]").locator("#noteDetails")).toBeVisible();
    await expect(detail).toHaveCount(1);
    await expect(detail.getByText("原文引句")).toBeVisible();
    await expect(detail.getByRole("link", { name: /查看问答原文/ })).toHaveAttribute("href", new RegExp(readingNoteId));
    const readingEditor = detail.getByRole("textbox", { name: "学习笔记" });
    await readingEditor.fill("补充阅读笔记");
    await detail.getByRole("button", { name: "保存笔记" }).click();
    await expect(list.getByRole("cell", { name: "补充阅读笔记", exact: true })).toBeVisible();
    await second.click();
    await expect(detail).toHaveCount(0);

    await page.getByRole("button", { name: "下一页" }).click();
    await expect(rows).toHaveCount(2);
    await expect(page.getByText("第 2 页")).toBeVisible();
    await expect(page.getByRole("button", { name: "下一页" })).toBeDisabled();
    await page.getByRole("cell", { name: "笔记 13" }).click();
    await expect(detail).toBeVisible();
    await expect(detail.getByRole("textbox", { name: "学习笔记" })).toHaveValue("笔记 13");
    page.once("dialog", dialog => dialog.accept());
    await page.getByRole("button", { name: "删除笔记" }).click();
    await expect(rows).toHaveCount(13);
    await expect(page.getByRole("button", { name: "下一页" })).toHaveCount(0);
    await expect(detail).toHaveCount(0);
});
