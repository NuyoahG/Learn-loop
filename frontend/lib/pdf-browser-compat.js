export function ensurePdfAbortSignalAny() {
    // PDF.js 的兼容构建仍直接调用 AbortSignal.any，Edge 114 缺少这个 DOM API。
    if (typeof AbortSignal.any === "function") return;
    Object.defineProperty(AbortSignal, "any", {
        configurable: true,
        writable: true,
        value(signals) {
            const combined = new AbortController();
            for (const signal of signals) {
                if (signal.aborted) {
                    combined.abort(signal.reason);
                    break;
                }
                // 合成信号取消时也移除其余输入监听器，避免随 PDF 翻页堆积。
                signal.addEventListener("abort", () => combined.abort(signal.reason),
                    { once: true, signal: combined.signal });
            }
            return combined.signal;
        }
    });
}
