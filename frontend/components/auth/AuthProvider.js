"use client";

import { createContext, useCallback, useContext, useEffect, useMemo, useRef, useState } from "react";
import { ApiError, apiRequest, clearCsrf, refreshCsrf, setExpectedUser } from "@/lib/api";

const AuthContext = createContext(null);

export function useAuth() {
    const value = useContext(AuthContext);
    if (!value) throw new Error("useAuth must be used inside AuthProvider");
    return value;
}

export function AuthProvider({ children }) {
    const [state, setState] = useState({ status: "checking", user: null, message: "" });
    const [loggingOut, setLoggingOut] = useState(false);
    const userRef = useRef(null);
    const checkingRef = useRef(false);
    const versionRef = useRef(0);

    const expire = useCallback((signedOut = false) => {
        versionRef.current++;
        clearCsrf();
        setExpectedUser(null);
        userRef.current = null;
        setState({ status: "unauthenticated", user: null, message: "", signedOut });
    }, []);

    const check = useCallback(async ({ initial = false } = {}) => {
        if (checkingRef.current) return;
        checkingRef.current = true;
        const version = ++versionRef.current;
        if (initial) setState(current => ({ ...current, status: "checking", message: "正在确认登录状态……" }));
        try {
            const user = await apiRequest("/api/auth/me");
            if (version !== versionRef.current) return;
            if (!user || !Number.isSafeInteger(user.userId) || user.userId <= 0 || typeof user.username !== "string") {
                throw new Error("登录响应不完整");
            }
            if (userRef.current && userRef.current.userId !== user.userId) {
                // 另一个标签页切换了账号，整页重载清空旧账号的个人状态。
                window.location.reload();
                return;
            }
            userRef.current = user;
            setExpectedUser(user.userId);
            setState({ status: "authenticated", user, message: "" });
        } catch (error) {
            if (version !== versionRef.current) return;
            if (error instanceof ApiError && error.status === 401) {
                expire();
            } else if (initial) {
                setState({ status: "error", user: null, message: "暂时无法确认登录状态，请检查服务后重试。" });
            }
        } finally {
            checkingRef.current = false;
        }
    }, [expire]);

    const request = useCallback(async (path, options = {}) => {
        try {
            return await apiRequest(path, options);
        } catch (error) {
            if (error instanceof ApiError && error.status === 401) expire();
            throw error;
        }
    }, [expire]);

    const logout = useCallback(async () => {
        if (loggingOut) return;
        setLoggingOut(true);
        try {
            // 退出也是 POST；先取当前会话的最新令牌。
            await refreshCsrf();
            await apiRequest("/api/auth/logout", { method: "POST" });
            // 主动退出后，下次登录走默认入口；会话意外过期仍保留原路径。
            expire(true);
        } catch (error) {
            if (error instanceof ApiError && error.status === 401) {
                expire(true);
            } else {
                await check();
                setState(current => ({ ...current, message: "未确认退出成功，请刷新页面后检查登录状态。" }));
            }
        } finally {
            setLoggingOut(false);
        }
    }, [check, expire, loggingOut]);

    useEffect(() => {
        void check({ initial: true });
    }, [check]);

    useEffect(() => {
        const recheck = () => { if (userRef.current) void check(); };
        const onVisibility = () => { if (!document.hidden) recheck(); };
        window.addEventListener("focus", recheck);
        document.addEventListener("visibilitychange", onVisibility);
        return () => {
            window.removeEventListener("focus", recheck);
            document.removeEventListener("visibilitychange", onVisibility);
        };
    }, [check]);

    const value = useMemo(() => ({
        ...state, loggingOut, request, check, logout, expire
    }), [state, loggingOut, request, check, logout, expire]);

    return <AuthContext.Provider value={value}>{children}</AuthContext.Provider>;
}
