import { expect, test } from "@playwright/test";
import { installMockApi } from "./mock-api.js";
import {
    MASTERY, QUESTION, QUESTION_VERSION, VARIANT_QUESTION, job, material, pdfBlocks
} from "./fixtures.js";

function workspaceMocks(overrides = {}) {
    return {
        materials: [material()],
        details: { 10: material() },
        blocks: { 10: pdfBlocks() },
        mastery: MASTERY,
        ...overrides
    };
}

test("长资料名不撑开筛选框，深色系统下控件依然是白底与绿色选中态", async ({ page }) => {
    const doc = material({ originalName: "一份很长的资料名称".repeat(24) + ".pdf" });
    await installMockApi(page, workspaceMocks({ materials: [doc], details: { 10: doc } }));
    await page.emulateMedia({ colorScheme: "dark" });
    for (const width of [1100, 390]) {
        await page.setViewportSize({ width, height: 900 });
        await page.goto("/questions");
        const filter = page.locator("#materialFilter");
        await expect(filter.locator('option[value="10"]')).toHaveCount(1);
        const within = await filter.evaluate(select => {
            const bounds = select.getBoundingClientRect();
            const frame = select.closest(".questions-filters").getBoundingClientRect();
            return bounds.right <= frame.right + 1 && bounds.left >= frame.left - 1;
        });
        expect(within).toBe(true);
        await expect(filter).toHaveCSS("background-color", "rgb(255, 255, 255)");
        const statusFilter = await page.locator("#statusFilter").boundingBox();
        const refresh = await page.getByRole("button", { name: "刷新", exact: true }).boundingBox();
        expect(refresh.height).toBe(statusFilter.height);
        expect(refresh.y).toBe(statusFilter.y);
    }
    await page.goto("/workspace?materialId=10");
    await expect(page.locator("#endPage")).toHaveCSS("background-color", "rgb(255, 255, 255)");
    const choice = page.getByRole("radio", { name: "困难", exact: true });
    await expect(choice.locator("..")).toHaveCSS("background-color", "rgb(255, 255, 255)");
    await choice.check();
    await expect(choice.locator("..")).toHaveCSS("background-color", "rgb(220, 239, 228)");
});

test("题库保持服务端新到旧的顺序，收起列表保留当前作答", async ({ page }) => {
    await page.setViewportSize({ width: 1246, height: 900 });
    const answered = { ...QUESTION, answered: true, latestCorrect: false };
    const newest = { ...QUESTION, versionId: "newest", jobId: "job-2", content: "最新题目" };
    const oldest = { ...QUESTION, versionId: "oldest", jobId: "job-2", content: "最早题目" };
    const api = await installMockApi(page, workspaceMocks({ library: [newest, answered, oldest] }));
    await page.goto("/questions");

    const list = page.locator("#libraryList");
    await expect(list.locator(".questions-card")).toHaveText(["最新题目", QUESTION.content, "最早题目"]);
    await expect(list.getByRole("heading")).toHaveCount(0);
    const questionButton = list.getByRole("button", { name: QUESTION.content, exact: true });
    await expect(questionButton).toBeVisible();
    await expect(page.getByText(/本页显示 .* 道题/)).toHaveCount(0);
    await expect(page.locator("#practiceMeta")).toHaveCount(0);

    await questionButton.click();
    await expect(questionButton).toHaveAttribute("aria-pressed", "true");
    await expect(page.locator("#practiceQuestion")).toHaveText(QUESTION.content);
    const meta = page.locator("#practiceMeta");
    await expect(meta).toContainText("来源：文件第 1 页");
    await expect(meta).toContainText("难度：适中");
    await expect(meta).toContainText("已作答 · 最近答错");

    const answer = page.locator('input[name="practiceAnswer"][value="B"]');
    const confidence = page.locator('input[name="practiceConfidence"][value="VERY_SURE"]');
    await answer.check();
    await confidence.check();
    await page.getByRole("button", { name: "收起题目列表" }).click();
    await expect(page.locator("#questionLibrary")).toBeHidden();
    const layout = await page.locator(".questions-layout").boundingBox();
    const practice = await page.locator(".questions-practice-column").boundingBox();
    expect(practice.width).toBe(layout.width);
    await expect(page.locator("#practiceQuestion")).toHaveText(QUESTION.content);
    await expect(answer).toBeChecked();
    await expect(confidence).toBeChecked();
    await page.getByRole("button", { name: "展开题目列表" }).click();
    await expect(questionButton).toBeVisible();
    await expect(answer).toBeChecked();
    expect(api.calls.filter(call => call.pathname === "/api/learning/attempts" && call.method === "POST")).toHaveLength(1);
});

test("工作台按页拆批、出题、作答并刷新掌握度", async ({ page }) => {
    const api = await installMockApi(page, workspaceMocks({
        library: [QUESTION],
        job: job(QUESTION, { mode: "WEAKNESS" }),
        knowledgeGroups: [{ chapter: "HTTP", name: "请求方法", probability: 0.4, evidenceCount: 5,
            topics: [{ id: "topic-1", name: "幂等性", hasRecentError: true }] }]
    }));

    await page.goto("/workspace?materialId=10");
    await expect(page.getByRole("heading", { name: "学习工作台" })).toBeVisible();
    await expect(page.getByText("资料共 11 块、2 页")).toBeVisible();

    await page.locator("#endPage").fill("2");
    await page.getByRole("button", { name: "应用页码范围" }).click();
    await expect(page.getByText("已明确拆成 2 批")).toBeVisible();
    await expect(page.locator(".workspace-batch")).toHaveCount(2);

    await page.locator(".workspace-batch").first().click();
    await expect(page.locator(".workspace-badge").first()).toContainText("已选 10 块");
    await page.getByRole("radio", { name: "薄弱点强化" }).check();
    await page.getByRole("button", { name: "开始出题" }).click();
    await expect(page.getByRole("heading", { name: "出题任务 · 题目已保存" })).toBeVisible();
    await expect(page.getByText("可用正文有限。")).toBeVisible();

    const generation = api.calls.find(call =>
        call.method === "POST" && call.pathname === "/api/generation/jobs");
    expect(generation).toBeTruthy();
    const payload = JSON.parse(generation.body);
    expect(payload.materialId).toBe(10);
    expect(payload.blockIds).toHaveLength(10);
    expect(payload.mode).toBe("WEAKNESS");
    expect(typeof payload.requestKey).toBe("string");
    expect(generation.headers["x-learnloop-expected-user"]).toBe("1");

    await page.locator(".workspace-questions li button").filter({ hasText: QUESTION.content }).click();
    await expect(page.getByRole("heading", { name: "3. 专注这一题" })).toBeVisible();
    await expect(page.locator('input[name="answer"][value="A"]')).toBeEnabled();

    await page.locator('input[name="answer"][value="A"]').check();
    await page.locator('input[name="confidence"][value="SOMEWHAT_SURE"]').check();
    await page.getByRole("button", { name: "提交答案" }).click();

    await expect(page.getByText("答对 · 正确答案 A", { exact: true })).toBeVisible();
    await expect(page.getByText("学习状态已更新。")).toBeVisible();
    await page.locator(".workspace-mastery summary").filter({ hasText: "40%" }).click();
    await expect(page.getByText("幂等性 · 有错题")).toBeVisible();

    const attempt = api.calls.find(call =>
        call.method === "POST" && call.pathname === "/api/learning/attempts");
    expect(JSON.parse(attempt.body).questionVersionId).toBe(QUESTION_VERSION);
    expect(attempt.headers["x-learnloop-expected-user"]).toBe("1");
    const answer = api.calls.find(call =>
        call.method === "POST" && call.pathname === "/api/learning/attempts/501/answers");
    expect(JSON.parse(answer.body)).toMatchObject({ selectedOption: "A", confidence: "SOMEWHAT_SURE" });
    expect(answer.headers["x-learnloop-expected-user"]).toBe("1");
    await expect.poll(() => api.calls.filter(call =>
        call.pathname === "/api/generation/jobs/knowledge-groups" && call.method === "GET").length).toBeGreaterThanOrEqual(2);
    expect(api.unmatched).toEqual([]);
});

test("恢复未确认出题请求时不自动创建付费任务，点击后复用原键", async ({ page }) => {
    const pending = {
        materialId: 10,
        blockIds: [101, 102],
        count: 5,
        difficulty: "HARD",
        granularity: "FINE",
        requestKey: "pending-key-1"
    };
    await page.addInitScript(value => {
        try {
            sessionStorage.setItem("learnloop:1:workspace", JSON.stringify(value));
        } catch { /* 无 storage 的环境不影响本测试的页面断言。 */ }
    }, {
        materialId: 10,
        jobId: null,
        pendingRequest: pending,
        questionVersionId: null
    });
    const api = await installMockApi(page, workspaceMocks({ job: job(QUESTION) }));

    await page.goto("/workspace");
    await expect(page.getByText("上次出题请求尚未确认。")).toBeVisible();
    await expect(page.getByRole("button", { name: "继续确认原出题请求" })).toBeEnabled();
    await expect(page.locator('input[name="difficulty"][value="HARD"]')).toBeChecked();
    expect(api.calls.filter(call =>
        call.method === "POST" && call.pathname === "/api/generation/jobs")).toHaveLength(0);

    await page.getByRole("button", { name: "继续确认原出题请求" }).click();
    await expect(page.getByRole("heading", { name: "出题任务 · 题目已保存" })).toBeVisible();
    const posts = api.calls.filter(call =>
        call.method === "POST" && call.pathname === "/api/generation/jobs");
    expect(posts).toHaveLength(1);
    expect(JSON.parse(posts[0].body)).toEqual({ ...pending, blockIds: [101, 102] });
    expect(api.unmatched).toEqual([]);
});

test("恢复已有任务只查询状态，不重发付费请求", async ({ page }) => {
    await page.addInitScript(() => {
        try {
            sessionStorage.setItem("learnloop:1:workspace", JSON.stringify({
                materialId: 10,
                jobId: "job-77",
                pendingRequest: null,
                questionVersionId: null
            }));
        } catch { /* 忽略存储不可用。 */ }
    });
    const api = await installMockApi(page, workspaceMocks({
        job: job(QUESTION, { jobId: "job-77" })
    }));

    await page.goto("/workspace");
    await expect(page.getByRole("heading", { name: "出题任务 · 题目已保存" })).toBeVisible();
    expect(api.calls.some(call =>
        call.method === "POST" && call.pathname === "/api/generation/jobs/retry")).toBe(false);
    expect(api.calls.filter(call =>
        call.method === "POST" && call.pathname === "/api/generation/jobs")).toHaveLength(0);
    expect(api.calls.some(call =>
        call.method === "POST" && call.pathname === "/api/learning/attempts")).toBe(false);
    expect(api.unmatched).toEqual([]);
});

test("题目中心深链自动打开题目、提交后生成同考点新题并打开", async ({ page }) => {
    page.on("dialog", dialog => dialog.accept());
    const api = await installMockApi(page, {
        materials: [material()],
        library: [QUESTION],
        mastery: MASTERY,
        variantRecord: VARIANT_QUESTION,
        variantJob: job(VARIANT_QUESTION, { jobId: "variant-job" })
    });

    await page.goto(`/questions?materialId=10&versionId=${QUESTION_VERSION}`);
    await expect(page.getByRole("heading", { name: "题目中心与答题练习" })).toBeVisible();
    await expect(page.locator("#practiceQuestion")).toHaveText(QUESTION.content);
    await expect(page.locator('input[name="practiceAnswer"][value="A"]')).toBeEnabled();

    await page.locator('input[name="practiceAnswer"][value="A"]').check();
    await page.getByRole("button", { name: "提交答案" }).click();
    await expect(page.locator(".questions-result")).toContainText("答对 · 正确答案 A");
    await expect(page.locator(".questions-result")).toContainText("掌握度：");

    await page.getByRole("button", { name: "同考点再出一题" }).click();
    await expect(page.locator("#practiceQuestion")).toHaveText(VARIANT_QUESTION.content);

    const variant = api.calls.find(call =>
        call.method === "POST" && call.pathname === "/api/generation/jobs/variants");
    expect(JSON.parse(variant.body).questionVersionId).toBe(QUESTION_VERSION);
    expect(JSON.parse(variant.body).requestKey).toEqual(expect.any(String));
    expect(api.calls.some(call =>
        call.method === "GET" && call.pathname === "/api/generation/jobs/variant-job")).toBe(true);
    expect(api.calls.filter(call =>
        call.method === "POST" && call.pathname === "/api/learning/attempts").length).toBeGreaterThanOrEqual(2);
    expect(api.unmatched).toEqual([]);
});

test("同考点出题失败时显示错误并允许确认后重试", async ({ page }) => {
    page.on("dialog", dialog => dialog.accept());
    const api = await installMockApi(page, {
        materials: [material()],
        library: [QUESTION],
        mastery: MASTERY,
        variantJob: job(VARIANT_QUESTION, {
            jobId: "variant-job",
            status: "FAILED",
            generatedCount: 0,
            errorCode: "PROVIDER_ERROR",
            questions: []
        })
    });

    await page.goto(`/questions?materialId=10&versionId=${QUESTION_VERSION}`);
    await expect(page.locator("#practiceQuestion")).toHaveText(QUESTION.content);
    await page.getByRole("button", { name: "同考点再出一题" }).click();

    await expect(page.getByText("同考点出题失败（PROVIDER_ERROR）。")).toBeVisible();
    await expect(page.getByRole("button", { name: "重试生成同考点新题" })).toBeVisible();
    expect(api.unmatched).toEqual([]);
});
test("下一题沿筛选后的列表跨页，到最后一题停止", async ({ page }) => {
    const library = Array.from({ length: 22 }, (_, i) => ({
        ...QUESTION, versionId: "v" + i, content: "练习 " + i, answered: i === 5
    }));
    const api = await installMockApi(page, workspaceMocks({ library }));
    await page.goto("/questions");
    await page.locator("#statusFilter").selectOption("UNANSWERED");
    await page.locator(".questions-card").filter({ hasText: /^练习 20$/ }).click();
    await page.getByRole("button", { name: "收起题目列表" }).click();
    const next = page.getByRole("button", { name: "下一题", exact: true });
    await next.click();
    await expect(page.locator("#practiceQuestion")).toHaveText("练习 21");
    await expect(next).toBeDisabled();
    await page.getByRole("button", { name: "展开题目列表" }).click();
    await expect(page.locator(".questions-card[aria-pressed=true]")).toHaveText("练习 21");
    expect(api.calls.some(call => call.pathname.includes("/generation/jobs"))).toBe(false);
});

test("当前题退出未作答列表后，下一题接着原位置", async ({ page }) => {
    const library = ["甲", "乙", "丙"].map((content, i) => ({ ...QUESTION, versionId: "v" + i, content }));
    const api = await installMockApi(page, workspaceMocks({ library }));
    await page.goto("/questions");
    await page.locator("#statusFilter").selectOption("UNANSWERED");
    await page.getByRole("button", { name: "乙", exact: true }).click();
    await page.locator('input[name="practiceAnswer"][value="A"]').check();
    api.library[1].answered = true;
    await page.getByRole("button", { name: "提交答案", exact: true }).click();
    await expect(page.locator(".questions-card")).toHaveText(["甲", "丙"]);
    await page.getByRole("button", { name: "下一题", exact: true }).click();
    await expect(page.locator("#practiceQuestion")).toHaveText("丙");
});
