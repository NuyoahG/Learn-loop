"use client";

import { useEffect } from "react";
import { useRouter } from "next/navigation";
import { useAuth } from "./AuthProvider";
import { AppShell } from "./AppShell";

export function ProtectedGate({ children }) {
    const { status, user, message, check, logout, loggingOut, signedOut } = useAuth();
    const router = useRouter();

    useEffect(() => {
        if (status !== "unauthenticated") return;
        const next = window.location.pathname + window.location.search;
        router.replace(signedOut ? "/login" : `/login?next=${encodeURIComponent(next)}`);
    }, [status, signedOut, router]);

    if (status === "checking") {
        return (
            <main className="practice-page">
                <p role="status" aria-live="polite">正在确认登录状态……</p>
            </main>
        );
    }
    if (status === "error") {
        return (
            <main className="practice-page">
                <p role="status" aria-live="polite">{message}</p>
                <button type="button" onClick={() => void check({ initial: true })}>重新检查登录状态</button>
            </main>
        );
    }
    if (status !== "authenticated" || !user) {
        return (
            <main className="practice-page">
                <p role="status" aria-live="polite">正在跳转登录……</p>
            </main>
        );
    }
    return (
        <AppShell user={user} onLogout={logout} loggingOut={loggingOut} message={message}>
            {children}
        </AppShell>
    );
}
