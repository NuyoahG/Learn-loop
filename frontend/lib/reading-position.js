// 阅读位置只含编号和偏移，按已登录账号隔离。浏览器禁用存储时仍能正常阅读。
function storageKey(userId) {
    return "learnloop:last-reading:" + userId;
}

export function lastReading(userId) {
    if (!Number.isSafeInteger(userId) || userId < 1) return null;
    try {
        const value = JSON.parse(localStorage.getItem(storageKey(userId)));
        const positive = number => Number.isSafeInteger(number) && number > 0;
        if (value?.view === "original") {
            const position = value.position;
            const coordinate = number => Number.isFinite(number) && Math.abs(number) <= 100_000_000;
            return positive(value.materialId) && coordinate(position?.top) &&
                (position.page === undefined || (positive(position.page) && coordinate(position.left))) ? value : null;
        }
        return value && positive(value.materialId) && positive(value.parseAttempt)
            && positive(value.blockId) && positive(value.page) && value.page <= 2000
            && Number.isFinite(value.offset) && Math.abs(value.offset) <= 100_000 ? value : null;
    } catch {
        return null;
    }
}

export function rememberReading(userId, position) {
    try {
        const key = storageKey(userId);
        const value = JSON.stringify(position);
        if (localStorage.getItem(key) !== value) localStorage.setItem(key, value);
    } catch { /* 阅读位置为可选的本机状态。 */ }
}
