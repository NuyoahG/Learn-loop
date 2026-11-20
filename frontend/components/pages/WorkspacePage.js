"use client";

import { generationFailureHint } from "../../lib/generation-diagnostics";

import { useEffect, useRef, useState } from "react";
import { useAuth } from "@/components/auth/AuthProvider";
import { ApiError } from "@/lib/api";
import { fileSize, stateLabels } from "@/lib/materials-model";
import {
    blockPage, codePoints, normalizeRecovery, pageRangeLabel, queryMaterialId, queryVersionId,
    splitBatches
} from "@/lib/workspace-model";
import { loadWorkspaceRecovery, saveWorkspaceRecovery } from "@/lib/tickets";
import { dateLabel, locatorLabel, reason } from "@/lib/format";
import { AnswerOptions, ConfidenceOptions } from "@/components/practice/AnswerOptions";
import { AnswerExplanation } from "@/components/practice/AnswerExplanation";
import { TutorFeedback } from "@/components/practice/TutorFeedback";
import { NoteEditor } from "@/components/practice/NoteEditor";
import { useAttemptSession } from "@/components/practice/useAttemptSession";

function validKey(value) {
    return /^[\x21-\x7e]{16,512}$/.test(value);
}

export function WorkspacePage({ navigation }) {
    const { request, user } = useAuth();

    const [settings, setSettings] = useState(null);
    const [settingsState, setSettingsState] = useState("loading");
    const [modelMessage, setModelMessage] = useState("");
    const [modelKey, setModelKey] = useState("");
    const [settingsBusy, setSettingsBusy] = useState(false);

    const [materials, setMaterials] = useState([]);
    const [materialsPage, setMaterialsPage] = useState(1);
    const [materialsPages, setMaterialsPages] = useState(0);
    const [materialsTotal, setMaterialsTotal] = useState(0);
    const [materialsLoading, setMaterialsLoading] = useState(false);
    const [listStatus, setListStatus] = useState("");

    const [material, setMaterial] = useState(null);
    const [selectedId, setSelectedId] = useState(null);
    const [detailStatus, setDetailStatus] = useState("");
    const [parseBusy, setParseBusy] = useState(false);
    const [refreshBusy, setRefreshBusy] = useState(false);

    const [rangeMode, setRangeMode] = useState("range");
    const [allBlocks, setAllBlocks] = useState([]);
    const [allBlocksLoaded, setAllBlocksLoaded] = useState(false);
    const [allBlocksLoading, setAllBlocksLoading] = useState(false);
    const [startPage, setStartPage] = useState("1");
    const [endPage, setEndPage] = useState("1");
    const [maxPage, setMaxPage] = useState(null);
    const [batches, setBatches] = useState([]);
    const [activeBatch, setActiveBatch] = useState(null);
    const [selectedBlocks, setSelectedBlocks] = useState(() => new Map());
    const [pageRangeStatus, setPageRangeStatus] = useState("");

    const [blockRecords, setBlockRecords] = useState([]);
    const [blocksPage, setBlocksPage] = useState(1);
    const [blocksPages, setBlocksPages] = useState(0);
    const [blocksTotal, setBlocksTotal] = useState(0);
    const [blocksLoading, setBlocksLoading] = useState(false);

    const [difficulty, setDifficulty] = useState("MEDIUM");
    const [generationMode, setGenerationMode] = useState("COVERAGE");
    const [granularity, setGranularity] = useState("MEDIUM");
    const [refreshCatalog, setRefreshCatalog] = useState(false);
    const [count, setCount] = useState(3);
    const [generationMessage, setGenerationMessage] = useState("");
    const [job, setJob] = useState(null);
    const [pendingRequest, setPendingRequest] = useState(null);
    const [recoveringJobId, setRecoveringJobId] = useState(null);
    const [generateBusy, setGenerateBusy] = useState(false);

    const [mastery, setMastery] = useState([]);
    const [masteryStatus, setMasteryStatus] = useState("");
    const [recommendations, setRecommendations] = useState([]);
    const [recommendStatus, setRecommendStatus] = useState("");
    const [recommendBusy, setRecommendBusy] = useState(false);

    const session = useAttemptSession({ mode: "workspace", materialId: material?.materialId });
    const practiceRef = useRef(null);
    const modelPanelRef = useRef(null);

    const mountedRef = useRef(true);
    const startedRef = useRef(false);
    const materialRef = useRef(null);
    const selectedIdRef = useRef(null);
    const rangeModeRef = useRef("range");
    const allBlocksRef = useRef([]);
    const allBlocksLoadedRef = useRef(false);
    const allBlocksLoadingRef = useRef(false);
    const selectedRef = useRef(new Map());
    const batchesRef = useRef([]);
    const activeBatchRef = useRef(null);
    const recordsRef = useRef([]);
    const blockRecordsRef = useRef([]);
    const listPageRef = useRef(1);
    const listPagesRef = useRef(0);
    const jobRef = useRef(null);
    const pendingRef = useRef(null);
    const recoveringRef = useRef(null);
    const parseBusyRef = useRef(false);
    const generateBusyRef = useRef(false);
    const settingsBusyRef = useRef(false);
    const recommendBusyRef = useRef(false);
    const sessionQuestionRef = useRef(null);

    const listSeqRef = useRef(0);
    const detailSeqRef = useRef(0);
    const blocksSeqRef = useRef(0);
    const allBlocksSeqRef = useRef(0);
    const jobSeqRef = useRef(0);
    const masterySeqRef = useRef(0);
    const materialTimerRef = useRef(null);
    const jobTimerRef = useRef(null);

    sessionQuestionRef.current = session.question;

    function syncMaterial(value) {
        materialRef.current = value;
        setMaterial(value);
    }

    function syncSelected(value) {
        selectedRef.current = value;
        setSelectedBlocks(value);
    }

    function syncBatches(value) {
        batchesRef.current = value;
        setBatches(value);
    }

    function syncActiveBatch(value) {
        activeBatchRef.current = value;
        setActiveBatch(value);
    }

    function syncJob(value) {
        jobRef.current = value;
        setJob(value);
    }

    function syncPending(value) {
        pendingRef.current = value;
        setPendingRequest(value);
    }

    function syncRecovering(value) {
        recoveringRef.current = value;
        setRecoveringJobId(value);
    }

    function saveRecovery() {
        const current = materialRef.current;
        if (!current) return;
        saveWorkspaceRecovery(user.userId, {
            materialId: current.materialId,
            jobId: jobRef.current?.jobId ?? null,
            pendingRequest: pendingRef.current
                ? { ...pendingRef.current, blockIds: [...pendingRef.current.blockIds] }
                : null,
            questionVersionId: sessionQuestionRef.current?.versionId ?? null
        });
    }

    function stopMaterialTimer() {
        clearTimeout(materialTimerRef.current);
        materialTimerRef.current = null;
    }

    function stopJobTimer() {
        clearTimeout(jobTimerRef.current);
        jobTimerRef.current = null;
    }

    function resetScope() {
        allBlocksSeqRef.current++;
        blocksSeqRef.current++;
        allBlocksRef.current = [];
        allBlocksLoadedRef.current = false;
        allBlocksLoadingRef.current = false;
        setAllBlocks([]);
        setAllBlocksLoaded(false);
        setAllBlocksLoading(false);
        blockRecordsRef.current = [];
        setBlockRecords([]);
        setBlocksPage(1);
        setBlocksPages(0);
        setBlocksTotal(0);
        setBlocksLoading(false);
        syncBatches([]);
        syncActiveBatch(null);
        syncSelected(new Map());
        setPageRangeStatus("");
    }

    function clearDetail() {
        stopMaterialTimer();
        detailSeqRef.current++;
        blocksSeqRef.current++;
        allBlocksSeqRef.current++;
        materialRef.current = null;
        setMaterial(null);
        selectedIdRef.current = null;
        setSelectedId(null);
        resetScope();
        blockRecordsRef.current = [];
        setBlockRecords([]);
        setBlocksPage(1);
        setBlocksPages(0);
        setBlocksTotal(0);
        setBlocksLoading(false);
        syncJob(null);
        syncPending(null);
        syncRecovering(null);
        setDetailStatus("");
        setGenerationMessage("");
        setMastery([]);
        setMasteryStatus("");
        setRecommendations([]);
        setRecommendStatus("");
        session.clear();
    }

    async function loadMaterials(page = listPageRef.current, quiet = false) {
        if (!mountedRef.current) return;
        const sequence = ++listSeqRef.current;
        setMaterialsLoading(true);
        if (!quiet) setListStatus("正在加载资料……");
        try {
            const data = await request(`/api/materials?page=${page}&size=5`);
            if (!mountedRef.current || sequence !== listSeqRef.current) return;
            if (!Array.isArray(data.records)) throw new Error("资料列表响应不完整。");
            listPageRef.current = data.page;
            listPagesRef.current = data.totalPages;
            recordsRef.current = data.records;
            setMaterials(data.records);
            setMaterialsPage(data.page);
            setMaterialsPages(data.totalPages);
            setMaterialsTotal(data.total);
            setListStatus(data.records.length ? "只显示当前账号的资料。"
                : data.total ? "这一页没有资料，请返回上一页。" : "还没有资料，请先到“我的资料”上传并解析。");
        } catch (error) {
            if (!mountedRef.current || sequence !== listSeqRef.current) return;
            setListStatus("列表加载失败：" + reason(error) + " 请点击“刷新列表”。");
        } finally {
            if (mountedRef.current && sequence === listSeqRef.current) setMaterialsLoading(false);
        }
    }

    async function selectMaterial(id, restored = null) {
        clearDetail();
        syncMaterial({ materialId: id });
        selectedIdRef.current = id;
        setSelectedId(id);
        let payload = restored;
        if (!payload) {
            const saved = normalizeRecovery(loadWorkspaceRecovery(user.userId));
            payload = saved && saved.materialId === id ? saved : null;
        }
        const sequence = ++detailSeqRef.current;
        setListStatus("正在读取资料……");
        try {
            const next = await request(`/api/materials/${id}`);
            if (!mountedRef.current || sequence !== detailSeqRef.current ||
                materialRef.current?.materialId !== id) return;
            if (next.materialId !== id) throw new Error("资料详情响应不完整。");
            syncMaterial(next);
            // PDF 默认按页选择；其他格式没有可靠页号，默认正文块。
            const mode = next.fileType === "pdf" ? "range" : "blocks";
            rangeModeRef.current = mode;
            setRangeMode(mode);
            setDetailStatus("");
            if (next.parseStatus === "PARSED") {
                if (mode === "blocks") void loadBlocks(1);
                else void loadAllBlocks();
                void loadMastery();
            } else if (next.parseStatus === "PARSING") {
                scheduleMaterialPoll();
            }

            if (payload?.pendingRequest && payload.pendingRequest.materialId === id) {
                syncPending(payload.pendingRequest);
                applyOptions(payload.pendingRequest);
                setGenerationMessage("上次出题请求尚未确认。可使用原请求继续，避免重复创建任务。");
            } else if (payload?.jobId) {
                syncRecovering(payload.jobId);
                await recoverJob();
            }
            if (payload?.questionVersionId) {
                await restoreQuestion(payload.questionVersionId);
            }
            saveRecovery();
        } catch (error) {
            if (!mountedRef.current || sequence !== detailSeqRef.current) return;
            setDetailStatus("资料读取失败：" + reason(error));
            clearDetail();
        } finally {
            if (mountedRef.current && sequence === detailSeqRef.current) setListStatus("");
        }
    }

    async function refreshMaterial() {
        const id = materialRef.current?.materialId;
        if (!id) return;
        const sequence = ++detailSeqRef.current;
        setRefreshBusy(true);
        try {
            const next = await request(`/api/materials/${id}`);
            if (!mountedRef.current || sequence !== detailSeqRef.current ||
                materialRef.current?.materialId !== id) return;
            const previous = materialRef.current;
            syncMaterial(next);
            if (previous?.parseAttempt !== next.parseAttempt) {
                // 重解析后旧正文块 ID 可能失效，不能继续用旧页码范围或旧批次。
                resetScope();
            }
            setDetailStatus("");
            if (next.parseStatus === "PARSED" && previous?.parseStatus !== "PARSED") {
                if (rangeModeRef.current === "blocks") void loadBlocks(1);
                else void loadAllBlocks();
                void loadMastery();
            } else if (next.parseStatus === "PARSED" && rangeModeRef.current === "blocks" &&
                blockRecordsRef.current.length === 0) void loadBlocks(1);
            if (next.parseStatus === "PARSING") scheduleMaterialPoll();
        } catch (error) {
            if (!mountedRef.current || sequence !== detailSeqRef.current) return;
            setDetailStatus("状态查询失败：" + reason(error));
        } finally {
            if (mountedRef.current && sequence === detailSeqRef.current) setRefreshBusy(false);
        }
    }

    async function startParse() {
        const id = materialRef.current?.materialId;
        if (!id || parseBusyRef.current) return;
        parseBusyRef.current = true;
        setParseBusy(true);
        setDetailStatus("正在提交解析请求……");
        try {
            const next = await request(`/api/materials/${id}/parse`, { method: "POST" });
            if (!mountedRef.current || materialRef.current?.materialId !== id) return;
            syncMaterial(next);
            resetScope();
            setDetailStatus("解析请求已提交，完成后正文会自动出现。");
            if (next.parseStatus === "PARSING") scheduleMaterialPoll();
            else if (next.parseStatus === "PARSED") {
                if (rangeModeRef.current === "blocks") void loadBlocks(1);
                else void loadAllBlocks();
                void loadMastery();
            }
            await loadMaterials(listPageRef.current, true);
        } catch (error) {
            if (!mountedRef.current) return;
            // 请求结果可能不明；只查询事实，不自动重复 POST。
            await refreshMaterial();
            setDetailStatus("未确认本次解析请求：" + reason(error) + " 请先刷新状态，再按需继续解析。");
        } finally {
            parseBusyRef.current = false;
            if (mountedRef.current) setParseBusy(false);
        }
    }

    function scheduleMaterialPoll() {
        stopMaterialTimer();
        if (!mountedRef.current || document.hidden) return;
        const id = materialRef.current?.materialId;
        if (!id) return;
        materialTimerRef.current = setTimeout(async () => {
            materialTimerRef.current = null;
            if (!mountedRef.current || materialRef.current?.materialId !== id) return;
            await refreshMaterial();
            if (materialRef.current?.parseStatus === "PARSING") scheduleMaterialPoll();
        }, 2500);
    }

    async function loadAllBlocks() {
        const current = materialRef.current;
        if (!current || current.parseStatus !== "PARSED" ||
            allBlocksLoadingRef.current || allBlocksLoadedRef.current) return;
        const id = current.materialId;
        const sequence = ++allBlocksSeqRef.current;
        allBlocksLoadingRef.current = true;
        setAllBlocksLoading(true);
        setPageRangeStatus("正在读取全部正文页码……");
        try {
            const records = [];
            let page = 1;
            let pages = 1;
            do {
                const data = await request(`/api/materials/${id}/blocks?page=${page}&size=50`);
                if (!mountedRef.current || sequence !== allBlocksSeqRef.current ||
                    materialRef.current?.materialId !== id) return;
                if (!Array.isArray(data.records)) throw new Error("正文响应不完整。");
                records.push(...data.records);
                pages = data.totalPages;
                page++;
            } while (page <= pages);

            allBlocksRef.current = records;
            allBlocksLoadedRef.current = true;
            setAllBlocks(records);
            setAllBlocksLoaded(true);
            const pageNumbers = records.map(blockPage).filter(value => value !== null);
            const largest = pageNumbers.length ? Math.max(...pageNumbers) : null;
            setMaxPage(largest);
            if (largest !== null) {
                setStartPage("1");
                setEndPage("1");
                setPageRangeStatus(`资料共 ${records.length} 块、${largest} 页。输入页码范围，或选择“整份资料”。`);
            } else {
                setPageRangeStatus(`资料共 ${records.length} 块；该格式没有页号，可使用“整份资料”分批。`);
            }
        } catch (error) {
            if (!mountedRef.current || sequence !== allBlocksSeqRef.current) return;
            allBlocksLoadedRef.current = false;
            setAllBlocksLoaded(false);
            setPageRangeStatus("正文页码读取失败：" + reason(error));
        } finally {
            if (mountedRef.current && sequence === allBlocksSeqRef.current) {
                allBlocksLoadingRef.current = false;
                setAllBlocksLoading(false);
            }
        }
    }

    function selectBatch(batch, quiet = false) {
        syncActiveBatch(batch);
        syncSelected(new Map(batch.blocks.map(block => [block.blockId, block])));
        if (!quiet) {
            setPageRangeStatus(`已选择 ${pageRangeLabel(batch.blocks)} 这一批（${batch.blocks.length} 块 / ${batch.chars} 字符）；` +
                "点击“开始出题”才会调用模型。");
        }
    }

    async function applyPageRange(useWhole = false) {
        const current = materialRef.current;
        if (!current || current.parseStatus !== "PARSED") return;
        if (!allBlocksLoadedRef.current) {
            await loadAllBlocks();
            if (!allBlocksLoadedRef.current) return;
        }
        const pdf = current.fileType === "pdf";
        let blocks = [...allBlocksRef.current];
        let label = "整份资料";
        if (pdf && !useWhole) {
            const start = Number(startPage);
            const end = Number(endPage);
            if (!Number.isSafeInteger(start) || !Number.isSafeInteger(end) || start < 1 || end < start) {
                setPageRangeStatus("请输入有效的起始页和结束页，起始页不能大于结束页。");
                return;
            }
            blocks = blocks.filter(block => {
                const pageNumber = blockPage(block);
                return pageNumber !== null && pageNumber >= start && pageNumber <= end;
            });
            label = `第 ${start}—${end} 页`;
            if (blocks.length === 0) {
                syncBatches([]);
                syncActiveBatch(null);
                syncSelected(new Map());
                setPageRangeStatus("这个页码范围没有可出题的正文，请检查页号。");
                return;
            }
        }
        const split = splitBatches(blocks);
        if (!split || split.length === 0) {
            setPageRangeStatus("正文为空或单个正文块超过 12,000 字符，无法出题。");
            return;
        }
        syncBatches(split);
        syncActiveBatch(null);
        syncSelected(new Map());
        if (split.length === 1) {
            selectBatch(split[0], true);
            setPageRangeStatus(`${label}共 ${blocks.length} 块 / ${split[0].chars} 字符，适合一次出题，已选中；点击“开始出题”才会调用模型。`);
        } else {
            setPageRangeStatus(`${label}共 ${blocks.length} 块，超过单批上限，已明确拆成 ${split.length} 批；请选择一批出题。`);
        }
    }

    async function loadBlocks(page = 1) {
        const current = materialRef.current;
        if (!current || current.parseStatus !== "PARSED") return;
        const id = current.materialId;
        const sequence = ++blocksSeqRef.current;
        setBlocksLoading(true);
        blockRecordsRef.current = [];
        setBlockRecords([]);
        setDetailStatus("正在读取正文……");
        try {
            const data = await request(`/api/materials/${id}/blocks?page=${page}&size=10`);
            if (!mountedRef.current || sequence !== blocksSeqRef.current ||
                materialRef.current?.materialId !== id) return;
            if (!Array.isArray(data.records)) throw new Error("正文响应不完整。");
            blockRecordsRef.current = data.records;
            setBlockRecords(data.records);
            setBlocksPage(data.page);
            setBlocksPages(data.totalPages);
            setBlocksTotal(data.total);
            setDetailStatus(data.records.length ? "" : "这一页没有正文。");
        } catch (error) {
            if (!mountedRef.current || sequence !== blocksSeqRef.current) return;
            setDetailStatus("正文读取失败：" + reason(error));
        } finally {
            if (mountedRef.current && sequence === blocksSeqRef.current) setBlocksLoading(false);
        }
    }

    function toggleBlock(block) {
        const next = new Map(selectedRef.current);
        if (next.has(block.blockId)) next.delete(block.blockId);
        else {
            if (next.size >= 10) {
                setDetailStatus("每批最多选择 10 个正文块，可以跨页选择。");
                return;
            }
            const total = [...next.values()].reduce((sum, item) => sum + codePoints(item.text), 0) + codePoints(block.text);
            if (total > 12000) {
                setDetailStatus("所选正文超过 12,000 字符，请缩小范围。");
                return;
            }
            next.set(block.blockId, block);
        }
        setDetailStatus("");
        syncSelected(next);
    }

    function applyOptions(value) {
        setRefreshCatalog(value.refreshCatalog === true);
        if (["COVERAGE", "WEAKNESS"].includes(value.mode)) setGenerationMode(value.mode);
        if (value.difficulty) setDifficulty(value.difficulty);
        if (value.granularity) setGranularity(value.granularity);
        if ([3, 5, 10].includes(value.count)) setCount(value.count);
    }

    async function generate() {
        const current = materialRef.current;
        if (!current || current.parseStatus !== "PARSED" || generateBusyRef.current) return;
        if (settingsState === "ready" && settings && !settings.configured) {
            if (modelPanelRef.current) modelPanelRef.current.open = true;
            setGenerationMessage("出题前请先保存你自己的 DeepSeek API Key；不会回落到其他账号的密钥。");
            return;
        }
        if (!pendingRef.current && selectedRef.current.size === 0) {
            setGenerationMessage(rangeModeRef.current === "range"
                ? "请先应用页码范围并选择一批正文。" : "请先选择至少一个正文块。");
            return;
        }
        // 先固定并保存原请求，再发送。响应丢失时复用同一 requestKey，不能悄悄重建付费任务。
        const payload = pendingRef.current ?? {
            materialId: current.materialId,
            blockIds: [...selectedRef.current.keys()].sort((a, b) => a - b),
            count,
            mode: generationMode,
            difficulty,
            granularity,
            refreshCatalog,
            requestKey: crypto.randomUUID()
        };
        syncPending(payload);
        saveRecovery();
        generateBusyRef.current = true;
        setGenerateBusy(true);
        setGenerationMessage("正在提交出题任务……");
        const sequence = ++jobSeqRef.current;
        try {
            const next = await request("/api/generation/jobs", {
                method: "POST",
                headers: { "Content-Type": "application/json" },
                body: JSON.stringify(payload)
            });
            if (!mountedRef.current || sequence !== jobSeqRef.current) return;
            syncPending(null);
            syncRecovering(null);
            syncJob(next);
            setGenerationMessage("");
            saveRecovery();
            scheduleJobPoll();
        } catch (error) {
            if (!mountedRef.current || sequence !== jobSeqRef.current) return;
            setGenerationMessage("出题请求未确认：" + reason(error) + " 可使用原请求编号继续，不会创建重复任务。");
            // 服务端明确拒绝（受控错误码或 4xx）表示没有创建任务；网络中断才保留幂等重放。
            if (error.errorCode || (error.status >= 400 && error.status < 500)) {
                syncPending(null);
                saveRecovery();
            }
        } finally {
            generateBusyRef.current = false;
            if (mountedRef.current) setGenerateBusy(false);
        }
    }

    async function recoverJob() {
        const jobId = recoveringRef.current;
        if (!jobId) return;
        const sequence = ++jobSeqRef.current;
        setGenerationMessage("正在恢复上次任务的状态……");
        try {
            const next = await request(`/api/generation/jobs/${encodeURIComponent(jobId)}`);
            if (!mountedRef.current || sequence !== jobSeqRef.current) return;
            syncJob(next);
            syncRecovering(null);
            setGenerationMessage("");
            saveRecovery();
            scheduleJobPoll();
            if (next.status === "SUCCEEDED") void loadMastery();
            if (pendingRef.current) {
                syncPending(null);
                saveRecovery();
            }
        } catch (error) {
            if (!mountedRef.current || sequence !== jobSeqRef.current) return;
            setGenerationMessage("任务状态读取失败：" + reason(error));
            if (error.status === 404) {
                syncRecovering(null);
                saveRecovery();
            }
        }
    }

    function scheduleJobPoll() {
        stopJobTimer();
        if (!mountedRef.current || document.hidden) return;
        const current = jobRef.current;
        if (!current || !["PENDING", "PROCESSING"].includes(current.status)) return;
        jobTimerRef.current = setTimeout(async () => {
            jobTimerRef.current = null;
            if (!mountedRef.current || jobRef.current?.jobId !== current.jobId) return;
            const sequence = ++jobSeqRef.current;
            try {
                const next = await request(`/api/generation/jobs/${encodeURIComponent(current.jobId)}`);
                if (!mountedRef.current || sequence !== jobSeqRef.current) return;
                syncJob(next);
                saveRecovery();
                if (next.status === "PENDING" || next.status === "PROCESSING") scheduleJobPoll();
                else if (next.status === "SUCCEEDED") void loadMastery();
            } catch (error) {
                if (!mountedRef.current) return;
                setGenerationMessage("任务状态查询失败：" + reason(error) + " 可点击“重新查询上次任务”重试。");
            }
        }, 1500);
    }

    async function retryFailedJob() {
        const current = jobRef.current;
        if (!current || current.status !== "FAILED" || generateBusyRef.current) return;
        if (!window.confirm("重新生成可能再次调用模型并产生费用。确认重试这个失败任务？")) return;
        generateBusyRef.current = true;
        setGenerateBusy(true);
        setGenerationMessage("正在提交重试……");
        const sequence = ++jobSeqRef.current;
        try {
            const next = await request(`/api/generation/jobs/${encodeURIComponent(current.jobId)}/retry`,
                { method: "POST" });
            if (!mountedRef.current || sequence !== jobSeqRef.current) return;
            syncJob(next);
            setGenerationMessage("");
            saveRecovery();
            scheduleJobPoll();
        } catch (error) {
            if (mountedRef.current) setGenerationMessage("重试失败：" + reason(error));
        } finally {
            generateBusyRef.current = false;
            if (mountedRef.current) setGenerateBusy(false);
        }
    }

    async function selectQuestion(question, source = "") {
        await session.select(question, source);
        saveRecovery();
        practiceRef.current?.scrollIntoView({ behavior: "smooth", block: "start" });
    }

    async function restoreQuestion(versionId) {
        if (!versionId) return;
        const current = jobRef.current;
        if (current) {
            const item = current.questions?.find(entry => entry.question.versionId === versionId);
            if (item) {
                await selectQuestion(item.question, locatorLabel(item.sourceLocator));
                return;
            }
        }
        // 任务列表可能已被清理；从本人已保存的学习题中恢复。
        try {
            const list = await request(`/api/learning/questions?materialId=${materialRef.current.materialId}`);
            const question = Array.isArray(list) ? list.find(entry => entry.versionId === versionId) : null;
            if (question) await selectQuestion(question);
        } catch { /* 学习功能未启用时不恢复作答，不影响出题页面。 */ }
    }

    async function loadMastery() {
        const current = materialRef.current;
        if (!current || current.parseStatus !== "PARSED") return;
        const id = current.materialId;
        const sequence = ++masterySeqRef.current;
        try {
            const list = await request(`/api/generation/jobs/knowledge-groups?materialId=${id}`);
            if (!mountedRef.current || sequence !== masterySeqRef.current ||
                materialRef.current?.materialId !== id) return;
            const values = Array.isArray(list) ? list : [];
            setMastery(values);
            setMasteryStatus(values.length ? "" : "暂无知识组");
        } catch (error) {
            if (!mountedRef.current || sequence !== masterySeqRef.current) return;
            setMasteryStatus(error.errorCode === "LEARNING_NOT_ENABLED"
                ? "学习功能尚未启用（默认关闭），完成数据库迁移和开关配置后才可用。"
                : "学习状态读取失败：" + reason(error));
        }
    }

    async function recommend() {
        const current = materialRef.current;
        if (!current || current.parseStatus !== "PARSED" || recommendBusyRef.current) return;
        recommendBusyRef.current = true;
        setRecommendBusy(true);
        setRecommendStatus("正在选择推荐练习……");
        const sequence = ++masterySeqRef.current;
        try {
            const selection = await request(
                `/api/learning/recommendations?materialId=${current.materialId}&size=3`);
            if (!mountedRef.current || sequence !== masterySeqRef.current) return;
            const questions = Array.isArray(selection.questions) ? selection.questions : [];
            setRecommendations(questions);
            setRecommendStatus(questions.length
                ? "优先补薄弱点，同时保留新考点覆盖；每个考点最多一道。"
                : "暂无合适的未答题目，可以选择正文范围再生成一组。");
        } catch (error) {
            if (!mountedRef.current || sequence !== masterySeqRef.current) return;
            setRecommendations([]);
            setRecommendStatus(error.errorCode === "LEARNING_NOT_ENABLED"
                ? "学习功能尚未启用，暂无推荐。" : "推荐读取失败：" + reason(error));
        } finally {
            recommendBusyRef.current = false;
            if (mountedRef.current && sequence === masterySeqRef.current) setRecommendBusy(false);
        }
    }

    async function loadSettings() {
        setSettingsState("loading");
        try {
            const next = await request("/api/settings/model");
            if (!mountedRef.current) return;
            setSettings(next);
            setSettingsState("ready");
            setModelMessage("");
        } catch (error) {
            if (!mountedRef.current) return;
            if (error.errorCode === "MODEL_SETTINGS_NOT_ENABLED" || error.errorCode === "MODEL_MASTER_KEY_NOT_CONFIGURED") {
                setSettingsState("disabled");
                setModelMessage(error.message);
            } else {
                setSettingsState("error");
                setModelMessage("模型设置读取失败：" + reason(error));
            }
        }
    }

    async function changeKey(remove) {
        if (settingsBusyRef.current || settingsState !== "ready") return;
        if (!remove && !validKey(modelKey)) {
            setModelMessage("密钥须为 16—512 个可见 ASCII 字符，不能包含空格或换行。");
            return;
        }
        settingsBusyRef.current = true;
        setSettingsBusy(true);
        setModelMessage(remove ? "正在删除……" : "正在加密保存……");
        try {
            const next = remove
                ? await request("/api/settings/model-key", { method: "DELETE" })
                : await request("/api/settings/model-key", {
                    method: "PUT",
                    headers: { "Content-Type": "application/json" },
                    body: JSON.stringify({ apiKey: modelKey })
                });
            if (!mountedRef.current) return;
            setSettings(next);
            setModelKey("");
            setModelMessage(remove ? "密钥已删除，后续出题将无法调用模型。" : "密钥已加密保存，首次出题时会验证连接。");
        } catch (error) {
            if (mountedRef.current) {
                setModelMessage(remove ? "删除失败：" + reason(error) : "保存失败：" + reason(error));
            }
        } finally {
            settingsBusyRef.current = false;
            if (mountedRef.current) setSettingsBusy(false);
        }
    }

    useEffect(() => {
        mountedRef.current = true;
        if (!startedRef.current) {
            startedRef.current = true;
            void loadSettings();
            void loadMaterials(1);
            const search = window.location.search;
            const linked = Number(queryMaterialId(search));
            if (Number.isSafeInteger(linked) && linked > 0) {
                const versionId = queryVersionId(search);
                void selectMaterial(linked, {
                    materialId: linked,
                    jobId: null,
                    pendingRequest: null,
                    questionVersionId: versionId || null
                });
            } else {
                const saved = normalizeRecovery(loadWorkspaceRecovery(user.userId));
                if (saved) void selectMaterial(saved.materialId, saved);
            }
        }
        return () => {
            mountedRef.current = false;
            stopMaterialTimer();
            stopJobTimer();
        };
        // eslint-disable-next-line react-hooks/exhaustive-deps
    }, []);

    useEffect(() => {
        const onVisibility = () => {
            if (document.hidden) {
                stopMaterialTimer();
                stopJobTimer();
            } else if (mountedRef.current) {
                scheduleJobPoll();
                if (materialRef.current?.parseStatus === "PARSING") scheduleMaterialPoll();
            }
        };
        document.addEventListener("visibilitychange", onVisibility);
        return () => document.removeEventListener("visibilitychange", onVisibility);
        // eslint-disable-next-line react-hooks/exhaustive-deps
    }, []);

    useEffect(() => {
        if (session.appliedCount > 0) void loadMastery();
        // eslint-disable-next-line react-hooks/exhaustive-deps
    }, [session.appliedCount]);

    useEffect(() => {
        if (job?.status === "SUCCEEDED" && ["COVERAGE", "WEAKNESS"].includes(job.mode)) void loadMastery();
        // eslint-disable-next-line react-hooks/exhaustive-deps
    }, [job?.jobId, job?.status]);

    const parsed = material?.parseStatus === "PARSED";
    const runningJob = !!(job && ["PENDING", "PROCESSING"].includes(job.status));
    const pending = !!pendingRequest || !!recoveringJobId || runningJob;
    // 未确认的原请求必须允许继续确认；只有进行中的任务才禁止再次出题。
    const generationBlocked = !!recoveringJobId || runningJob;
    const selectedCharacters = [...selectedBlocks.values()]
        .reduce((sum, block) => sum + codePoints(block.text), 0);
    const needKey = settingsState === "ready" && settings && !settings.configured;
    const batchLabel = activeBatch ? ` · ${pageRangeLabel(activeBatch.blocks)}` : "";

    const materialStateText = !material ? "" :
        material.parseStatus === "PARSED" ? "解析完成，可以选择正文范围出题。" :
            material.parseStatus === "PARSING" ? "正在解析，页面会自动更新。" :
                material.parseStatus === "FAILED" ? `解析失败：${material.errorMessage ?? "请重试"}` :
                    "原件已保存，尚未解析。";

    const modelStatus = settingsState === "ready"
        ? settings.configured ? "已保存个人密钥" : "尚未配置个人密钥"
        : settingsState === "disabled" ? "本机未启用网页密钥模式"
            : settingsState === "error" ? "状态暂时无法读取" : "正在读取……";
    const modelDetail = settingsState === "ready" && settings
        ? `${settings.provider} · ${settings.model}。已保存的密钥不会返回页面。`
        : settingsState === "disabled"
            ? "服务器未启用网页按用户保存密钥；出题会使用服务器配置的密钥。这是配置状态，不是错误。"
            : settingsState === "error" ? "可以稍后刷新；若持续失败，请检查服务配置。" : "";

    const attempt = session.attempt;
    const ticket = session.ticket;
    const submission = session.submission;
    const uncertain = !!ticket?.submit && !submission;
    const practiceLocked = session.busy || !!ticket?.submit || !!submission || attempt?.status === "SUBMITTED";
    let practiceHint = session.busy ? "正在与服务器确认……"
        : uncertain ? "服务器可能已收到答案。选项和信心已锁定，请用原提交确认，避免重复记录。"
            : attempt?.status === "EXPIRED" ? "这次答题尝试已过期，只有服务器明确返回过期后才能重新开始。"
                : attempt?.status === "SUBMITTED" && !ticket?.submit && !submission
                    ? "此尝试已完成，请选择其他题目。"
                    : attempt?.assisted || ticket?.revealRequested
                        ? "辅助练习：已请求查看答案，本次不作为独立掌握度证据。" : session.hint;
    if (session.revealed && !submission) {
        practiceHint = `正确选项：${session.revealed}。可以继续选择答案并保存这次练习（本次记为辅助练习）。`;
    }

    const resultText = submission
        ? `${submission.correct ? "答对" : "答错"} · 正确答案 ${submission.correctOption}`
                : "";

    let eventStatus = "";
    if (submission) {
        if (!submission.eligible) eventStatus = "本次练习已保存，未计入独立学习证据。";
        else if (session.event?.status === "APPLIED") eventStatus = "学习状态已更新。";
        else if (session.event?.status === "FAILED") {
            eventStatus = `答案已保存，学习状态更新失败（${session.event.errorCode ?? "UNKNOWN"}）。`;
        } else eventStatus = "正在更新学习状态……";
    }
    if (session.eventError) eventStatus = `${eventStatus} ${session.eventError}`.trim();

    const jobLabels = { PENDING: "排队中", PROCESSING: "正在提炼考点与出题", SUCCEEDED: "题目已保存", FAILED: "生成失败" };
    const jobStatusText = job
        ? `${job.generatedCount} / ${job.requestedCount} 题 · 难度 ${job.difficulty} · 细致度 ${job.granularity}` +
        (job.attemptCount > 1 ? ` · 第 ${job.attemptCount} 次尝试` : "") +
        (job.status === "FAILED" ? ` · ${generationFailureHint(job.errorCode)}（${job.errorCode ?? "UNKNOWN"}）。已保存的内容不受影响。` : "")
        : "";
    const shortfall = !!(job && job.status === "SUCCEEDED" && job.generatedCount < job.requestedCount);

    return (
        <div className="workspace-page">
            <header className="workspace-heading">
                <div className="personal-center-heading">
                    <h1>学习工作台</h1>
                    {navigation}
                </div>
                <p className="intro">从自己的资料出发，把理解变成答案。上传、浏览和选择正文都不会调用模型，只有明确点击“开始出题”才会产生费用。</p>
            </header>

            <details ref={modelPanelRef} className="workspace-card">
                <summary>模型设置 · <span>{modelStatus}</span></summary>
                <div className="workspace-card-body">
                    <p className="input-hint">{modelDetail}</p>
                    <form onSubmit={event => { event.preventDefault(); void changeKey(false); }}>
                        <label htmlFor="modelKey">DeepSeek API Key</label>
                        <input id="modelKey" type="password" autoComplete="off" spellCheck="false"
                            minLength="16" maxLength="512"
                            placeholder={settings?.configured ? "输入新密钥以替换，原密钥不回显" : "在此粘贴你的 DeepSeek API Key"}
                            disabled={settingsState !== "ready" || settingsBusy}
                            value={modelKey} onChange={event => setModelKey(event.target.value)} />
                        <div className="workspace-actions">
                            <button id="saveModelKey" type="submit"
                                disabled={settingsState !== "ready" || settingsBusy || !validKey(modelKey)}>保存密钥</button>
                            {settingsState === "ready" && settings?.configured ? (
                                <button type="button" className="secondary" disabled={settingsBusy}
                                    onClick={() => {
                                        if (window.confirm("删除当前账号保存的模型密钥？已生成的题目不会删除。")) {
                                            void changeKey(true);
                                        }
                                    }}>删除密钥</button>
                            ) : null}
                        </div>
                    </form>
                    <p role="status" aria-live="polite">{modelMessage}</p>
                    <p className="input-hint">密钥由服务端加密保存，只用于当前账号，保存后不会回显。模型调用费用由你的模型账户承担。若本机未启用网页密钥模式，这里会提示功能未启用，出题将使用服务器配置的密钥。</p>
                </div>
            </details>

            <section className="workspace-card" aria-labelledby="materialsHeading">
                <div className="workspace-section-heading">
                    <h2 id="materialsHeading">1. 选择资料</h2>
                    <button type="button" className="secondary" disabled={materialsLoading}
                        onClick={() => void loadMaterials(listPageRef.current)}>刷新列表</button>
                </div>
                <p role="status" aria-live="polite">{listStatus}</p>
                <ul className="workspace-materials">
                    {materials.map(item => (
                        <li key={item.materialId}>
                            <button type="button" aria-pressed={item.materialId === selectedId}
                                onClick={() => void selectMaterial(item.materialId)}>
                                <strong>{item.originalName}</strong>
                                <small>
                                    {stateLabels[item.parseStatus] ?? "状态未知"} · {item.fileType.toUpperCase()} · {fileSize(item.sizeBytes)}
                                    {item.blockCount ? ` · ${item.blockCount} 块 / ${item.textCharCount} 字符` : ""}
                                    {` · ${dateLabel(item.createdAt)}`}
                                </small>
                            </button>
                        </li>
                    ))}
                </ul>
                <div className="history-pagination">
                    <button type="button" className="secondary"
                        disabled={materialsLoading || materialsPage <= 1}
                        onClick={() => void loadMaterials(materialsPage - 1)}>资料上一页</button>
                    <span>
                        {materialsTotal
                            ? `第 ${materialsPage} / ${materialsPages} 页，共 ${materialsTotal} 份`
                            : "共 0 份资料"}
                    </span>
                    <button type="button" className="secondary"
                        disabled={materialsLoading || materialsPage >= materialsPages}
                        onClick={() => void loadMaterials(materialsPage + 1)}>资料下一页</button>
                </div>
            </section>

            {material ? (
                <section className="workspace-card" aria-labelledby="generationHeading">
                    <div className="workspace-section-heading">
                        <h2 id="generationHeading">2. 选择范围并出题</h2>
                    </div>
                    <div className="workspace-material-summary">
                        <strong>{material.originalName}</strong>
                        <small>
                            资料 #{material.materialId} · {stateLabels[material.parseStatus] ?? "状态未知"} · {material.blockCount} 块 / {material.textCharCount} 字符
                        </small>
                    </div>
                    <p>{materialStateText}</p>
                    <div className="workspace-actions">
                        {["UPLOADED", "FAILED"].includes(material.parseStatus) ? (
                            <button type="button" disabled={parseBusy}
                                onClick={() => void startParse()}>
                                {material.parseStatus === "FAILED" ? "重新解析" : "开始解析"}
                            </button>
                        ) : null}
                        <button type="button" className="secondary" disabled={refreshBusy || parseBusy}
                            onClick={() => void refreshMaterial()}>刷新状态</button>
                    </div>
                    <p role="status" aria-live="polite">{detailStatus}</p>
                    <p><a className="ll-action-link" href={`/questions?materialId=${encodeURIComponent(material.materialId)}`}>打开题目中心查看这份资料的全部题目</a></p>

                    {parsed ? (
                        <div>
                            <div className="workspace-section-heading">
                                <h3>正文范围</h3>
                                <span className="workspace-badge">
                                    已选 {selectedBlocks.size} 块 · {selectedCharacters} 字符{batchLabel}
                                </span>
                            </div>
                            <div className="workspace-range-modes" role="radiogroup" aria-label="选题方式">
                                {[["range", "按页/整份资料"], ["blocks", "正文块（高级）"]].map(([value, label]) => (
                                    <label key={value}>
                                        <input type="radio" name="rangeMode" value={value}
                                            checked={rangeMode === value}
                                            disabled={!parsed || pending}
                                            onChange={() => {
                                                rangeModeRef.current = value;
                                                setRangeMode(value);
                                                syncSelected(new Map());
                                                syncActiveBatch(null);
                                                syncBatches([]);
                                                setPageRangeStatus("");
                                                if (value === "range" && material.fileType === "pdf" &&
                                                    !allBlocksLoadedRef.current && !allBlocksLoadingRef.current) {
                                                    void loadAllBlocks();
                                                }
                                                if (value === "blocks" && blockRecordsRef.current.length === 0) {
                                                    void loadBlocks(1);
                                                }
                                            }} />
                                        {label}
                                    </label>
                                ))}
                            </div>
                            <p className="input-hint">
                                {material.fileType === "pdf"
                                    ? "按页选择会把该页范围内的所有正文块算作一批；超过 10 块或 12,000 字符时会在下方明确拆批，不会静默截断。"
                                    : "该资料没有可靠页号；可使用“整份资料”按正文顺序分批，或切换到正文块高级选择。"}
                            </p>

                            {rangeMode === "range" ? (
                                <div>
                                    {material.fileType === "pdf" ? (
                                        <div className="workspace-page-inputs">
                                            <label htmlFor="startPage">起始页</label>
                                            <input id="startPage" type="number" min="1" step="1"
                                                value={startPage} max={maxPage ?? undefined}
                                                disabled={pending} onChange={event => setStartPage(event.target.value)} />
                                            <label htmlFor="endPage">结束页</label>
                                            <input id="endPage" type="number" min="1" step="1"
                                                value={endPage} max={maxPage ?? undefined}
                                                disabled={pending} onChange={event => setEndPage(event.target.value)} />
                                            <button type="button" className="secondary"
                                                disabled={!parsed || allBlocksLoading || pending}
                                                onClick={() => void applyPageRange(false)}>应用页码范围</button>
                                            <button type="button" className="secondary"
                                                disabled={!parsed || allBlocksLoading || pending}
                                                onClick={() => void applyPageRange(true)}>整份资料</button>
                                        </div>
                                    ) : (
                                        <div className="workspace-page-inputs">
                                            <button type="button" className="secondary"
                                                disabled={!parsed || allBlocksLoading || pending}
                                                onClick={() => void applyPageRange(true)}>整份资料</button>
                                        </div>
                                    )}
                                    <p role="status" aria-live="polite">{pageRangeStatus}</p>
                                    <div className="workspace-batches">
                                        {batches.map((batch, index) => (
                                            <button key={index} type="button" className="workspace-batch"
                                                aria-pressed={activeBatch === batch}
                                                disabled={pending || generateBusy}
                                                onClick={() => selectBatch(batch)}>
                                                <strong>第 {index + 1} 批 · {pageRangeLabel(batch.blocks)}</strong>
                                                <small>
                                                    {batch.blocks.length} 块 · {batch.chars} 字符
                                                    {activeBatch === batch ? " · 已选中" : ""}
                                                </small>
                                            </button>
                                        ))}
                                    </div>
                                </div>
                            ) : (
                                <div>
                                    <p className="input-hint">可跨页选择，最多 10 个正文块；所有选中正文合计不超过 12,000 字符（按 Unicode 码点统计）。</p>
                                    <div className="workspace-blocks">
                                        {blockRecords.map(block => (
                                            <article key={block.blockId} className="workspace-block"
                                                data-selected={String(selectedBlocks.has(block.blockId))}>
                                                <input type="checkbox" checked={selectedBlocks.has(block.blockId)}
                                                    disabled={pending}
                                                    onChange={() => toggleBlock(block)} />
                                                <div className="workspace-block-body">
                                                    <p className="workspace-block-source">
                                                        正文块 {block.blockNo} · {locatorLabel(block.sourceLocator)}
                                                    </p>
                                                    <p className="workspace-block-text">{block.text}</p>
                                                </div>
                                            </article>
                                        ))}
                                    </div>
                                    <div className="history-pagination">
                                        <button type="button" className="secondary"
                                            disabled={blocksLoading || blocksPage <= 1}
                                            onClick={() => void loadBlocks(blocksPage - 1)}>正文上一页</button>
                                        <span>
                                            {blocksTotal ? `第 ${blocksPage} / ${blocksPages} 页，共 ${blocksTotal} 块` : ""}
                                        </span>
                                        <button type="button" className="secondary"
                                            disabled={blocksLoading || blocksPage >= blocksPages}
                                            onClick={() => void loadBlocks(blocksPage + 1)}>正文下一页</button>
                                        <button type="button" className="secondary"
                                            disabled={selectedBlocks.size === 0 || pending}
                                            onClick={() => {
                                                syncSelected(new Map());
                                                syncActiveBatch(null);
                                                setDetailStatus("");
                                            }}>清空选择</button>
                                    </div>
                                </div>
                            )}

                            <div className="workspace-generation-settings">
                                <fieldset className="workspace-options" disabled={pending || generateBusy}>
                                    <legend>出题偏好</legend>
                                    <div className="workspace-option-row">
                                        <span className="workspace-option-label">出题方式</span>
                                        {[["COVERAGE", "覆盖出题"], ["WEAKNESS", "薄弱点强化"]].map(([value, label]) => (
                                            <label key={value}>
                                                <input type="radio" name="generationMode" value={value}
                                                    checked={generationMode === value}
                                                    onChange={() => setGenerationMode(value)} /> {label}
                                            </label>
                                        ))}
                                    </div>
                                    <div className="workspace-option-row">
                                        <span className="workspace-option-label">题目难度</span>
                                        {[["EASY", "简单"], ["MEDIUM", "适中"], ["HARD", "困难"]].map(([value, label]) => (
                                            <label key={value}>
                                                <input type="radio" name="difficulty" value={value}
                                                    checked={difficulty === value}
                                                    onChange={() => setDifficulty(value)} /> {label}
                                            </label>
                                        ))}
                                    </div>
                                    <div className="workspace-option-row">
                                        <span className="workspace-option-label">考点细致度</span>
                                        {[["COARSE", "概要"], ["MEDIUM", "标准"], ["FINE", "细致"]].map(([value, label]) => (
                                            <label key={value}>
                                                <input type="radio" name="granularity" value={value}
                                                    checked={granularity === value}
                                                    onChange={() => setGranularity(value)} /> {label}
                                            </label>
                                        ))}
                                    </div>
                                    <div className="workspace-option-row">
                                        <span className="workspace-option-label">题目数量</span>
                                        {[[3, "3 题"], [5, "5 题"], [10, "10 题"]].map(([value, label]) => (
                                            <label key={value}>
                                                <input type="radio" name="count" value={String(value)}
                                                    checked={count === value}
                                                    onChange={() => setCount(value)} /> {label}
                                            </label>
                                        ))}
                                    </div>
                                    <label className="input-hint">
                                        <input type="checkbox" checked={refreshCatalog}
                                            onChange={() => setRefreshCatalog(!refreshCatalog)} /> 重新提炼所选范围考点（会调用模型）
                                    </label>
                                    {difficulty === "HARD" ? <p className="input-hint">可能包含最多一道扩展题，新增前提会在题干中给出。</p> : null}
                                </fieldset>
                                <aside className="workspace-knowledge" aria-labelledby="masteryHeading">
                                    <div className="workspace-section-heading">
                                        <h3 id="masteryHeading">知识组掌握度</h3>
                                        <button type="button" className="secondary" disabled={!parsed}
                                            onClick={() => void loadMastery()}>刷新</button>
                                    </div>
                                    <p role="status" aria-live="polite">{masteryStatus}</p>
                                    <ul className="workspace-mastery">
                                        {mastery.map(item => (
                                            <li key={JSON.stringify([item.chapter, item.name])}>
                                                <details>
                                                    <summary>
                                                        <strong>{item.name}</strong>{" · "}
                                                        {item.probability == null ? "样本不足" : `${Math.round(item.probability * 100)}%`}
                                                        <small>{` · ${item.evidenceCount} 个题族`}</small>
                                                    </summary>
                                                    <small>{item.chapter}</small>
                                                    <ul>
                                                        {item.topics.map(topic => (
                                                            <li key={topic.id}>{topic.name}{topic.hasRecentError ? " · 有错题" : ""}</li>
                                                        ))}
                                                    </ul>
                                                </details>
                                            </li>
                                        ))}
                                    </ul>
                                </aside>
                            </div>

                            <p role="status" aria-live="polite">{generationMessage}</p>
                            <div className="workspace-actions">
                                <button type="button"
                                    disabled={!parsed || generateBusy || parseBusy || generationBlocked ||
                                        (selectedBlocks.size === 0 && !pendingRequest) || needKey}
                                    onClick={() => void generate()}>
                                    {pendingRequest ? "继续确认原出题请求"
                                        : generateBusy ? "正在确认请求……" : "开始出题"}
                                </button>
                                {job?.status === "FAILED" ? (
                                    <button type="button" className="secondary" disabled={generateBusy}
                                        onClick={() => void retryFailedJob()}>重试失败任务（可能再次计费）</button>
                                ) : null}
                                {recoveringJobId ? (
                                    <button type="button" className="secondary" disabled={generateBusy}
                                        onClick={() => void recoverJob()}>重新查询上次任务</button>
                                ) : null}
                            </div>
                        </div>
                    ) : null}

                    {job ? (
                        <div>
                            <h3>出题任务 · {jobLabels[job.status] ?? job.status}</h3>
                            <p role="status" aria-live="polite">{jobStatusText}</p>
                            {shortfall || (job?.status === "SUCCEEDED" && job.shortfallReason) ? (
                                <p className="input-hint">
                                    {shortfall ? `已出 ${job.generatedCount} / ${job.requestedCount} 题 · ` : ""}
                                    {job.shortfallReason ?? "考点不足"}
                                </p>
                            ) : null}
                            <ul className="workspace-questions">
                                {job.questions.map((item, index) => (
                                    <li key={item.question.versionId}>
                                        <button type="button"
                                            aria-pressed={session.question?.versionId === item.question.versionId}
                                            onClick={() => void selectQuestion(item.question, locatorLabel(item.sourceLocator))}>
                                            <small>第 {index + 1} 题 · {locatorLabel(item.sourceLocator)}</small>
                                            <strong>{item.question.content}</strong>
                                        </button>
                                    </li>
                                ))}
                            </ul>
                            {job.questions.length ? (
                                <p className="input-hint">点击题目开始作答；题目会保存到你的题库，请结合原文核对生成内容。</p>
                            ) : null}
                            <p><a className="ll-action-link" href={`/questions?materialId=${encodeURIComponent(material.materialId)}`}>打开题目中心查看全部题目</a></p>
                        </div>
                    ) : null}
                </section>
            ) : null}

            {session.question ? (
                <section ref={practiceRef} className="workspace-card" aria-labelledby="practiceHeading">
                    <div className="workspace-section-heading">
                        <h2 id="practiceHeading">3. 专注这一题</h2>
                        <span className="workspace-badge">单选题</span>
                    </div>
                    <p className="input-hint">
                        {session.questionSource ? `来源：${session.questionSource}` : ""}
                    </p>
                    <p className="workspace-question-text">{session.question.content}</p>
                    <AnswerOptions question={session.question} name="answer"
                        selected={session.option}
                        disabled={!attempt || attempt.status !== "OPEN" || practiceLocked}
                        correctOption={submission?.correctOption ?? session.revealed}
                        onChange={session.setOption} />
                    {!submission && attempt?.status !== "SUBMITTED" && <ConfidenceOptions name="confidence" value={session.confidence}
                        disabled={!attempt || attempt.status !== "OPEN" || practiceLocked}
                        onChange={session.setConfidence} />}
                    {!submission && attempt?.status !== "SUBMITTED" && <p role="status" aria-live="polite">{practiceHint}</p>}
                    <div className="workspace-actions">
                        <button type="button" className="secondary"
                            disabled={!attempt || attempt.status !== "OPEN" || !!ticket?.submit ||
                                !!submission || session.busy}
                            onClick={() => void session.reveal()}>
                            {ticket?.revealRequested && !session.revealed ? "重新查看答案" : "查看答案"}
                        </button>
                        <button type="button"
                            disabled={session.busy || !attempt || !!submission ||
                                (!session.option && !ticket?.submit) ||
                                (attempt.status !== "OPEN" && !ticket?.submit)}
                            onClick={() => void session.submit()}>
                            {session.busy ? "正在确认……" : uncertain ? "确认原提交结果" : "提交答案"}
                        </button>
                        {attempt?.status === "EXPIRED" ? (
                            <button type="button" className="secondary" disabled={session.busy}
                                onClick={() => void session.restart()}>重新开始本题</button>
                        ) : null}
                        {session.event?.status === "FAILED" ? (
                            <button type="button" className="secondary" disabled={session.busy}
                                onClick={() => void session.retryEvent()}>重试更新学习状态</button>
                        ) : null}
                    </div>
                    {submission ? (
                        <div className="workspace-result" data-correct={String(submission.correct)}>
                            {resultText}
                        </div>
                    ) : null}
                    <p role="status" aria-live="polite">{eventStatus}</p>
                    {submission && <AnswerExplanation key={submission.attemptId ?? attempt?.attemptId}
                        attemptId={submission.attemptId ?? attempt?.attemptId} />}
                    {submission?.correct === false && <TutorFeedback key={submission.attemptId} kind="PRACTICE" attemptId={submission.attemptId} />}
                    <NoteEditor key={session.question.versionId} version={session.question.versionId} />
                </section>
            ) : null}

            <section className="workspace-card" aria-label="已有题练习">
                <div className="workspace-actions">
                    <button type="button" className="secondary" disabled={!parsed || recommendBusy}
                        onClick={() => void recommend()}>看看推荐练习</button>
                </div>
                <p role="status" aria-live="polite">{recommendStatus}</p>
                <ul className="workspace-questions">
                    {recommendations.map(item => (
                        <li key={item.question.versionId}>
                            <button type="button"
                                onClick={() => void selectQuestion(item.question, "推荐练习")}>
                                <small>
                                    {item.reasonCode === "BKT_WEAKNESS" ? "补薄弱点" : "扩展覆盖"}
                                </small>
                                <strong>{item.question.content}</strong>
                            </button>
                        </li>
                    ))}
                </ul>
            </section>
        </div>
    );
}
