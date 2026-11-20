"use client";

import { useEffect, useRef, useState } from "react";
import { useAuth } from "@/components/auth/AuthProvider";
import { ApiError } from "@/lib/api";
import { difficultyLabels, locatorLabel, reason } from "@/lib/format";
import { queryMaterialId, queryVersionId } from "@/lib/workspace-model";
import {
    clearQuestionVariantTicket, loadQuestionVariantTicket, saveQuestionVariantTicket
} from "@/lib/tickets";
import { AnswerOptions, ConfidenceOptions } from "@/components/practice/AnswerOptions";
import { TutorFeedback } from "@/components/practice/TutorFeedback";
import { NoteEditor } from "@/components/practice/NoteEditor";
import { useAttemptSession } from "@/components/practice/useAttemptSession";
import { AnswerExplanation } from "@/components/practice/AnswerExplanation";

const PAGE_SIZE = 20;

function delay(ms) {
    return new Promise(resolve => setTimeout(resolve, ms));
}

export function QuestionsPage() {
    const { request, user } = useAuth();
    const session = useAttemptSession({ mode: "question" });

    const [materialOptions, setMaterialOptions] = useState([]);
    const [materialId, setMaterialId] = useState("");
    const [statusFilter, setStatusFilter] = useState("ALL");
    const [filterHint, setFilterHint] = useState("");
    const [records, setRecords] = useState([]);
    const [position, setPosition] = useState(null);
    const [libraryCollapsed, setLibraryCollapsed] = useState(false);
    const [page, setPage] = useState(1);
    const [pages, setPages] = useState(0);
    const [total, setTotal] = useState(0);
    const [loading, setLoading] = useState(false);
    const [libraryStatus, setLibraryStatus] = useState("");
    const [librarySummary, setLibrarySummary] = useState("尚未加载");
    const [pageInfo, setPageInfo] = useState("尚未加载");
    const [mastery, setMastery] = useState(null);
    const [masteryLoading, setMasteryLoading] = useState(false);
    const [masteryError, setMasteryError] = useState("");
    const [variantBusy, setVariantBusy] = useState(false);
    const [variantJobId, setVariantJobId] = useState(null);
    const [variantFailed, setVariantFailed] = useState(false);
    const [variantStatus, setVariantStatus] = useState("");

    const mountedRef = useRef(true);
    const startedRef = useRef(false);
    const materialIdRef = useRef("");
    const statusRef = useRef("ALL");
    const pageRef = useRef(1);
    const autoOpenRef = useRef("");
    const librarySeqRef = useRef(0);
    const masterySeqRef = useRef(0);
    const variantSeqRef = useRef(0);
    const sessionQuestionRef = useRef(null);
    const variantJobIdRef = useRef(null);
    const variantRequestKeyRef = useRef(null);
    const variantBusyRef = useRef(false);

    sessionQuestionRef.current = session.question;

    async function loadMaterials() {
        try {
            const data = await request("/api/materials?page=1&size=50");
            if (!mountedRef.current) return;
            if (!Array.isArray(data.records)) throw new Error("资料列表响应不完整。");
            const current = materialIdRef.current;
            const options = [
                { value: "", label: "全部资料" },
                ...data.records.map(material => ({
                    value: String(material.materialId),
                    label: `${material.originalName} · ${material.blockCount} 块`
                }))
            ];
            // 深链可能指向不在最近 50 份里的资料；保留筛选项比静默丢失更清楚。
            if (current && !options.some(option => option.value === current)) {
                options.push({ value: current, label: `资料 #${current}` });
            }
            setMaterialOptions(options);
            setFilterHint(data.total > data.records.length
                ? `筛选器只列出最近 ${data.records.length} 份资料。`
                : "");
        } catch (error) {
            if (!mountedRef.current) return;
            setLibraryStatus("资料筛选加载失败：" + reason(error));
        }
    }

    async function loadLibrary(requestedPage, requestedMaterial = materialIdRef.current,
        requestedStatus = statusRef.current) {
        if (!mountedRef.current) return null;
        const sequence = ++librarySeqRef.current;
        setLoading(true);
        setLibraryStatus("正在加载题目……");
        const params = new URLSearchParams({
            page: String(requestedPage),
            size: String(PAGE_SIZE),
            status: requestedStatus
        });
        if (requestedMaterial) params.set("materialId", requestedMaterial);

        try {
            const data = await request("/api/learning/question-library?" + params);
            if (!mountedRef.current || sequence !== librarySeqRef.current) return null;
            if (!Array.isArray(data.records)) throw new Error("题目列表响应不完整。");
            pageRef.current = data.page;
            setPage(data.page);
            setPages(data.totalPages);
            setTotal(data.total);
            setRecords(data.records);
            setLibrarySummary(`共 ${data.total} 题`);
            setPageInfo(data.total ? `第 ${data.page} / ${data.totalPages} 页` : "");
            setLibraryStatus("");

            if (autoOpenRef.current) {
                const wanted = autoOpenRef.current;
                autoOpenRef.current = "";
                const record = data.records.find(item => item.versionId === wanted);
                if (record) void session.select(record);
                else setLibraryStatus("深链中的题目不在当前筛选页，请从题目列表中重新选择。");
            }
            return data;
        } catch (error) {
            if (!mountedRef.current || sequence !== librarySeqRef.current) return null;
            setRecords([]);
            setLibrarySummary("读取失败");
            setPageInfo("");
            setLibraryStatus(error instanceof ApiError && error.errorCode === "LEARNING_NOT_ENABLED"
                ? "学习功能尚未启用（默认关闭），题库与作答暂不可用。"
                : "题目读取失败：" + reason(error));
            return null;
        } finally {
            if (mountedRef.current && sequence === librarySeqRef.current) setLoading(false);
        }
    }

    async function loadMasteryFor(question) {
        if (!question) return;
        const sequence = ++masterySeqRef.current;
        setMasteryLoading(true);
        setMasteryError("");
        try {
            const list = await request(`/api/learning/mastery?materialId=${encodeURIComponent(question.materialId)}`);
            if (!mountedRef.current || sequence !== masterySeqRef.current ||
                sessionQuestionRef.current?.conceptId !== question.conceptId) return;
            setMastery(Array.isArray(list)
                ? list.find(item => item.conceptId === question.conceptId) ?? null
                : null);
        } catch (error) {
            if (!mountedRef.current || sequence !== masterySeqRef.current) return;
            setMastery(null);
            setMasteryError(error instanceof ApiError && error.errorCode === "LEARNING_NOT_ENABLED"
                ? "学习功能尚未启用。" : "读取失败：" + reason(error));
        } finally {
            if (mountedRef.current && sequence === masterySeqRef.current) setMasteryLoading(false);
        }
    }

    useEffect(() => {
        mountedRef.current = true;
        if (!startedRef.current) {
            startedRef.current = true;
            const search = window.location.search;
            const linkedMaterial = queryMaterialId(search);
            materialIdRef.current = linkedMaterial;
            setMaterialId(linkedMaterial);
            autoOpenRef.current = queryVersionId(search);
            void loadMaterials();
            void loadLibrary(1, linkedMaterial, "ALL");
        }
        return () => {
            mountedRef.current = false;
            variantSeqRef.current++;
        };
        // eslint-disable-next-line react-hooks/exhaustive-deps
    }, []);

    useEffect(() => {
        if (session.question) void loadMasteryFor(session.question);
        else {
            masterySeqRef.current++;
            setMastery(null);
            setMasteryError("");
            setMasteryLoading(false);
        }
        // eslint-disable-next-line react-hooks/exhaustive-deps
    }, [session.question?.versionId]);

    useEffect(() => {
        if (session.appliedCount > 0) {
            if (session.question) void loadMasteryFor(session.question);
            void loadLibrary(pageRef.current);
        }
        // eslint-disable-next-line react-hooks/exhaustive-deps
    }, [session.appliedCount]);

    useEffect(() => {
        const versionId = session.question?.versionId;
        variantSeqRef.current++;
        variantBusyRef.current = false;
        setVariantBusy(false);
        setVariantFailed(false);
        if (!versionId) {
            variantJobIdRef.current = null;
            variantRequestKeyRef.current = null;
            setVariantJobId(null);
            setVariantStatus("");
            return;
        }
        const saved = loadQuestionVariantTicket(user.userId, versionId);
        variantJobIdRef.current = saved?.jobId ?? null;
        variantRequestKeyRef.current = saved?.requestKey ?? null;
        setVariantJobId(saved?.jobId ?? null);
        setVariantStatus(saved?.jobId ? "已有同考点出题任务；点击按钮继续确认结果。"
            : saved?.requestKey ? "上次出题请求未确认；点击按钮可复用原请求键继续。" : "");
    }, [session.question?.versionId, user.userId]);

    function isCurrentVariant(versionId, sequence) {
        return mountedRef.current && sessionQuestionRef.current?.versionId === versionId &&
            variantSeqRef.current === sequence;
    }

    function setVariantBusyBoth(value) {
        variantBusyRef.current = value;
        setVariantBusy(value);
    }

    function updateVariantTicket(ticket) {
        variantJobIdRef.current = ticket.jobId ?? null;
        variantRequestKeyRef.current = ticket.requestKey ?? null;
        setVariantJobId(ticket.jobId ?? null);
        saveQuestionVariantTicket(user.userId, ticket);
    }

    async function openVariantQuestion(ticket, job, versionId, sequence) {
        const createdVersionId = job.questions?.[0]?.question?.versionId;
        clearQuestionVariantTicket(user.userId, ticket.questionVersionId);
        variantJobIdRef.current = null;
        variantRequestKeyRef.current = null;
        setVariantJobId(null);
        setVariantFailed(false);
        if (!createdVersionId) {
            setVariantStatus("任务已完成，但没有返回新题。");
            return;
        }
        setVariantStatus("已生成同考点新题，正在打开……");
        // 新题按最近生成排序；切到“全部”避免当前筛选把它隐藏。
        statusRef.current = "ALL";
        setStatusFilter("ALL");
        const data = await loadLibrary(1, materialIdRef.current, "ALL");
        if (!isCurrentVariant(versionId, sequence)) return;
        const record = data?.records.find(item => item.versionId === createdVersionId);
        if (!record) {
            setVariantStatus("新题已生成，但不在当前题库页；请刷新题目列表。");
            return;
        }
        await session.select(record);
        setVariantStatus(sessionQuestionRef.current?.versionId === createdVersionId
            ? "已打开同考点新题。" : "新题已生成，请从左侧打开。");
    }

    async function pollVariantJob(ticket, versionId, sequence) {
        setVariantBusyBoth(true);
        setVariantStatus("正在生成同考点新题……");
        try {
            for (let attempt = 0; attempt < 60; attempt++) {
                const job = await request(`/api/generation/jobs/${encodeURIComponent(ticket.jobId)}`);
                if (!isCurrentVariant(versionId, sequence)) return;
                if (job.status === "SUCCEEDED") {
                    await openVariantQuestion(ticket, job, versionId, sequence);
                    return;
                }
                if (job.status === "FAILED") {
                    setVariantFailed(true);
                    setVariantStatus(`同考点出题失败（${job.errorCode ?? "UNKNOWN"}）。` +
                        "可点击按钮重试；重试可能再次计费。");
                    return;
                }
                setVariantStatus(job.status === "PROCESSING" ? "模型正在生成同考点新题……" : "出题任务排队中……");
                await delay(1200);
                if (!isCurrentVariant(versionId, sequence)) return;
            }
            setVariantStatus("出题仍在进行，请稍后刷新页面查看结果。");
        } catch (error) {
            if (!isCurrentVariant(versionId, sequence)) return;
            setVariantStatus("出题结果未确认：" + reason(error) + " 再次点击会继续确认原任务。");
        } finally {
            if (isCurrentVariant(versionId, sequence)) setVariantBusyBoth(false);
        }
    }

    async function generateVariant() {
        const question = session.question;
        if (!question || variantBusyRef.current) return;
        let ticket = variantJobIdRef.current || variantRequestKeyRef.current
            ? {
                questionVersionId: question.versionId,
                requestKey: variantRequestKeyRef.current,
                jobId: variantJobIdRef.current
            }
            : loadQuestionVariantTicket(user.userId, question.versionId);

        if (ticket?.jobId && variantFailed) {
            if (!window.confirm("上次同考点出题失败；重试会再次调用模型并可能产生费用。继续吗？")) return;
            const sequence = ++variantSeqRef.current;
            setVariantBusyBoth(true);
            setVariantStatus("正在重新提交同考点出题任务……");
            try {
                await request(`/api/generation/jobs/${encodeURIComponent(ticket.jobId)}/retry`, { method: "POST" });
                if (!isCurrentVariant(question.versionId, sequence)) return;
                setVariantFailed(false);
                await pollVariantJob(ticket, question.versionId, sequence);
            } catch (error) {
                if (isCurrentVariant(question.versionId, sequence)) {
                    setVariantStatus("重试失败：" + reason(error));
                }
            } finally {
                if (isCurrentVariant(question.versionId, sequence)) setVariantBusyBoth(false);
            }
            return;
        }

        if (ticket?.jobId) {
            await pollVariantJob(ticket, question.versionId, ++variantSeqRef.current);
            return;
        }

        if (!window.confirm("将为这道题所属考点再生成一道新题，会调用模型并可能产生费用。继续吗？")) return;
        const requestKey = ticket?.requestKey ?? crypto.randomUUID();
        ticket = { questionVersionId: question.versionId, requestKey, jobId: null };
        updateVariantTicket(ticket);
        setVariantFailed(false);
        const sequence = ++variantSeqRef.current;
        setVariantBusyBoth(true);
        setVariantStatus("正在提交同考点出题请求……");
        try {
            const job = await request("/api/generation/jobs/variants", {
                method: "POST",
                headers: { "Content-Type": "application/json" },
                body: JSON.stringify({ questionVersionId: question.versionId, requestKey })
            });
            if (!isCurrentVariant(question.versionId, sequence)) return;
            ticket = { ...ticket, jobId: job.jobId };
            updateVariantTicket(ticket);
            await pollVariantJob(ticket, question.versionId, sequence);
        } catch (error) {
            if (isCurrentVariant(question.versionId, sequence)) {
                setVariantStatus("出题请求未确认：" + reason(error) + " 再次点击会复用原请求键，不会重复提交。");
            }
        } finally {
            if (isCurrentVariant(question.versionId, sequence)) setVariantBusyBoth(false);
        }
    }

    const question = session.question;
    const attempt = session.attempt;
    const ticket = session.ticket;
    const submission = session.submission;
    // 列表在答题后刷新；筛选掉当前题目时仍保留右侧练习的原始快照。
    const selectedIndex = records.findIndex(item => item.versionId === question?.versionId);
    const activeRecord = records[selectedIndex] ?? question;
    useEffect(() => {
        if (loading || !question) return;
        if (selectedIndex >= 0) {
            setPosition({ versionId: question.versionId, page, index: selectedIndex, materialId, statusFilter, present: true });
        } else {
            setPosition(last => last?.versionId === question.versionId && last.page === page &&
                last.materialId === materialId && last.statusFilter === statusFilter && last.present
                ? { ...last, present: false } : last);
        }
    }, [loading, selectedIndex, question?.versionId, page, materialId, statusFilter]);

    const sameList = position && position.versionId === question?.versionId &&
        position.materialId === materialId && position.statusFilter === statusFilter;
    const originPage = selectedIndex >= 0 || !sameList ? page : position.page;
    // 提交后当前题可能被「未作答」筛掉，后继题会移到它原先的位置。
    let nextIndex = 0;
    if (selectedIndex >= 0) nextIndex = selectedIndex + 1;
    else if (sameList) nextIndex = position.index + Number(originPage !== page && position.present);
    const hasNext = records.length > 0 && (originPage - 1) * PAGE_SIZE + nextIndex < total;

    async function openNextQuestion() {
        let index = nextIndex;
        let rows = records;
        // 手动翻过题库页时，仍从正在练习的题继续。
        if (originPage !== page) {
            const origin = await loadLibrary(originPage);
            if (!origin || sessionQuestionRef.current?.versionId !== question.versionId) return;
            rows = origin.records;
            const found = rows.findIndex(row => row.versionId === question.versionId);
            index = found >= 0 ? found + 1 : position.index;
            if (!rows[index] && originPage >= origin.totalPages) return;
        }
        let next = rows[index];
        if (!next) {
            const following = await loadLibrary(originPage + 1);
            next = following?.records[0];
        }
        if (next && sessionQuestionRef.current?.versionId === question.versionId) await session.select(next);
    }
    const lastCorrect = submission?.correct ?? activeRecord?.latestCorrect;
    let answerState = "未作答";
    if (submission || activeRecord?.answered) {
        answerState = lastCorrect === true ? "已作答 · 最近答对"
            : lastCorrect === false ? "已作答 · 最近答错" : "已作答";
    }
    const uncertain = !!ticket?.submit && !submission;
    const locked = session.busy || !!ticket?.submit || !!submission || attempt?.status !== "OPEN";
    const practiceStatus = !question ? "未选题" : session.busy ? "准备中"
        : submission ? "已提交"
            : attempt?.status === "EXPIRED" ? "已过期"
                : attempt?.status === "OPEN" ? "作答中" : "加载中";

    const hint = session.busy ? "正在与服务器确认……"
        : uncertain ? "服务器可能已收到答案。选项和把握已锁定，请用原提交确认，避免重复记录。"
            : attempt?.status === "EXPIRED" ? "这次答题尝试已过期，请点击“重新开始答题”。"
                : attempt?.status === "SUBMITTED" && !ticket?.submit && !submission
                    ? "此尝试已完成，可重新选择本题开始新的练习。"
                    : session.hint || "选择答案和答题把握后提交；独立作答才会更新掌握度。";

    let resultText = "";
    let eventText = "";
    if (submission) {
        resultText = `${submission.correct ? "答对" : "答错"} · 正确答案 ${submission.correctOption}`;
        // 正常结果只展示答案和掌握度，更新失败/查询异常仍需保留，不能被精简样式掩盖。
        if (session.event?.status === "FAILED") {
            eventText = `答案已保存，掌握度更新失败（${session.event.errorCode ?? "UNKNOWN"}）。可点击“重试掌握度更新”。`;
        }
    }

    let masteryText = "";
    if (submission?.eligible && !["APPLIED", "FAILED"].includes(session.event?.status)) masteryText = "掌握度更新中…";
    else if (masteryLoading) masteryText = "掌握度读取中…";
    else if (masteryError) masteryText = "掌握度：" + masteryError;
    else if (!mastery) masteryText = "掌握度：暂无记录";
    else if (mastery.updating) masteryText = "掌握度：更新中……";
    else if (mastery.evidenceStatus === "SOURCE_INVALID") masteryText = "掌握度：来源已更新";
    else if (mastery.probability == null) masteryText = `掌握度：样本不足 · ${mastery.evidenceCount ?? 0} 条证据`;
    else masteryText = `掌握度：${Math.round(mastery.probability * 100)}% · ${mastery.evidenceCount ?? 0} 条证据`;

    return (
        <div className="workspace-page questions-page">
            <header className="workspace-heading questions-heading">
                <h1>题目中心与答题练习</h1>
                <button type="button" className="secondary" aria-controls="questionLibrary"
                    aria-expanded={!libraryCollapsed} onClick={() => setLibraryCollapsed(value => !value)}>
                    {libraryCollapsed ? "展开题目列表" : "收起题目列表"}
                </button>
            </header>

            <div className="questions-layout" data-library-collapsed={libraryCollapsed}>
                <section id="questionLibrary" className="workspace-card questions-library-column"
                    aria-labelledby="libraryHeading" hidden={libraryCollapsed}>
                    <div className="workspace-section-heading">
                        <h2 id="libraryHeading">我的题目</h2>
                        <span id="librarySummary" className="workspace-badge">{librarySummary}</span>
                    </div>
                    <div className="questions-filters">
                        <label htmlFor="materialFilter">资料
                            <select id="materialFilter" value={materialId} onChange={event => {
                                const value = event.target.value;
                                materialIdRef.current = value;
                                setMaterialId(value);
                                void loadLibrary(1, value);
                            }}>
                                {materialOptions.map(option => (
                                    <option key={option.value} value={option.value}>{option.label}</option>
                                ))}
                            </select>
                        </label>
                        <label htmlFor="statusFilter">作答状态
                            <select id="statusFilter" value={statusFilter} onChange={event => {
                                const value = event.target.value;
                                statusRef.current = value;
                                setStatusFilter(value);
                                void loadLibrary(1, materialIdRef.current, value);
                            }}>
                                <option value="ALL">全部</option>
                                <option value="UNANSWERED">未作答</option>
                                <option value="ANSWERED">已作答</option>
                            </select>
                        </label>
                        <button type="button" className="secondary" disabled={loading}
                            onClick={() => void loadLibrary(pageRef.current)}>刷新</button>
                    </div>
                    {filterHint && <p className="input-hint">{filterHint}</p>}
                    <p id="libraryStatus" role="status" aria-live="polite">{libraryStatus}</p>
                    <div id="libraryList" className="questions-list-wrap">
                        {records.length === 0 ? (
                            <p className="input-hint">
                                {total === 0
                                    ? "还没有符合条件的题目。请先到工作台选择资料范围并出题。"
                                    : "这一页没有题目，请返回上一页。"}
                            </p>
                        ) : (
                            <ul className="questions-list">
                                {records.map(item => (
                                    <li key={item.versionId}>
                                        <button type="button" className="questions-card"
                                            aria-pressed={question?.versionId === item.versionId}
                                            onClick={() => void session.select(item)}>
                                            {item.content}
                                        </button>
                                    </li>
                                ))}
                            </ul>
                        )}
                    </div>
                    <div className="history-pagination">
                        <button type="button" className="secondary"
                            disabled={loading || page <= 1 || pages === 0}
                            onClick={() => void loadLibrary(page - 1)}>上一页</button>
                        <span id="libraryPageInfo">{pageInfo}</span>
                        <button type="button" className="secondary"
                            disabled={loading || page >= pages || pages === 0}
                            onClick={() => void loadLibrary(page + 1)}>下一页</button>
                    </div>
                </section>

                <section className="workspace-card questions-practice-column" aria-labelledby="practiceHeading">
                    <div className="workspace-section-heading">
                        <h2 id="practiceHeading">答题练习</h2>
                        <span id="practiceStatus" className="workspace-badge">{practiceStatus}</span>
                    </div>

                    {!question ? (
                        <div id="practiceEmpty" className="questions-empty">
                            <p>从左侧选择一道题开始练习。</p>
                        </div>
                    ) : (
                        <div id="practicePanel">
                            <p id="practiceMeta" className="input-hint">
                                {[
                                    question.materialName,
                                    question.conceptName ? `考点：${question.conceptName}` : "",
                                    `来源：${locatorLabel(question.sourceLocator)}`,
                                    `难度：${difficultyLabels[question.difficulty] ?? "未知"}`,
                                    answerState
                                ].filter(Boolean).join(" · ")}
                            </p>
                            <p id="practiceQuestion" className="workspace-question-text">{question.content}</p>

                            <AnswerOptions question={question} name="practiceAnswer"
                                selected={session.option}
                                disabled={!attempt || attempt.status !== "OPEN" || locked}
                                correctOption={submission?.correctOption ?? null}
                                onChange={session.setOption} />

                            {!submission && attempt?.status !== "SUBMITTED" && <ConfidenceOptions name="practiceConfidence" value={session.confidence}
                                disabled={!attempt || attempt.status !== "OPEN" || locked}
                                onChange={session.setConfidence} />}

                            {!submission && attempt?.status !== "SUBMITTED" && <p id="practiceHint" role="status" aria-live="polite">{hint}</p>}
                            <div className="workspace-actions">
                                <button id="practiceSubmit" type="button"
                                    disabled={session.busy || !attempt || !!submission ||
                                        (!session.option && !ticket?.submit) ||
                                        (attempt.status !== "OPEN" && !ticket?.submit)}
                                    onClick={() => void session.submit()}>
                                    {session.busy ? "正在确认……" : uncertain ? "确认原提交结果" : "提交答案"}
                                </button>
                                {["EXPIRED", "SUBMITTED"].includes(attempt?.status) &&
                                    !ticket?.submit && !submission ? (
                                    <button type="button" className="secondary"
                                        onClick={() => void session.restart()}>
                                        {attempt?.status === "EXPIRED" ? "重新开始答题" : "重新练习这道题"}
                                    </button>
                                ) : null}
                                {session.event?.status === "FAILED" ? (
                                    <button type="button" className="secondary"
                                        disabled={session.busy}
                                        onClick={() => void session.retryEvent()}>重试掌握度更新</button>
                                ) : null}
                                <button type="button" className="secondary"
                                    disabled={variantBusy || session.busy}
                                    onClick={() => void generateVariant()}>
                                    {variantBusy ? "正在生成同考点新题……"
                                        : variantFailed ? "重试生成同考点新题"
                                            : variantJobId ? "确认原出题请求" : "同考点再出一题"}
                                </button>
                                <button type="button" className="secondary practice-next"
                                    disabled={!hasNext || loading || session.busy || uncertain || variantBusy}
                                    onClick={() => void openNextQuestion()}>下一题</button>
                            </div>
                            <p className="input-hint" role="status" aria-live="polite">{variantStatus}</p>

                            {submission ? (
                                <section className="questions-result" data-correct={String(submission.correct)}
                                    aria-live="polite">
                                    <p>{resultText}</p>
                                    <p className="questions-mastery">{masteryText}</p>
                                    {(eventText || session.eventError) && <p className="input-hint questions-result-error">
                                        {eventText}{session.eventError ? " " + session.eventError : ""}
                                    </p>}
                                </section>
                            ) : null}
                            {submission && <AnswerExplanation key={submission.attemptId ?? attempt?.attemptId}
                                attemptId={submission.attemptId ?? attempt?.attemptId} />}
                            {submission?.correct === false && <TutorFeedback key={submission.attemptId} kind="PRACTICE" attemptId={submission.attemptId} />}
                            <NoteEditor key={question.versionId} version={question.versionId} />
                        </div>
                    )}
                </section>
            </div>
        </div>
    );
}
