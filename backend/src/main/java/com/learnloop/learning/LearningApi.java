package com.learnloop.learning;

import java.time.Instant;
import java.util.List;

/**
 * 学习接口契约与数据库对象分开：取题 DTO 根本没有正确答案字段。
 * 所有用户编号都由 Session 提供，浏览器只提交资料、选项和幂等请求键。
 */
public final class LearningApi {
    private LearningApi() { }
    public record CreateConcept(long materialId, long blockId, String name, String definition,
                                String granularity, String requestKey) { }
    public record BindQuestion(String conceptId, String familyReferenceVersionId) { }
    public record CreateAttempt(String questionVersionId, String requestKey) { }
    public record Submit(String selectedOption, String submitKey, String confidence) { }
    public record ConceptView(String id, long materialId, long blockId, String name,
                              String definition, String granularity, long parseAttempt) { }
    public record QuestionView(String versionId, long questionId, String conceptId, String content,
                               String optionA, String optionB, String optionC, String optionD) { }
    public record AttemptView(String attemptId, String status, Instant expiresAt,
                              boolean assisted, QuestionView question) { }
    public record MasteryView(String conceptId, String name, String modelVersion, Double probability,
                              long evidenceCount, long stateVersion, boolean updating,
                              String evidenceStatus, Instant updatedAt) { }
    public record SubmissionView(String attemptId, long recordId, boolean correct, String correctOption,
                                 boolean eligible, String exclusionReason, String eventId, String eventStatus) { }
    public record EventView(String eventId, String status, long sequence, String errorCode,
                            Double priorProbability, Double appliedProbability) { }
    public record Recommendation(QuestionView question, String reasonCode, Double probability,
                                 long evidenceCount, long stateVersion) { }
    public record Selection(long materialId, String policyVersion, String modelVersion,
                            int requested, List<Recommendation> questions, String limitation) { }

    /**
     * 题目中心只返回作答前可安全展示的字段；不返回正确答案、解析或来源正文。
     * sourceLocator 是已受校验的定位 JSON，只用于显示页码/行号。
     */
    public record LibraryQuestion(String versionId, long questionId, long materialId, String materialName,
                                  String conceptId, String conceptName, String content,
                                  String optionA, String optionB, String optionC, String optionD,
                                  String jobId, Instant jobCreatedAt, String difficulty, String sourceLocator,
                                  boolean answered, Boolean latestCorrect, Instant createdAt) { }
    public record LibraryPage(int page, int size, long total, long totalPages,
                              List<LibraryQuestion> records) { }
}
