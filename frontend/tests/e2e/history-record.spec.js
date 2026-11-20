import { expect, test } from "@playwright/test";
import { installMockApi } from "./mock-api.js";
import { historyRecords } from "./fixtures.js";

test("点击记录回看当时的题目，返回保留筛选", async ({ page }) => {
    const records = historyRecords();
    const latest = records.at(-1);
    const api = await installMockApi(page, { answers: records });
    await page.route("**/api/answers/12", route => {
        expect(route.request().method()).toBe("GET");
        expect(route.request().headers()["x-learnloop-expected-user"]).toBe("1");
        return route.fulfill({ json: {
            ...latest, content: "作答时的题干", questionVersionId: "saved-version",
            optionA: "历史选项甲", optionB: "历史选项乙", optionC: "历史选项丙", optionD: "历史选项丁",
            correctOptionSnapshot: "A"
        } });
    });

    await page.goto("/history");
    await page.getByRole("button", { name: "答错记录" }).click();
    await expect(page.locator("#historyBody tr")).toHaveCount(6);
    await page.locator("#historyBody tr").first().getByRole("cell").nth(1).click();
    await expect(page).toHaveURL("/history?record=12&correct=false");
    await expect(page.getByRole("heading", { name: "作答时的题干" })).toBeVisible();
    const options = page.getByRole("list", { name: "题目选项" });
    await expect(options.locator("li[data-selected=true]")).toContainText("历史选项丁");
    await expect(options.locator("li[data-correct=true]")).toContainText("历史选项甲");
    await expect(page.getByRole("button", { name: "提交答案" })).toHaveCount(0);

    await page.getByRole("link", { name: "返回答题记录" }).click();
    await expect(page.getByRole("button", { name: "答错记录" })).toHaveAttribute("aria-pressed", "true");
    await expect(page.locator("#historyBody tr")).toHaveCount(6);
    expect(api.unmatched).toEqual([]);
});

test("记录列表用题目前十个字符替代编号，键盘仍可打开完整题目", async ({ page }) => {
    const content = "😀一二三四五六七八九十十一";
    const records = [
        { ...historyRecords()[0], recordId: 1, content },
        { ...historyRecords()[1], recordId: 2, content: "短题目" },
        { ...historyRecords()[2], recordId: 3, content: null }
    ];
    const api = await installMockApi(page, { answers: records });
    await page.route("**/api/answers/1", route => route.fulfill({ json: {
        ...records[0], content, optionA: "甲", optionB: "乙", optionC: "丙", optionD: "丁", correctOptionSnapshot: "A"
    } }));
    await page.goto("/history");
    await expect(page.getByRole("columnheader")).toHaveText(["题目", "所选答案", "判分结果", "提交时间"]);
    const link = page.getByRole("link", { name: `查看题目：${content}`, exact: true });
    await expect(link).toHaveText("😀一二三四五六七八九…");
    await expect(link).toHaveAttribute("title", content);
    await expect(page.getByRole("link", { name: "查看题目：短题目", exact: true })).toHaveText("短题目");
    await expect(page.getByRole("link", { name: "查看题目：题目内容暂不可用", exact: true })).toHaveText("题目内容暂不可用");
    expect(api.calls.filter(call => /^\/api\/answers\/\d+$/.test(call.pathname))).toHaveLength(0);
    await link.focus();
    await page.keyboard.press("Enter");
    await expect(page.getByRole("heading", { name: content, exact: true })).toBeVisible();
    await expect(page).toHaveURL(/history\?record=1$/);
});
