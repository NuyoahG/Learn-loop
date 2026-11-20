"use client";

import { useEffect, useState } from "react";
import Link from "next/link";
import { useAuth } from "@/components/auth/AuthProvider";

const OPTIONS = ["A", "B", "C", "D"];

export function HistoryQuestion({ recordId, backHref }) {
    const { request } = useAuth();
    const valid = /^[1-9]\d{0,18}$/.test(recordId);
    const [detail, setDetail] = useState(null);
    const [error, setError] = useState("");
    const [reload, setReload] = useState(0);

    useEffect(() => {
        if (!valid) return;
        const controller = new AbortController();
        request(`/api/answers/${recordId}`, { signal: controller.signal })
            .then(result => { if (!controller.signal.aborted) setDetail(result); })
            .catch(problem => { if (!controller.signal.aborted) setError(problem.message); });
        return () => controller.abort();
    }, [request, recordId, reload, valid]);

    return (
        <div className="journal-page history-question">
            <header className="journal-hero">
                <div>
                    <Link className="ll-action-link" href={backHref}>返回答题记录</Link>
                    <h1>题目回看</h1>
                </div>
            </header>

            {!valid ? <p role="alert" className="journal-error">答题记录编号无效。</p> : error ? (
                <section className="journal-surface">
                    <p role="alert" className="journal-error">{error}</p>
                    <button type="button" className="secondary" onClick={() => {
                        setError("");
                        setReload(value => value + 1);
                    }}>重新加载</button>
                </section>
            ) : !detail ? <p role="status">正在加载题目…</p> : (
                <section className="journal-surface">
                    <p className="journal-kicker">题目 {detail.questionId} · 记录 {detail.recordId}</p>
                    {detail.content ? <h2>{detail.content}</h2> : <p>题目内容已不可用。</p>}
                    {detail.content && !detail.questionVersionId && (
                        <p className="journal-kicker">旧记录未保存原题版本，以下显示当前题目内容。</p>
                    )}
                    {detail.content && (
                        <ul className="history-question-options" aria-label="题目选项">
                            {OPTIONS.map(option => (
                                <li key={option}
                                    data-selected={option === detail.selectedOption}
                                    data-correct={option === detail.correctOptionSnapshot}>
                                    <strong>{option}.</strong> <span>{detail["option" + option]}</span>
                                    {option === detail.selectedOption && <small>你的选择</small>}
                                    {option === detail.correctOptionSnapshot && <small>当时的正确答案</small>}
                                </li>
                            ))}
                        </ul>
                    )}
                    <p>你的选择 <strong>{detail.selectedOption}</strong> · 当时的正确答案 <strong>{detail.correctOptionSnapshot}</strong> · {detail.correct ? "答对了" : "答错了"}</p>
                    <p className="journal-kicker">提交时间 {detail.submittedAt?.replace("T", " ")}</p>
                </section>
            )}
        </div>
    );
}
