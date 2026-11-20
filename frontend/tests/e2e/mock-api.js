const JSON_HEADERS = { "Content-Type": "application/json; charset=utf-8" };

function json(route, status, body) {
    return route.fulfill({ status, headers: JSON_HEADERS, body: JSON.stringify(body) });
}

function pageOf(items, query, fallbackSize) {
    const page = Number(query.page ?? 1);
    const size = Number(query.size ?? fallbackSize);
    const total = items.length;
    return {
        records: items.slice((page - 1) * size, page * size),
        page,
        total,
        totalPages: total === 0 ? 0 : Math.ceil(total / size)
    };
}

function requestBody(request) {
    const buffer = request.postDataBuffer();
    return buffer ? buffer.toString("utf8") : null;
}

export async function installMockApi(page, options = {}) {
    const state = {
        authenticated: options.authenticated ?? true,
        user: options.user ?? { userId: 1, username: "demo_learner" },
        materials: [...(options.materials ?? [])],
        details: { ...(options.details ?? {}) },
        blocks: { ...(options.blocks ?? {}) },
        settings: options.settings ?? { provider: "DeepSeek", model: "deepseek-chat", configured: true },
        answers: [...(options.answers ?? [])],
        library: [...(options.library ?? [])],
        mastery: [...(options.mastery ?? [])],
        recommendations: options.recommendations ?? [],
        savedQuestions: options.savedQuestions ?? [],
        attempt: options.attempt ?? { attemptId: 501, status: "OPEN", assisted: false },
        submission: options.submission ?? {
            correct: true, correctOption: "A", eligible: true, exclusionReason: null, eventId: "event-1"
        },
        event: options.event ?? { eventId: "event-1", status: "APPLIED", errorCode: null },
        revealCorrectOption: options.revealCorrectOption ?? "A",
        job: options.job ?? null,
        variantJob: options.variantJob ?? null,
        variantJobId: options.variantJobId ?? "variant-job",
        uploadedMaterial: options.uploadedMaterial ?? null,
        parseResult: options.parseResult ?? null,
        notes: {},
        highlights: [],
        annotations: [...(options.annotations ?? [])],
        calls: [],
        unmatched: []
    };

    await page.route("**/api/**", async route => {
        const request = route.request();
        const url = new URL(request.url());
        const pathname = url.pathname;
        const method = request.method();
        const query = Object.fromEntries(url.searchParams);
        const call = {
            method,
            pathname,
            search: url.search,
            headers: request.headers(),
            body: requestBody(request)
        };
        state.calls.push(call);

        const materialDetail = pathname.match(/^\/api\/materials\/(\d+)$/);
        const materialBlocks = pathname.match(/^\/api\/materials\/(\d+)\/blocks$/);
        const materialParse = pathname.match(/^\/api\/materials\/(\d+)\/parse$/);
        const attemptDetail = pathname.match(/^\/api\/learning\/attempts\/(\d+)$/);
        const attemptAnswers = pathname.match(/^\/api\/learning\/attempts\/(\d+)\/answers$/);
        const attemptReveal = pathname.match(/^\/api\/learning\/attempts\/(\d+)\/reveal$/);
        const eventDetail = pathname.match(/^\/api\/learning\/events\/([^/]+)$/);
        const eventRetry = pathname.match(/^\/api\/learning\/events\/([^/]+)\/retry$/);
        const jobDetail = pathname.match(/^\/api\/generation\/jobs\/([^/]+)$/);
        const jobRetry = pathname.match(/^\/api\/generation\/jobs\/([^/]+)\/retry$/);

        try {
            const notePath = pathname.match(/^\/api\/notes\/([^/]+)$/);
            if (notePath) {
                const version = notePath[1];
                if (method === "GET") return await json(route, 200, { note: state.notes[version] ?? null });
                if (method === "DELETE") {
                    delete state.notes[version];
                    return await route.fulfill({ status: 204 });
                }
                const question = state.library.find(item => item.versionId === version);
                state.notes[version] = { questionVersionId: version, question: question?.content,
                    materialId: question?.materialId, materialName: question?.materialName,
                    ...JSON.parse(call.body), revision: "revision-1" };
                return await json(route, 200, state.notes[version]);
            }
            if (pathname === "/api/notes") {
                const records = Object.values(state.notes).filter(note => !query.q || note.content.includes(query.q));
                return await json(route, 200, { records, page: Number(query.page), hasMore: false });
            }
            const reading = pathname.match(/^\/api\/reading\/(\d+)(.*)$/);
            if (reading) {
                const material = state.details[reading[1]];
                if (reading[2] === "/annotations" && method === "GET") {
                    return await json(route, 200, { records: state.annotations });
                }
                if (reading[2] === "" && material?.parseStatus !== "PARSED") {
                    return await json(route, 409, { errorCode: "MATERIAL_NOT_PARSED", message: "资料尚未解析完成，暂无正文预览。" });
                }
                if (reading[2] === "") return await json(route, 200, { title: material.originalName,
                    materialId: material.materialId, parseAttempt: material.parseAttempt,
                    blocks: { ...pageOf(state.blocks[reading[1]] ?? [], { page: query.page }, 12), size: 12 },
                    highlights: state.highlights.filter(h => h.materialId === material.materialId) });
                if (reading[2] === "/ask") return await json(route, 200, { ...JSON.parse(call.body),
                    answer: "这是阅读答案。", expiresAt: 9999999999, receipt: "receipt-1" });
                if (reading[2].endsWith("/study")) {
                    const id = reading[2].split("/")[2];
                    const index = state.highlights.findIndex(item => item.id === id);
                    if (index < 0) return await json(route, 404, { message: "not found" });
                    if (method === "GET") return await json(route, 200, { page: 1, topics: [
                        { id: "topic-1", chapter: "事务", groupName: "ACID", name: "持久性", suggested: true },
                        { id: "topic-2", chapter: "事务", groupName: "ACID", name: "原子性", suggested: false }
                    ] });
                    const edit = JSON.parse(call.body);
                    const updated = { ...state.highlights[index], ...edit,
                        topicName: edit.topicId === "topic-1" ? "持久性" : edit.topicId === "topic-2" ? "原子性" : null };
                    state.highlights[index] = updated;
                    return await json(route, 200, updated);
                }
                if (method === "DELETE") {
                    state.highlights = state.highlights.filter(item => !reading[2].endsWith("/" + item.id));
                    return await route.fulfill({ status: 204 });
                }
                const { draft, color, pdfPosition } = JSON.parse(call.body);
                const saved = { ...draft.anchor, question: draft.question, detail: draft.detail,
                    answer: draft.answer, id: "a".repeat(64), color };
                state.highlights.push(saved);
                if (pdfPosition) state.annotations.push({ id: saved.id, ...pdfPosition, quote: draft.anchor.quote,
                    color, revision: "annotation-revision", noteId: saved.id, note: draft.answer, noteRevision: "note-revision" });
                return await json(route, 200, saved);
            }
            if (pathname === "/api/tutor/suggestion" && method === "GET") {
                return await route.fulfill({ status: 204 });
            }
            if (pathname === "/api/auth/me" && method === "GET") {
                if (state.authenticated) return await json(route, 200, state.user);
                return await json(route, 401, { message: "unauthorized" });
            }
            if (pathname === "/api/auth/csrf" && method === "GET") {
                return await json(route, 200, { headerName: "X-CSRF-TOKEN", token: "e2e-token" });
            }
            if (pathname === "/api/auth/login" && method === "POST") {
                if (options.loginStatus === 401) return await json(route, 401, { message: "bad credentials" });
                state.authenticated = true;
                return await route.fulfill({ status: 204 });
            }
            if (pathname === "/api/auth/logout" && method === "POST") {
                state.authenticated = false;
                return await route.fulfill({ status: 204 });
            }
            if (pathname === "/api/auth/register" && method === "POST") {
                const body = JSON.parse(call.body ?? "{}");
                if (body.username === "taken") return await json(route, 409, { message: "conflict" });
                return await json(route, 201, { userId: 2, username: body.username });
            }

            if (pathname === "/api/materials" && method === "GET") {
                return await json(route, 200, pageOf(state.materials, query, 5));
            }
            if (pathname === "/api/materials" && method === "POST") {
                const material = state.uploadedMaterial;
                if (!material) throw new Error("uploadedMaterial not configured");
                state.materials.unshift(material);
                state.details[material.materialId] = material;
                return await json(route, 201, material);
            }
            const original = /^\/api\/materials\/(\d+)\/original$/.exec(pathname);
            if (original && method === "GET") {
                if (!state.details[original[1]]) return await json(route, 404, { message: "not found" });
                const fixture = options.originals?.[original[1]];
                return await route.fulfill({ contentType: "application/octet-stream", body: fixture ?? "测试原件" });
            }
            if (materialDetail && method === "GET") {
                const material = state.details[materialDetail[1]];
                if (!material) return await json(route, 404, { errorCode: "MATERIAL_NOT_FOUND", message: "not found" });
                return await json(route, 200, material);
            }
            if (materialBlocks && method === "GET") {
                return await json(route, 200, pageOf(state.blocks[materialBlocks[1]] ?? [], query, 10));
            }
            if (materialParse && method === "POST") {
                const current = state.details[materialParse[1]];
                const parsed = { ...current, ...state.parseResult };
                state.details[materialParse[1]] = parsed;
                const listed = state.materials.find(item => item.materialId === parsed.materialId);
                if (listed) Object.assign(listed, parsed);
                return await json(route, 200, parsed);
            }

            if (pathname === "/api/settings/model" && method === "GET") {
                return await json(route, 200, state.settings);
            }
            if (pathname === "/api/settings/model-key" && (method === "PUT" || method === "DELETE")) {
                state.settings = { ...state.settings, configured: method === "PUT" };
                return await json(route, 200, state.settings);
            }

            if (pathname === "/api/answers" && method === "GET") {
                let records = [...state.answers];
                if (query.correct === "true") records = records.filter(item => item.correct);
                if (query.correct === "false") records = records.filter(item => !item.correct);
                records.sort((a, b) => String(a.submittedAt).localeCompare(String(b.submittedAt)));
                if ((query.sort ?? "desc") === "desc") records.reverse();
                return await json(route, 200, pageOf(records, query, 10));
            }

            if (pathname === "/api/learning/question-library" && method === "GET") {
                let records = [...state.library];
                if (query.materialId) records = records.filter(item => String(item.materialId) === query.materialId);
                if (query.status === "UNANSWERED") records = records.filter(item => !item.answered);
                if (query.status === "ANSWERED") records = records.filter(item => item.answered);
                return await json(route, 200, pageOf(records, query, 20));
            }
            if (pathname === "/api/learning/mastery" && method === "GET") {
                return await json(route, 200, state.mastery);
            }
            if (pathname === "/api/generation/jobs/knowledge-groups" && method === "GET") {
                return await json(route, 200, options.knowledgeGroups ?? []);
            }
            if (pathname === "/api/learning/recommendations" && method === "GET") {
                return await json(route, 200, { questions: state.recommendations });
            }
            if (pathname === "/api/learning/questions" && method === "GET") {
                return await json(route, 200, state.savedQuestions);
            }
            if (/^\/api\/learning\/explanations\/(PRACTICE|REVIEW)\/[^/]+$/.test(pathname)) {
                return await json(route, 200, method === "GET"
                    ? { brief: options.briefExplanation ?? "正确选项符合资料中的定义，需要结合适用条件判断。" }
                    : { answer: options.detailedExplanation ?? "先看题目要求，再逐项对照资料中的条件。" });
            }
            if (pathname === "/api/learning/attempts" && method === "POST") {
                return await json(route, 201, state.attempt);
            }
            if (attemptDetail && method === "GET") {
                return await json(route, 200, state.attempt);
            }
            if (attemptAnswers && method === "POST") {
                return await json(route, 200, state.submission);
            }
            if (attemptReveal && method === "POST") {
                return await json(route, 200, { correctOption: state.revealCorrectOption });
            }
            if (eventRetry && method === "POST") {
                return await json(route, 200, state.event);
            }
            if (eventDetail && method === "GET") {
                return await json(route, 200, state.event);
            }

            if (pathname === "/api/generation/jobs" && method === "POST") {
                if (!state.job) throw new Error("job not configured");
                return await json(route, 200, state.job);
            }
            if (pathname === "/api/generation/jobs/variants" && method === "POST") {
                const record = options.variantRecord;
                if (record) state.library.unshift(record);
                return await json(route, 200, { jobId: state.variantJobId });
            }
            if (jobRetry && method === "POST") {
                const job = jobRetry[1] === state.variantJobId ? state.variantJob : state.job;
                if (!job) throw new Error("job not configured");
                job.status = "SUCCEEDED";
                return await json(route, 200, job);
            }
            if (jobDetail && method === "GET") {
                const job = jobDetail[1] === state.variantJobId ? state.variantJob : state.job;
                if (!job) return await json(route, 404, { errorCode: "JOB_NOT_FOUND", message: "not found" });
                return await json(route, 200, job);
            }

            state.unmatched.push(`${method} ${pathname}${url.search}`);
            return await json(route, 500, { errorCode: "E2E_UNMATCHED", message: "unmatched mock" });
        } catch (error) {
            state.unmatched.push(`${method} ${pathname}: ${error.message}`);
            return await json(route, 500, { errorCode: "E2E_MOCK_ERROR", message: "mock failed" });
        }
    });

    return state;
}
