"use client";

import { useCallback, useEffect, useRef, useState } from "react";
import "pdfjs-dist/web/pdf_viewer.css";
import { selectionMenuPosition } from "@/lib/reading-selection";
import { ensurePdfAbortSignalAny } from "@/lib/pdf-browser-compat";
import { PdfAnnotations } from "./PdfAnnotations";

async function outlineEntries(document, items, depth = 0) {
    if (!items || depth > 12) return [];
    const result = [];
    for (const item of items) {
        // 只使用指向本文件的有效目标。PDF 目录内嵌的网页链接不作为跳转。
        try {
            const dest = typeof item.dest === "string" ? await document.getDestination(item.dest) : item.dest;
            const reference = dest?.[0];
            const page = Number.isInteger(reference) ? reference + 1
                : reference ? (await document.getPageIndex(reference)) + 1 : 0;
            if (page > 0 && page <= document.numPages && item.title?.trim()) {
                result.push({ label: item.title.trim(), depth, target: dest, page });
            }
        } catch { /* 单条失效书签不影响其余目录。 */ }
        result.push(...await outlineEntries(document, item.items, depth + 1));
    }
    return result;
}

export function PdfOriginal({ file, position, onReady, onPosition, onError, onAsk, materialId,
    refreshVersion, targetAnnotation }) {
    const containerRef = useRef(null);
    const viewerRef = useRef(null);
    const pdfViewerRef = useRef(null);
    const [selection, setSelection] = useState(null);
    const [scale, setScale] = useState(0);
    const [fitting, setFitting] = useState(true);
    const [layoutVersion, setLayoutVersion] = useState(0);

    function changeScale(factor) {
        const viewer = pdfViewerRef.current;
        if (viewer) viewer.currentScale = Math.max(.25, Math.min(4, viewer.currentScale * factor));
    }

    const readSelection = useCallback(() => {
        const native = window.getSelection();
        const range = native && !native.isCollapsed && native.rangeCount === 1 ? native.getRangeAt(0) : null;
        // 从真实文字层识别页码。目录、批注、工具栏或跨页选区均不作为正文引用。
        const layer = node => (node.nodeType === 1 ? node : node.parentElement)?.closest(".textLayer");
        const start = range && layer(range.startContainer);
        if (!start || start !== layer(range.endContainer) || !viewerRef.current?.contains(start)) {
            setSelection(null);
            return;
        }
        const pageNumber = Number(start.closest("[data-page-number]")?.dataset.pageNumber);
        const quote = range.toString().trim();
        const menu = pageNumber > 0 && quote && selectionMenuPosition(native);
        const canvas = start.closest(".page")?.querySelector(".canvasWrapper")?.getBoundingClientRect();
        if (!menu || !canvas?.width || !canvas.height) {
            setSelection(null);
            return;
        }
        // 坐标相对于原件页内容归一化，避免缩放后继续使用旧屏幕像素。
        const rectangles = Array.from(range.getClientRects()).map(rect => {
            const left = Math.max(canvas.left, rect.left);
            const top = Math.max(canvas.top, rect.top);
            const right = Math.min(canvas.right, rect.right);
            const bottom = Math.min(canvas.bottom, rect.bottom);
            return { x: (left - canvas.left) / canvas.width, y: (top - canvas.top) / canvas.height,
                width: (right - left) / canvas.width, height: (bottom - top) / canvas.height };
        }).filter(rect => rect.width > 0 && rect.height > 0);
        const rects = rectangles.filter((rect, index) => rectangles.findIndex(other =>
            Math.abs(rect.x - other.x) < .00001 && Math.abs(rect.y - other.y) < .00001 &&
            Math.abs(rect.width - other.width) < .00001 && Math.abs(rect.height - other.height) < .00001) === index);
        setSelection(rects.length && rects.length <= 128
            ? { id: crypto.randomUUID(), pageNumber, quote, menu, rects } : null);
    }, []);

    const clearSelection = useCallback(() => {
        setSelection(null);
        window.getSelection()?.removeAllRanges();
    }, []);

    useEffect(() => {
        const touch = window.matchMedia("(pointer: coarse)").matches;
        function onSelectionChange() {
            // 手机的原生选区手柄不会稳定触发 pointerup。
            if (touch) readSelection();
            else if (window.getSelection()?.isCollapsed) setSelection(null);
        }
        document.addEventListener("selectionchange", onSelectionChange);
        return () => document.removeEventListener("selectionchange", onSelectionChange);
    }, [readSelection]);

    const showMenu = !!selection;
    useEffect(() => {
        if (!showMenu) return;
        const dismiss = () => setSelection(null);
        window.addEventListener("scroll", dismiss, { passive: true });
        window.addEventListener("resize", dismiss);
        return () => {
            window.removeEventListener("scroll", dismiss);
            window.removeEventListener("resize", dismiss);
        };
    }, [showMenu]);

    useEffect(() => {
        let cancelled = false;
        let task;
        let viewer;
        let listeners;
        let resizeObserver;
        let restoring = true;
        async function show() {
            try {
                ensurePdfAbortSignalAny();
                // 原件预览使用同版本兼容构建和 Worker，支持缺少较新 JS 接口的 Chromium。
                const pdf = await import("pdfjs-dist/legacy/build/pdf.mjs");
                const { EventBus, PDFLinkService, PDFViewer } = await import("pdfjs-dist/legacy/web/pdf_viewer.mjs");
                const bytes = await file.arrayBuffer();
                if (cancelled) return;
                const assets = location.origin + "/pdf-assets/";
                pdf.GlobalWorkerOptions.workerSrc = assets + "legacy/pdf.worker.min.mjs?v=" + pdf.version;
                task = pdf.getDocument({ data: new Uint8Array(bytes), cMapUrl: assets + "cmaps/", cMapPacked: true,
                    standardFontDataUrl: assets + "standard_fonts/", wasmUrl: assets + "wasm/", iccUrl: assets + "iccs/",
                    enableXfa: false, isEvalSupported: false });
                task.onPassword = () => {
                    onError("这个 PDF 需要密码，请先在本机解密后重新上传。");
                    void task.destroy();
                };
                const document = await task.promise;
                if (cancelled) return;
                const events = new EventBus();
                listeners = new AbortController();
                const options = { signal: listeners.signal };
                const link = new PDFLinkService({ eventBus: events, ignoreDestinationZoom: true });
                link.externalLinkEnabled = false;
                viewer = new PDFViewer({ container: containerRef.current, viewer: viewerRef.current, eventBus: events,
                    linkService: link, annotationMode: pdf.AnnotationMode.ENABLE,
                    annotationEditorMode: pdf.AnnotationEditorType.DISABLE });
                pdfViewerRef.current = viewer;
                link.setViewer(viewer);
                const needsPageSizeFallback = !CSS.supports("width", "round(down, 10px, 1px)");
                function sizePage(pageView) {
                    pageView.div.style.width = Math.floor(pageView.width) + "px";
                    pageView.div.style.height = Math.floor(pageView.height) + "px";
                }
                function sizePages() {
                    for (let index = 0; index < viewer.pagesCount; index++) sizePage(viewer.getPageView(index));
                }
                // 旧 Edge 不认识 PDF.js 的 CSS round()，需要让实际页框与画布同尺寸才可定位。
                if (needsPageSizeFallback) {
                    events.on("scalechanging", sizePages, options);
                    events.on("pagesloaded", sizePages, options);
                    events.on("pagerender", ({ source }) => sizePage(source), options);
                }
                events.on("scalechanging", ({ scale }) => {
                    setScale(scale);
                    setFitting(viewer.currentScaleValue === "page-width");
                    setSelection(null);
                    setLayoutVersion(value => value + 1);
                }, options);
                events.on("pagerendered", () => setLayoutVersion(value => value + 1), options);
                let containerWidth = 0;
                resizeObserver = new ResizeObserver(() => {
                    const width = containerRef.current?.clientWidth;
                    if (width === containerWidth) return;
                    containerWidth = width;
                    // 手动缩放后保留比例，只有适合宽度模式随阅读区尺寸变化。
                    if (viewer.currentScaleValue === "page-width") viewer.currentScaleValue = "page-width";
                });
                resizeObserver.observe(containerRef.current);
                events.on("updateviewarea", ({ location }) => {
                    if (!restoring) onPosition({ page: location.pageNumber, left: location.left, top: location.top });
                }, options);
                events.on("pagesinit", () => {
                    viewer.currentScaleValue = "page-width";
                    if (needsPageSizeFallback) sizePages();
                    if (position?.page <= document.numPages) {
                        viewer.scrollPageIntoView({ pageNumber: position.page,
                            destArray: [null, { name: "XYZ" }, position.left, position.top, null], ignoreDestinationZoom: true });
                    }
                    restoring = false;
                    viewer.update();
                    setLayoutVersion(value => value + 1);
                }, options);
                viewer.setDocument(document);
                link.setDocument(document);
                onReady(null);
                let entries = [];
                try { entries = await outlineEntries(document, await document.getOutline()); } catch {}
                if (!cancelled) onReady(entries, entry => link.goToDestination(entry.target));
            } catch {
                if (!cancelled && !task?.destroyed) onError("暂时无法预览这个 PDF。你可以下载原件后在本机打开。");
            }
        }
        void show();
        return () => {
            cancelled = true;
            resizeObserver?.disconnect();
            pdfViewerRef.current = null;
            listeners?.abort();
            viewer?.setDocument(null);
            if (task && !task.destroyed) void task.destroy().catch(() => {});
        };
    }, [file, position, onReady, onPosition, onError]);

    return (
        <div className="pdf-original">
            <div className="pdf-zoom" role="group" aria-label="PDF 缩放">
                <button type="button" className="secondary" aria-label="缩小 PDF" disabled={!scale || scale <= .25}
                    onClick={() => changeScale(1 / 1.2)}>−</button>
                <output aria-label="PDF 缩放比例">{scale ? Math.round(scale * 100) + "%" : "—"}</output>
                <button type="button" className="secondary" aria-label="放大 PDF" disabled={!scale || scale >= 4}
                    onClick={() => changeScale(1.2)}>+</button>
                <button type="button" className="secondary" aria-pressed={fitting} disabled={!scale}
                    onClick={() => { pdfViewerRef.current.currentScaleValue = "page-width"; }}>适合宽度</button>
            </div>
            <div ref={containerRef} className="pdf-scroll" tabIndex={0} aria-label="PDF 原件"
                onPointerUp={readSelection} onKeyUp={readSelection} onScroll={() => setSelection(null)}>
                <div ref={viewerRef} className="pdfViewer" />
            </div>
            {materialId ? <PdfAnnotations materialId={materialId} viewer={pdfViewerRef.current}
                layoutVersion={layoutVersion} selection={selection} clearSelection={clearSelection}
                onAsk={onAsk}
                refreshVersion={refreshVersion} targetAnnotation={targetAnnotation} /> : null}
        </div>
    );
}
