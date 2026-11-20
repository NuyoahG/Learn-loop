package com.learnloop.material;

import java.time.Instant;
import java.util.List;

/** 面向浏览器的白名单 DTO：不暴露 userId、requestId、storageKey 或本机路径。 */
public record MaterialResponse(
        Long materialId, String originalName, String fileType, long sizeBytes, String sha256,
        String parseStatus, long parseAttempt, String parserVersion, String errorCode,
        String errorMessage, List<Warning> warnings, int blockCount, int textCharCount,
        Instant createdAt, Instant updatedAt, Instant parsedAt) {
    /** 当前上传阶段为空数组；后续解析可明确标出警告与相关 PDF 物理页。 */
    public record Warning(String code, String message, List<Integer> pageNumbers) {}
}
