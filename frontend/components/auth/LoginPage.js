"use client";

import { useEffect, useRef, useState } from "react";
import { ApiError, apiRequest, clearCsrf, refreshCsrf } from "@/lib/api";
import { safeTarget } from "@/lib/auth-target";
import { lastReading } from "@/lib/reading-position";
import { AuthLanding } from "./AuthLanding";

export function LoginPage() {
    const usernameRef = useRef(null);
    const passwordRef = useRef(null);
    const startedRef = useRef(false);
    const [ready, setReady] = useState(false);
    const [busy, setBusy] = useState(false);
    const [message, setMessage] = useState("正在检查登录状态……");
    const targetRef = useRef(null);

    function continueTo(user) {
        window.location.replace(targetRef.current ?? (lastReading(user?.userId) ? "/reading" : "/workspace"));
    }

    useEffect(() => {
        if (startedRef.current) return;
        startedRef.current = true;
        const next = new URLSearchParams(window.location.search).get("next");
        targetRef.current = next ? safeTarget(next, window.location.origin) : null;

        apiRequest("/api/auth/me").then(user => {
            // 已登录时直接进入目标页面，不要求用户重复输入密码。
            continueTo(user);
        }).catch(error => {
            if (error instanceof ApiError && error.status === 401) {
                setReady(true);
                setMessage("请输入账号和密码。");
                usernameRef.current?.focus();
            } else {
                setMessage("暂时无法确认登录状态，请检查服务后重试。");
            }
        });
    }, []);

    async function submit(event) {
        event.preventDefault();
        if (busy || !ready) return;

        const username = usernameRef.current.value.trim();
        const password = passwordRef.current.value;
        if (!username || !password) {
            setMessage("请输入用户名和密码。");
            return;
        }

        setBusy(true);
        setMessage("正在登录……");
        let loginSucceeded = false;
        try {
            // 重新准备登录请求的令牌，避免复用其他会话遗留的值。
            clearCsrf();
            const body = new URLSearchParams({ username, password });
            await apiRequest("/api/auth/login", {
                method: "POST",
                headers: { "Content-Type": "application/x-www-form-urlencoded;charset=UTF-8" },
                body
            });

            loginSucceeded = true;
            clearCsrf();
            await refreshCsrf();
            // replace 不把已经完成的登录页留在后退路径中。
            continueTo(targetRef.current ? null : await apiRequest("/api/auth/me"));
        } catch (error) {
            if (loginSucceeded) {
                setMessage("登录已成功，但页面准备失败，请刷新页面后继续。");
            } else if (error instanceof ApiError && error.status === 401) {
                setMessage("用户名或密码不正确，请重新输入。");
            } else if (error instanceof TypeError) {
                setMessage("无法连接服务，请检查网络后重试。");
            } else {
                setMessage(error.message);
            }
        } finally {
            passwordRef.current.value = "";
            setBusy(false);
        }
    }

    return (
        <AuthLanding>
            <h1>登录 LearnLoop</h1>
            <p className="intro">登录后继续练习，查看自己的答题记录。</p>

            <form id="loginForm" method="post" action="/api/auth/login" onSubmit={submit}>
                <fieldset id="loginFields" className="login-fields" disabled={!ready || busy}>
                    <legend>账号信息</legend>
                    <label htmlFor="usernameInput">用户名</label>
                    <input ref={usernameRef} id="usernameInput" name="username" type="text"
                        required maxLength="32" autoComplete="username" />

                    <label htmlFor="passwordInput">密码</label>
                    <input ref={passwordRef} id="passwordInput" name="password" type="password"
                        required maxLength="128" autoComplete="current-password" />

                    <button id="loginButton" type="submit">{busy ? "登录中……" : "登录"}</button>
                </fieldset>
            </form>

            <p id="loginMessage" role="status" aria-live="polite">{message}</p>
            <p><a className="ll-action-link" href="/register">还没有账号？去注册</a></p>
        </AuthLanding>
    );
}
