"use client";

import { useEffect, useState } from "react";

export function useTutorQuiet(userId) {
    const key = `learnloop:tutor-quiet:${userId}`;
    const [quiet, setQuiet] = useState(true);

    useEffect(() => {
        function read() {
            try { setQuiet(localStorage.getItem(key) === "true"); }
            catch { setQuiet(false); }
        }
        function sync(event) {
            if (event.key === key || event.key === null) read();
        }
        read();
        window.addEventListener("storage", sync);
        return () => window.removeEventListener("storage", sync);
    }, [key]);

    function saveQuiet(value) {
        setQuiet(value);
        try { localStorage.setItem(key, String(value)); }
        catch { /* 存储不可用时，当前页面仍可切换提醒。 */ }
    }
    return [quiet, saveQuiet];
}
