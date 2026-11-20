import { expect, test } from "@playwright/test";
import { material } from "./fixtures.js";
import { installMockApi } from "./mock-api.js";

test("主要导航顺序固定，工作台与资料在个人中心切换", async ({ page }) => {
    await installMockApi(page, { materials: [material()], details: { 10: material() } });
    await page.goto("/workspace");
    const mainNav = page.getByRole("navigation", { name: "主要导航" });
    await expect(mainNav.getByRole("link")).toHaveText([
        "资料阅读", "题目中心与练习", "今日复习", "答题记录", "我的笔记", "小栗老师", "个人中心"
    ]);
    await expect(mainNav.getByRole("link", { name: "个人中心" })).toHaveAttribute("aria-current", "page");
    await expect(page.getByRole("heading", { name: "学习工作台" })).toBeVisible();

    const centerNav = page.getByRole("navigation", { name: "个人中心" });
    await centerNav.getByRole("link", { name: "我的资料" }).click();
    await expect(page).toHaveURL("/workspace?tab=materials");
    await expect(page.getByRole("heading", { name: "我的资料" })).toBeVisible();
    await expect(mainNav.getByRole("link", { name: "个人中心" })).toHaveAttribute("aria-current", "page");
    await centerNav.getByRole("link", { name: "学习工作台" }).click();
    await expect(page.getByRole("heading", { name: "学习工作台" })).toBeVisible();

    await page.goto("/materials.html?manage=10");
    await expect(page).toHaveURL("/workspace?manage=10&tab=materials");
    await expect(page.locator("#materialName")).toHaveText("网络基础.pdf");
});
