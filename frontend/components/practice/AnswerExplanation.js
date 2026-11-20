"use client";

import { useEffect, useRef, useState } from "react";
import { useAuth } from "@/components/auth/AuthProvider";
import "./answer-explanation.css";

/** 已提交答案的讲解。自动读取已有解析；只有用户点击详解才允许调用模型。 */
export function AnswerExplanation({ kind = "PRACTICE", attemptId, initialBrief = "" }) {
    const { request } = useAuth();
    const [brief, setBrief] = useState(initialBrief.trim());
    const [briefError, setBriefError] = useState("");
    const [reload, setReload] = useState(0);
    const [answer, setAnswer] = useState("");
    const [expanded, setExpanded] = useState(false);
    const [busy, setBusy] = useState(false);
    const [error, setError] = useState("");
    const detailRequest = useRef(null);
    const path = `/api/learning/explanations/${kind}/${encodeURIComponent(attemptId)}`;

    useEffect(() => {
        if (!attemptId || initialBrief.trim()) return;
        const controller = new AbortController();
        setBriefError("");
        request(path, { signal: controller.signal }).then(value => {
            if (controller.signal.aborted) return;
            if (!value?.brief?.trim()) throw new Error("暂时没有可用的讲解。");
            setBrief(value.brief);
        }).catch(problem => { if (!controller.signal.aborted) setBriefError(problem.message); });
        return () => controller.abort();
    }, [attemptId, path, initialBrief, request, reload]);

    useEffect(() => () => detailRequest.current?.abort(), []);

    async function explain() {
        // 已生成内容只展开/收起，不重复付费；同步 ref 阻止双击在重渲染前发出第二次请求。
        if (answer) { setExpanded(value => !value); return; }
        if (detailRequest.current || !attemptId) return;
        const controller = new AbortController();
        detailRequest.current = controller;
        setBusy(true); setError("");
        try {
            const value = await request(path, { method: "POST", signal: controller.signal });
            if (controller.signal.aborted) return;
            if (!value?.answer?.trim()) throw new Error("暂时没有生成有效详解，请重试。");
            setAnswer(value.answer); setExpanded(true);
        } catch (problem) {
            if (!controller.signal.aborted) setError(problem.message);
        } finally {
            if (detailRequest.current === controller) detailRequest.current = null;
            if (!controller.signal.aborted) setBusy(false);
        }
    }

    if (!attemptId) return null;
    return <section className="answer-explanation" aria-label="答案讲解">
        <h3>简单讲解</h3>
        {brief ? <p>{brief}</p> : briefError ? <div role="status">
            <p>{briefError}</p><button type="button" className="secondary" onClick={() => setReload(value => value + 1)}>重试读取讲解</button>
        </div> : <p role="status">正在读取讲解…</p>}
        <button type="button" className="secondary" disabled={busy} onClick={() => void explain()}
            aria-expanded={answer ? expanded : undefined}>
            {busy ? "正在生成详解…" : answer ? expanded ? "收起详解" : "查看详解" : "我需要详解"}
        </button>
        {error && <p className="answer-explanation-error" role="alert">{error}</p>}
        {answer && expanded && <div className="answer-explanation-detail" aria-live="polite">
            <h3>详细讲解</h3><p>{answer}</p>
        </div>}
    </section>;
}
