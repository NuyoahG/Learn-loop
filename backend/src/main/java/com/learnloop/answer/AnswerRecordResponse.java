package com.learnloop.answer; // 声明当前类型所属的包

import java.time.LocalDateTime; // 导入日期时间类型，用于表示提交时间

public record AnswerRecordResponse( // 定义查询接口返回的数据结构
                                    Long recordId, // 答题记录编号，对应 answer_records.id
                                    Long questionId, // 题目编号，对应 answer_records.question_id
                                    String selectedOption, // 用户当时选择的选项
                                    boolean correct, // 当时的判分结果：true 答对，false 答错
                                    LocalDateTime submittedAt, // 本次作答的提交时间
                                    String content // 优先使用作答时的题干快照，供记录列表显示题目
) { // record 类型的主体开始
} // 类型定义结束
