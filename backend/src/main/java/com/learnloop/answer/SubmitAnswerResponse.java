package com.learnloop.answer;

/**
 * 提交答案成功后返回给浏览器的数据。
 *
 * record 会自动提供 recordId()、correct()、correctOption() 等访问方法，
 * Spring 将这些属性序列化成同名 JSON 字段，网页即可读取 result.correctOption。
 *
 * 正确选项只在提交成功后的响应中提供；取题接口继续使用 QuestionResponse，
 * 不增加正确答案字段，避免用户在作答前直接从取题响应中看到答案。
 *
 * @param recordId 本次已经保存的答题记录编号
 * @param correct 本次是否答对；false 表示答错，仍然是一次正常保存的作答
 * @param correctOption 本次判分时使用的正确选项，例如 B，与保存的答案快照一致
 */
public record SubmitAnswerResponse(
        Long recordId,
        boolean correct,
        String correctOption
) {
}
