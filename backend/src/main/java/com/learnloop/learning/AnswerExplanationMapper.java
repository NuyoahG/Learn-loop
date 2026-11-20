package com.learnloop.learning;

import org.apache.ibatis.annotations.*;

/** 已提交答题的讲解投影。身份、题目和原文均从本人记录获取，浏览器不能代传答案或资料正文。 */
@Mapper
public interface AnswerExplanationMapper {
    record Answer(String content,String optionA,String optionB,String optionC,String optionD,
                  String correctAnswer,String explanation,String conceptName,
                  String definition,String sourceText,String selected,String sourceQuote) {}

    // questions 的解析不是版本快照；原题被编辑后不能把新解析挂到旧作答上。
    // BINARY 比较保留大小写等差异，避免数据库排序规则把改变了语义的选项当作相同文本。
    String FIELDS="q.content,q.option_a AS optionA,q.option_b AS optionB,q.option_c AS optionC,q.option_d AS optionD,"
            +"q.correct_answer AS correctAnswer,CASE WHEN BINARY o.content=BINARY q.content "
            +"AND BINARY o.option_a=BINARY q.option_a AND BINARY o.option_b=BINARY q.option_b "
            +"AND BINARY o.option_c=BINARY q.option_c AND BINARY o.option_d=BINARY q.option_d "
            +"AND BINARY o.correct_answer=BINARY q.correct_answer THEN o.explanation ELSE NULL END AS explanation,"
            +"c.name AS conceptName,c.definition,c.source_text AS sourceText";
    String QUOTE="(SELECT i.source_quote FROM generation_job_items i WHERE i.user_id=q.user_id "
            +"AND i.question_version_id=q.id LIMIT 1) AS sourceQuote";
    String JOINS=" JOIN learning_questions q ON q.user_id=a.user_id AND q.id=a.question_version_id "
            +"JOIN learning_concepts c ON c.user_id=q.user_id AND c.id=q.concept_id "
            +"LEFT JOIN questions o ON o.user_id=q.user_id AND o.id=q.question_id ";

    // 构造器映射按列顺序，selected 和 sourceQuote 必须与 Answer 的最后两个字段对应。
    @Select("SELECT "+FIELDS+",a.selected_option AS selected,"+QUOTE+" FROM learning_attempts a "+JOINS
            +"WHERE a.user_id=#{user} AND a.id=#{id} AND a.status='SUBMITTED' AND a.selected_option IS NOT NULL")
    Answer practice(@Param("user") long user,@Param("id") String id);

    // 复习在答题时就创建记录；此时可以讲解，但还没有提交回忆反馈，不要求 rating 已保存。
    @Select("SELECT "+FIELDS+",a.selected AS selected,"+QUOTE+" FROM topic_review_attempts a "+JOINS
            +"WHERE a.user_id=#{user} AND a.id=#{id} AND a.selected IS NOT NULL")
    Answer review(@Param("user") long user,@Param("id") String id);
}
