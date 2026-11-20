"use client";

import Link from "next/link";
import { useSearchParams } from "next/navigation";
import { useCallback, useEffect, useLayoutEffect, useRef, useState } from "react";
import { useAuth } from "@/components/auth/AuthProvider";
import { OriginalReader } from "@/components/reading/OriginalReader";
import { HighlightColors } from "@/components/reading/HighlightColors";
import { sourceLabel } from "@/lib/materials-model";
import { captureAnchor, highlightParts, selectionMenuPosition } from "@/lib/reading-selection";
import { lastReading, rememberReading } from "@/lib/reading-position";

const READING_LINE = 90;
const studyIntents = [["HIGHLIGHT", "只高亮"], ["UNCLEAR", "没理解"], ["CONFUSED", "容易混淆"], ["REMEMBER", "想记住"]];

export function ReadingPage() {
    const params = useSearchParams();
    const { user } = useAuth();
    const saved = lastReading(user.userId);
    const selected = params.get("materialId");
    const materialId = Number(selected ?? saved?.materialId);
    const view = params.get("view");
    // 从资料列表打开时默认看原件；从导航返回时恢复上次阅读模式。
    const textMode = view === "text" || (!view && !selected && saved && saved.view !== "original");
    const Preview = textMode ? Reader : OriginalReader;
    return Number.isSafeInteger(materialId) && materialId > 0
        ? <Preview key={user.userId + ":" + materialId + ":" + (params.get("highlight") ?? "") + ":" + (params.get("annotation") ?? "")}
            materialId={materialId} userId={user.userId} targetHighlight={params.get("highlight")}
            targetAnnotation={params.get("annotation")} />
        : <div className="journal-page journal-empty"><h1>资料阅读</h1><Link className="ll-action-link" href="/workspace?tab=materials">从资料列表选择一份资料 →</Link></div>;
}


function ReadingStudy({ highlight, path, busy, onSaved }) {
    const { request } = useAuth();
    const [editing, setEditing] = useState(false);
    const [topics, setTopics] = useState([]);
    const [intent, setIntent] = useState("HIGHLIGHT");
    const [topicId, setTopicId] = useState("");
    const [pending, setPending] = useState(false);
    const [message, setMessage] = useState("");
    const endpoint = path + "/highlights/" + encodeURIComponent(highlight.id) + "/study";

    async function edit() {
        if (pending) return;
        setPending(true);
        setMessage("");
        try {
            const context = await request(endpoint);
            setTopics(context.topics);
            setIntent(highlight.intent ?? "HIGHLIGHT");
            setTopicId(context.topics.some(topic => topic.id === highlight.topicId) ? highlight.topicId : "");
            setEditing(true);
        } catch (error) { setMessage(error.message); }
        finally { setPending(false); }
    }

    async function save(event) {
        event.preventDefault();
        if (pending) return;
        setPending(true);
        setMessage("");
        try {
            const value = await request(endpoint, { method: "PUT", headers: { "Content-Type": "application/json" },
                body: JSON.stringify({ intent, topicId: topicId || null }) });
            onSaved(value);
            setEditing(false);
            setMessage("标记已更新");
        } catch (error) { setMessage(error.message); }
        finally { setPending(false); }
    }

    return <div className="reading-form">
        {editing ? <form className="reading-form" onSubmit={save}>
            <label>标记意图 <select value={intent} disabled={pending || busy} onChange={event => setIntent(event.target.value)}>
                {studyIntents.map(([value, label]) => <option key={value} value={value}>{label}</option>)}
            </select></label>
            <label>关联考点 <select value={topicId} disabled={pending || busy || !topics.length}
                onChange={event => setTopicId(event.target.value)}>
                <option value="">不关联</option>
                {topics.map(topic => <option key={topic.id} value={topic.id}>
                    {topic.suggested ? "建议 · " : ""}{topic.chapter} / {topic.groupName} / {topic.name}
                </option>)}
            </select></label>
            {!topics.length && <small>这份资料还没有可关联的考点。</small>}
            <div className="journal-actions">
                <button type="submit" disabled={pending || busy}>保存标记</button>
                <button type="button" className="secondary" disabled={pending || busy} onClick={() => setEditing(false)}>取消</button>
            </div>
        </form> : <>
            {highlight.intent && <small>{studyIntents.find(([value]) => value === highlight.intent)?.[1]}
                {highlight.topicName ? " · " + highlight.topicName : ""}</small>}
            <button type="button" className="secondary" disabled={pending || busy} onClick={() => void edit()}>编辑学习标记</button>
        </>}
        {message && <small role="status">{message}</small>}
    </div>;
}

function Reader({ materialId, userId, targetHighlight }) {
    const { request } = useAuth();
    const rootRef = useRef(null);
    const panelRef = useRef(null);
    const endRef = useRef(null);
    const inFlight = useRef(null);
    const scrollAfterLoad = useRef(null);
    const [resume] = useState(() => {
        const saved = lastReading(userId);
        return saved?.materialId === materialId && saved.view !== "original" ? saved : null;
    });
    const [reader, setReader] = useState(null);
    const [loadError, setLoadError] = useState(null);
    const [batchError, setBatchError] = useState("");
    const [loadingBatch, setLoadingBatch] = useState(false);
    const [selection, setSelection] = useState(null);
    const [anchor, setAnchor] = useState(null);
    const [question, setQuestion] = useState("");
    const [detail, setDetail] = useState("BRIEF");
    const [color, setColor] = useState("LEMON");
    const [draft, setDraft] = useState(null);
    const [active, setActive] = useState(null);
    const [busy, setBusy] = useState(false);
    const [notice, setNotice] = useState("");
    const path = "/api/reading/" + materialId;

    useEffect(() => {
        const controller = new AbortController();
        const options = { signal: controller.signal };
        async function load() {
            try {
                let targetPage;
                if (targetHighlight && /^[0-9a-f]{64}$/.test(targetHighlight)) {
                    try {
                        const context = await request(path + "/highlights/" + targetHighlight + "/study", options);
                        targetPage = context.page;
                    } catch (error) {
                        if (controller.signal.aborted) return;
                        if (error.status !== 404) throw error;
                        setNotice("这条高亮已移除或资料版本已更新。");
                    }
                }
                let value = await request(path + "?page=" + (targetPage ?? resume?.page ?? 1), options);
                const canResume = resume && resume.parseAttempt === value.parseAttempt
                    && value.blocks.records.some(block => block.blockId === resume.blockId);
                if (!targetPage && !canResume && resume?.page > 1) value = await request(path + "?page=1", options);
                if (controller.signal.aborted) return;
                const target = targetPage && value.highlights.find(item => item.id === targetHighlight);
                if (target) {
                    setActive(target);
                    scrollAfterLoad.current = { blockId: target.blockId, top: READING_LINE };
                } else if (canResume) scrollAfterLoad.current = { blockId: resume.blockId, top: READING_LINE - resume.offset };
                setReader({ ...value, firstPage: value.blocks.page, lastPage: value.blocks.page });
            } catch (error) {
                if (!controller.signal.aborted) setLoadError(error);
            }
        }
        void load();
        return () => {
            controller.abort();
            inFlight.current?.abort();
        };
    }, [resume, path, request, targetHighlight]);

    const loadBatch = useCallback(async (page, before = false) => {
        if (!reader || inFlight.current) return;
        const controller = new AbortController();
        inFlight.current = controller;
        setLoadingBatch(true);
        setBatchError("");
        try {
            const value = await request(path + "?page=" + page, { signal: controller.signal });
            if (controller.signal.aborted) return;
            if (value.parseAttempt !== reader.parseAttempt) throw new Error("正文版本已变化，请刷新阅读页。");
            if (before) {
                const first = rootRef.current?.querySelector("[data-reader-text]");
                if (first) scrollAfterLoad.current = { blockId: Number(first.dataset.readerText), top: first.getBoundingClientRect().top };
            }
            setReader(current => ({ ...current,
                blocks: { ...current.blocks, records: before
                    ? [...value.blocks.records, ...current.blocks.records]
                    : [...current.blocks.records, ...value.blocks.records] },
                highlights: [...current.highlights, ...value.highlights],
                firstPage: before ? page : current.firstPage,
                lastPage: before ? current.lastPage : page
            }));
        } catch (error) {
            if (!controller.signal.aborted) setBatchError(error.message);
        } finally {
            if (inFlight.current === controller) {
                inFlight.current = null;
                if (!controller.signal.aborted) setLoadingBatch(false);
            }
        }
    }, [path, reader, request]);

    useLayoutEffect(() => {
        const target = scrollAfterLoad.current;
        if (!target || !reader) return;
        scrollAfterLoad.current = null;
        const block = rootRef.current?.querySelector(`[data-reader-text="${target.blockId}"]`);
        if (block) window.scrollBy(0, block.getBoundingClientRect().top - target.top);
    }, [reader]);

    const morePage = reader && reader.lastPage < reader.blocks.totalPages ? reader.lastPage + 1 : null;
    useEffect(() => {
        if (!morePage || batchError || loadingBatch || !endRef.current || !("IntersectionObserver" in window)) return;
        if ((anchor || active) && window.matchMedia("(max-width: 900px)").matches) return;
        const observer = new IntersectionObserver(entries => {
            if (entries.some(entry => entry.isIntersecting)) void loadBatch(morePage);
        }, { rootMargin: "220px 0px" });
        observer.observe(endRef.current);
        return () => observer.disconnect();
    }, [active, anchor, batchError, loadBatch, loadingBatch, morePage]);

    useEffect(() => {
        const paper = rootRef.current;
        if (!reader || !paper) return;
        function persist() {
            const visible = [...paper.querySelectorAll("[data-reader-text]")].find(node => {
                const rect = node.getBoundingClientRect();
                return rect.bottom > READING_LINE && rect.top < window.innerHeight;
            });
            if (!visible) return;
            rememberReading(userId, {
                view: "text", materialId, parseAttempt: reader.parseAttempt, blockId: Number(visible.dataset.readerText),
                page: Math.ceil(Number(visible.dataset.blockNo) / reader.blocks.size),
                offset: Math.round(READING_LINE - visible.getBoundingClientRect().top)
            });
        }
        let frame;
        function onScroll() {
            cancelAnimationFrame(frame);
            frame = requestAnimationFrame(persist);
        }
        onScroll();
        window.addEventListener("scroll", onScroll, { passive: true });
        window.addEventListener("pagehide", persist);
        return () => {
            cancelAnimationFrame(frame);
            if (paper.isConnected) persist();
            window.removeEventListener("scroll", onScroll);
            window.removeEventListener("pagehide", persist);
        };
    }, [materialId, reader, userId]);

    const readSelection = useCallback(() => {
        if (!reader || busy || !rootRef.current) return;
        const native = window.getSelection();
        const selected = captureAnchor(native, rootRef.current, reader);
        const position = selected && selectionMenuPosition(native);
        setSelection(position ? { anchor: selected, position } : null);
    }, [reader, busy]);

    useEffect(() => {
        // 手机长按由浏览器接管，选区手柄变化不一定向正文发送 pointerup。
        const touch = window.matchMedia("(pointer: coarse)").matches;
        function onSelectionChange() {
            if (touch) readSelection();
            else if (window.getSelection()?.isCollapsed) setSelection(null);
        }
        document.addEventListener("selectionchange", onSelectionChange);
        return () => document.removeEventListener("selectionchange", onSelectionChange);
    }, [readSelection]);

    const hasSelection = !!selection;
    useEffect(() => {
        if (!hasSelection) return;
        window.addEventListener("scroll", readSelection, { passive: true });
        window.addEventListener("resize", readSelection);
        return () => {
            window.removeEventListener("scroll", readSelection);
            window.removeEventListener("resize", readSelection);
        };
    }, [hasSelection, readSelection]);

    function bringPanelIntoView() {
        if (window.matchMedia("(max-width: 900px)").matches) panelRef.current?.scrollIntoView({ block: "start" });
    }

    function openSelection() {
        if (!selection || busy) return;
        setAnchor(selection.anchor);
        setSelection(null);
        setQuestion("");
        setDraft(null);
        setActive(null);
        setNotice("");
        window.getSelection()?.removeAllRanges();
        bringPanelIntoView();
    }

    function openHighlight(highlight) {
        if (busy || !window.getSelection()?.isCollapsed) return;
        setAnchor(null);
        setDraft(null);
        setActive(highlight);
        setNotice("");
        bringPanelIntoView();
    }

    async function ask(event) {
        event.preventDefault();
        if (!anchor || busy) return;
        setBusy(true);
        setNotice("");
        setDraft(null);
        try {
            const result = await request(path + "/ask", { method: "POST", headers: { "Content-Type": "application/json" },
                body: JSON.stringify({ anchor, question, detail }) });
            setDraft(result);
        } catch (error) {
            setNotice(error.message);
        } finally {
            setBusy(false);
        }
    }

    async function save() {
        if (!draft || busy) return;
        setBusy(true);
        setNotice("");
        try {
            const highlight = await request(path + "/highlights", { method: "POST", headers: { "Content-Type": "application/json" },
                body: JSON.stringify({ draft, color }) });
            setReader(current => ({ ...current, highlights: [...current.highlights.filter(row => row.id !== highlight.id), highlight] }));
            setDraft(null);
            setAnchor(null);
            setActive(highlight);
            setNotice("已保存到正文");
        } catch (error) {
            setNotice(error.message);
        } finally {
            setBusy(false);
        }
    }

    async function remove() {
        if (!active || busy || !window.confirm("移除这处高亮和已保存的问答？")) return;
        setBusy(true);
        try {
            await request(path + "/highlights/" + encodeURIComponent(active.id), { method: "DELETE" });
            setReader(current => ({ ...current, highlights: current.highlights.filter(item => item.id !== active.id) }));
            setActive(null);
            setNotice("已移除高亮");
        } catch (error) {
            setNotice(error.message);
        } finally {
            setBusy(false);
        }
    }

    const answer = active ?? draft;
    return (
        <div className="journal-page reader-page">
            <header className="journal-hero reader-hero">
                <div>
                    <Link href="/workspace?tab=materials" className="reader-back ll-inline-link">← 我的资料</Link>
                    <h1>{reader?.title ?? "资料阅读"}</h1>
                    <p>选中同一段内的文字即可提问。已保存的回答可从高亮处回看。</p>
                    <Link className="ll-action-link" href={`/reading?materialId=${materialId}&view=original`}>原件预览</Link>
                </div>
                <HighlightColors color={color} onChange={setColor} />
            </header>
            <div className="journal-grid reader-grid">
                <section ref={rootRef} className="journal-surface reader-body" aria-label="资料正文"
                    onPointerUp={readSelection} onKeyUp={readSelection}>
                    {loadError ? (
                        <div className="journal-empty">
                            <p className="journal-error" role="alert">{loadError.message}</p>
                            <Link className="ll-action-link" href={`/workspace?tab=materials&manage=${materialId}`}>查看资料详情与解析 →</Link>
                        </div>
                    ) : null}
                    {!reader && !loadError ? <p role="status">正在加载正文…</p> : null}
                    {reader?.firstPage > 1 ? (
                        <button type="button" className="secondary reader-more" disabled={loadingBatch}
                            onClick={() => void loadBatch(reader.firstPage - 1, true)}>加载前文</button>
                    ) : null}
                    {reader?.blocks.records.map(block => {
                        const highlights = reader.highlights.filter(h => h.blockId === block.blockId).sort((a, b) => a.start - b.start);
                        return (
                            <p key={block.blockId} className="reader-text" data-reader-text={block.blockId}
                                data-block-no={block.blockNo} data-continuation={block.sourceLocator.partIndex > 1}
                                title={sourceLabel(block.sourceLocator)}>
                                    {highlightParts(block.text, highlights).map((part, index) => part.highlight ? (
                                        <button type="button" key={part.highlight.id} className="reader-mark"
                                            data-color={part.highlight.color} aria-label={"查看已保存问答：" + part.text}
                                            onClick={() => openHighlight(part.highlight)}>{part.text}</button>
                                    ) : <span key={index}>{part.text}</span>)}
                            </p>
                        );
                    })}
                    {selection && !busy ? (
                        <button type="button" className="selection-ask"
                            style={{ left: selection.position.left, top: selection.position.top }}
                            data-above={selection.position.above}
                            onPointerDown={event => event.preventDefault()} onClick={openSelection}>✦ 问问 AI</button>
                    ) : null}
                    {batchError ? <p role="alert" className="journal-error">{batchError}</p> : null}
                    {morePage ? (
                        <div ref={endRef} className="reader-more">
                            <button type="button" className="secondary" disabled={loadingBatch}
                                onClick={() => void loadBatch(morePage)}>{loadingBatch ? "正在加载后文…" : "加载后文"}</button>
                        </div>
                    ) : null}
                </section>
                <aside ref={panelRef} className="journal-surface reading-panel" aria-labelledby="readingPanelHeading">
                    <p className="journal-kicker">划线问答</p>
                    <h2 id="readingPanelHeading">{active ? "已保存的问答" : "阅读助手"}</h2>
                    {anchor || active ? <blockquote>{active?.quote ?? anchor.quote}</blockquote>
                        : <p className="journal-empty">在正文里选中文字后点击「问问 AI」，或点击任一处高亮查看历史回答。</p>}
                    {anchor ? (
                        <form className="reading-form" onSubmit={ask}>
                            <label htmlFor="reading-question">你想问什么？</label>
                            <textarea id="reading-question" value={question} maxLength={500} rows={3}
                                placeholder="可留空，让助手解释这段原文…" disabled={busy}
                                onChange={event => setQuestion(event.target.value)} />
                            <fieldset className="reading-depth">
                                <legend>回答长度</legend>
                                {[["BRIEF", "简短"], ["DETAILED", "详细"]].map(([value, label]) => (
                                    <label key={value}><input type="radio" name="reading-detail" value={value}
                                        checked={detail === value} disabled={busy} onChange={() => setDetail(value)} />{label}</label>
                                ))}
                            </fieldset>
                            <button type="submit" disabled={busy}>发送问题</button>
                        </form>
                    ) : null}
                    {answer ? (
                        <div className="reading-answer">
                            <p className="journal-kicker">{active ? "已保存" : "临时回答"} · {answer.detail === "BRIEF" ? "简短" : "详细"}</p>
                            <h3>{answer.question || "解释所选原文"}</h3>
                            <p className="reading-answer-text">{answer.answer}</p>
                            {active ? <button type="button" className="secondary" disabled={busy} onClick={() => void remove()}>移除高亮</button>
                                : <button type="button" disabled={busy} onClick={() => void save()}>保存回答并高亮</button>}
                            {active ? <ReadingStudy key={active.id} highlight={active} path={path} busy={busy}
                                onSaved={highlight => {
                                    setActive(current => current?.id === highlight.id ? highlight : current);
                                    setReader(current => ({ ...current, highlights: current.highlights.map(item =>
                                        item.id === highlight.id ? highlight : item) }));
                                    window.dispatchEvent(new Event("learnloop:tutor-evidence"));
                                }} /> : <small>保存后可从正文高亮回看。</small>}
                        </div>
                    ) : null}
                    <p className="journal-status" role="status">{busy ? "正在处理…" : notice}</p>
                </aside>
            </div>
        </div>
    );
}
