package com.learnloop.answer;

import org.apache.ibatis.annotations.Insert;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Options;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;

import java.util.List;

@Mapper
public interface AnswerMapper {

    // user_id 与本次答题内容一起写入，满足数据库的 NOT NULL 和外键约束。
    @Insert("""
            INSERT INTO answer_records (
                user_id,
                question_id,
                selected_option,
                correct_option_snapshot,
                is_correct
            )
            VALUES (
                #{userId},
                #{questionId},
                #{selectedOption},
                #{correctOptionSnapshot},
                #{correct}
            )
            """)
    @Options(useGeneratedKeys = true, keyProperty = "id")
    int insert(AnswerRecord record);

    // 以这条记录对应的提交为准；旧接口写入的记录没有题干快照，才回退到当前题目。
    @Select("""
            SELECT a.id AS recordId,
                   a.question_id AS questionId,
                   a.selected_option AS selectedOption,
                   a.is_correct AS correct,
                   a.submitted_at AS submittedAt,
                   a.correct_option_snapshot AS correctOptionSnapshot,
                   snapshot.id AS questionVersionId,
                   COALESCE(snapshot.content, q.content) AS content,
                   COALESCE(snapshot.option_a, q.option_a) AS optionA,
                   COALESCE(snapshot.option_b, q.option_b) AS optionB,
                   COALESCE(snapshot.option_c, q.option_c) AS optionC,
                   COALESCE(snapshot.option_d, q.option_d) AS optionD
            FROM answer_records a
            LEFT JOIN learning_attempts attempt
                ON attempt.answer_record_id = a.id AND attempt.user_id = a.user_id AND attempt.status = 'SUBMITTED'
            LEFT JOIN learning_questions snapshot
                ON snapshot.id = attempt.question_version_id AND snapshot.user_id = a.user_id AND snapshot.question_id = a.question_id
            LEFT JOIN questions q ON q.id = a.question_id AND q.user_id = a.user_id
            WHERE a.user_id = #{userId}
              AND a.id = #{recordId}
            """)
    AnswerRecordDetail findById(
            @Param("userId") Long userId,
            @Param("recordId") Long recordId
    );

    // user_id 是每次都必须有的条件；correct 才是可选筛选条件。
    // 排序方向通过固定 SQL 分支选择，不把 sort 直接拼进 SQL。
    @Select("""
            <script>
            SELECT a.id AS recordId,
                   a.question_id AS questionId,
                   a.selected_option AS selectedOption,
                   a.is_correct AS correct,
                   a.submitted_at AS submittedAt,
                   COALESCE(snapshot.content, q.content) AS content
            FROM answer_records a
            LEFT JOIN learning_attempts attempt
                ON attempt.answer_record_id = a.id AND attempt.user_id = a.user_id AND attempt.status = 'SUBMITTED'
            LEFT JOIN learning_questions snapshot
                ON snapshot.id = attempt.question_version_id AND snapshot.user_id = a.user_id AND snapshot.question_id = a.question_id
            LEFT JOIN questions q ON q.id = a.question_id AND q.user_id = a.user_id
            WHERE a.user_id = #{userId}
            <if test="correct != null">
                AND a.is_correct = #{correct}
            </if>
            <choose>
                <when test="sort == 'asc'">
                    ORDER BY a.submitted_at ASC, a.id ASC
                </when>
                <otherwise>
                    ORDER BY a.submitted_at DESC, a.id DESC
                </otherwise>
            </choose>
            LIMIT #{size} OFFSET #{offset}
            </script>
            """)
    List<AnswerRecordResponse> findPage(
            @Param("userId") Long userId,
            @Param("size") int size,
            @Param("offset") long offset,
            @Param("correct") Boolean correct,
            @Param("sort") String sort
    );

    // 统计使用与列表相同的用户限制和对错筛选，保证分页信息对应本人数据。
    @Select("""
            <script>
            SELECT COUNT(*)
            FROM answer_records
            WHERE user_id = #{userId}
            <if test="correct != null">
                AND is_correct = #{correct}
            </if>
            </script>
            """)
    long countByCorrect(
            @Param("userId") Long userId,
            @Param("correct") Boolean correct
    );
}
