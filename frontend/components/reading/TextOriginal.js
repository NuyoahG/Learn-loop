"use client";

import { useEffect, useLayoutEffect, useRef, useState } from "react";

export function TextOriginal({ file, type, position, onReady, onPosition, onError }) {
    const [lines, setLines] = useState(null);
    const rootRef = useRef(null);
    useEffect(() => {
        let cancelled = false;
        file.arrayBuffer().then(bytes => {
            const text = new TextDecoder("utf-8", { fatal: true }).decode(bytes);
            if (!cancelled) setLines(text.split(/\r?\n/));
        }).catch(() => { if (!cancelled) onError("这个文本文件不是有效的 UTF-8，请下载原件后打开。"); });
        return () => { cancelled = true; };
    }, [file, onError]);

    useLayoutEffect(() => {
        if (!lines) return;
        const root = rootRef.current;
        let fence = null;
        const entries = type === "md" ? lines.flatMap((line, index) => {
            // 代码块里的井号属于示例代码，不是 Markdown 章节。
            const marker = /^ {0,3}(`{3,}|~{3,})(.*)$/.exec(line);
            if (marker && !fence) { fence = marker[1]; return []; }
            if (fence) {
                if (marker && marker[1][0] === fence[0] && marker[1].length >= fence.length && !marker[2].trim()) fence = null;
                return [];
            }
            const heading = /^ {0,3}(#{1,6})[ \t]+(.*)$/.exec(line);
            const label = heading?.[2].replace(/[ \t]+#+[ \t]*$/, "").trim();
            return label ? [{ label, depth: heading[1].length - 1, target: index }] : [];
        }) : [];
        root.scrollTop = position?.top ?? 0;
        onPosition({ top: root.scrollTop });
        onReady(entries, entry => {
            const line = root.querySelector(`[data-line="${entry.target}"]`);
            if (line) root.scrollTop += line.getBoundingClientRect().top - root.getBoundingClientRect().top - 12;
        });
    }, [lines, type, position, onReady, onPosition]);

    return (
        <pre ref={rootRef} className="text-original" tabIndex={0} aria-label="文本原件"
            onScroll={event => onPosition({ top: event.currentTarget.scrollTop })}>
            {lines?.map((line, index) => <span key={index} data-line={index}>{line}{"\n"}</span>)}
        </pre>
    );
}
