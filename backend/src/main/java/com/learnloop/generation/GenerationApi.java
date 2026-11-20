package com.learnloop.generation;

import com.learnloop.learning.LearningApi.QuestionView;
import java.time.Instant;
import java.util.List;

/** 浏览器契约刻意不复用模型输出 DTO，以免任务查询在作答前泄露答案或考点定义。 */
public final class GenerationApi {
    private GenerationApi() { }
    public record CreateJob(long materialId, List<Long> blockIds, int count, String difficulty,
                            String granularity, String requestKey, String mode, Boolean refreshCatalog) {
        public CreateJob { refreshCatalog=Boolean.TRUE.equals(refreshCatalog); }
        public CreateJob(long materialId,List<Long> blockIds,int count,String difficulty,String granularity,String requestKey,String mode) {
            this(materialId,blockIds,count,difficulty,granularity,requestKey,mode,false);
        }
        public CreateJob(long materialId, List<Long> blockIds, int count, String difficulty,
                         String granularity, String requestKey) {
            this(materialId, blockIds, count, difficulty, granularity, requestKey, null);
        }
    }
    /** 同考点变式只接受题目版本编号；资料、考点和来源由服务端按所有权重新读取。 */
    public record CreateVariant(String questionVersionId, String requestKey) { }
    // 精确引用可能直接解释答案；作答前仅公开定位，引用正文留给后续 assisted 门控接口。
    public record Question(QuestionView question, long blockId, String sourceLocator, String difficulty) { }
    public record JobView(String jobId, long materialId, String status, int requestedCount, int generatedCount,
                          String difficulty, String granularity, int attemptCount, String errorCode,
                          String shortfallReason, List<Question> questions, Instant createdAt, Instant updatedAt,
                          String mode, String targetConceptId) { }
}
