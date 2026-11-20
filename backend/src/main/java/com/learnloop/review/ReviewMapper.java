package com.learnloop.review;

import java.time.LocalDateTime;
import org.apache.ibatis.annotations.*;

@Mapper
public interface ReviewMapper {
    record Card(long materialId, String topicId, LocalDateTime dueAt) {}
    record Due(long materialId, String topicId, String topicName, String materialName, String versionId,
               String content, String optionA, String optionB, String optionC, String optionD, long remaining,
               LocalDateTime dueAt) {}
    record Question(String correctAnswer, String explanation) {}
    record Attempt(String id, long materialId, String topicId, String versionId, LocalDateTime priorDue,
                   String selected, Integer rating, LocalDateTime nextDue) {}

    @Select("""
        SELECT c.material_id AS materialId,c.topic_id AS topicId,t.name AS topicName,m.original_name AS materialName,
          q.id AS versionId,q.content,q.option_a AS optionA,q.option_b AS optionB,q.option_c AS optionC,q.option_d AS optionD,
          COUNT(*) OVER() AS remaining,c.due_at AS dueAt
        FROM topic_review_cards c
        JOIN adaptive_topics t ON t.id=c.topic_id AND t.user_id=c.user_id
        JOIN materials m ON m.id=c.material_id AND m.user_id=c.user_id
        LEFT JOIN topic_review_attempts a ON a.user_id=c.user_id AND a.material_id=c.material_id
            AND a.topic_id=c.topic_id AND a.prior_due=c.due_at
        JOIN learning_questions q ON q.user_id=c.user_id AND q.id=COALESCE(a.question_version_id, (
            SELECT v.id FROM adaptive_topic_links l JOIN learning_questions v
              ON v.user_id=l.user_id AND v.concept_id=l.concept_id
            WHERE l.user_id=c.user_id AND l.topic_id=c.topic_id ORDER BY v.created_at DESC,v.id DESC LIMIT 1
        ))
        WHERE c.user_id=#{user} AND c.due_at <= #{now}
        ORDER BY c.due_at,c.topic_id LIMIT 1
        """)
    Due next(@Param("user") long user, @Param("now") LocalDateTime now);

    @Select("""
        SELECT material_id AS materialId,topic_id AS topicId,due_at AS dueAt
        FROM topic_review_cards WHERE user_id=#{user} AND topic_id=#{topic} FOR UPDATE
        """)
    Card lockCard(@Param("user") long user, @Param("topic") String topic);

    @Select("""
        SELECT q.correct_answer AS correctAnswer,o.explanation
        FROM learning_questions q JOIN adaptive_topic_links l ON l.user_id=q.user_id AND l.concept_id=q.concept_id
        JOIN questions o ON o.id=q.question_id AND o.user_id=q.user_id
        WHERE q.user_id=#{user} AND q.id=#{version} AND l.topic_id=#{topic}
        """)
    Question question(@Param("user") long user, @Param("topic") String topic, @Param("version") String version);

    String ATTEMPT_FIELDS = "id,material_id AS materialId,topic_id AS topicId,question_version_id AS versionId,"
            + "prior_due AS priorDue,selected,rating,next_due AS nextDue";

    @Select("SELECT " + ATTEMPT_FIELDS + " FROM topic_review_attempts WHERE user_id=#{user} AND id=#{id}")
    Attempt attempt(@Param("user") long user, @Param("id") String id);

    @Select("SELECT " + ATTEMPT_FIELDS + " FROM topic_review_attempts WHERE user_id=#{user}"
            + " AND material_id=#{card.materialId} AND topic_id=#{card.topicId} AND prior_due=#{card.dueAt}")
    Attempt current(@Param("user") long user, @Param("card") Card card);

    @Insert("""
        INSERT INTO topic_review_attempts(id,user_id,material_id,topic_id,question_version_id,prior_due,selected)
        VALUES(#{id},#{user},#{card.materialId},#{card.topicId},#{version},#{card.dueAt},#{selected})
        """)
    void reveal(@Param("id") String id, @Param("user") long user, @Param("card") Card card,
                @Param("version") String version, @Param("selected") String selected);

    @Select("""
        SELECT fsrs_state FROM topic_review_attempts WHERE user_id=#{user}
        AND material_id=#{a.materialId} AND topic_id=#{a.topicId} AND next_due=#{a.priorDue} LIMIT 1
        """)
    String previousState(@Param("user") long user, @Param("a") Attempt attempt);

    @Update("""
        UPDATE topic_review_attempts SET rating=#{rating},reviewed_at=#{reviewed},next_due=#{due},fsrs_state=#{state}
        WHERE user_id=#{user} AND id=#{id} AND rating IS NULL
        """)
    int rate(@Param("user") long user, @Param("id") String id, @Param("rating") int rating,
             @Param("reviewed") LocalDateTime reviewed, @Param("due") LocalDateTime due, @Param("state") String state);

    @Update("""
        UPDATE topic_review_cards SET due_at=#{due}
        WHERE user_id=#{user} AND material_id=#{card.materialId} AND topic_id=#{card.topicId} AND due_at=#{card.dueAt}
        """)
    int schedule(@Param("user") long user, @Param("card") Card card, @Param("due") LocalDateTime due);
}
