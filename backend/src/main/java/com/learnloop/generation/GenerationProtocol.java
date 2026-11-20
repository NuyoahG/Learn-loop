package com.learnloop.generation;

import java.util.List;

/** Java 与 Python 的最小出题契约。所有来源均由 Java 从本人资料冻结，模型不能指定用户。 */
public final class GenerationProtocol {
    private GenerationProtocol() { }
    /**
     * mode=NEW 表示正常选资料出题，已有考点只作为“不要重复”的目录；
     * mode=VARIANT 表示用户明确要求为某个考点再出一道不同考法的题。
     */
    public record Request(String schemaVersion, String jobId, String attemptToken, int count,
                          String difficulty, String granularity, List<Source> sources, List<KnownConcept> knownConcepts,
                          String mode, String targetConceptId, List<KnownQuestion> knownQuestions,
                          List<String> reinforcementIds) {
        public Request(String schemaVersion, String jobId, String attemptToken, int count,
                       String difficulty, String granularity, List<Source> sources, List<KnownConcept> knownConcepts,
                       String mode, String targetConceptId, List<KnownQuestion> knownQuestions) {
            this(schemaVersion, jobId, attemptToken, count, difficulty, granularity, sources, knownConcepts,
                    mode, targetConceptId, knownQuestions, List.of());
        }
        /** 兼容既有调用与测试；默认是普通出题模式。 */
        public Request(String schemaVersion, String jobId, String attemptToken, int count,
                       String difficulty, String granularity, List<Source> sources, List<KnownConcept> knownConcepts) {
            this(schemaVersion, jobId, attemptToken, count, difficulty, granularity, sources, knownConcepts,
                    "NEW", null, List.of());
        }
    }
    public record Source(long blockId, String text, String noiseRanges) {
        public Source(long blockId,String text) { this(blockId,text,null); }
    }
    public record KnownConcept(String conceptId, long blockId, String name, String definition) {
        public KnownConcept(String conceptId, long blockId, String name) {
            this(conceptId, blockId, name, "");
        }
    }
    /** 已出过的题只传作答前可见字段，用于避免跨批次重复事实和过于相似的变式。 */
    public record KnownQuestion(long blockId, String sourceQuote, String content) { }
    /** conceptId 可空；已有稳定考点必须回传原编号。答案仅在服务内部传递。 */
    public record Item(String conceptId, long blockId, String sourceQuote, String conceptName,
                       String conceptDefinition, String content, String optionA, String optionB,
                       String optionC, String optionD, String correctAnswer, String explanation) { }
    public record Result(String schemaVersion, String jobId, String attemptToken, String model,
                         List<Item> items, String shortfallReason) { }
}
