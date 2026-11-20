"use client";

import { useEffect, useState } from "react";
import { useAuth } from "@/components/auth/AuthProvider";

const reasons = [
    ["NOT_UNDERSTOOD", "没理解"], ["CONFUSED", "概念混淆"], ["FORGOT", "忘记了"],
    ["MISREAD", "看漏条件"], ["PROCEDURE", "计算或步骤错了"], ["MISCLICK", "误触"],
    ["QUESTION_ISSUE", "题目可能有误"], ["UNSURE", "不确定"], ["OTHER", "其他"]
];

export function TutorFeedback({ kind, attemptId }) {
    const { request } = useAuth();
    const [reason, setReason] = useState("");
    const [detail, setDetail] = useState("");
    const [closed, setClosed] = useState(false);
    const [ready, setReady] = useState(false);
    const [saved, setSaved] = useState(false);
    const [busy, setBusy] = useState(false);
    const [error, setError] = useState("");
    const path = `/api/tutor/feedback/${kind}/${encodeURIComponent(attemptId)}`;

    useEffect(() => {
        const controller = new AbortController();
        request(path, { signal: controller.signal }).then(value => {
            if (controller.signal.aborted) return;
            if (value) {
                setReason(value.reason);
                setDetail(value.detail);
                setSaved(true);
                setClosed(true);
            }
            setReady(true);
        }).catch(problem => {
            if (!controller.signal.aborted) {
                setError(problem.message);
                setReady(true);
            }
        });
        return () => controller.abort();
    }, [path, request]);

    async function submit(event) {
        event.preventDefault();
        if (busy || !reason || (reason === "OTHER" && !detail.trim())) return;
        setBusy(true);
        setError("");
        try {
            await request(path, {
                method: "PUT", headers: { "Content-Type": "application/json" },
                body: JSON.stringify({ reason, detail: reason === "OTHER" ? detail.trim() : "" })
            });
            setSaved(true);
            setClosed(true);
            window.dispatchEvent(new Event("learnloop:tutor-evidence"));
        } catch (problem) {
            setError(problem.message);
        } finally {
            setBusy(false);
        }
    }

    if (!ready) return null;
    if (closed) return saved ? <p className="tutor-feedback-status">错因已保存
        {" "}<button type="button" className="secondary" onClick={() => setClosed(false)}>修改</button>
    </p> : null;

    return <form className="tutor-feedback" onSubmit={submit}>
        <fieldset disabled={busy}>
            <legend>这次为什么选错了？</legend>
            <div className="tutor-reasons">
                {reasons.map(([value, label]) => <label key={value}>
                    <input type="radio" name={`reason-${attemptId}`} value={value} checked={reason === value}
                        onChange={() => setReason(value)} /> {label}
                </label>)}
            </div>
            {reason === "OTHER" && <label>补充原因
                <textarea maxLength={300} rows={2} value={detail} onChange={event => setDetail(event.target.value)} />
            </label>}
        </fieldset>
        <div className="workspace-actions">
            <button type="submit" disabled={busy || !reason || (reason === "OTHER" && !detail.trim())}>告诉老师</button>
            <button type="button" className="secondary" disabled={busy} onClick={() => setClosed(true)}>跳过</button>
        </div>
        {error && <p role="status">{error}</p>}
    </form>;
}
