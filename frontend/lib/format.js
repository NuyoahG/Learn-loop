import { sourceLabel } from "./materials-model.js";

export const difficultyLabels = { EASY: "简单", MEDIUM: "适中", HARD: "困难" };
export const confidenceValues = new Set(["UNSURE", "SOMEWHAT_SURE", "VERY_SURE"]);
export const exclusionLabels = {
    ASSISTED: "已查看答案，本次作为辅助练习保存，不计入独立学习证据。",
    PREVIOUSLY_ANSWERED: "这道题已作答过，本次不重复计算掌握度。",
    REPEATED_QUESTION: "重复题目不重复计入独立学习证据。",
    FAMILY_DAILY_CAP: "同类题今日已有学习证据，本次练习已保存。"
};

export function dateLabel(value, fallback = "时间未知") {
    return value ? new Date(value).toLocaleString("zh-CN", { hour12: false }) : fallback;
}

export function locatorLabel(value, fallback = "来源位置") {
    if (typeof value === "string") {
        try { return sourceLabel(JSON.parse(value)); } catch { return fallback; }
    }
    if (value && typeof value === "object") return sourceLabel(value);
    return fallback;
}

export function reason(error) {
    return error instanceof TypeError ? "无法连接服务，请检查网络或服务状态。" : error.message;
}
