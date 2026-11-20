package com.learnloop.tutor;

import java.time.LocalDateTime;
import java.util.List;
import org.apache.ibatis.annotations.*;

@Mapper
interface TutorPlanMapper {
    record Candidate(String versionId, String topicId, String topic, long materialId, String material,
                     LocalDateTime dueAt, int recentErrors) {}
    record Row(String id, String status, int minutes, String reason, String planner, String trace,
               String items, int position, String activeAttempt, LocalDateTime createdAt) {}

    // 题池取自已发布考点的复习卡。使用当前解析版本的题，避免把旧锚点加入新计划。
    String CANDIDATES = """
        SELECT q.id AS versionId,t.id AS topicId,t.name AS topic,c.material_id AS materialId,
            m.original_name AS material,c.due_at AS dueAt,
            (SELECT COUNT(DISTINCT a.question_version_id) FROM learning_attempts a
             JOIN learning_questions old ON old.user_id=a.user_id AND old.id=a.question_version_id
             JOIN adaptive_topic_links al ON al.user_id=old.user_id AND al.concept_id=old.concept_id
             WHERE a.user_id=c.user_id AND al.topic_id=c.topic_id AND a.is_correct=FALSE
              AND a.assisted=FALSE AND a.submitted_at>=UTC_TIMESTAMP(6)-INTERVAL 30 DAY) AS recentErrors
        FROM topic_review_cards c
        JOIN adaptive_topics t ON t.user_id=c.user_id AND t.id=c.topic_id
        JOIN materials m ON m.user_id=c.user_id AND m.id=c.material_id AND m.parse_status='PARSED'
        LEFT JOIN topic_review_attempts r ON r.user_id=c.user_id AND r.topic_id=c.topic_id AND r.prior_due=c.due_at
        JOIN learning_questions q ON q.user_id=c.user_id AND q.id=COALESCE(r.question_version_id, (
            SELECT v.id FROM adaptive_topic_links l
            JOIN learning_questions v ON v.user_id=l.user_id AND v.concept_id=l.concept_id
            JOIN learning_concepts cv ON cv.user_id=v.user_id AND cv.id=v.concept_id
            WHERE l.user_id=c.user_id AND l.topic_id=c.topic_id AND cv.parse_attempt=m.parse_attempt
            ORDER BY v.created_at DESC,v.id DESC LIMIT 1
        ))
        JOIN learning_concepts cv ON cv.user_id=q.user_id AND cv.id=q.concept_id AND cv.parse_attempt=m.parse_attempt
        WHERE c.user_id=#{user}
        """;

    @Select(CANDIDATES + """
        AND (#{material} IS NULL OR c.material_id=#{material})
        ORDER BY (c.due_at<=UTC_TIMESTAMP(6)) DESC, recentErrors DESC, c.due_at,c.topic_id LIMIT 18
        """)
    List<Candidate> candidates(@Param("user") long user, @Param("material") Long material);

    @Select("""
        SELECT EXISTS(SELECT 1 FROM learning_questions q
        JOIN learning_concepts cv ON cv.user_id=q.user_id AND cv.id=q.concept_id
        JOIN materials m ON m.user_id=cv.user_id AND m.id=cv.material_id
            AND m.parse_status='PARSED' AND m.parse_attempt=cv.parse_attempt
        JOIN adaptive_topic_links l ON l.user_id=q.user_id AND l.concept_id=q.concept_id
        JOIN topic_review_cards c ON c.user_id=l.user_id AND c.topic_id=l.topic_id AND c.material_id=m.id
        WHERE q.user_id=#{user} AND q.id=#{item.versionId} AND c.topic_id=#{item.topicId}
          AND (#{item.dueAt} IS NULL OR (c.due_at=#{item.dueAt} AND c.due_at<=UTC_TIMESTAMP(6)
              AND NOT EXISTS (SELECT 1 FROM topic_review_attempts a WHERE a.user_id=c.user_id
                  AND a.topic_id=c.topic_id AND a.prior_due=c.due_at AND a.question_version_id<>q.id))))
        """)
    boolean available(@Param("user") long user, @Param("item") TutorPlanService.Item item);

    @Insert("""
        INSERT INTO tutor_plans(id,user_id,status,minutes,reason,planner,trace,items,created_at)
        VALUES(#{r.id},#{user},'DRAFT',#{r.minutes},#{r.reason},#{r.planner},#{r.trace},#{r.items},#{r.createdAt})
        """)
    void insert(@Param("user") long user, @Param("r") Row row);

    String COLUMNS = """
        id,status,minutes,reason,planner,trace,items,position,
        active_attempt AS activeAttempt,created_at AS createdAt
        """;

    @Select("SELECT " + COLUMNS + " FROM tutor_plans WHERE user_id=#{user} AND id=#{id} FOR UPDATE")
    Row lock(@Param("user") long user, @Param("id") String id);

    @Select("SELECT " + COLUMNS + " FROM tutor_plans WHERE user_id=#{user} AND status IN ('DRAFT','ACTIVE')"
            + " ORDER BY created_at DESC,id DESC LIMIT 1")
    Row latest(@Param("user") long user);

    @Update("UPDATE tutor_plans SET status=#{status},position=#{position},active_attempt=#{attempt} WHERE user_id=#{user} AND id=#{id}")
    void update(@Param("user") long user, @Param("id") String id, @Param("status") String status,
                @Param("position") int position, @Param("attempt") String attempt);

    @Select("""
        SELECT EXISTS(SELECT 1 FROM topic_review_attempts a WHERE a.user_id=#{user}
            AND a.topic_id=#{item.topicId} AND a.prior_due=#{item.dueAt}
            AND a.question_version_id=#{item.versionId} AND a.rating IS NOT NULL)
        """)
    boolean reviewDone(@Param("user") long user, @Param("item") TutorPlanService.Item item);

    @Select("""
        SELECT EXISTS(SELECT 1 FROM learning_attempts WHERE user_id=#{user} AND id=#{attempt}
            AND question_version_id=#{version} AND status='SUBMITTED')
        """)
    boolean practiceDone(@Param("user") long user, @Param("attempt") String attempt, @Param("version") String version);
}
