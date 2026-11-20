package com.learnloop.tutor;

import java.util.List;
import org.apache.ibatis.annotations.*;

@Mapper
public interface TutorMapper {
    record Feedback(String reason, String detail) {}
    record ErrorEvidence(String attemptId, String familyId, String topicId, String topicName,
                         String chapter, String groupName, long materialId, String materialName,
                         String reason, String detail) {}
    record Mark(String id, long materialId, String materialName, long blockId, String quote, String question,
                String intent, String topicId, String topicName) {}
    record Note(String versionId, long materialId, String materialName, String content) {}
    record DueTopic(String topicId, String topicName, long materialId, String materialName) {}

    @Select("""
        SELECT id FROM learning_attempts
        WHERE user_id=#{user} AND id=#{id} AND status='SUBMITTED' AND is_correct=FALSE
        """)
    String wrongPractice(@Param("user") long user, @Param("id") String id);

    @Select("""
        SELECT a.id FROM topic_review_attempts a
        JOIN learning_questions q ON q.user_id=a.user_id AND q.id=a.question_version_id
        WHERE a.user_id=#{user} AND a.id=#{id} AND a.selected<>q.correct_answer
        """)
    String wrongReview(@Param("user") long user, @Param("id") String id);

    @Select("""
        SELECT reason,detail FROM tutor_feedback
        WHERE user_id=#{user} AND attempt_kind=#{kind} AND attempt_id=#{id}
        """)
    Feedback feedback(@Param("user") long user, @Param("kind") String kind, @Param("id") String id);

    @Insert("""
        INSERT INTO tutor_feedback(user_id,attempt_kind,attempt_id,reason,detail,updated_at)
        VALUES(#{user},#{kind},#{id},#{f.reason},#{f.detail},UTC_TIMESTAMP(6)) AS incoming
        ON DUPLICATE KEY UPDATE reason=incoming.reason,detail=incoming.detail,updated_at=incoming.updated_at
        """)
    void saveFeedback(@Param("user") long user, @Param("kind") String kind, @Param("id") String id,
                      @Param("f") Feedback feedback);

    // 保留题族 ID，避免同一道题反复答错被当作习惯证据。
    @Select("""
        SELECT a.id AS attemptId,q.family_id AS familyId,t.id AS topicId,t.name AS topicName,
            t.chapter,t.group_name AS groupName,c.material_id AS materialId,m.original_name AS materialName,
            f.reason,f.detail
        FROM learning_attempts a
        JOIN learning_questions q ON q.user_id=a.user_id AND q.id=a.question_version_id
        JOIN learning_concepts c ON c.user_id=q.user_id AND c.id=q.concept_id
        JOIN materials m ON m.user_id=c.user_id AND m.id=c.material_id
        LEFT JOIN adaptive_topic_links l ON l.user_id=q.user_id AND l.concept_id=q.concept_id
        LEFT JOIN adaptive_topics t ON t.user_id=l.user_id AND t.id=l.topic_id
        LEFT JOIN tutor_feedback f ON f.user_id=a.user_id AND f.attempt_kind='PRACTICE' AND f.attempt_id=a.id
        WHERE a.user_id=#{user} AND a.status='SUBMITTED' AND a.is_correct=FALSE AND a.assisted=FALSE
            AND a.submitted_at>=UTC_TIMESTAMP(6)-INTERVAL 30 DAY
        ORDER BY a.submitted_at DESC,a.id DESC LIMIT 60
        """)
    List<ErrorEvidence> recentErrors(@Param("user") long user);

    @Select("""
        SELECT t.id AS topicId,t.name AS topicName,c.material_id AS materialId,m.original_name AS materialName
        FROM topic_review_cards c
        JOIN adaptive_topics t ON t.user_id=c.user_id AND t.id=c.topic_id
        JOIN materials m ON m.user_id=c.user_id AND m.id=c.material_id
        WHERE c.user_id=#{user} AND c.due_at<=UTC_TIMESTAMP(6)
            AND (#{material} IS NULL OR c.material_id=#{material})
        ORDER BY c.due_at,t.id LIMIT 6
        """)
    List<DueTopic> due(@Param("user") long user, @Param("material") Long material);

    @Select("""
        SELECT h.id,h.material_id AS materialId,m.original_name AS materialName,h.block_id AS blockId,
            LEFT(h.quote,300) AS quote,LEFT(h.question,160) AS question,s.intent,t.id AS topicId,t.name AS topicName
        FROM reading_highlights h
        LEFT JOIN reading_highlight_study s ON s.highlight_id=h.id
        LEFT JOIN adaptive_topics t ON t.id=s.topic_id AND t.user_id=h.user_id AND t.material_id=h.material_id
        JOIN materials m ON m.user_id=h.user_id AND m.id=h.material_id
          AND m.parse_status='PARSED' AND m.parse_attempt=h.parse_attempt
        WHERE h.user_id=#{user} AND (#{material} IS NULL OR h.material_id=#{material})
        ORDER BY (LOCATE(#{query},h.quote)>0 OR LOCATE(#{query},h.question)>0) DESC,
            (s.intent IN ('UNCLEAR','CONFUSED')) DESC, h.created_at DESC,h.id DESC LIMIT 5
        """)
    List<Mark> marks(@Param("user") long user, @Param("material") Long material, @Param("query") String query);

    @Select("""
        SELECT n.question_version_id AS versionId,c.material_id AS materialId,m.original_name AS materialName,
            LEFT(n.content,350) AS content
        FROM question_notes n
        JOIN learning_questions q ON q.user_id=n.user_id AND q.id=n.question_version_id
        JOIN learning_concepts c ON c.user_id=q.user_id AND c.id=q.concept_id
        JOIN materials m ON m.user_id=c.user_id AND m.id=c.material_id
        WHERE n.user_id=#{user} AND (#{material} IS NULL OR c.material_id=#{material})
        ORDER BY (LOCATE(#{query},n.content)>0) DESC,n.updated_at DESC LIMIT 4
        """)
    List<Note> notes(@Param("user") long user, @Param("material") Long material, @Param("query") String query);

    @Select("""
        SELECT suggestion_key FROM tutor_suggestion_state WHERE user_id=#{user}
            AND (status='CORRECTED' OR updated_at>=UTC_TIMESTAMP(6)-INTERVAL 1 DAY)
        """)
    List<String> suppressed(@Param("user") long user);

    @Select("SELECT suggestion_key FROM tutor_suggestion_state WHERE user_id=#{user} AND status='CORRECTED'")
    List<String> corrected(@Param("user") long user);

    @Insert("""
        INSERT INTO tutor_suggestion_state(user_id,suggestion_key,status,updated_at)
        VALUES(#{user},#{key},#{status},UTC_TIMESTAMP(6)) AS incoming
        ON DUPLICATE KEY UPDATE
            status=IF(tutor_suggestion_state.status='CORRECTED','CORRECTED',incoming.status),
            updated_at=incoming.updated_at
        """)
    void respond(@Param("user") long user, @Param("key") String key, @Param("status") String status);
}
