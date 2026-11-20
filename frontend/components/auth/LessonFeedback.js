"use client";

import { useRef, useState } from "react";

export function LessonFeedback({ turn, request, onSaved, onRetry, disabled }) {
    const [detail, setDetail] = useState(turn.detail ?? "");
    const [saving, setSaving] = useState(false);
    const [error, setError] = useState("");
    const pending = useRef(false);
    async function save(status, text = detail) {
        if (pending.current) return;
        pending.current = true; setSaving(true); setError("");
        try {
            const value = await request(`/api/tutor/lessons/${turn.id}/feedback`, {
                method: "PUT", headers: { "Content-Type": "application/json" },
                body: JSON.stringify({ status, detail: text })
            });
            onSaved(value);
            if (status === "PENDING") setDetail("");
        } catch (problem) { setError(problem.message); }
        finally { pending.current = false; setSaving(false); }
    }
    if (!turn.id) return null;
    return <div className="tutor-lesson-feedback" aria-label="这次讲解的反馈">
        <div className="tutor-actions">
            {[["HELPFUL", "有帮助"], ["UNRESOLVED", "没解决"], ["INCORRECT", "判断不对"]].map(([status, label]) =>
                <button key={status} type="button" className="secondary" aria-pressed={turn.feedback === status}
                    disabled={saving || disabled} onClick={() => void save(turn.feedback === status ? "PENDING" : status)}>{label}</button>)}
        </div>
        {["UNRESOLVED", "INCORRECT"].includes(turn.feedback) && <>
            <label>补充说明（可选）<textarea rows={2} maxLength={300} value={detail}
                onChange={event => setDetail(event.target.value)} disabled={saving || disabled} /></label>
            <div className="tutor-actions">
                <button type="button" className="secondary" disabled={saving || disabled || detail === turn.detail}
                    onClick={() => void save(turn.feedback)}>保存说明</button>
                {onRetry && <button type="button" className="secondary" disabled={saving || disabled} onClick={() => onRetry(turn)}>换个讲法</button>}
            </div>
        </>}
        {saving && <small role="status">正在保存…</small>}
        {error && <small role="alert">{error}</small>}
    </div>;
}
