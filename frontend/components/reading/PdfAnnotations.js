"use client";

import { createPortal } from "react-dom";
import { useEffect, useLayoutEffect, useRef, useState } from "react";
import { useAuth } from "@/components/auth/AuthProvider";
import { HighlightColors } from "./HighlightColors";
import "./pdf-annotations.css";

export function PdfAnnotations({ materialId, viewer, layoutVersion, selection, clearSelection,
    onAsk, refreshVersion, targetAnnotation }) {
    const { request } = useAuth();
    const [records, setRecords] = useState([]);
    const [editor, setEditor] = useState(null);
    const [content, setContent] = useState("");
    const [color, setColor] = useState("LEMON");
    const [busy, setBusy] = useState(false);
    const [loading, setLoading] = useState(true);
    const [message, setMessage] = useState("");
    const [layers, setLayers] = useState([]);
    const [preview, setPreview] = useState(null);
    const controller = useRef(null);
    const heading = useRef(null);
    const menuElement = useRef(null);
    const previewTimer = useRef(null);
    const jumped = useRef(null);
    const hosts = useRef({ viewer: null, pages: new Map() });
    const path = `/api/reading/${materialId}/annotations`;

    useEffect(() => {
        const loading = new AbortController();
        setLoading(true);
        request(path, { signal: loading.signal }).then(value => {
            if (!Array.isArray(value.records)) throw new Error("标注列表响应不完整，请稍后重试。");
            if (!loading.signal.aborted) { setRecords(value.records); setMessage(""); }
        }).catch(error => {
            if (!loading.signal.aborted) setMessage(error.message);
        }).finally(() => {
            if (!loading.signal.aborted) setLoading(false);
        });
        return () => loading.abort();
    }, [path, request, refreshVersion]);

    useEffect(() => () => controller.current?.abort(), []);
    useEffect(() => () => clearTimeout(previewTimer.current), []);
    useEffect(() => { heading.current?.focus({ preventScroll: true }); }, [editor?.id]);
    useEffect(() => () => { for (const host of hosts.current.pages.values()) host.remove(); }, []);
    useEffect(() => {
        if (!preview) return;
        const dismiss = event => {
            if (!(event.target instanceof Element) || !event.target.closest("#pdf-note-preview")) setPreview(null);
        };
        window.addEventListener("scroll", dismiss, true);
        window.addEventListener("resize", dismiss);
        return () => {
            window.removeEventListener("scroll", dismiss, true);
            window.removeEventListener("resize", dismiss);
        };
    }, [preview]);
    useLayoutEffect(() => {
        const menu = menuElement.current;
        if (!selection || !menu) return;
        const bounds = menu.getBoundingClientRect();
        const top = selection.menu.above ? selection.menu.top - bounds.height : selection.menu.top;
        menu.style.left = Math.max(8, Math.min(selection.menu.left, window.innerWidth - bounds.width - 8)) + "px";
        menu.style.top = Math.max(8, Math.min(top, window.innerHeight - bounds.height - 8)) + "px";
    }, [selection, editor, message]);

    useLayoutEffect(() => {
        if (hosts.current.viewer !== viewer) {
            for (const host of hosts.current.pages.values()) host.remove();
            hosts.current = { viewer, pages: new Map() };
        }
        const pages = [...new Set(records.map(mark => mark.pageNumber))];
        const visible = [];
        for (const pageNumber of pages) {
            const pageView = viewer?.getPageView(pageNumber - 1);
            const canvas = pageView?.div.querySelector(".canvasWrapper");
            if (!canvas) continue;
            let host = hosts.current.pages.get(pageNumber);
            if (!host) {
                host = document.createElement("div");
                host.className = "pdf-mark-layer";
                hosts.current.pages.set(pageNumber, host);
            }
            // PDF.js 在重绘时会移除页内元素，保留独立宿主后可重新挂回，避免破坏 React 节点。
            if (host.parentElement !== pageView.div) pageView.div.append(host);
            Object.assign(host.style, { left: canvas.offsetLeft + "px", top: canvas.offsetTop + "px",
                width: canvas.offsetWidth + "px", height: canvas.offsetHeight + "px" });
            visible.push({ pageNumber, host });
        }
        setLayers(visible);
    }, [viewer, records, layoutVersion]);

    function open(mark) {
        if (busy || loading) return;
        setPreview(null);
        if (editor?.id === mark.id) { heading.current?.focus({ preventScroll: true }); return; }
        setEditor(mark);
        setContent(mark.note ?? "");
        setColor(mark.color ?? "LEMON");
        setMessage("");
        clearSelection();
    }

    function showPreview(event, mark) {
        clearTimeout(previewTimer.current);
        if (!mark.note) return;
        const bounds = event.currentTarget.getBoundingClientRect();
        setPreview({ mark, left: Math.max(8, Math.min(bounds.left, window.innerWidth - 296)),
            top: bounds.bottom + 8, above: bounds.bottom + 178 > window.innerHeight });
    }

    function hidePreview() {
        previewTimer.current = setTimeout(() => setPreview(null), 150);
    }

    useEffect(() => {
        if (!viewer || loading || !targetAnnotation || jumped.current === targetAnnotation) return;
        const mark = records.find(item => item.id === targetAnnotation || item.noteId === targetAnnotation);
        if (!mark || !viewer.getPageView(mark.pageNumber - 1)) return;
        jumped.current = targetAnnotation;
        const pageView = viewer.getPageView(mark.pageNumber - 1);
        const point = pageView.viewport.convertToPdfPoint(0, mark.rects[0].y * pageView.viewport.height);
        viewer.scrollPageIntoView({ pageNumber: mark.pageNumber,
            destArray: [null, { name: "XYZ" }, point[0], point[1], null], ignoreDestinationZoom: true });
        open(mark);
    }, [viewer, targetAnnotation, records, layoutVersion, loading]);

    async function perform(action) {
        if (controller.current) return;
        const pending = new AbortController();
        controller.current = pending;
        setBusy(true);
        setMessage("");
        try {
            await action(pending.signal);
        } catch (error) {
            if (!pending.signal.aborted) setMessage(error.message);
        } finally {
            if (!pending.signal.aborted) setBusy(false);
            controller.current = null;
        }
    }

    function keep(mark) {
        setRecords(current => [mark, ...current.filter(item => item.id !== mark.id)]);
    }

    function create(selected, note, signal) {
        return request(path, { signal, method: "POST", headers: { "Content-Type": "application/json" },
            body: JSON.stringify({ id: selected.id, pageNumber: selected.pageNumber,
                quote: selected.quote, rects: selected.rects, color, note }) });
    }

    function highlight() {
        void perform(async signal => {
            const saved = await create(selection, null, signal);
            if (signal.aborted) return;
            keep(saved);
            clearSelection();
        });
    }

    function save(event) {
        event.preventDefault();
        void perform(async signal => {
            const saved = editor.creating ? await create(editor, content, signal)
                : await request(path + "/" + encodeURIComponent(editor.id), {
                    signal, method: "PUT", headers: { "Content-Type": "application/json" },
                    body: JSON.stringify({ revision: editor.revision, color,
                        note: content, noteRevision: editor.noteRevision })
                });
            if (signal.aborted) return;
            keep(saved);
            setEditor(null);
            setMessage("已保存");
        });
    }

    function remove() {
        if (!window.confirm("删除这处高亮和关联笔记？")) return;
        void perform(async signal => {
            await request(path + "/" + encodeURIComponent(editor.id) + "?revision=" + encodeURIComponent(editor.revision),
                { method: "DELETE", signal });
            if (signal.aborted) return;
            setRecords(current => current.filter(item => item.id !== editor.id));
            setEditor(null);
            setMessage("");
        });
    }

    return <>
        {layers.map(({ pageNumber, host }) => createPortal(<>
            {/* 只把色块放入统一透明层；笔记图标继续独立响应悬停、键盘和编辑。 */}
            <div className="pdf-mark-paint" aria-hidden="true">
                {records.filter(mark => mark.pageNumber === pageNumber).flatMap(mark =>
                    mark.rects.map((rect, index) => <span key={`${mark.id}:${index}`} className="pdf-mark-fill" data-color={mark.color}
                        style={{ left: `${rect.x * 100}%`, top: `${rect.y * 100}%`,
                            width: `${rect.width * 100}%`, height: `${rect.height * 100}%` }} />))}
            </div>
            {records.filter(mark => mark.pageNumber === pageNumber).map(mark => {
            const first = mark.rects[0];
            return <div key={mark.id} data-annotation-id={mark.id}>
                <div className="pdf-mark-handle" style={{ left: `${first.x * 100}%`, top: `${first.y * 100}%` }}>
                    <button type="button" className="pdf-note-marker" data-color={mark.color}
                        aria-label={mark.note ? "编辑阅读笔记" : "编辑高亮"}
                        aria-describedby={preview?.mark.id === mark.id ? "pdf-note-preview" : undefined} disabled={busy || loading}
                        onMouseEnter={event => showPreview(event, mark)} onMouseLeave={hidePreview}
                        onFocus={event => showPreview(event, mark)} onBlur={hidePreview}
                        onClick={() => open(mark)}>{mark.note ? "▤" : "●"}</button>
                </div>
            </div>;
            })}
        </>, host, pageNumber))}
        {preview ? <div className="pdf-note-preview" role="tooltip" id="pdf-note-preview"
            style={{ left: preview.left, top: preview.above ? preview.top - 26 : preview.top,
                transform: preview.above ? "translateY(-100%)" : undefined }}
            onMouseEnter={() => clearTimeout(previewTimer.current)} onMouseLeave={hidePreview}>
            {preview.mark.note}
        </div> : null}
        {selection && !editor ? <div className="pdf-selection-menu" ref={menuElement}
            style={{ left: selection.menu.left, top: selection.menu.top }}
            data-above={selection.menu.above} onPointerDown={event => event.preventDefault()}>
            <HighlightColors color={color} onChange={setColor} disabled={busy || loading} />
            <div className="journal-actions">
                <button type="button" disabled={busy || loading} onClick={highlight}>高亮</button>
                <button type="button" className="secondary" disabled={busy || loading}
                    onClick={() => open({ ...selection, color, creating: true })}>添加笔记</button>
                <button type="button" className="secondary" disabled={busy} onClick={() => {
                    onAsk(selection);
                    clearSelection();
                }}>✦ 问问 AI</button>
            </div>
            {message ? <p role="status">{message}</p> : null}
        </div> : null}
        {editor ? <aside className="journal-surface pdf-annotation-editor" aria-labelledby="pdfAnnotationHeading"
            onKeyDown={event => { if (event.key === "Escape" && !busy) setEditor(null); }}>
            <div className="journal-heading">
                <h2 id="pdfAnnotationHeading" tabIndex={-1} ref={heading}>阅读笔记</h2>
                <button type="button" className="secondary" disabled={busy} onClick={() => setEditor(null)}>收起</button>
            </div>
            <blockquote>{editor.quote}</blockquote>
            <form onSubmit={save}>
                <label htmlFor="pdf-annotation-note">笔记</label>
                <textarea id="pdf-annotation-note" rows={4} maxLength={8000} value={content}
                    disabled={busy} onChange={event => setContent(event.target.value)} />
                <HighlightColors color={color} onChange={setColor} disabled={busy} />
                <div className="journal-actions">
                    <button type="submit" disabled={busy || loading}>保存笔记</button>
                    {!editor.creating ? <button type="button" className="secondary" disabled={busy} onClick={remove}>删除高亮</button> : null}
                </div>
            </form>
            {message ? <p role="status">{message}</p> : null}
        </aside> : !selection && message ? <p className="pdf-annotation-status" role="status">{message}</p> : null}
    </>;
}
