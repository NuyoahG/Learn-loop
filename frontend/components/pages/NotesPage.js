"use client";

import Link from "next/link";
import { Fragment, useEffect, useState } from "react";
import { useAuth } from "@/components/auth/AuthProvider";
import { NoteEditor } from "@/components/practice/NoteEditor";
import "./notes.css";

const PAGE_SIZE = 12;
const noteKey = note => note.readingNoteId ?? note.questionVersionId;

export function NotesPage() {
    const { request } = useAuth();
    const [input, setInput] = useState("");
    const [filter, setFilter] = useState({ q: "", page: 1 });
    const [result, setResult] = useState(null);
    const [selected, setSelected] = useState(null);
    const [error, setError] = useState("");
    const [loading, setLoading] = useState(true);

    useEffect(() => {
        const controller = new AbortController();
        const params = new URLSearchParams({ ...filter, size: PAGE_SIZE });
        request("/api/notes?" + params, { signal: controller.signal }).then(value => {
            if (controller.signal.aborted) return;
            setResult(value);
            setSelected(current => value.records.some(note => noteKey(note) === current) ? current : null);
        }).catch(error => {
            if (!controller.signal.aborted) setError(error.message);
        }).finally(() => {
            if (!controller.signal.aborted) setLoading(false);
        });
        return () => controller.abort();
    }, [filter, request]);

    function load(q, page) {
        setError("");
        setLoading(true);
        setSelected(null);
        setFilter({ q, page });
    }

    function toggleNote(key) {
        // 保留行按钮的焦点，键盘用户可以 Tab 到紧接着出现的编辑区。
        setSelected(current => current === key ? null : key);
    }

    return (
        <div className="journal-page notes-page">
            <header className="notes-heading">
                <h1>我的笔记</h1>
                <form className="journal-search" onSubmit={event => { event.preventDefault(); load(input.trim(), 1); }}>
                    <input aria-label="搜索笔记" type="search" maxLength={100} value={input}
                        placeholder="搜索笔记、题目或资料" onChange={event => setInput(event.target.value)} />
                    <button type="submit">搜索</button>
                </form>
            </header>
            <section aria-label="笔记列表" aria-busy={loading}>
                {error ? <p className="journal-error" role="alert">{error}</p> : null}
                {loading ? <p role="status">正在读取笔记…</p> : null}
                {!loading && !error && !result?.records.length ? (
                    <div className="journal-empty"><p>这里暂时没有笔记。</p><Link className="ll-action-link" href="/questions">去答题练习 →</Link></div>
                ) : null}
                {result?.records.length > 0 ? (
                    <table className="history-table notes-table">
                        <thead><tr><th scope="col">题目 / 原文</th><th scope="col">笔记</th></tr></thead>
                        <tbody>
                            {result.records.map(item => {
                                const key = noteKey(item);
                                const expanded = selected === key;
                                const reading = item.readingNoteId != null;
                                const readingUrl = "/reading?materialId=" + encodeURIComponent(item.materialId);
                                return <Fragment key={key}>
                                    <tr className="notes-row" data-selected={expanded} onClick={event => {
                                        if (!loading && !event.target.closest("button") && !window.getSelection()?.toString()) {
                                            toggleNote(key);
                                        }
                                    }}>
                                        <td><button type="button" className="secondary" aria-expanded={expanded}
                                            aria-controls={expanded ? "noteDetails" : undefined} disabled={loading}
                                            onClick={() => toggleNote(key)}>{reading ? item.quote || item.question : item.question}</button></td>
                                        <td>{item.content}</td>
                                    </tr>
                                    {expanded ? (
                                        <tr className="notes-detail-row"><td colSpan={2}>
                                            <section className="journal-surface notes-detail" id="noteDetails" aria-label="笔记详情">
                                                <p className="journal-kicker">{item.materialName}</p>
                                                <h2>{item.question}</h2>
                                                {reading ? <blockquote>{item.quote}</blockquote> : null}
                                                <div className="journal-actions journal-links">
                                                    {!reading ? <Link className="ll-action-link"
                                                        href={"/questions?materialId=" + item.materialId + "&versionId=" + encodeURIComponent(item.questionVersionId)}>
                                                        查看资料题库 ↗
                                                    </Link> : null}
                                                    <Link className="ll-action-link" href={item.annotationId
                                                        ? readingUrl + "&annotation=" + encodeURIComponent(item.annotationId)
                                                        : reading ? readingUrl + "&view=text&highlight=" + encodeURIComponent(item.readingNoteId) : readingUrl}>
                                                        {reading ? "查看问答原文 ↗" : "阅读资料 ↗"}
                                                    </Link>
                                                </div>
                                                <NoteEditor version={item.questionVersionId} readingNoteId={item.readingNoteId} initialNote={item}
                                                    onDeleted={() => load(filter.q, result.records.length === 1 ? Math.max(1, filter.page - 1) : filter.page)}
                                                    onSaved={updated => setResult(current => ({ ...current, records: current.records.map(row =>
                                                        noteKey(row) === noteKey(updated) ? updated : row) }))} />
                                            </section>
                                        </td></tr>
                                    ) : null}
                                </Fragment>;
                            })}
                        </tbody>
                    </table>
                ) : null}
                {result && (result.page > 1 || result.hasMore) ? (
                    <div className="history-pagination">
                        <button type="button" className="secondary" disabled={loading || result.page <= 1}
                            onClick={() => load(filter.q, result.page - 1)}>上一页</button>
                        <span>第 {result.page} 页</span>
                        <button type="button" className="secondary" disabled={loading || !result.hasMore}
                            onClick={() => load(filter.q, result.page + 1)}>下一页</button>
                    </div>
                ) : null}
            </section>
        </div>
    );
}
