package com.learnloop.question;

import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;

@Mapper
public interface QuestionMapper {

    // 取题响应不包含正确答案；只有“用户编号 + 题目编号”同时匹配才返回。
    @Select("""
            SELECT id,
                   content,
                   option_a AS optionA,
                   option_b AS optionB,
                   option_c AS optionC,
                   option_d AS optionD
            FROM questions
            WHERE user_id = #{userId}
              AND id = #{id}
            """)
    QuestionResponse findById(
            @Param("userId") Long userId,
            @Param("id") Long id
    );

    // 判分读取正确答案时也检查归属；查不到时返回 null。
    // 所有方法统一把 userId 放在第一个参数，减少调用时写反的机会。
    @Select("""
            SELECT correct_answer
            FROM questions
            WHERE user_id = #{userId}
              AND id = #{id}
            """)
    String findCorrectAnswerById(
            @Param("userId") Long userId,
            @Param("id") Long id
    );
}
