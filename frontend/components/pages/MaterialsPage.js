"use client";

import { useEffect, useRef, useState } from "react";
import Link from "next/link";
import { useAuth } from "@/components/auth/AuthProvider";
import { ApiError } from "@/lib/api";
import {
    DOCUMENT_FILE_MIB, TEXT_FILE_MIB, UploadTickets, failureAdvice, fileSize, sourceLabel, stateLabels, validateFile
} from "@/lib/materials-model";

const PAGE_SIZE = 5;

function dateLabel(value) {
    return new Date(value).toLocaleString("zh-CN", { hour12: false });
}

export function MaterialsPage({ navigation }) {
    const { request } = useAuth();
    const inputRef = useRef(null);
    const ticketsRef = useRef(null);
    if (!ticketsRef.current) ticketsRef.current = new UploadTickets();

    const [selectedFileName, setSelectedFileName] = useState("尚未选择文件。");
    const [selectedFile, setSelectedFile] = useState(null);
    const [ticket, setTicket] = useState(null);
    const [uploadBusy, setUploadBusy] = useState(false);
    const [uploadStatus, setUploadStatus] = useState("");

    const [listRecords, setListRecords] = useState([]);
    const [listPage, setListPage] = useState(1);
    const [listPages, setListPages] = useState(0);
    const [listTotal, setListTotal] = useState(0);
    const [listLoading, setListLoading] = useState(false);
    const [listStatus, setListStatus] = useState("");
    const [listLoaded, setListLoaded] = useState(false);

    const [selectedId, setSelectedId] = useState(null);
    const [selected, setSelected] = useState(null);
    const [readerMessage, setReaderMessage] = useState("从资料列表选择一份，查看处理状态与提取的正文。");
    const [detailStatus, setDetailStatus] = useState("");
    const [refreshBusy, setRefreshBusy] = useState(false);
    const [starting, setStarting] = useState(false);

    const [blocks, setBlocks] = useState([]);
    const [blocksPage, setBlocksPage] = useState(1);
    const [blocksPages, setBlocksPages] = useState(0);
    const [blocksTotal, setBlocksTotal] = useState(0);
    const [blocksLoaded, setBlocksLoaded] = useState(false);

    const mountedRef = useRef(true);
    const startedRef = useRef(false);
    const requestsRef = useRef(new Set());
    const listSeqRef = useRef(0);
    const detailSeqRef = useRef(0);
    const blocksSeqRef = useRef(0);
    const fileSeqRef = useRef(0);
    const listPageRef = useRef(1);
    const listPagesRef = useRef(0);
    const recordsRef = useRef([]);
    const selectedIdRef = useRef(null);
    const selectedRef = useRef(null);
    const ticketRef = useRef(null);
    const fileRef = useRef(null);
    const uploadBusyRef = useRef(false);
    const startingRef = useRef(false);
    const pollTimerRef = useRef(null);
    const pollingRef = useRef(false);
    const pollFailedRef = useRef(false);

    function cancelled(error) {
        return error?.name === "AbortError";
    }

    function reason(error) {
        return error instanceof TypeError ? "无法连接服务，请检查网络或服务状态。" : error.message;
    }

    function stopPoll() {
        clearTimeout(pollTimerRef.current);
        pollTimerRef.current = null;
    }

    function perform(path, options = {}) {
        if (!mountedRef.current) return Promise.reject(new DOMException("Page inactive", "AbortError"));
        const controller = new AbortController();
        requestsRef.current.add(controller);
        return request(path, { ...options, signal: controller.signal })
            .finally(() => { requestsRef.current.delete(controller); });
    }

    function clearPreview() {
        blocksSeqRef.current++;
        setBlocks([]);
        setBlocksPage(1);
        setBlocksPages(0);
        setBlocksTotal(0);
        setBlocksLoaded(false);
    }

    function clearReader() {
        clearPreview();
        selectedRef.current = null;
        setSelected(null);
        setDetailStatus("");
    }

    function applyDetail(material) {
        selectedRef.current = material;
        setSelected(material);
    }

    async function loadBlocks(page = 1) {
        const material = selectedRef.current;
        const id = selectedIdRef.current;
        if (!material || material.parseStatus !== "PARSED" || !id) return;
        const sequence = ++blocksSeqRef.current;
        setBlocks([]);
        setBlocksLoaded(false);
        setDetailStatus("正在读取正文……");
        try {
            const data = await perform(`/api/materials/${id}/blocks?page=${page}&size=${PAGE_SIZE}`);
            if (!mountedRef.current || id !== selectedIdRef.current || sequence !== blocksSeqRef.current) return;
            if (!Array.isArray(data.records)) throw new Error("正文响应不完整。");
            setBlocks(data.records);
            setBlocksPage(data.page);
            setBlocksPages(data.totalPages);
            setBlocksTotal(data.total);
            setBlocksLoaded(true);
            setDetailStatus(data.records.length ? "" : "这一页没有正文。");
        } catch (error) {
            if (!mountedRef.current || cancelled(error) || id !== selectedIdRef.current ||
                sequence !== blocksSeqRef.current) return;
            setDetailStatus("正文读取失败：" + reason(error) + " 点击“重新查询状态”重试。");
        }
    }

    async function readDetail(fromPoll = false) {
        const id = selectedIdRef.current;
        if (!id) return;
        const sequence = ++detailSeqRef.current;
        const previouslyParsed = selectedRef.current?.parseStatus === "PARSED";
        if (!fromPoll) {
            clearReader();
            setReaderMessage("正在读取资料……");
        }
        setRefreshBusy(true);
        try {
            const material = await perform(`/api/materials/${id}`);
            if (!mountedRef.current || id !== selectedIdRef.current || sequence !== detailSeqRef.current) return;
            if (material.materialId !== id || !stateLabels[material.parseStatus]) {
                throw new Error("资料详情响应不完整。");
            }
            applyDetail(material);
            setReaderMessage("");
            if (material.parseStatus === "PARSED" && (!fromPoll || !previouslyParsed)) await loadBlocks(1);
            else if (material.parseStatus !== "PARSED") clearPreview();
        } catch (error) {
            if (!mountedRef.current || cancelled(error) || id !== selectedIdRef.current ||
                sequence !== detailSeqRef.current) return;
            clearReader();
            pollFailedRef.current = true;
            setReaderMessage("资料读取失败：" + reason(error) + " 请重新选择资料或刷新列表。");
        } finally {
            if (mountedRef.current && id === selectedIdRef.current && sequence === detailSeqRef.current) {
                setRefreshBusy(false);
                schedulePoll();
            }
        }
    }

    async function loadList(page = listPageRef.current, quiet = false) {
        if (!mountedRef.current) return;
        const sequence = ++listSeqRef.current;
        setListLoading(true);
        if (!quiet) setListStatus("正在加载资料……");
        try {
            const data = await perform(`/api/materials?page=${page}&size=${PAGE_SIZE}`);
            if (!mountedRef.current || sequence !== listSeqRef.current) return;
            if (!Array.isArray(data.records)) throw new Error("资料列表响应不完整。");
            listPageRef.current = data.page;
            listPagesRef.current = data.totalPages;
            recordsRef.current = data.records;
            setListPage(data.page);
            setListPages(data.totalPages);
            setListTotal(data.total);
            setListRecords(data.records);
            setListLoaded(true);
            setListStatus(data.records.length ? "只显示当前账号的资料。"
                : data.total ? "这一页没有资料，请返回上一页。" : "还没有资料，先上传一份学习材料。");
        } catch (error) {
            if (!mountedRef.current || cancelled(error) || sequence !== listSeqRef.current) return;
            pollFailedRef.current = true;
            setListStatus("列表加载失败：" + reason(error) + " 请点击“刷新列表”。");
        } finally {
            if (mountedRef.current && sequence === listSeqRef.current) {
                setListLoading(false);
                schedulePoll();
            }
        }
    }

    async function selectMaterial(id) {
        selectedIdRef.current = id;
        setSelectedId(id);
        pollFailedRef.current = false;
        clearReader();
        await readDetail();
    }

    async function startParse(id) {
        if (!mountedRef.current || startingRef.current) return;
        startingRef.current = true;
        setStarting(true);
        try {
            const material = await perform(`/api/materials/${id}/parse`, { method: "POST" });
            if (!mountedRef.current) return;
            if (selectedIdRef.current === id) {
                applyDetail(material);
                clearPreview();
                if (material.parseStatus === "PARSED") await loadBlocks(1);
            }
            pollFailedRef.current = false;
            await loadList(listPageRef.current, true);
        } catch (error) {
            if (!mountedRef.current || cancelled(error)) return;
            if (selectedIdRef.current === id) {
                // POST 的结果可能不明；只用 GET 确认事实，不自动重复 POST。
                await readDetail();
                if (mountedRef.current && selectedIdRef.current === id) {
                    setDetailStatus("原件已保存。未确认本次解析请求：" + reason(error) + " 请先查询状态，再按需继续解析。");
                }
            }
        } finally {
            startingRef.current = false;
            if (mountedRef.current) {
                setStarting(false);
                schedulePoll();
            }
        }
    }

    // 只有可见列表或详情有 PARSING 时才查询，串行 timeout 避免慢请求叠成多个 interval。
    function schedulePoll() {
        stopPoll();
        if (!mountedRef.current || document.hidden || pollingRef.current || pollFailedRef.current) return;
        const parsing = selectedRef.current?.parseStatus === "PARSING" ||
            recordsRef.current.some(material => material.parseStatus === "PARSING");
        if (parsing) pollTimerRef.current = setTimeout(() => { void poll(); }, 2500);
    }

    async function poll() {
        if (!mountedRef.current || pollingRef.current) return;
        pollingRef.current = true;
        try {
            const tasks = [loadList(listPageRef.current, true)];
            if (selectedRef.current?.parseStatus === "PARSING") tasks.push(readDetail(true));
            await Promise.all(tasks);
        } finally {
            pollingRef.current = false;
            schedulePoll();
        }
    }

    useEffect(() => {
        mountedRef.current = true;
        if (!startedRef.current) {
            startedRef.current = true;
            void loadList(1);
            const manage = Number(new URLSearchParams(window.location.search).get("manage"));
            if (Number.isSafeInteger(manage) && manage > 0) void selectMaterial(manage);
        }
        const onVisibility = () => {
            if (document.hidden) stopPoll();
            else { pollFailedRef.current = false; schedulePoll(); }
        };
        document.addEventListener("visibilitychange", onVisibility);
        return () => {
            mountedRef.current = false;
            // Strict Mode 会先卸载再挂载 effect；允许第二次挂载重新发起首次列表请求。
            startedRef.current = false;
            document.removeEventListener("visibilitychange", onVisibility);
            stopPoll();
            for (const controller of requestsRef.current) controller.abort();
            requestsRef.current.clear();
        };
        // 首次加载一次；后续由按钮和轮询触发。
        // eslint-disable-next-line react-hooks/exhaustive-deps
    }, []);

    async function onFileChange(event) {
        const sequence = ++fileSeqRef.current;
        const file = event.target.files[0] ?? null;
        fileRef.current = file;
        setSelectedFile(file);
        ticketRef.current = null;
        setTicket(null);
        setSelectedFileName(file ? `${file.name} · ${fileSize(file.size)}` : "尚未选择文件。");

        const problem = validateFile(file);
        setUploadStatus(problem || "正在确认文件内容……");
        if (problem) {
            fileRef.current = null;
            setSelectedFile(null);
            return;
        }
        try {
            const selectedTicket = await ticketsRef.current.select(file);
            if (sequence !== fileSeqRef.current || !mountedRef.current) return;
            ticketRef.current = selectedTicket;
            setTicket(selectedTicket);
            setUploadStatus(selectedTicket.materialId
                ? "这份文件已上传，点击下方按钮查看原资料。"
                : selectedTicket.uncertain
                    ? "上次上传结果不明。先刷新列表核对，也可手动使用原请求重试，不会覆盖旧资料。"
                    : "已准备好，点击“上传并解析”。");
        } catch {
            if (sequence === fileSeqRef.current && mountedRef.current) {
                setUploadStatus("暂时无法确认文件，请重新选择后重试。");
            }
        }
    }

    async function onUpload(event) {
        event.preventDefault();
        const file = fileRef.current, attempt = ticketRef.current;
        if (uploadBusyRef.current || !file || !attempt) return;
        if (attempt.materialId) {
            await selectMaterial(attempt.materialId);
            return;
        }

        uploadBusyRef.current = true;
        setUploadBusy(true);
        setUploadStatus("正在保存原件，请稍候……");
        // 从发出 POST 起就按“结果可能不明”处理；取消浏览器请求不能撤销服务端保存。
        attempt.uncertain = true;
        try {
            const body = new FormData();
            body.append("file", file);
            body.append("requestId", attempt.requestId);
            const material = await perform("/api/materials", { method: "POST", body });
            if (!Number.isSafeInteger(material.materialId) || material.materialId <= 0) {
                throw new Error("上传响应不完整。");
            }
            attempt.materialId = material.materialId;
            attempt.uncertain = false;
            if (!mountedRef.current) return;
            setUploadStatus("原件已保存，正在请求解析；如果解析失败，可在资料详情继续处理。");
            await selectMaterial(material.materialId);
            await startParse(material.materialId);
            if (mountedRef.current) {
                setUploadStatus("原件已保存，请在资料详情查看状态；服务问题修复后可继续解析，无须重新上传。");
                await loadList(1, true);
            }
        } catch (error) {
            if (!mountedRef.current || cancelled(error)) return;
            if (error instanceof ApiError && error.status >= 400 && error.status < 500) {
                attempt.uncertain = false;
            }
            setUploadStatus(attempt.materialId
                ? "原件已保存，后续查询暂未完成，请刷新列表继续。"
                : attempt.uncertain
                    ? "未确认上传结果，原请求编号已保留。请先刷新列表核对；需要时点击“使用原请求重试上传”。"
                    : reason(error));
        } finally {
            uploadBusyRef.current = false;
            if (mountedRef.current) setUploadBusy(false);
        }
    }

    const parseAvailable = selected && (selected.parseStatus === "UPLOADED" || selected.parseStatus === "FAILED");
    let advice = "";
    if (selected?.parseStatus === "UPLOADED") advice = "原件已保存，点击“开始解析”提取正文。";
    else if (selected?.parseStatus === "FAILED") {
        advice = `${selected.errorMessage} ${failureAdvice(selected.errorCode).text}`;
    } else if (selected?.parseStatus === "PARSING") {
        advice = "正在提取正文，页面会自动更新。离开页面后服务器仍会继续处理。";
    } else if (selected?.parseStatus === "PARSED") {
        advice = `共 ${selected.blockCount} 个正文块、${selected.textCharCount} 字符。可在学习工作台选择范围出题。`;
    }

    const uploadLabel = uploadBusy ? "正在上传……"
        : ticket?.materialId ? "查看已上传资料"
            : ticket?.uncertain ? "使用原请求重试上传" : "上传并解析";

    return (
        <div className="materials-page">
            <header className="personal-center-heading">
                <h1>我的资料</h1>
                {navigation}
            </header>
            <section id="uploadSection" className="material-upload" aria-labelledby="uploadHeading">
                <h2 id="uploadHeading">上传一份资料</h2>
                <p id="fileLimits" className="input-hint">TXT / Markdown：UTF-8，最多 {TEXT_FILE_MIB} MiB；Word DOCX / 文字型 PDF：最多 {DOCUMENT_FILE_MIB} MiB。每次一份。</p>
                <form id="uploadForm" onSubmit={onUpload}>
                    <fieldset id="uploadFields" className="upload-controls" disabled={uploadBusy}>
                        <legend>选择文件</legend>
                        <label htmlFor="materialFile">学习资料</label>
                        <input ref={inputRef} id="materialFile" type="file"
                            accept=".txt,.md,.docx,.pdf" aria-describedby="fileLimits selectedFileName"
                            onChange={onFileChange} />
                        <button id="uploadButton" type="submit"
                            disabled={uploadBusy || !selectedFile || !ticket}>{uploadLabel}</button>
                    </fieldset>
                </form>
                <p id="selectedFileName" className="input-hint">{selectedFileName}</p>
                <p id="uploadStatus" role="status" aria-live="polite">{uploadStatus}</p>
                <details className="format-boundaries">
                    <summary>支持范围与内容限制</summary>
                    <p>当前没有 OCR，不识别扫描件或图片中的文字，不支持加密 PDF、旧版 .doc。Word 只提取正文和基础表格，图片、文本框、脚注与公式可能遗漏；PDF 多栏、表格和公式的顺序需要人工核对。</p>
                    <p>每份最多 100 页 PDF、200000 字符、2000 个正文块；超限请拆分上传。原件与解析正文只读，修改内容请上传新资料。</p>
                </details>
            </section>

            <div className="materials-workspace">
                <section className="material-library" aria-labelledby="libraryHeading">
                    <div className="section-toolbar">
                        <h2 id="libraryHeading">资料列表</h2>
                        <button id="refreshMaterials" type="button" disabled={listLoading}
                            onClick={() => { pollFailedRef.current = false; void loadList(listPageRef.current); }}>
                            刷新列表
                        </button>
                    </div>
                    <p id="listStatus" role="status" aria-live="polite">{listStatus}</p>
                    <ul id="materialsList" className="material-list">
                        {listRecords.map(material => (
                            <li key={material.materialId}>
                                <Link className="material-card" href={`/reading?materialId=${material.materialId}`}>
                                    <strong>{material.originalName}</strong>
                                    <span className="material-card-meta">
                                        <span className="material-badge" data-state={material.parseStatus}>
                                            {stateLabels[material.parseStatus] ?? "状态未知"}
                                        </span>
                                        <small>{material.fileType.toUpperCase()} · {fileSize(material.sizeBytes)}</small>
                                        {material.warnings.length ? (
                                            <span className="warning-mark">有 {material.warnings.length} 条提取提示</span>
                                        ) : null}
                                    </span>
                                    <small>创建于 {dateLabel(material.createdAt)}</small>
                                </Link>
                                <button type="button" className="secondary material-manage"
                                    aria-label={`${material.originalName} 的详情与解析`}
                                    aria-pressed={material.materialId === selectedId}
                                    onClick={() => void selectMaterial(material.materialId)}>详情与解析</button>
                            </li>
                        ))}
                    </ul>
                    <div className="history-pagination">
                        <button type="button" disabled={listLoading || listPage <= 1}
                            onClick={() => void loadList(listPage - 1)}>资料上一页</button>
                        <span id="materialsPageInfo">
                            {listLoaded
                                ? listTotal ? `第 ${listPage} / ${listPages} 页，共 ${listTotal} 份` : "共 0 份资料"
                                : "尚未加载"}
                        </span>
                        <button type="button" disabled={listLoading || listPage >= listPages}
                            onClick={() => void loadList(listPage + 1)}>资料下一页</button>
                    </div>
                </section>

                <section className="material-reader" aria-labelledby="readerHeading">
                    <h2 id="readerHeading">正文与来源</h2>
                    {!selected ? <p className="input-hint">{readerMessage}</p> : null}
                    {selected ? (
                        <div id="materialDetail">
                            <h3 id="materialName">{selected.originalName}</h3>
                            <p id="materialMeta" className="input-hint">
                                资料 #{selected.materialId} · {selected.fileType.toUpperCase()} · {fileSize(selected.sizeBytes)} · {dateLabel(selected.createdAt)}
                            </p>
                            <p id="materialState">{stateLabels[selected.parseStatus] ?? "状态未知"}</p>
                            <ul id="materialWarnings" className="material-warnings">
                                {selected.warnings.map((warning, index) => (
                                    <li key={index}>
                                        {warning.message}
                                        {warning.pageNumbers.length ? `（文件页号：${warning.pageNumbers.join("、")}）` : ""}
                                    </li>
                                ))}
                            </ul>
                            <p id="materialAdvice">{advice}</p>
                            <div className="reader-actions">
                                {selected.parseStatus === "PARSED" ? (
                                    <Link className="reader-entry" href={"/reading?materialId=" + encodeURIComponent(selected.materialId)}>打开阅读与问答 →</Link>
                                ) : null}
                                {parseAvailable ? (
                                    <button type="button" disabled={starting}
                                        onClick={() => selectedId && void startParse(selectedId)}>
                                        {selected.parseStatus === "FAILED" ? "重新解析" : "开始解析"}
                                    </button>
                                ) : null}
                                <button type="button" disabled={refreshBusy}
                                    onClick={() => { pollFailedRef.current = false; void readDetail(); }}>
                                    重新查询状态
                                </button>
                                <a className="ll-action-link" href="#uploadSection">上传其他资料</a>
                            </div>
                            {detailStatus && <p id="detailStatus" role="status" aria-live="polite">{detailStatus}</p>}
                            <div id="materialBlocks" className="material-blocks">
                                {blocks.map(block => (
                                    <article key={block.blockId} className="material-block">
                                        <h4>正文块 {block.blockNo}</h4>
                                        <p className="source">{sourceLabel(block.sourceLocator)}</p>
                                        <pre>{block.text}</pre>
                                    </article>
                                ))}
                            </div>
                            {blocksLoaded ? (
                                <div id="blocksPagination" className="history-pagination">
                                    <button type="button" disabled={blocksPage <= 1}
                                        onClick={() => void loadBlocks(blocksPage - 1)}>正文上一页</button>
                                    <span id="blocksPageInfo">
                                        第 {blocksPage} / {blocksPages} 页，共 {blocksTotal} 块
                                    </span>
                                    <button type="button" disabled={blocksPage >= blocksPages}
                                        onClick={() => void loadBlocks(blocksPage + 1)}>正文下一页</button>
                                </div>
                            ) : null}
                        </div>
                    ) : null}
                </section>
            </div>
        </div>
    );
}
