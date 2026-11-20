package com.learnloop.answer;

import java.time.LocalDateTime;

/** 历史回看所需的作答快照；旧记录没有题目版本时，题干和选项显示当前内容。 */
public record AnswerRecordDetail(
        Long recordId,
        Long questionId,
        String selectedOption,
        boolean correct,
        LocalDateTime submittedAt,
        String correctOptionSnapshot,
        String questionVersionId,
        String content,
        String optionA,
        String optionB,
        String optionC,
        String optionD
) {}
