package com.learnloop.learning;

import java.time.LocalDate;
import java.time.LocalDateTime;

/**
 * 学习闭环持久化快照。record 只传递已确定的数据，避免 MyBatis 查询结果被 Service 随意修改。
 *
 * <p>所有 UUID 均由服务端创建；所有 LocalDateTime 均表示 UTC。掌握度针对概念，题目版本则保存
 * 不可变题干与答案。二者不能混为同一个对象，否则改题或重新解析资料会篡改历史学习证据。</p>
 */
public final class LearningRows {
    private LearningRows() { }

    /** 仅在绑定概念时读取已发布正文，正文及来源定位随后复制进 Concept。 */
    public record Source(long materialId, long blockId, String sha256, long parseAttempt,
                         String originalName, String textContent, String sourceLocator) { }

    /** 既有 questions 表的完整内部视图；正确答案绝不能直接作为取题接口响应。 */
    public record ExistingQuestion(long id, long userId, String content, String optionA,
                                   String optionB, String optionC, String optionD, String correctAnswer) { }

    /** 稳定概念：同名不代表同一概念，主键及来源版本共同保存其语义。 */
    public record Concept(String id, long userId, String requestKey, long materialId, long blockId,
                          String materialSha256, long parseAttempt, String name, String definition,
                          String granularity, String sourceText, String sourceLocator,
                          LocalDateTime createdAt) { }

    /**
     * 一道旧题在 M1 只绑定一次；题干、选项、答案和主考点固定在版本中。
     * familyId 由服务端控制，用于防止同一道题的轻微变式在同日重复贡献独立证据。
     */
    public record QuestionVersion(String id, long userId, long questionId, String conceptId,
                                  String familyId, String content, String optionA, String optionB,
                                  String optionC, String optionD, String correctAnswer,
                                  String contentDigest, LocalDateTime createdAt) { }

    /**
     * 一次有期限的答题尝试。创建 requestKey/requestDigest 和提交 submitKey 分开保存；
     * 由此可以区分“重新发送创建请求”和“重新发送答案”，防止网络重试产生第二条学习事件。
     */
    public record Attempt(String id, long userId, String questionVersionId, String requestKey,
                          String requestDigest, String status, LocalDateTime expiresAt,
                          boolean assisted, LocalDateTime assistedAt, String submitKey,
                          String confidence, String selectedOption, Boolean correct,
                          Long answerRecordId, String eventId, String ineligibilityReason,
                          LocalDateTime createdAt, LocalDateTime submittedAt) { }

    /**
     * nextSequence 是已分配的事件号，appliedSequence 是已应用的事件号；两者可短暂不一致。
     * stateVersion 只在应用算法结果时递增，不因队列新增事件而变化。
     */
    public record Mastery(long userId, String conceptId, String modelVersion,
                         double masteryProbability, long nextSequence, long appliedSequence,
                         long stateVersion, long evidenceCount, LocalDateTime updatedAt) { }

    /**
     * 只有符合独立答题条件的观测才产生事件。claimToken 是每次认领的新 UUID，
     * 旧 worker 即使稍后返回也不能覆盖重新认领后的结果。
     */
    public record Event(String id, long userId, String conceptId, String questionVersionId,
                        String attemptId, String modelVersion, long sequenceNo, boolean correct,
                        String status, Double priorProbability, Double appliedProbability,
                        String claimToken, int attemptCount, LocalDateTime retryAt,
                        LocalDateTime leaseUntil, String errorCode, LocalDateTime createdAt,
                        LocalDateTime appliedAt) { }

    /** 每用户、题目版本只观测一次；每用户、变式家族、UTC 自然日最多观测一次。 */
    public record EvidenceClaim(String id, long userId, String questionVersionId, String familyId,
                                LocalDate evidenceDate, String attemptId, LocalDateTime createdAt) { }

    /**
     * 题目中心的只读行。jobId 可能为空：M1 人工绑定的旧题没有生成批次，
     * 页面仍应能按资料和作答状态找到它们。
     * answered 是“是否已有已提交尝试”；latestCorrect 只用于展示最近一次判分，不参与 BKT。
     */
    public record LibraryQuestion(String id, long userId, long questionId, String conceptId,
                                  String content, String optionA, String optionB, String optionC,
                                  String optionD, LocalDateTime createdAt, long materialId,
                                  String materialName, String conceptName, String jobId,
                                  LocalDateTime jobCreatedAt, String difficulty, String sourceLocator,
                                  boolean answered, Boolean latestCorrect) { }
}
