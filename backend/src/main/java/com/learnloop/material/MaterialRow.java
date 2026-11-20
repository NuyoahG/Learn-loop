package com.learnloop.material;

import java.time.LocalDateTime;

/**
 * 数据库映射对象，不直接作为 API 响应。保留 setter 是因为 MyBatis 需要回填自增 ID。
 * 其余业务入口不能让浏览器绑定此对象，否则可能把 userId、状态或存储键当作用户输入。
 */
public class MaterialRow {
    private Long id;
    private Long userId;
    private String originalName;
    private String fileType;
    private Long sizeBytes;
    private String sha256;
    private String storageKey;
    private String requestId;
    private String parseStatus;
    private Long parseAttempt;
    private String parserVersion;
    private String errorCode;
    private String errorMessage;
    private String warnings;
    private Integer blockCount;
    private Integer textCharCount;
    private LocalDateTime createdAt;
    private LocalDateTime updatedAt;
    private LocalDateTime parsedAt;
    public Long getId() { return id; }
    public void setId(Long id) { this.id = id; }
    public Long getUserId() { return userId; }
    public void setUserId(Long userId) { this.userId = userId; }
    public String getOriginalName() { return originalName; }
    public void setOriginalName(String originalName) { this.originalName = originalName; }
    public String getFileType() { return fileType; }
    public void setFileType(String fileType) { this.fileType = fileType; }
    public Long getSizeBytes() { return sizeBytes; }
    public void setSizeBytes(Long sizeBytes) { this.sizeBytes = sizeBytes; }
    public String getSha256() { return sha256; }
    public void setSha256(String sha256) { this.sha256 = sha256; }
    public String getStorageKey() { return storageKey; }
    public void setStorageKey(String storageKey) { this.storageKey = storageKey; }
    public String getRequestId() { return requestId; }
    public void setRequestId(String requestId) { this.requestId = requestId; }
    public String getParseStatus() { return parseStatus; }
    public void setParseStatus(String parseStatus) { this.parseStatus = parseStatus; }
    public Long getParseAttempt() { return parseAttempt; }
    public void setParseAttempt(Long parseAttempt) { this.parseAttempt = parseAttempt; }
    public String getParserVersion() { return parserVersion; }
    public void setParserVersion(String parserVersion) { this.parserVersion = parserVersion; }
    public String getErrorCode() { return errorCode; }
    public void setErrorCode(String errorCode) { this.errorCode = errorCode; }
    public String getErrorMessage() { return errorMessage; }
    public void setErrorMessage(String errorMessage) { this.errorMessage = errorMessage; }
    public String getWarnings() { return warnings; }
    public void setWarnings(String warnings) { this.warnings = warnings; }
    public Integer getBlockCount() { return blockCount; }
    public void setBlockCount(Integer blockCount) { this.blockCount = blockCount; }
    public Integer getTextCharCount() { return textCharCount; }
    public void setTextCharCount(Integer textCharCount) { this.textCharCount = textCharCount; }
    public LocalDateTime getCreatedAt() { return createdAt; }
    public void setCreatedAt(LocalDateTime createdAt) { this.createdAt = createdAt; }
    public LocalDateTime getUpdatedAt() { return updatedAt; }
    public void setUpdatedAt(LocalDateTime updatedAt) { this.updatedAt = updatedAt; }
    public LocalDateTime getParsedAt() { return parsedAt; }
    public void setParsedAt(LocalDateTime parsedAt) { this.parsedAt = parsedAt; }
}
