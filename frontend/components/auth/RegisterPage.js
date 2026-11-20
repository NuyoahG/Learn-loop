"use client";

import { useEffect, useRef, useState } from "react";
import { ApiError, apiRequest, clearCsrf } from "@/lib/api";
import { AuthLanding } from "./AuthLanding";

export function RegisterPage() {
    const usernameRef = useRef(null);
    const passwordRef = useRef(null);
    const confirmRef = useRef(null);
    const startedRef = useRef(false);
    const [ready, setReady] = useState(false);
    const [busy, setBusy] = useState(false);
    const [registered, setRegistered] = useState(false);
    const [message, setMessage] = useState("正在检查登录状态……");
    const [retrying, setRetrying] = useState(false);

    useEffect(() => {
        if (startedRef.current) return;
        startedRef.current = true;
        apiRequest("/api/auth/me").then(() => {
            // 已登录则继续使用当前账号，不在已有会话里再注册另一个账号。
            window.location.replace("/history");
        }).catch(error => {
            if (error instanceof ApiError && error.status === 401) {
                setReady(true);
                setMessage("请填写注册信息。");
                usernameRef.current?.focus();
            } else {
                // 网络错误不能直接当作“未登录”，先保持禁用并让用户重试。
                setMessage("暂时无法确认登录状态，请检查服务后重新检查。");
                setRetrying(true);
            }
        });
    }, []);

    async function submit(event) {
        event.preventDefault();
        if (busy || registered || !ready) return;

        const username = usernameRef.current.value;
        const password = passwordRef.current.value;
        const confirmation = confirmRef.current.value;

        if (!/^[A-Za-z0-9_]{3,32}$/.test(username)) {
            setMessage("用户名应为 3—32 位英文字母、数字或下划线。");
            usernameRef.current.focus();
            return;
        }
        if (password.trim() === "") {
            setMessage("密码不能为空或全为空白。");
            passwordRef.current.focus();
            return;
        }
        const codePoints = Array.from(password).length;
        if (codePoints < 8 || codePoints > 64) {
            setMessage("密码长度应为 8—64 个字符。");
            passwordRef.current.focus();
            return;
        }
        const utf8Bytes = new TextEncoder().encode(password).length;
        if (utf8Bytes > 72) {
            setMessage("密码的 UTF-8 长度超过 72 字节，请缩短后重试。");
            passwordRef.current.focus();
            return;
        }
        if (password !== confirmation) {
            setMessage("两次输入的密码不一致。");
            confirmRef.current.focus();
            return;
        }

        setBusy(true);
        setMessage("正在创建账号……");
        try {
            // 注册接口接收 JSON，与 Spring Security 的表单登录不同。
            const result = await apiRequest("/api/auth/register", {
                method: "POST",
                headers: { "Content-Type": "application/json" },
                body: JSON.stringify({ username, password })
            });
            if (!result || !Number.isSafeInteger(result.userId) ||
                result.userId <= 0 || typeof result.username !== "string") {
                throw new Error("注册响应不完整");
            }

            setRegistered(true);
            setMessage("账号 " + result.username + " 注册成功。请点击“已有账号，去登录”完成登录。");
            // 注册不自动登录。登录页将重新准备自己的 CSRF。
            clearCsrf();
        } catch (error) {
            if (error instanceof ApiError && error.status === 409) {
                setMessage("用户名已存在，请换一个；若这是你的账号，可以直接登录。");
            } else if (error instanceof ApiError && error.status === 400) {
                setMessage("注册信息不符合要求，请按上方规则检查用户名和密码。");
            } else if (error instanceof ApiError && error.status === 403) {
                setMessage("请求校验未通过，请刷新注册页后重试。");
            } else if (error instanceof ApiError && error.status === 401) {
                setMessage("暂时无法完成注册，请刷新页面检查状态后重试。");
            } else {
                // 服务端可能已创建账号；不自动重新发送 POST。
                setMessage("未确认注册结果，请先尝试登录；若账号已存在，请直接登录，不要连续重复提交。");
            }
        } finally {
            passwordRef.current.value = "";
            confirmRef.current.value = "";
            setBusy(false);
        }
    }

    return (
        <AuthLanding>
            <h1>注册 LearnLoop</h1>
            <p className="intro">创建账号，保存自己的学习记录。</p>

            <form id="registerForm" method="post" action="/api/auth/register" onSubmit={submit} hidden={registered}>
                <fieldset id="registerFields" className="login-fields" disabled={!ready || busy || registered}>
                    <legend>新账号信息</legend>

                    <label htmlFor="registerUsernameInput">用户名</label>
                    <input ref={usernameRef} id="registerUsernameInput" name="username" type="text"
                        required minLength="3" maxLength="32" pattern="[A-Za-z0-9_]{3,32}"
                        autoComplete="username" aria-describedby="usernameRule" />
                    <small id="usernameRule">3—32 位英文字母、数字或下划线，区分大小写，不含空格。</small>

                    <label htmlFor="registerPasswordInput">密码</label>
                    <input ref={passwordRef} id="registerPasswordInput" name="password" type="password"
                        required maxLength="128" autoComplete="new-password" aria-describedby="passwordRule" />
                    <small id="passwordRule">8—64 个字符，UTF-8 不超过 72 字节；含中文或 emoji 时字节数会增加。空格也计入密码。</small>

                    <label htmlFor="confirmPasswordInput">确认密码</label>
                    <input ref={confirmRef} id="confirmPasswordInput" type="password"
                        required maxLength="128" autoComplete="new-password" />

                    <button id="registerButton" type="submit">{registered ? "注册成功" : busy ? "注册中……" : "注册"}</button>
                </fieldset>
            </form>

            <p id="registerMessage" role="status" aria-live="polite">{message}</p>
            {retrying && !ready ? <button type="button" onClick={() => window.location.reload()}>重新检查</button> : null}

            <p><a className="ll-action-link" href="/login?next=%2Fhistory">已有账号，去登录</a></p>
        </AuthLanding>
    );
}
