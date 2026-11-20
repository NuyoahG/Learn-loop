package com.learnloop.experiments.bkt;
//定义“Python 返回给 Java 的数据
import com.fasterxml.jackson.annotation.JsonProperty;

/**
 * Python BKT 接口的成功响应。
 *
 * Python 返回的结构是：
 * {
 *   "event_id": "...",
 *   "status": "APPLIED",
 *   "state": { ... }
 * }
 *
 * 因此，Java 也使用相同的层级结构。
 */
public record BktAnswerResponse(

        @JsonProperty("event_id")
        String eventId,

        // 例如 APPLIED、IGNORED_ASSISTED。
        String status,

        // state 是一个嵌套 JSON 对象，
        // 所以这里使用另一个 record 来接收。
        State state
) {

    /**
     * 一次事件处理完成后返回的学习状态快照。
     *
     * 把 State 写在响应类里面，是为了表达：
     * 它是这个响应的一部分。
     */
    public record State(

            @JsonProperty("user_id")
            long userId,

            @JsonProperty("concept_id")
            String conceptId,

            @JsonProperty("concept_version")
            int conceptVersion,

            @JsonProperty("model_version")
            String modelVersion,

            // 当前 BKT 掌握概率估计。
            @JsonProperty("p_mastery")
            double pMastery,

            // 已计入模型更新的有效作答数量。
            @JsonProperty("evidence_count")
            int evidenceCount
    ) {
    }
}