"use client";

import { useCallback, useEffect, useRef, useState } from "react";
import { TutorFeedback } from "@/components/practice/TutorFeedback";
import { AnswerExplanation } from "@/components/practice/AnswerExplanation";
import { useAuth } from "@/components/auth/AuthProvider";

const ratings = [[1, "忘了"], [2, "吃力"], [3, "记得"], [4, "轻松"]];
const letters = ["A", "B", "C", "D"];
const date = new Intl.DateTimeFormat("zh-CN", { dateStyle: "medium", timeStyle: "short" });

export function ReviewPage() {
    const { request } = useAuth();
    const [card, setCard] = useState(null);
    const [loaded, setLoaded] = useState(false);
    const [selected, setSelected] = useState("");
    const [answer, setAnswer] = useState(null);
    const [result, setResult] = useState(null);
    const [pendingRating, setPendingRating] = useState(null);
    const [busy, setBusy] = useState(false);
    const [error, setError] = useState("");
    const gate = useRef(false);
    const load = useCallback(async signal => {
        try {
            const queue = await request("/api/review/today", { signal });
            if (signal?.aborted) return;
            setCard(queue.card);
            setLoaded(true);
            setSelected("");
            setAnswer(null);
            setResult(null);
            setPendingRating(null);
        } catch (problem) {
            if (!signal?.aborted) setError(problem.message);
        }
    }, [request]);
    useEffect(() => {
        const controller = new AbortController();
        void load(controller.signal);
        return () => controller.abort();
    }, [load]);

    async function run(action) {
        if (gate.current) return;
        gate.current = true;
        setBusy(true);
        setError("");
        try { await action(); }
        catch (problem) { setError(problem.message); }
        finally { gate.current = false; setBusy(false); }
    }

    const post = (path, payload) => request(path, {
        method: "POST", headers: { "Content-Type": "application/json" }, body: JSON.stringify(payload)
    });

    function reveal(event) {
        event.preventDefault();
        if (!selected || !card || answer) return;
        void run(async () => setAnswer(await post("/api/review/answer",
            { topicId: card.topicId, versionId: card.versionId, dueAt: card.dueAt, selected })));
    }

    function rate(rating) {
        // 保留第一次提交的反馈，响应丢失时用同一尝试和评分重发。
        void run(async () => {
            setPendingRating(rating);
            setResult(await post(`/api/review/${answer.attemptId}/rating`, { rating }));
        });
    }

    return (
        <div className="journal-page">
            <header className="journal-hero">
                <h1>今日复习</h1>
                {card ? <span>还剩 {Math.max(0, card.remaining - (result ? 1 : 0))} 题</span> : null}
            </header>
            {error ? <p className="journal-error" role="alert">{error}</p> : null}
            {!loaded || !card ? (
                <section className="journal-surface">
                    <p>{loaded ? "今日已完成" : error ? "加载失败" : "正在加载…"}</p>
                    <button type="button" className="secondary" disabled={busy} onClick={() => void run(() => load())}>刷新</button>
                </section>
            ) : (
                <section className="journal-surface">
                    <p>{card.materialName} · {card.topicName}</p>
                    <form onSubmit={reveal}>
                        <fieldset disabled={busy || !!answer}>
                            <legend>{card.content}</legend>
                            {letters.map(letter => (
                                <label className="workspace-answer-option" key={letter}>
                                    <input type="radio" name="review-answer" value={letter}
                                        checked={(answer?.selected ?? selected) === letter}
                                        onChange={() => setSelected(letter)} />
                                    {letter}. {card[`option${letter}`]}
                                </label>
                            ))}
                        </fieldset>
                        {!answer ? <button type="submit" disabled={!selected || busy}>提交答案</button> : null}
                    </form>
                    {answer ? <div>
                        <p>{answer.selected === answer.correctAnswer ? "答对了" : "答错了"} · 正确答案 {answer.correctAnswer}</p>
                        {answer.selected !== answer.correctAnswer && <TutorFeedback key={answer.attemptId} kind="REVIEW" attemptId={answer.attemptId} />}
                        <AnswerExplanation key={answer.attemptId} kind="REVIEW" attemptId={answer.attemptId}
                            initialBrief={answer.explanation ?? ""} />
                        {result ? (
                            <div aria-live="polite">
                                <p>下次复习 {date.format(new Date(result.dueAt))}</p>
                                <button type="button" disabled={busy} onClick={() => void run(() => load())}>下一题</button>
                            </div>
                        ) : (
                            <fieldset className="workspace-options" disabled={busy}>
                                <legend>刚才回忆得怎么样？</legend>
                                <div className="journal-actions">
                                    {ratings.map(([value, label]) => (
                                        <button type="button" className="secondary" key={value}
                                            disabled={pendingRating !== null && pendingRating !== value}
                                            onClick={() => rate(value)}>{label}</button>
                                    ))}
                                </div>
                            </fieldset>
                        )}
                    </div> : null}
                </section>
            )}
        </div>
    );
}
