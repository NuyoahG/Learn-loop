"use client";

import Link from "next/link";
import { useCallback, useEffect, useRef, useState } from "react";
import { useAuth } from "@/components/auth/AuthProvider";
import { lastReading, rememberReading } from "@/lib/reading-position";
import { PdfOriginal } from "./PdfOriginal";
import { WordOriginal } from "./WordOriginal";
import { TextOriginal } from "./TextOriginal";
import { PdfQuestion } from "./PdfQuestion";
import "./original-reader.css";

function outlineTree(entries) {
    const roots = [];
    const ancestors = [];
    for (const entry of entries) {
        const branch = { entry, children: [] };
        // 允许文档跳过标题级别，挂到最近的上一级标题。
        while (ancestors.length && ancestors.at(-1).entry.depth >= entry.depth) ancestors.pop();
        (ancestors.at(-1)?.children ?? roots).push(branch);
        ancestors.push(branch);
    }
    return roots;
}

function OutlineBranch({ branch, navigate }) {
    const [expanded, setExpanded] = useState(false);
    const { entry, children } = branch;
    const hasChildren = children.length > 0;
    return <li>
        <div className="outline-row">
            <button type="button" className="secondary outline-entry"
                aria-expanded={hasChildren ? expanded : undefined}
                onClick={() => hasChildren ? setExpanded(!expanded) : navigate(entry)}>
                <span>
                    {hasChildren && <span className="outline-chevron" aria-hidden="true">{expanded ? "▾" : "▸"}</span>}
                    {entry.label}
                </span>
                {entry.page ? <small>第 {entry.page} 页</small> : null}
            </button>
            {hasChildren ? <button type="button" className="secondary outline-jump"
                title={`跳转到 ${entry.label}`} aria-label={`跳转到 ${entry.label}`} onClick={() => navigate(entry)}>↗</button> : null}
        </div>
        {expanded && <OutlineList branches={children} navigate={navigate} />}
    </li>;
}

function OutlineList({ branches, navigate }) {
    return <ul className="outline-list">
        {branches.map((branch, index) => <OutlineBranch key={index} branch={branch} navigate={navigate} />)}
    </ul>;
}

export function OriginalReader({ materialId, userId, targetAnnotation }) {
    const { request } = useAuth();
    const [saved] = useState(() => {
        const prior = lastReading(userId);
        return prior?.materialId === materialId && prior.view === "original" ? prior.position : null;
    });
    const [document, setDocument] = useState(null);
    const [error, setError] = useState("");
    const [ready, setReady] = useState(false);
    const [outline, setOutline] = useState(null);
    const [outlineOpen, setOutlineOpen] = useState(false);
    const [questionPanel, setQuestionPanel] = useState({ selection: null, version: 0 });
    const [questionOpen, setQuestionOpen] = useState(true);
    const [annotationVersion, setAnnotationVersion] = useState(0);
    const jump = useRef(null);
    const outlineButton = useRef(null);
    const questionButton = useRef(null);
    const questionPanelVersion = useRef(0);
    const originalPath = `/api/materials/${materialId}/original`;

    useEffect(() => {
        const controller = new AbortController();
        const options = { signal: controller.signal };
        Promise.all([request(`/api/materials/${materialId}`, options),
            request(originalPath, { ...options, responseType: "blob" })]).then(([meta, file]) => {
            if (!controller.signal.aborted) setDocument({ meta, file });
        }).catch(problem => { if (!controller.signal.aborted) setError(problem.message); });
        return () => controller.abort();
    }, [materialId, originalPath, request]);

    const onPosition = useCallback(position => {
        rememberReading(userId, { view: "original", materialId, position });
    }, [materialId, userId]);

    const onReady = useCallback((entries, navigate) => {
        setReady(true);
        setOutline(entries && outlineTree(entries));
        jump.current = navigate;
    }, []);

    function closeOutline() {
        setOutlineOpen(false);
        outlineButton.current?.focus();
    }

    function closeQuestionPanel() {
        // 收起只改变布局，保留本次选区、已生成回答和输入内容。
        setQuestionOpen(false);
        questionButton.current?.focus({ preventScroll: true });
    }

    function openQuestionPanel(selection) {
        setOutlineOpen(false);
        // 新选区启动独立会话，关闭旧请求，避免旧回答覆盖新选区。
        setQuestionPanel({ selection, version: ++questionPanelVersion.current });
        setQuestionOpen(true);
    }

    function downloadOriginal() {
        const url = URL.createObjectURL(document.file);
        const anchor = window.document.createElement("a");
        anchor.href = url;
        anchor.download = document.meta.originalName;
        anchor.click();
        window.setTimeout(() => URL.revokeObjectURL(url), 1000);
    }

    const meta = document?.meta;
    const preview = meta?.fileType === "pdf" ? PdfOriginal : meta?.fileType === "docx" ? WordOriginal : TextOriginal;
    const Preview = preview;

    return (
        <div className="journal-page reader-page original-reader">
            <header className="journal-hero reader-hero">
                <div>
                    <Link href="/workspace?tab=materials" className="reader-back ll-inline-link">← 我的资料</Link>
                    <h1 title={meta?.originalName ?? "资料阅读"}>{meta?.originalName ?? "资料阅读"}</h1>
                </div>
                <div className="journal-actions original-toolbar" onKeyDown={event => {
                    if (event.key === "Escape" && outlineOpen) closeOutline();
                }}>
                    <button type="button" className="secondary" ref={outlineButton} aria-controls="document-outline"
                        aria-expanded={outlineOpen} onClick={() => {
                            setOutlineOpen(open => !open);
                        }}>目录</button>
                    {meta?.fileType === "pdf" ? (
                        <button type="button" className="secondary" ref={questionButton}
                            aria-controls="pdf-ai-panel" aria-expanded={questionOpen}
                            onClick={() => questionOpen ? closeQuestionPanel() : setQuestionOpen(true)}>AI 问答</button>
                    ) : <Link className="ll-action-link" href={`/reading?materialId=${materialId}&view=text`}>文字问答</Link>}
                    {document ? <button type="button" className="secondary" onClick={downloadOriginal}>下载原件</button> : null}
                </div>
            </header>
            <div className="original-layout" data-ai-open={meta?.fileType === "pdf" && questionOpen}
                onKeyDown={event => { if (event.key === "Escape" && outlineOpen) closeOutline(); }}>
                {outlineOpen ? (
                    <aside id="document-outline" className="journal-surface outline-drawer" aria-label="文档目录">
                        <div className="journal-heading">
                            <h2>文档目录</h2>
                            <button type="button" className="secondary" onClick={closeOutline}>收起</button>
                        </div>
                        {error || outline?.length === 0 ? <p role="status">无法识别目录</p> : null}
                        {!error && outline === null ? <p role="status">正在识别目录…</p> : null}
                        {outline?.length ? (
                            <nav aria-label="目录跳转">
                                <OutlineList branches={outline} navigate={entry => {
                                    jump.current?.(entry);
                                    closeOutline();
                                }} />
                            </nav>
                        ) : null}
                    </aside>
                ) : null}
                <div className="original-document">
                    {error ? <p className="journal-error" role="alert">{error}</p> : null}
                    {!error && !ready ? <p role="status" className="original-loading">正在加载原件…</p> : null}
                    {document && !error ? <Preview file={document.file} type={meta.fileType} position={saved}
                        materialId={materialId} refreshVersion={annotationVersion} targetAnnotation={targetAnnotation}
                        onReady={onReady} onPosition={onPosition} onError={setError} onAsk={openQuestionPanel} /> : null}
                </div>
                {meta?.fileType === "pdf" ? <div className="pdf-ai-pane" id="pdf-ai-panel" hidden={!questionOpen}>
                    <PdfQuestion key={questionPanel.version} materialId={materialId}
                        selection={questionPanel.selection} onClose={closeQuestionPanel}
                        onSaved={() => setAnnotationVersion(value => value + 1)} />
                </div> : null}
            </div>
        </div>
    );
}
