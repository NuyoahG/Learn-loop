"use client";

import Link from "next/link";
import { Suspense } from "react";
import { usePathname } from "next/navigation";
import { TutorCat } from "./TutorCat";

const links = [
    ["/reading", "资料阅读"],
    ["/questions", "题目中心与练习"],
    ["/review", "今日复习"],
    ["/history", "答题记录"],
    ["/notes", "我的笔记"],
    ["/tutor", "小栗老师"],
    ["/workspace", "个人中心"]
];

export function AppShell({ user, onLogout, loggingOut, message, children }) {
    const pathname = usePathname();
    return (
        <main className="practice-page">
            <header className="app-topbar">
                <Link href="/workspace" className="app-brand"><span aria-hidden="true">L</span> LearnLoop</Link>
                <nav className="site-nav" aria-label="主要导航">
                    {links.map(([href, label]) => (
                        <Link key={href} href={href}
                            aria-current={(pathname === href || (href === "/tutor" && pathname === "/tutor-practice")) ? "page" : undefined}>{label}</Link>
                    ))}
                </nav>
                <div className="auth-bar">
                    <span>当前用户：<strong>{user.username}</strong></span>
                    <button type="button" className="secondary" onClick={() => void onLogout()} disabled={loggingOut}>
                        {loggingOut ? "正在退出……" : "退出登录"}
                    </button>
                </div>
            </header>
            {message ? <p role="status" aria-live="polite">{message}</p> : null}
            <section id="protectedContent">{children}</section>
            {pathname !== "/tutor" && <Suspense fallback={null}><TutorCat key={user.userId} userId={user.userId} /></Suspense>}
        </main>
    );
}
