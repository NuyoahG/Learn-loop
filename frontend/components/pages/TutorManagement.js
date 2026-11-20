"use client";

import Link from "next/link";
import { useEffect, useState } from "react";
import { useAuth } from "@/components/auth/AuthProvider";
import { LessonFeedback } from "@/components/auth/LessonFeedback";
import { useTutorQuiet } from "@/components/auth/useTutorQuiet";
import "./tutor-management.css";

export function TutorManagement() {
    const { user, request } = useAuth();
    return <TutorSettings key={user.userId} userId={user.userId} request={request} />;
}

function TutorSettings({ userId, request }) {
    const [quiet, saveQuiet] = useTutorQuiet(userId);
    const [lessons, setLessons] = useState(null);
    const [error, setError] = useState("");
    const [attempt, setAttempt] = useState(0);

    useEffect(() => {
        const controller = new AbortController();
        setError("");
        request("/api/tutor/lessons", { signal: controller.signal }).then(rows => {
            if (!Array.isArray(rows)) throw new Error("辅导记录格式有误，请重新加载。");
            if (!controller.signal.aborted) setLessons(rows);
        }).catch(problem => {
            if (!controller.signal.aborted) setError(problem.message);
        });
        return () => controller.abort();
    }, [request, attempt]);

    function updateFeedback(updated) {
        setLessons(current => current.map(row => row.id === updated.id ? updated : row));
    }

    return <div className="tutor-management">
        <header><h1>小栗老师管理</h1><Link href="/tutor-practice" className="ll-action-link">安排练习</Link></header>
        <section className="journal-surface tutor-settings" aria-label="提醒设置">
            <label><input type="checkbox" checked={!quiet} onChange={event => saveQuiet(!event.target.checked)} />
                在此浏览器显示学习提醒</label>
        </section>
        <section className="journal-surface tutor-lessons" aria-labelledby="recent-lessons">
            <h2 id="recent-lessons">最近辅导记录</h2>
            {error ? <div role="alert">{error} <button type="button" className="secondary"
                onClick={() => setAttempt(n => n + 1)}>重新加载</button></div>
                : lessons === null ? <p role="status">正在加载辅导记录…</p>
                : !lessons.length ? <p>暂无辅导记录。</p>
                : lessons.map(lesson => <details key={lesson.id}>
                    <summary>{lesson.question}</summary>
                    <p className="tutor-lesson-answer">{lesson.answer}</p>
                    <LessonFeedback turn={lesson} request={request} onSaved={updateFeedback} />
                </details>)}
        </section>
    </div>;
}
