"use client";

import Link from "next/link";
import { useEffect, useId, useState } from "react";
import { useAuth } from "@/components/auth/AuthProvider";

export function NoteEditor({ version, readingNoteId, initialNote, onDeleted, onSaved }) {
    const { request } = useAuth();
    const fieldId = useId();
    const [saved, setSaved] = useState(initialNote ?? null);
    const [content, setContent] = useState(initialNote?.content ?? "");
    const [loading, setLoading] = useState(initialNote === undefined);
    const [busy, setBusy] = useState(false);
    const [ready, setReady] = useState(initialNote !== undefined);
    const [notice, setNotice] = useState("");
    const path = readingNoteId ? "/api/notes/reading/" + encodeURIComponent(readingNoteId)
        : "/api/notes/" + encodeURIComponent(version);

    useEffect(() => {
        if (initialNote !== undefined) return;
        const controller = new AbortController();
        request(path, { signal: controller.signal }).then(({ note }) => {
            if (controller.signal.aborted) return;
            setSaved(note);
            setContent(note?.content ?? "");
            setReady(true);
        }).catch(error => {
            if (!controller.signal.aborted) setNotice(error.message);
        }).finally(() => {
            if (!controller.signal.aborted) setLoading(false);
        });
        return () => controller.abort();
    }, [path, request, initialNote]);

    async function save(event) {
        event.preventDefault();
        if (busy || !ready || !content.trim()) return;
        setBusy(true);
        setNotice("");
        try {
            const note = await request(path, { method: "PUT", headers: { "Content-Type": "application/json" },
                body: JSON.stringify({ content, revision: saved?.revision ?? null }) });
            setSaved(note);
            setContent(note.content);
            setNotice("笔记已保存");
            onSaved?.(note);
        } catch (error) {
            setNotice(error.message);
        } finally {
            setBusy(false);
        }
    }

    async function remove() {
        if (!saved || busy || !window.confirm("删除这条笔记？")) return;
        setBusy(true);
        try {
            await request(path + "?revision=" + encodeURIComponent(saved.revision), { method: "DELETE" });
            setSaved(null);
            setContent("");
            setNotice("笔记已删除");
            onDeleted?.();
        } catch (error) {
            setNotice(error.message);
        } finally {
            setBusy(false);
        }
    }

    return (
        <form className="note-editor" onSubmit={save}>
            <div className="journal-heading">
                <label htmlFor={fieldId}>学习笔记</label>
                {initialNote === undefined ? <Link className="ll-inline-link" href="/notes">查看全部笔记 ↗</Link> : null}
            </div>
            <textarea id={fieldId} rows={4} value={content} maxLength={8000}
                disabled={!ready || busy} placeholder="记下解题思路、错因或想复习的地方…"
                onChange={event => setContent(event.target.value)} />
            <div className="journal-actions">
                <button type="submit" disabled={busy || !ready || !content.trim() || content === saved?.content}>保存笔记</button>
                {saved ? <button type="button" className="secondary" onClick={() => void remove()} disabled={busy}>删除笔记</button> : null}
                <span role="status">{loading ? "正在读取笔记…" : notice || (saved ? "已保存" : "仅你可见")}</span>
            </div>
        </form>
    );
}
