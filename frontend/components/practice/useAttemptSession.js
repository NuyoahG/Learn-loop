"use client";

import { useEffect, useRef, useState } from "react";
import { useAuth } from "@/components/auth/AuthProvider";
import {
    loadQuestionPracticeTicket, loadWorkspaceTicket, saveQuestionPracticeTicket, saveWorkspaceTicket
} from "@/lib/tickets";

function reason(error) {
    return error instanceof TypeError ? "无法连接服务，请检查网络或服务状态。" : error.message;
}

// 工作台与题目中心共用的答题流程；区别只在票据键和是否允许查看答案。
export function useAttemptSession({ mode, materialId }) {
    const { request, user } = useAuth();
    const [question, setQuestion] = useState(null);
    const [questionSource, setQuestionSource] = useState("");
    const [ticket, setTicket] = useState(null);
    const [attempt, setAttempt] = useState(null);
    const [option, setOptionState] = useState("");
    const [confidence, setConfidenceState] = useState("UNSURE");
    const [submission, setSubmission] = useState(null);
    const [event, setEvent] = useState(null);
    const [eventError, setEventError] = useState("");
    const [revealed, setRevealed] = useState(null);
    const [busy, setBusy] = useState(false);
    const [hint, setHint] = useState("");
    const [appliedCount, setAppliedCount] = useState(0);

    const questionRef = useRef(null);
    const ticketRef = useRef(null);
    const attemptRef = useRef(null);
    const submissionRef = useRef(null);
    const eventRef = useRef(null);
    const optionRef = useRef("");
    const confidenceRef = useRef("UNSURE");
    const busyRef = useRef(false);
    const mountedRef = useRef(true);
    const epochRef = useRef(0);
    const eventTimerRef = useRef(null);
    // 资料可能在首次渲染后才选定；票据键始终读取最新值，而不是旧闭包快照。
    const materialIdRef = useRef(materialId);
    materialIdRef.current = materialId;

    function setBusyBoth(value) {
        busyRef.current = value;
        setBusy(value);
    }

    function setOption(value) {
        optionRef.current = value;
        setOptionState(value);
    }

    function setConfidence(value) {
        confidenceRef.current = value;
        setConfidenceState(value);
    }

    function putTicket(value) {
        ticketRef.current = value;
        setTicket(value);
    }

    function putAttempt(value) {
        attemptRef.current = value;
        setAttempt(value);
    }

    function putSubmission(value) {
        submissionRef.current = value;
        setSubmission(value);
    }

    function putEvent(value) {
        eventRef.current = value;
        setEvent(value);
    }

    function stopEventPoll() {
        clearTimeout(eventTimerRef.current);
        eventTimerRef.current = null;
    }

    function loadSavedTicket(versionId) {
        return mode === "workspace"
            ? loadWorkspaceTicket(user.userId, materialIdRef.current, versionId)
            : loadQuestionPracticeTicket(user.userId, versionId);
    }

    function saveTicket(value) {
        if (mode === "workspace") saveWorkspaceTicket(user.userId, materialIdRef.current, value);
        else saveQuestionPracticeTicket(user.userId, value);
    }

    function pollEvent(eventId, version) {
        stopEventPoll();
        let attempts = 0;
        const tick = async () => {
            try {
                const next = await request(`/api/learning/events/${encodeURIComponent(eventId)}`);
                if (!mountedRef.current || version !== epochRef.current) return;
                putEvent(next);
                setEventError("");
                if (next.status === "APPLIED") {
                    setAppliedCount(count => count + 1);
                } else if (next.status === "PENDING" || next.status === "PROCESSING") {
                    attempts++;
                    if (attempts < 60) eventTimerRef.current = setTimeout(tick, 1200);
                    else setEventError("答案已保存，掌握度仍在更新；稍后刷新页面即可查看最新结果。");
                }
            } catch (error) {
                if (!mountedRef.current || version !== epochRef.current) return;
                attempts++;
                setEventError("掌握度查询失败：" + reason(error) +
                    (attempts < 60 ? " 正在自动重试……" : " 请稍后刷新页面查看。"));
                if (attempts < 60) eventTimerRef.current = setTimeout(tick, 2000);
            }
        };
        void tick();
    }

    async function openAttempt(currentTicket, version) {
        setBusyBoth(true);
        try {
            const next = currentTicket.attemptId
                ? await request(`/api/learning/attempts/${encodeURIComponent(currentTicket.attemptId)}`)
                : await request("/api/learning/attempts", {
                    method: "POST",
                    headers: { "Content-Type": "application/json" },
                    body: JSON.stringify({
                        questionVersionId: currentTicket.questionVersionId,
                        requestKey: currentTicket.requestKey
                    })
                });
            if (!mountedRef.current || version !== epochRef.current) return;
            putAttempt(next);
            if (next.attemptId !== currentTicket.attemptId) {
                const saved = { ...currentTicket, attemptId: next.attemptId };
                putTicket(saved);
                saveTicket(saved);
            }
        } catch (error) {
            if (!mountedRef.current || version !== epochRef.current) return;
            setHint("答题尝试未确认：" + reason(error) + " 可重新点击本题重试，不会创建重复的可作答尝试。");
        } finally {
            if (mountedRef.current && version === epochRef.current) setBusyBoth(false);
        }
    }

    async function select(nextQuestion, source = "") {
        if (!nextQuestion) return;
        if (busyRef.current) {
            setHint("正在与服务器确认，请稍后再切换题目。");
            return;
        }
        const existing = ticketRef.current;
        if (existing?.submit && !submissionRef.current &&
            questionRef.current?.versionId !== nextQuestion.versionId) {
            setHint("当前答案尚未得到确认，请先点击“确认原提交结果”，再切换题目。");
            return;
        }

        const version = ++epochRef.current;
        stopEventPoll();
        questionRef.current = nextQuestion;
        setQuestion(nextQuestion);
        setQuestionSource(source);
        putSubmission(null);
        putEvent(null);
        setEventError("");
        setRevealed(null);
        putAttempt(null);
        setHint("");

        const saved = loadSavedTicket(nextQuestion.versionId) ?? {
            questionVersionId: nextQuestion.versionId,
            requestKey: crypto.randomUUID()
        };
        putTicket(saved);
        saveTicket(saved);
        setOption(saved.submit?.selectedOption ?? "");
        setConfidence(saved.submit?.confidence ?? "UNSURE");
        await openAttempt(saved, version);
    }

    async function restart() {
        const current = questionRef.current;
        if (!current || busyRef.current) return;
        const version = ++epochRef.current;
        stopEventPoll();
        const nextTicket = { questionVersionId: current.versionId, requestKey: crypto.randomUUID() };
        putTicket(nextTicket);
        saveTicket(nextTicket);
        putAttempt(null);
        putSubmission(null);
        putEvent(null);
        setEventError("");
        setRevealed(null);
        setOption("");
        setConfidence("UNSURE");
        setHint("");
        await openAttempt(nextTicket, version);
    }

    async function submit() {
        const activeAttempt = attemptRef.current;
        const activeTicket = ticketRef.current;
        if (!activeAttempt || !activeTicket || !questionRef.current || busyRef.current || submissionRef.current) return;
        const pending = activeTicket.submit ?? {
            selectedOption: optionRef.current,
            submitKey: crypto.randomUUID(),
            confidence: confidenceRef.current
        };
        if (!pending.selectedOption) {
            setHint("请先选择一个答案。");
            return;
        }
        // 发送前固定载荷；响应丢失后重试仍使用同一 submitKey，不会重复记录。
        const saved = { ...activeTicket, submit: pending };
        putTicket(saved);
        saveTicket(saved);
        setOption(pending.selectedOption);
        setConfidence(pending.confidence);
        setBusyBoth(true);
        const version = epochRef.current;
        try {
            const result = await request(
                `/api/learning/attempts/${encodeURIComponent(activeAttempt.attemptId)}/answers`, {
                    method: "POST",
                    headers: { "Content-Type": "application/json" },
                    body: JSON.stringify(pending)
                });
            if (!mountedRef.current || version !== epochRef.current) return;
            putSubmission(result);
            setHint("");
            setEventError("");
            if (result.eventId) pollEvent(result.eventId, version);
        } catch (error) {
            if (!mountedRef.current || version !== epochRef.current) return;
            setHint("提交结果未确认：" + reason(error) + " 请点击“确认原提交结果”，答案不会重复记录。");
        } finally {
            if (mountedRef.current && version === epochRef.current) setBusyBoth(false);
        }
    }

    async function reveal() {
        const activeAttempt = attemptRef.current;
        const activeTicket = ticketRef.current;
        if (mode !== "workspace" || !activeAttempt || !activeTicket || busyRef.current || activeTicket.submit) return;
        if (!activeTicket.revealRequested &&
            !window.confirm("查看后，本次作答会标记为辅助练习，不计入独立学习证据。继续查看？")) return;

        const saved = { ...activeTicket, revealRequested: true };
        putTicket(saved);
        saveTicket(saved);
        setBusyBoth(true);
        const version = epochRef.current;
        try {
            const next = await request(
                `/api/learning/attempts/${encodeURIComponent(activeAttempt.attemptId)}/reveal`,
                { method: "POST" });
            if (!mountedRef.current || version !== epochRef.current) return;
            setRevealed(next.correctOption);
            putAttempt({ ...activeAttempt, assisted: true });
        } catch (error) {
            if (mountedRef.current && version === epochRef.current) {
                setHint("查看答案失败：" + reason(error));
            }
        } finally {
            if (mountedRef.current && version === epochRef.current) setBusyBoth(false);
        }
    }

    async function retryEvent() {
        const current = eventRef.current;
        if (!current || current.status !== "FAILED" || busyRef.current) return;
        setBusyBoth(true);
        const version = epochRef.current;
        try {
            await request(`/api/learning/events/${encodeURIComponent(current.eventId)}/retry`, { method: "POST" });
            if (!mountedRef.current || version !== epochRef.current) return;
            putEvent({ ...current, status: "PENDING" });
            setEventError("");
            pollEvent(current.eventId, version);
        } catch (error) {
            if (mountedRef.current && version === epochRef.current) {
                setEventError("重试失败：" + reason(error));
            }
        } finally {
            if (mountedRef.current && version === epochRef.current) setBusyBoth(false);
        }
    }

    function clear() {
        epochRef.current++;
        stopEventPoll();
        questionRef.current = null;
        setQuestion(null);
        setQuestionSource("");
        putTicket(null);
        putAttempt(null);
        putSubmission(null);
        putEvent(null);
        setEventError("");
        setRevealed(null);
        setOption("");
        setConfidence("UNSURE");
        setHint("");
        setBusyBoth(false);
    }

    useEffect(() => {
        mountedRef.current = true;
        return () => {
            mountedRef.current = false;
            epochRef.current++;
            stopEventPoll();
        };
    }, []);

    return {
        question, questionSource, ticket, attempt, option, confidence, submission, event,
        eventError, revealed, busy, hint, appliedCount,
        select, restart, submit, reveal, retryEvent, clear, setOption, setConfidence, setHint
    };
}
