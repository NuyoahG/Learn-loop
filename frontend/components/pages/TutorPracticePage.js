"use client";

import { useEffect, useRef, useState } from "react";
import { useAuth } from "@/components/auth/AuthProvider";
import { AnswerOptions, ConfidenceOptions } from "@/components/practice/AnswerOptions";
import { TutorFeedback } from "@/components/practice/TutorFeedback";
import { AnswerExplanation } from "@/components/practice/AnswerExplanation";

const root = "/api/tutor/plans";
const ratings = [[1, "忘了"], [2, "吃力"], [3, "记得"], [4, "轻松"]];
const toolLabels = { SUMMARY: "学习摘要与可用题目", DUE: "到期复习", PRACTICE: "题库与错答", MASTERY: "知识组掌握度" };

export function TutorPracticePage() {
    const { request } = useAuth();
    const [plan, setPlan] = useState(null);
    const [loaded, setLoaded] = useState(false);
    const [step, setStep] = useState(null);
    const [option, setOption] = useState("");
    const [confidence, setConfidence] = useState("UNSURE");
    const [answer, setAnswer] = useState(null);
    const [rating, setRating] = useState(null);
    const [rated, setRated] = useState(false);
    const [minutes, setMinutes] = useState(10);
    const [materialId, setMaterialId] = useState(null);
    const [busy, setBusy] = useState(false);
    const [error, setError] = useState("");
    const action = useRef(false);
    const frozen = useRef(null);

    const post = (path, body) => request(path, {
        method: "POST", headers: { "Content-Type": "application/json" }, body: JSON.stringify(body ?? {})
    });

    useEffect(() => {
        const controller = new AbortController();
        const material = new URLSearchParams(window.location.search).get("materialId");
        if (material && /^[1-9][0-9]*$/.test(material) && Number.isSafeInteger(Number(material))) setMaterialId(Number(material));
        request(root + "/latest", { signal: controller.signal }).then(value => {
            if (!controller.signal.aborted) { setPlan(value); setLoaded(true); }
        }).catch(problem => {
            if (!controller.signal.aborted) { setError(problem.message); setLoaded(true); }
        });
        return () => controller.abort();
    }, [request]);

    async function run(operation) {
        if (action.current) return;
        action.current = true;
        setBusy(true);
        setError("");
        try { await operation(); }
        catch (problem) { setError(problem.message); }
        finally { action.current = false; setBusy(false); }
    }

    function clearStep() {
        setStep(null);
        setOption("");
        setConfidence("UNSURE");
        setAnswer(null);
        setRating(null);
        setRated(false);
        frozen.current = null;
    }

    async function open(id) {
        const next = await post(`${root}/${id}/open`);
        clearStep();
        setStep(next);
    }

    function submit(event) {
        event.preventDefault();
        if (!step || !option || answer) return;
        void run(async () => {
            // 网络结果未知时沿用原选择、信心和提交键，保证普通作答可重试。
            const payload = frozen.current ??= { selectedOption: option, confidence, submitKey: crypto.randomUUID() };
            const result = step.kind === "REVIEW"
                ? await post("/api/review/answer", { topicId: step.item.topicId, versionId: step.item.versionId,
                    dueAt: step.item.dueAt, selected: payload.selectedOption })
                : await post(`/api/learning/attempts/${step.attemptId}/answers`, payload);
            setAnswer(result);
        });
    }

    function rate(value) {
        if (rating !== null && value !== rating) return;
        void run(async () => {
            setRating(value);
            await post(`/api/review/${answer.attemptId}/rating`, { rating: value });
            setRated(true);
        });
    }

    const review = step?.kind === "REVIEW";
    const correct = review ? answer?.correctAnswer === answer?.selected : answer?.correct;
    const reveal = review ? answer?.correctAnswer : answer?.correctOption;
    const done = step?.finished || (answer && (!review || rated));
    return <div className="journal-page">
        <header className="journal-header"><h1>小栗的练习</h1></header>
        {error && <p className="journal-error" role="alert">{error}</p>}
        {loaded && !plan && <section className="journal-surface">
            <label>学习时间 <select value={minutes} onChange={event => setMinutes(Number(event.target.value))}>
                {[4, 6, 10, 15, 20].map(value => <option key={value} value={value}>{value} 分钟</option>)}
            </select></label>
            <div className="journal-actions"><button type="button" disabled={busy}
                onClick={() => void run(async () => setPlan(await post(root, { minutes, materialId })))}>
                {busy ? "正在安排…" : "安排练习"}
            </button></div>
        </section>}
        {plan && <section className="journal-surface">
            <h2>{plan.status === "COMPLETED" ? "练习已完成" : `练习计划 · ${plan.items.length} 题`}</h2>
            {plan.status === "DRAFT" && <>
                <p>{plan.reason}</p>
                <ol>{plan.items.map(item => <li key={item.versionId}>
                    {item.dueAt ? "到期复习" : "巩固练习"} · {item.material} · {item.topic}
                </li>)}</ol>
                <details><summary>{plan.planner === "AGENT" ? "查看规划依据" : "规则安排"}</summary>
                    <p>{plan.trace.map(tool => toolLabels[tool]).join("、") || "到期优先，再看近期错答。"}</p>
                </details>
            </>}
            <div className="journal-actions">
                {plan.status === "DRAFT" && <button type="button" disabled={busy}
                    onClick={() => void run(async () => {
                        const active = await post(`${root}/${plan.id}/confirm`);
                        setPlan(active);
                        if (active.status === "ACTIVE") await open(active.id);
                    })}>确认并开始</button>}
                {plan.status === "ACTIVE" && !step && <button type="button" disabled={busy}
                    onClick={() => void run(() => open(plan.id))}>继续练习</button>}
                {plan.status !== "COMPLETED" && <button type="button" className="secondary" disabled={busy}
                    onClick={() => void run(async () => {
                        await post(`${root}/${plan.id}/cancel`);
                        clearStep();
                        setPlan(null);
                    })}>{plan.status === "DRAFT" ? "重新安排" : "结束练习"}</button>}
                {plan.status === "COMPLETED" && <button type="button" onClick={() => setPlan(null)}>再安排一次</button>}
            </div>
        </section>}
        {step && <section className="journal-surface">
            <p>{step.position + 1} / {plan.items.length} · {step.item.topic}</p>
            {!step.finished && <form onSubmit={submit}>
                <h2>{step.content}</h2>
                <AnswerOptions question={step} name="tutor-answer" selected={option} onChange={setOption}
                    disabled={busy || !!answer || !!frozen.current} correctOption={reveal} />
                {!review && !answer && <ConfidenceOptions name="tutor-confidence" value={confidence} onChange={setConfidence}
                    disabled={busy || !!answer || !!frozen.current} />}
                {!answer && <button type="submit" disabled={!option || busy}>提交答案</button>}
            </form>}
            {answer && <p role="status">{correct ? "答对了" : "答错了"} · 正确选项 {reveal}</p>}
            {answer && <AnswerExplanation key={answer.attemptId ?? step.attemptId} kind={review ? "REVIEW" : "PRACTICE"}
                attemptId={answer.attemptId ?? step.attemptId} initialBrief={answer.explanation ?? ""} />}
            {answer && !correct && <TutorFeedback key={answer.attemptId} kind={review ? "REVIEW" : "PRACTICE"}
                attemptId={answer.attemptId} />}
            {review && answer && !rated && <fieldset disabled={busy}>
                <legend>刚才回忆得怎么样？</legend>
                <div className="journal-actions">{ratings.map(([value, label]) =>
                    <button key={value} type="button" className="secondary" disabled={rating !== null && rating !== value}
                        onClick={() => rate(value)}>{label}</button>)}</div>
            </fieldset>}
            {done && <button type="button" disabled={busy} onClick={() => void run(async () => {
                const next = await post(`${root}/${plan.id}/advance`, { position: step.position });
                clearStep();
                setPlan(next);
                if (next.status === "ACTIVE") await open(next.id);
            })}>{step.position + 1 === plan.items.length ? "完成练习" : "下一题"}</button>}
        </section>}
    </div>;
}
