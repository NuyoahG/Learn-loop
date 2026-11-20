"use client";

import { useCallback, useEffect, useRef, useState } from "react";
import Link from "next/link";
import { usePathname, useSearchParams } from "next/navigation";
import { useAuth } from "./AuthProvider";
import { LessonFeedback } from "./LessonFeedback";
import { useTutorQuiet } from "./useTutorQuiet";
import "./tutor.css";

function Cat({ thinking, alert }) {
    return <svg className="tutor-sprite" viewBox="4 4 36 34" shapeRendering="crispEdges" aria-hidden="true"
        data-thinking={thinking}>
        <g className="tutor-sprite-body">
            <g className="tutor-sprite-tail">
                <path fill="#664a3b" d="M27 25h4v-5h4v11h-8z"/>
                <path fill="#f3b67d" d="M28 26h4v-5h2v9h-6z"/>
            </g>
            <path fill="#664a3b" d="M10 21h19v12h-3v3h-4v-2h-5v2h-6v-3h-1z"/>
            <path fill="#f3b67d" d="M12 22h15v10H12z"/>
            <path fill="#ffe7be" d="M15 24h10v7H15z"/>
            <path fill="#664a3b" d="M8 7h3v2h3v2h3v3h7v-3h3V9h3V7h3v8h2v6h-2v3H8v-3H6v-6h2z"/>
            <path fill="#f3b67d" d="M10 10h2v2h3v2h11v-2h3v-2h2v6h3v4h-2v3H9v-3H7v-4h3z"/>
            <path fill="#e69c96" d="M11 12h2v3h-2zM28 12h2v3h-2zM19 19h3v1h-3z"/>
            <path fill="#ffe7be" d="M14 18h13v5H14z"/>
            <path fill="#664a3b" d="M17 14h2v3h-2zM22 14h2v3h-2z"/>
            <g className="tutor-sprite-eyes" fill="#453a43">
                <path className="tutor-sprite-awake" d="M13 16h2v3h-2zM26 16h2v3h-2z"/>
                <path className="tutor-sprite-blink" d="M12 17h4v1h-4zM25 17h4v1h-4z"/>
            </g>
            <path fill="#e69c96" d="M19 19h3v1h-3z"/>
            <path fill="#453a43" d="M20 20h1v1h2v1h-5v-1h2z"/>
            <path fill="#7294b1" d="M11 24h18v2H11zM25 26h3v4h-3z"/>
            <path fill="#664a3b" d="M12 28h16v7H12z"/>
            <path fill="#7294b1" d="M13 29h14v5H13z"/>
            <path fill="#f7f1da" d="M15 29h5v3h-5zM21 29h4v3h-4z"/>
            <path fill="#f3b67d" d="M10 28h4v4h-4zM27 28h3v4h-3z"/>
        </g>
        {(alert || thinking) && <path fill={alert ? "#69ba87" : "#7294b1"} d="M35 5h2v2h-2zM37 8h2v2h-2z"/>}
    </svg>;
}

function Evidence({ items }) {
    if (!items?.length) return null;
    return <details className="tutor-evidence"><summary>查看学习记录</summary>
        <ul>{items.map(item => <li key={`${item.kind}:${item.id}`}>
            {item.kind === "MARK" ? <a className="ll-inline-link" href={`/reading?materialId=${item.materialId}&view=text&highlight=${encodeURIComponent(item.id)}`}>{item.label}</a>
                : ["PDF_ANNOTATION", "SOURCE"].includes(item.kind) ? <a className="ll-inline-link" href={`/reading?materialId=${item.materialId}`}>{item.label}</a>
                : ["NOTE", "READING_NOTE"].includes(item.kind) ? <a className="ll-inline-link" href="/notes">{item.label}</a> : item.label}
        </li>)}</ul>
    </details>;
}

const toolLabels = { attempt_detail: "作答详情", search_material: "资料原文", search_notes: "笔记与标记" };
const teachingLabels = { EXPLAIN: "分步讲解", COMPARE: "对比理解", HINT: "思路提示", REVIEW: "复习建议" };

export function TutorCat({ userId }) {
    const { request } = useAuth();
    const pathname = usePathname();
    const params = useSearchParams();
    const rawMaterial = params.get("materialId");
    const materialId = /^[1-9][0-9]*$/.test(rawMaterial ?? "") && Number.isSafeInteger(Number(rawMaterial)) ? Number(rawMaterial) : null;
    const [open, setOpen] = useState(false);
    const [suggestion, setSuggestion] = useState(null);
    const [quiet, saveQuiet] = useTutorQuiet(userId);
    const [turns, setTurns] = useState([]);
    const [input, setInput] = useState("");
    const [busy, setBusy] = useState(false);
    const [error, setError] = useState("");
    const launcher = useRef(null);
    const panel = useRef(null);
    const messages = useRef(null);
    useEffect(() => {
        if (!open) return;
        const frame = requestAnimationFrame(() => {
            if (messages.current) messages.current.scrollTop = messages.current.scrollHeight;
        });
        return () => cancelAnimationFrame(frame);
    }, [open, turns.length, busy]);
    const focusOnOpen = useRef(false);
    const mouseInside = useRef(false);
    const closeTimer = useRef(null);
    const shown = useRef(false);
    const pending = useRef(false);
    const retry = useRef(null);
    const scope = useRef(materialId);
    const [historyLoaded, setHistoryLoaded] = useState(false);
    // 范围切换时不展示上一份资料的聊天。已经发出的回答仍保存到服务器，但不会插入新范围。
    scope.current = materialId;
    useEffect(() => {
        setTurns([]); setHistoryLoaded(false); setInput(""); setError(""); retry.current = null;
    }, [materialId]);

    useEffect(() => {
        if (!open || historyLoaded || pending.current) return;
        const controller = new AbortController();
        request(`/api/tutor/lessons${materialId ? `?materialId=${materialId}` : ""}`, { signal: controller.signal })
            .then(rows => {
                if (controller.signal.aborted || pending.current) return;
                setTurns((Array.isArray(rows) ? [...rows].reverse() : []).flatMap(row => [
                    { role: "user", text: row.question }, { ...row, role: "assistant", text: row.answer }
                ]));
                setHistoryLoaded(true);
            }).catch(problem => { if (!controller.signal.aborted) { setError(problem.message); setHistoryLoaded(true); } });
        return () => controller.abort();
    }, [open, historyLoaded, materialId, request]);
    useEffect(() => {
        if (quiet || !["/workspace", "/questions", "/review", "/reading"].includes(pathname)) return;
        const controller = new AbortController();
        const load = async () => {
            if (shown.current) return;
            try {
                const value = await request("/api/tutor/suggestion", { signal: controller.signal });
                if (!controller.signal.aborted && !shown.current && value) {
                    shown.current = true;
                    setSuggestion(value);
                }
            } catch { /* 辅导读取失败时，原学习页面仍可继续。 */ }
        };
        void load();
        window.addEventListener("learnloop:tutor-evidence", load);
        return () => {
            controller.abort();
            window.removeEventListener("learnloop:tutor-evidence", load);
        };
    }, [pathname, quiet, request]);

    async function chat(message, targetMaterial = materialId) {
        if (!message.trim() || pending.current) return;
        pending.current = true;
        setBusy(true);
        setError("");
        const text = message.trim();
        const originalScope = scope.current;
        const signature = JSON.stringify([text, targetMaterial]);
        if (retry.current?.signature !== signature) retry.current = { signature, id: crypto.randomUUID() };
        try {
            const reply = await request("/api/tutor/chat", {
                method: "POST", headers: { "Content-Type": "application/json" },
                body: JSON.stringify({
                    message: text, materialId: targetMaterial, requestId: retry.current.id
                })
            });
            if (scope.current === originalScope) {
                setTurns(old => [...old, { role: "user", text },
                    { ...reply, role: "assistant", text: reply.answer }].slice(-12));
                setInput(""); setHistoryLoaded(true); retry.current = null;
            }
        } catch (problem) {
            if (scope.current === originalScope) setError(problem.message);
        } finally {
            pending.current = false;
            setBusy(false);
        }
    }

    async function respond(status) {
        if (!suggestion || pending.current) return;
        const current = suggestion;
        pending.current = true;
        setError("");
        try {
            await request("/api/tutor/suggestion", {
                method: "PUT", headers: { "Content-Type": "application/json" },
                body: JSON.stringify({ key: current.key, status })
            });
            setSuggestion(null);
        } catch (problem) {
            setError(problem.message);
            setOpen(true);
            return;
        } finally {
            pending.current = false;
        }
        if (status === "OPENED") {
            setOpen(true);
            setInput("请结合我的近期学习记录解释这条建议：" + current.message);
            await chat("请结合我的近期学习记录解释这条建议：" + current.message, current.evidence[0]?.materialId);
        }
    }

    function toggleQuiet() {
        const next = !quiet;
        saveQuiet(next);
        if (next) setSuggestion(null);
    }

    const closePanel = useCallback(() => {
        clearTimeout(closeTimer.current);
        setOpen(false);
        if (panel.current?.contains(document.activeElement)) launcher.current?.focus({ preventScroll: true });
    }, []);

    useEffect(() => {
        if (!open) return;
        if (focusOnOpen.current) {
            focusOnOpen.current = false;
            panel.current?.querySelector("textarea")?.focus({ preventScroll: true });
        }
        const dismiss = event => {
            if (event.key !== "Escape" || event.defaultPrevented) return;
            closePanel();
        };
        window.addEventListener("keydown", dismiss);
        return () => {
            window.removeEventListener("keydown", dismiss);
            clearTimeout(closeTimer.current);
        };
    }, [open, closePanel]);

    function leaveMouse(event) {
        if (event.pointerType !== "mouse") return;
        mouseInside.current = false;
        // 给指针留出跨过猫咪和窗口间距的时间。输入区内有焦点时保持窗口。
        clearTimeout(closeTimer.current);
        closeTimer.current = setTimeout(() => {
            if (!mouseInside.current && !panel.current?.contains(document.activeElement)) setOpen(false);
        }, 260);
    }

    return <aside className="tutor-cat" aria-label="小栗老师"
        onPointerEnter={event => {
            if (event.pointerType !== "mouse") return;
            mouseInside.current = true;
            clearTimeout(closeTimer.current);
        }}
        onPointerLeave={leaveMouse}
        onBlur={event => {
            if (event.relatedTarget && !event.currentTarget.contains(event.relatedTarget) && !mouseInside.current) {
                setOpen(false);
            }
        }}>
        {open ? <section ref={panel} className="tutor-panel" id="tutor-panel" aria-label="和小栗聊天">
            <header><strong>小栗老师</strong><button type="button" className="secondary" onClick={closePanel}>收起</button></header>
            <div ref={messages} className="tutor-messages" aria-live="polite">
                {!turns.length && <p>想一起看看哪里卡住了吗？</p>}
                {suggestion && !quiet && <div className="tutor-bubble">
                    <p>{suggestion.message}</p><Evidence items={suggestion.evidence} />
                    <div className="tutor-actions">
                        <button type="button" disabled={busy} onClick={() => void respond("OPENED")}>一起看看</button>
                        <button type="button" className="secondary" onClick={() => void respond("LATER")}>稍后</button>
                        <button type="button" className="secondary" onClick={() => void respond("CORRECTED")}>不准确</button>
                    </div>
                </div>}
                {turns.map((turn, index) => <div className="tutor-turn" data-role={turn.role} key={turn.id ?? `turn-${index}`}>
                    {turn.teaching && <small className="tutor-method">{teachingLabels[turn.teaching]}</small>}
                    <p>{turn.text}</p><Evidence items={turn.evidence} />
                    {!!turn.trace?.length && <details className="tutor-evidence"><summary>本次查询</summary>
                        <ul>{turn.trace.map((item, i) => <li key={i}>{toolLabels[item.tool]} · {item.count} 条</li>)}</ul>
                    </details>}
                    {turn.role === "assistant" && <LessonFeedback turn={turn} request={request} disabled={busy}
                        onSaved={value => setTurns(old => old.map(row => row.id === value.id ? { ...row, feedback: value.feedback, detail: value.detail } : row))}
                        onRetry={value => { setInput(`请换一种讲法，帮我理解：${value.question}`.slice(0, 500)); panel.current?.querySelector("#tutor-question")?.focus(); }} />}
                </div>)}
                {busy && <p>小栗正在想……</p>}
                {error && <p role="status">{error}</p>}
            </div>
            <form className="tutor-input" onSubmit={event => { event.preventDefault(); void chat(input); }}>
                <label htmlFor="tutor-question">问小栗</label>
                <div className="tutor-actions tutor-shortcuts">
                    {["帮我找一个需要注意的学习问题", "结合我的笔记帮我解惑", "我今天先复习什么？"].map((message, index) =>
                        <button key={message} type="button" className="secondary" disabled={busy}
                            onClick={() => setInput(message)}>{["看看薄弱点", "笔记解惑", "复习建议"][index]}</button>)}
                </div>
                <textarea id="tutor-question" rows={2} maxLength={500} value={input} disabled={busy}
                    onChange={event => setInput(event.target.value)} />
                <div className="tutor-actions">
                    <Link className="ll-action-link tutor-plan-link" href={`/tutor-practice${materialId ? `?materialId=${materialId}` : ""}`}
                        onClick={() => setOpen(false)}>安排练习 <span aria-hidden="true">→</span></Link>
                    <button type="submit" disabled={!input.trim() || busy}>发送</button>
                    <button type="button" className="secondary" onClick={toggleQuiet}>{quiet ? "开启提醒" : "关闭提醒"}</button>
                </div>
            </form>
        </section> : suggestion && !quiet && <button type="button" className="tutor-preview secondary" onClick={() => setOpen(true)}>小栗有一条学习建议</button>}
        <button ref={launcher} type="button" className="tutor-launch" aria-label="小栗老师"
            aria-expanded={open} aria-controls={open ? "tutor-panel" : undefined}
            onPointerEnter={event => {
                if (event.pointerType === "mouse") setOpen(true);
            }}
            onClick={event => {
                if (event.detail === 0) {
                    if (open) panel.current?.querySelector("textarea")?.focus({ preventScroll: true });
                    else focusOnOpen.current = true;
                }
                setOpen(true);
            }}>
            <Cat thinking={busy} alert={!!suggestion && !quiet} />
        </button>
    </aside>;
}
