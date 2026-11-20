"use client";

import { useEffect, useRef, useState } from "react";
import Link from "next/link";
import { useRouter, useSearchParams } from "next/navigation";
import { useAuth } from "@/components/auth/AuthProvider";
import { ApiError } from "@/lib/api";
import { HistoryQuestion } from "./HistoryQuestion";
import "./history.css";

const PAGE_SIZE = 15;

function historyLink({ page, filter, sort }, recordId = null) {
    const params = new URLSearchParams();
    if (recordId !== null) params.set("record", String(recordId));
    if (page > 1) params.set("page", String(page));
    if (filter) params.set("correct", filter);
    if (sort === "asc") params.set("sort", sort);
    return params.size ? "/history?" + params : "/history";
}

export function HistoryPage() {
    const params = useSearchParams();
    const record = params.get("record");
    const rawPage = Number(params.get("page"));
    const initial = {
        page: Number.isInteger(rawPage) && rawPage > 0 && rawPage <= 2147483647 ? rawPage : 1,
        filter: ["true", "false"].includes(params.get("correct")) ? params.get("correct") : "",
        sort: params.get("sort") === "asc" ? "asc" : "desc"
    };
    return record === null ? <HistoryList key={historyLink(initial)} initial={initial} />
        : <HistoryQuestion key={record} recordId={record} backHref={historyLink(initial)} />;
}

function HistoryList({ initial }) {
    const { request } = useAuth();
    const router = useRouter();
    const [records, setRecords] = useState([]);
    const [total, setTotal] = useState(0);
    const [page, setPage] = useState(initial.page);
    const [totalPages, setTotalPages] = useState(0);
    const [filter, setFilter] = useState(initial.filter);
    const [sort, setSort] = useState(initial.sort);
    const [loading, setLoading] = useState(false);
    const [status, setStatus] = useState("");
    const [retryVisible, setRetryVisible] = useState(false);
    const [pageInfo, setPageInfo] = useState("尚未加载");
    const loadingRef = useRef(false);
    const hasLoadedRef = useRef(false);
    const lastRequestRef = useRef({ page: initial.page, correct: initial.filter, sort: initial.sort });
    const mountedRef = useRef(true);
    const startedRef = useRef(false);

    async function load(requestedPage, requestedFilter = filter, requestedSort = sort) {
        if (loadingRef.current || requestedPage < 1) return;
        loadingRef.current = true;
        lastRequestRef.current = { page: requestedPage, correct: requestedFilter, sort: requestedSort };
        setLoading(true);
        setRetryVisible(false);
        setStatus("正在加载答题记录……");

        try {
            const params = new URLSearchParams({
                page: String(requestedPage),
                size: String(PAGE_SIZE),
                sort: requestedSort
            });
            if (requestedFilter !== "") params.set("correct", requestedFilter);

            const data = await request("/api/answers?" + params);
            if (!Array.isArray(data.records)) throw new Error("接口返回的 records 不是数组");
            if (!mountedRef.current) return;

            setRecords(data.records);
            setTotal(data.total);
            setPage(data.page);
            setTotalPages(data.totalPages);
            setFilter(requestedFilter);
            setSort(requestedSort);
            hasLoadedRef.current = true;
            setPageInfo(data.total === 0
                ? "共 0 条记录"
                : `第 ${data.page} / ${data.totalPages} 页，共 ${data.total} 条`);
            setStatus("");
        } catch (error) {
            if (!mountedRef.current) return;
            if (error instanceof ApiError && error.status === 401) {
                setRecords([]);
                hasLoadedRef.current = false;
                return;
            }
            const reason = error instanceof TypeError
                ? "无法连接服务，请检查网络或服务是否正常运行。"
                : error.message;
            const previousResultHint = hasLoadedRef.current
                ? "下方仍显示上次成功加载的结果。"
                : "目前尚未获取到记录。";
            setStatus(`加载失败：${reason} ${previousResultHint} 请检查后点击“重新加载”。`);
            setRetryVisible(true);
        } finally {
            loadingRef.current = false;
            if (mountedRef.current) setLoading(false);
        }
    }

    useEffect(() => {
        mountedRef.current = true;
        if (!startedRef.current) {
            startedRef.current = true;
            void load(initial.page, initial.filter, initial.sort);
        }
        return () => { mountedRef.current = false; };
        // 首次加载一次；后续由按钮触发。
        // eslint-disable-next-line react-hooks/exhaustive-deps
    }, []);

    function emptyMessage() {
        if (total > 0) return "这一页没有记录，请返回上一页或切换筛选。";
        if (filter === "false") return "当前没有答错的记录。";
        if (filter === "true") return "当前没有答对的记录。";
        return "还没有答题记录，请先去答题练习完成一次作答。";
    }

    return (
        <>
            <div className="history-heading">
                <h1>答题记录</h1>

                <div className="history-filters" role="group" aria-label="筛选答题记录">
                    {[["", "全部"], ["false", "答错记录"], ["true", "答对记录"]].map(([value, label]) => (
                        <button key={value} type="button" data-correct={value}
                            aria-pressed={filter === value} disabled={loading}
                            onClick={() => void load(1, value)}>
                            {label}
                        </button>
                    ))}
                </div>

                <div id="historySortControl" className="history-sort" data-loading={String(loading)}>
                    <button id="historySortButton" className="history-sort-trigger" type="button" disabled={loading}>
                        排序：<span id="historySortLabel">{sort === "asc" ? "正序" : "倒序"}</span>
                        <span aria-hidden="true">▾</span>
                    </button>
                    <div className="history-sort-options" role="group" aria-label="选择排序方向">
                        {[["asc", "正序"], ["desc", "倒序"]].map(([value, label]) => (
                            <button key={value} type="button" data-sort={value}
                                aria-pressed={sort === value} disabled={loading}
                                onClick={() => void load(1, filter, value)}>
                                {label}
                            </button>
                        ))}
                    </div>
                </div>
            </div>

            {status && <p id="historyStatus" role="status" aria-live="polite">{status}</p>}
            {retryVisible ? (
                <button type="button" onClick={() => {
                    const last = lastRequestRef.current;
                    void load(last.page, last.correct, last.sort);
                }}>重新加载</button>
            ) : null}
            <div className="history-table-wrap">
                <table className="history-table">
                    <thead>
                        <tr>
                            <th scope="col">题目</th>
                            <th scope="col">所选答案</th>
                            <th scope="col">判分结果</th>
                            <th scope="col">提交时间</th>
                        </tr>
                    </thead>
                    <tbody id="historyBody">
                        {records.length === 0 ? (
                            <tr><td colSpan="4">{emptyMessage()}</td></tr>
                        ) : records.map(record => {
                            const href = historyLink({ page, filter, sort }, record.recordId);
                            const content = typeof record.content === "string" && record.content.trim()
                                ? record.content.replace(/\s+/g, " ").trim() : "题目内容暂不可用";
                            // 按 Unicode 码点截取，避免把题目中的 emoji 等字符切成半个代理对。
                            const characters = Array.from(content);
                            const preview = characters.length > 10 ? characters.slice(0, 10).join("") + "…" : content;
                            return <tr key={record.recordId} className="history-record-row" onClick={event => {
                                // 表格单元格也能点击；题目链接保留键盘和浏览器原生导航。
                                if (!event.target.closest("a") && !event.ctrlKey && !event.metaKey && !event.shiftKey && !event.altKey
                                    && !window.getSelection()?.toString()) router.push(href);
                            }}>
                                <td><Link className="ll-inline-link history-question-link" href={href} title={content}
                                    aria-label={`查看题目：${content}`}>{preview}</Link></td>
                                <td>{record.selectedOption}</td>
                                <td>{record.correct ? "正确" : "错误"}</td>
                                <td>{record.submittedAt}</td>
                            </tr>;
                        })}
                    </tbody>
                </table>
            </div>

            <div className="history-pagination">
                <button type="button" disabled={loading || page <= 1 || totalPages === 0}
                    onClick={() => void load(page - 1)}>上一页</button>
                <span id="historyPageInfo">{pageInfo}</span>
                <button type="button" disabled={loading || page >= totalPages}
                    onClick={() => void load(page + 1)}>下一页</button>
            </div>
        </>
    );
}
