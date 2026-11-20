package com.learnloop.tutor;

import java.util.List;
import org.apache.ibatis.annotations.*;

/** 统一摘要只读投影。每个分支先按用户与资料过滤，再排序限量，避免跨用户或跨资料混入证据。 */
@Mapper
public interface LearningSummaryMapper {
    record Attempt(String kind, String id, String familyId, Long materialId, String materialName,
                   String topicId, String topicName, String content, String optionA, String optionB,
                   String optionC, String optionD, String selected, String correctAnswer, boolean correct,
                   boolean assisted, String confidence, String reason, String detail, String occurredAt) {}
    record Reading(String kind, String id, Long materialId, String materialName, Integer page,
                   String quote, String question, String content, String intent, String occurredAt) {}

    @Select("SELECT COUNT(*)>0 FROM materials WHERE user_id=#{user} AND id=#{material}")
    boolean owns(@Param("user") long user, @Param("material") long material);

    // 正式复习用 reviewed_at；未评分记录没有可靠发生时间，不能拿到期时间冒充答题时间。
    // 这些行仅用于辅导，绝不写 learning_events，也不把重复复习当成新增 BKT 证据。
    @Select("""
        SELECT a.kind,a.id,q.family_id AS familyId,c.material_id AS materialId,m.original_name AS materialName,
          t.id AS topicId,t.name AS topicName,LEFT(q.content,500) AS content,
          LEFT(q.option_a,120) AS optionA,LEFT(q.option_b,120) AS optionB,
          LEFT(q.option_c,120) AS optionC,LEFT(q.option_d,120) AS optionD,
          a.selected,q.correct_answer AS correctAnswer,(a.selected=q.correct_answer) AS correct,
          a.assisted,a.confidence,f.reason,LEFT(f.detail,200) AS detail,CAST(a.occurred_at AS CHAR) AS occurredAt
        FROM (
          SELECT 'PRACTICE' AS kind,id,user_id,question_version_id,selected_option AS selected,
            assisted,confidence,submitted_at AS occurred_at
          FROM learning_attempts WHERE user_id=#{user} AND status='SUBMITTED'
            AND submitted_at>=UTC_TIMESTAMP(6)-INTERVAL 30 DAY
          UNION ALL
          SELECT 'REVIEW',id,user_id,question_version_id,selected,FALSE,NULL,reviewed_at
          FROM topic_review_attempts WHERE user_id=#{user} AND rating IS NOT NULL
            AND reviewed_at>=UTC_TIMESTAMP(6)-INTERVAL 30 DAY
        ) a
        JOIN learning_questions q ON q.user_id=a.user_id AND q.id=a.question_version_id
        JOIN learning_concepts c ON c.user_id=q.user_id AND c.id=q.concept_id
        JOIN materials m ON m.user_id=c.user_id AND m.id=c.material_id
        LEFT JOIN adaptive_topic_links l ON l.user_id=q.user_id AND l.concept_id=q.concept_id
        LEFT JOIN adaptive_topics t ON t.user_id=l.user_id AND t.id=l.topic_id
        LEFT JOIN tutor_feedback f ON f.user_id=a.user_id AND f.attempt_kind=a.kind AND f.attempt_id=a.id
        WHERE (#{material} IS NULL OR c.material_id=#{material})
        ORDER BY a.occurred_at DESC,a.kind,a.id DESC LIMIT 8
        """)
    List<Attempt> attempts(@Param("user") long user, @Param("material") Long material);

    // PDF 批注及其笔记合成一条；阅读问答的高亮与同步笔记也按同一 ID 合并。
    // 原件标记不依赖重新解析；文字高亮只在 parse_attempt 仍匹配时提供定位。
    @Select("""
        SELECT kind,id,materialId,materialName,page,quote,question,content,intent,CAST(updated AS CHAR) AS occurredAt
        FROM (
          SELECT 'PDF_ANNOTATION' AS kind,p.id,p.material_id AS materialId,m.original_name AS materialName,
            p.page_number AS page,LEFT(p.quote,250) AS quote,'' AS question,LEFT(COALESCE(n.content,''),350) AS content,
            'HIGHLIGHT' AS intent,GREATEST(p.updated_at,COALESCE(n.updated_at,p.updated_at)) AS updated
          FROM pdf_annotations p JOIN materials m ON m.user_id=p.user_id AND m.id=p.material_id
          LEFT JOIN reading_notes n ON n.user_id=p.user_id AND n.material_id=p.material_id AND n.id=p.note_id
          WHERE p.user_id=#{user} AND p.deleted=0 AND (#{material} IS NULL OR p.material_id=#{material})
          UNION ALL
          SELECT 'READING_NOTE',n.id,n.material_id,m.original_name,NULL,LEFT(n.quote,250),LEFT(n.question,120),
            LEFT(n.content,350),COALESCE(s.intent,'HIGHLIGHT'),n.updated_at
          FROM reading_notes n JOIN materials m ON m.user_id=n.user_id AND m.id=n.material_id
          LEFT JOIN reading_highlights h ON h.id=n.id AND h.user_id=n.user_id AND h.material_id=n.material_id
            AND h.parse_attempt=m.parse_attempt AND m.parse_status='PARSED'
          LEFT JOIN reading_highlight_study s ON s.highlight_id=h.id
          WHERE n.user_id=#{user} AND (#{material} IS NULL OR n.material_id=#{material})
            AND NOT EXISTS(SELECT 1 FROM pdf_annotations p WHERE p.user_id=n.user_id AND p.note_id=n.id)
          UNION ALL
          SELECT 'MARK',h.id,h.material_id,m.original_name,NULL,LEFT(h.quote,250),LEFT(h.question,120),
            '',COALESCE(s.intent,'HIGHLIGHT'),h.created_at
          FROM reading_highlights h JOIN materials m ON m.user_id=h.user_id AND m.id=h.material_id
            AND m.parse_status='PARSED' AND m.parse_attempt=h.parse_attempt
          LEFT JOIN reading_highlight_study s ON s.highlight_id=h.id
          WHERE h.user_id=#{user} AND (#{material} IS NULL OR h.material_id=#{material})
            AND NOT EXISTS(SELECT 1 FROM reading_notes n WHERE n.user_id=h.user_id AND n.id=h.id)
          UNION ALL
          SELECT 'NOTE',n.question_version_id,c.material_id,m.original_name,NULL,LEFT(q.content,250),'',
            LEFT(n.content,350),'NOTE',n.updated_at
          FROM question_notes n JOIN learning_questions q ON q.user_id=n.user_id AND q.id=n.question_version_id
          JOIN learning_concepts c ON c.user_id=q.user_id AND c.id=q.concept_id
          JOIN materials m ON m.user_id=c.user_id AND m.id=c.material_id
          WHERE n.user_id=#{user} AND (#{material} IS NULL OR c.material_id=#{material})
        ) reading
        ORDER BY updated DESC,kind,id DESC LIMIT 8
        """)
    List<Reading> reading(@Param("user") long user, @Param("material") Long material);
}
