package com.learnloop.generation;

import java.time.LocalDateTime;
import java.util.List;

/** 数据库快照与 HTTP 输出分开。snapshotJson 是私有来源快照，绝不直接返回浏览器。 */
public final class GenerationRows {
    private GenerationRows() { }
    public record Job(String id, long userId, long materialId, String requestKey, String requestDigest,
                      String status, int requestedCount, String difficulty, String granularity, String snapshotJson,
                      int attemptCount, String claimToken, LocalDateTime leaseUntil, String errorCode,
                      String model, String shortfallReason, LocalDateTime createdAt, LocalDateTime updatedAt) { }
    public record FrozenSource(long blockId, String text, String locator, String noiseRanges) {
        public FrozenSource(long blockId,String text,String locator) { this(blockId,text,locator,null); }
    }
    /**
     * 旧任务快照没有 mode/targetConceptId，Jackson 会读成 null；调用方按 NEW 处理。
     * variant 任务只冻结目标考点的来源块，避免把整段资料再次发给模型。
     */
    public record Snapshot(String materialSha256, long parseAttempt, List<FrozenSource> sources,
                           String mode, String targetConceptId, Boolean refreshCatalog) {
        // 包装类型兼容旧 JSON 中缺失的新字段；不能把旧任务因 boolean 缺失而反序列化失败。
        public Snapshot { refreshCatalog=Boolean.TRUE.equals(refreshCatalog); }
        public Snapshot(String materialSha256,long parseAttempt,List<FrozenSource> sources,String mode,String targetConceptId) {
            this(materialSha256,parseAttempt,sources,mode,targetConceptId,false);
        }
        public Snapshot(String materialSha256, long parseAttempt, List<FrozenSource> sources) {
            this(materialSha256, parseAttempt, sources, "NEW", null);
        }
    }
    /** 跨批次去重所需的已出题摘要；不包含正确答案。 */
    public record KnownQuestionRow(long blockId, String sourceQuote, String content, String conceptId) { }
    public record Published(String jobId, int itemNo, long userId, String questionVersionId,
                            long blockId, String sourceQuote, String sourceLocator, String difficulty) { }
    /** MyBatis 通过 setter 回填旧 questions 自增编号，答案只在内部使用。 */
    public static class NewQuestion {
        private Long id;
        private final long userId;
        private final GenerationProtocol.Item item;
        public NewQuestion(long userId, GenerationProtocol.Item item) { this.userId = userId; this.item = item; }
        public Long getId() { return id; }
        public void setId(Long id) { this.id = id; }
        public long getUserId() { return userId; }
        public GenerationProtocol.Item getItem() { return item; }
    }
}
