"use client";

import Link from "next/link";
import { useEffect, useRef, useState } from "react";
import { useAuth } from "@/components/auth/AuthProvider";
import { resolvePdfAnchor } from "@/lib/pdf-reading-selection";
import { HighlightColors } from "./HighlightColors";

export function PdfQuestion({ materialId, selection, onClose, onSaved }) {
    const { request } = useAuth();
    const heading = useRef(null);
    const inFlight = useRef(null);
    const [question, setQuestion] = useState("");
    const [detail, setDetail] = useState("BRIEF");
    const [busy, setBusy] = useState(false);
    const [notice, setNotice] = useState("");
    const [draft, setDraft] = useState(null);
    const [saved, setSaved] = useState(null);
    const [color, setColor] = useState("LEMON");
    const path = "/api/reading/" + materialId;

    useEffect(() => {
        heading.current?.focus({ preventScroll: true });
        return () => inFlight.current?.abort();
    }, []);

    async function perform(action) {
        if (inFlight.current) return;
        const controller = new AbortController();
        inFlight.current = controller;
        setBusy(true);
        setNotice("");
        try {
            await action(controller.signal);
        } catch (error) {
            if (!controller.signal.aborted) setNotice(error.message);
        } finally {
            if (!controller.signal.aborted) setBusy(false);
            inFlight.current = null;
        }
    }

    function post(suffix, body, signal) {
        return request(path + suffix, { signal, method: "POST", headers: { "Content-Type": "application/json" },
            body: JSON.stringify(body) });
    }

    function ask(event) {
        event.preventDefault();
        if (!selection) return;
        void perform(async signal => {
            setDraft(null);
            setSaved(null);
            if ([...selection.quote].length > 1200) throw new Error("请将选区缩短到 1200 字以内。");
            const anchor = await resolvePdfAnchor(page => request(path + "?page=" + page, { signal }),
                materialId, selection);
            if (!anchor) throw new Error("无法唯一定位这段原文。请缩短选区并重新选择后重试。");
            const answer = await post("/ask", { anchor, question, detail }, signal);
            if (!signal.aborted) setDraft(answer);
        });
    }

    function save() {
        if (!draft || saved) return;
        void perform(async signal => {
            const mark = await post("/highlights", { draft, color, saveAsNote: true,
                pdfPosition: selection?.rects ? { pageNumber: selection.pageNumber, rects: selection.rects } : null }, signal);
            if (!signal.aborted) {
                setSaved(mark);
                onSaved?.();
            }
        });
    }

    return <aside className="journal-surface pdf-question" aria-labelledby="pdfQuestionHeading"
        onKeyDown={event => { if (event.key === "Escape") onClose(); }}>
        <div className="journal-heading">
            <h2 id="pdfQuestionHeading" ref={heading} tabIndex={-1}>AI 问答</h2>
            <button type="button" className="secondary" onClick={onClose}>收起</button>
        </div>
        <div className="pdf-question-content">
        {selection ? <>
            <blockquote>{selection.quote}</blockquote>
            <form className="reading-form" onSubmit={ask}>
                <div className="pdf-question-label">
                    <label htmlFor="pdf-question-input">你想问什么？</label>
                    <div className="pdf-question-presets" role="group" aria-label="常见问题">
                        {["解释一下", "分析一下", "这是什么"].map(value => (
                            <button key={value} type="button" className="secondary" disabled={busy}
                                aria-pressed={question === value} onClick={() => setQuestion(value)}>{value}</button>
                        ))}
                    </div>
                </div>
                <textarea id="pdf-question-input" rows={2} maxLength={500} value={question} disabled={busy}
                    placeholder="留空则解释选中内容" onChange={event => setQuestion(event.target.value)} />
                <fieldset className="reading-depth">
                    <legend>回答长度</legend>
                    {[["BRIEF", "简短"], ["DETAILED", "详细"]].map(([value, label]) => (
                        <label key={value}><input type="radio" name="pdf-question-depth" value={value}
                            checked={detail === value} disabled={busy} onChange={() => setDetail(value)} />{label}</label>
                    ))}
                </fieldset>
                <button type="submit" disabled={busy}>发送问题</button>
            </form>
        </> : <p>在 PDF 中选中文字，点击「问问 AI」。</p>}
        {draft ? <div className="reading-answer">
            <h3>{draft.question || "解释所选原文"}</h3>
            <p className="reading-answer-text">{draft.answer}</p>
            {!saved ? <div className="journal-actions">
                <HighlightColors color={color} onChange={setColor} disabled={busy} />
                <button type="button" disabled={busy} onClick={save}>保存高亮和笔记</button>
            </div> : null}
        </div> : null}
        <p className="journal-status" role="status">{busy ? "正在处理…" : saved ? "已保存高亮和笔记" : notice}</p>
        {saved ? <div className="journal-actions"><Link className="ll-action-link" href="/notes">查看笔记</Link></div> : null}
        </div>
    </aside>;
}
