package com.learnloop.experiments.bkt;
//定义“Java 发给 Python 的数据
import com.fasterxml.jackson.annotation.JsonProperty;

/**
 * BktClientDemo 发起实验
 *     ↓ 创建
 * BktAnswerRequest：装好答题数据
 *     ↓ 交给
 * BktClient：转成 JSON，发送给 Python
 *     ↓
 * Python 处理事件，返回 JSON
 *     ↓
 * BktClient：把 JSON 转成 BktAnswerResponse
 *     ↓
 * BktClientDemo：读取结果、打印并检查
 * 发给 Python BKT 接口的答题事件。
 *
 * DTO 负责描述接口数据结构。
 * 这里不负责判题，也不负责操作数据库。
 *
 * 使用 record：
 * 1. 自动生成构造方法；
 * 2. 自动生成字段访问方法；
 * 3. 自动生成 equals、hashCode 和 toString；
 * 4. 字段引用创建后不能重新赋值，适合表达一次确定的请求。
 */
public record BktAnswerRequest(

        // Java 使用驼峰命名 eventId。
        // 发成 JSON 时，字段名必须是 Python 接口要求的 event_id。
        @JsonProperty("event_id")
        String eventId,

        // 同样，把 Java 的 userId 映射为 JSON 的 user_id。
        @JsonProperty("user_id")
        long userId,

        // 字段名与 Python 一致，所以不需要额外标注。
        // boolean 会被序列化为 JSON 的 true 或 false。
        boolean correct,

        // 是否借助提示、答案或其他辅助完成。
        // 当前实验接口会据此决定是否更新 BKT。
        boolean assisted
) {
}