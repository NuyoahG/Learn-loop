"use client";

import { useEffect, useRef, useState } from "react";

const sandbox = `<!doctype html><html lang="zh-CN"><head><meta charset="utf-8">
<meta http-equiv="Content-Security-Policy" content="default-src 'none'; img-src blob: data:; font-src blob: data:; style-src 'unsafe-inline'; base-uri 'none'; form-action 'none'">
<style>body{margin:0;background:#e9eeeb}@media(max-width:850px){.docx-wrapper{padding:10px!important}.docx-wrapper>section.docx{box-sizing:border-box;max-width:100%;padding-inline:clamp(16px,8vw,75px)!important}}</style>
</head><body></body></html>`;

function wordOutline(frame, word) {
    const linked = [];
    const seen = new Set();
    for (const link of frame.querySelectorAll("a[href]")) {
        const href = link.getAttribute("href");
        const target = href?.startsWith("#") ? frame.getElementById(href.slice(1)) : null;
        if (!target) {
            link.removeAttribute("href");
            continue;
        }
        const label = link.textContent.trim();
        if (/^#_Toc/i.test(href) && label && !seen.has(target)) {
            seen.add(target);
            const level = link.closest("p")?.className.match(/\bdocx_toc([1-9])\b/i)?.[1];
            linked.push({ label, depth: level ? Number(level) - 1 : 0, target });
        }
    }
    if (linked.length) return linked;
    const levels = new Map();
    for (const style of word.stylesPart?.styles ?? []) {
        const heading = /^heading([1-9])$/i.exec(style.id);
        const level = style.paragraphProps?.outlineLevel ?? (heading ? Number(heading[1]) - 1 : null);
        if (Number.isInteger(level) && level >= 0 && level < 9) levels.set(style.cssName, level);
    }
    return Array.from(frame.querySelectorAll("section.docx p")).flatMap(paragraph => {
        const level = [...paragraph.classList].map(name => levels.get(name)).find(Number.isInteger);
        const label = paragraph.textContent.trim();
        return Number.isInteger(level) && label ? [{ label, depth: level, target: paragraph }] : [];
    });
}

function releaseAssets(frame) {
    for (const element of frame.querySelectorAll("[src], [style], style")) {
        const values = [element.getAttribute("src"), element.getAttribute("style"), element.matches("style") ? element.textContent : ""];
        for (const value of values) {
            for (const [url] of (value ?? "").matchAll(/blob:[^\s"'()]+/g)) URL.revokeObjectURL(url);
        }
    }
}

export function WordOriginal({ file, position, onReady, onPosition, onError }) {
    const iframeRef = useRef(null);
    const [frame, setFrame] = useState(null);

    useEffect(() => {
        if (!frame?.body) return;
        let cancelled = false;
        const view = frame.defaultView;
        const onScroll = () => onPosition({ top: Math.round(view.scrollY) });
        async function show() {
            try {
                const { renderAsync } = await import("docx-preview");
                if (cancelled) return;
                // 文档的 HTML 保留在无脚本 iframe 内。禁用嵌入 HTML，并阻止外链跳转。
                const word = await renderAsync(file, frame.body, frame.head,
                    { renderAltChunks: false, renderComments: false, ignoreLastRenderedPageBreak: false });
                if (cancelled) return;
                const entries = wordOutline(frame, word);
                frame.addEventListener("click", preventNavigation);
                await frame.fonts.ready;
                if (cancelled) return;
                view.scrollTo(0, position?.top ?? 0);
                view.addEventListener("scroll", onScroll, { passive: true });
                onScroll();
                onReady(entries, entry => view.scrollTo(0, view.scrollY + entry.target.getBoundingClientRect().top));
            } catch {
                if (!cancelled) onError("暂时无法预览这个 Word 文件。你可以下载原件后在本机打开。");
            } finally {
                if (cancelled) releaseAssets(frame);
            }
        }
        function preventNavigation(event) {
            if (event.target.closest?.("a")) event.preventDefault();
        }
        void show();
        return () => {
            cancelled = true;
            frame.removeEventListener("click", preventNavigation);
            view?.removeEventListener("scroll", onScroll);
            releaseAssets(frame);
        };
    }, [file, frame, position, onReady, onPosition, onError]);

    return (
        <iframe ref={iframeRef} className="word-original" sandbox="allow-same-origin" srcDoc={sandbox}
            title="Word 原件预览" onLoad={() => setFrame(iframeRef.current?.contentDocument)} />
    );
}
