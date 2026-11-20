package com.learnloop.generation;

import java.time.LocalDateTime;
import java.util.List;
import org.apache.ibatis.annotations.*;

@Mapper
interface AdaptiveMapper {
    record Profile(String topicId, String aliasesJson, String granularity) {}
    record CatalogState(long blockId, String status, String reason) {}

    @Select("""
        SELECT p.topic_id AS topicId,p.aliases_json AS aliasesJson,p.granularity
        FROM adaptive_topic_profiles p JOIN adaptive_topics t ON t.user_id=p.user_id AND t.id=p.topic_id
        WHERE p.user_id=#{user} AND t.material_id=#{material}
        """)
    List<Profile> profiles(@Param("user") long user, @Param("material") long material);

    @Insert("""
        INSERT INTO adaptive_topic_profiles(user_id,topic_id,aliases_json,granularity)
        VALUES(#{user},#{topic},CAST(#{aliases} AS JSON),#{granularity}) AS incoming
        ON DUPLICATE KEY UPDATE aliases_json=incoming.aliases_json,granularity=incoming.granularity
        """)
    void saveProfile(@Param("user") long user, @Param("topic") String topic, @Param("aliases") String aliases,
                     @Param("granularity") String granularity);

    @Select("""
        SELECT block_id AS blockId,status,reason FROM adaptive_catalog_results
        WHERE user_id=#{user} AND material_id=#{material} AND parse_attempt=#{attempt} AND version=#{version}
        ORDER BY block_id
        """)
    List<CatalogState> catalogStates(@Param("user") long user, @Param("material") long material,
                                   @Param("attempt") long attempt, @Param("version") String version);

    @Insert("""
        INSERT INTO adaptive_catalog_results(user_id,material_id,parse_attempt,block_id,version,status,reason,updated_at)
        VALUES(#{user},#{material},#{attempt},#{block},#{version},#{status},#{reason},#{now}) AS incoming
        ON DUPLICATE KEY UPDATE version=incoming.version,status=incoming.status,reason=incoming.reason,updated_at=incoming.updated_at
        """)
    void saveCatalogState(@Param("user") long user,@Param("material") long material,@Param("attempt") long attempt,
                          @Param("block") long block,@Param("version") String version,@Param("status") String status,
                          @Param("reason") String reason,@Param("now") LocalDateTime now);

    @Select("""
        SELECT n.ranges_json FROM material_model_noise n
        JOIN material_blocks b ON b.material_id=n.material_id AND b.block_no=n.block_no
        JOIN materials m ON m.id=b.material_id
        WHERE m.user_id=#{user} AND m.id=#{material} AND n.parse_attempt=#{attempt} AND b.id=#{block}
        """)
    String noise(@Param("user") long user,@Param("material") long material,@Param("attempt") long attempt,@Param("block") long block);
    @Select("""
        SELECT j.id FROM generation_jobs j
        WHERE j.user_id=#{user} AND j.status='SUCCEEDED'
        AND EXISTS (SELECT 1 FROM generation_job_items i JOIN adaptive_generation_choices c
            ON c.user_id=i.user_id AND c.question_version_id=i.question_version_id
            WHERE i.user_id=j.user_id AND i.job_id=j.id
            AND NOT EXISTS (SELECT 1 FROM topic_review_cards r WHERE r.user_id=j.user_id
                AND r.material_id=j.material_id AND r.topic_id=c.topic_id))
        ORDER BY j.updated_at,j.id
        """)
    List<String> jobsMissingCards(@Param("user") long user);

    // locked_job_id 沿用旧列名，仅记录首次建卡的发布批次。跳过已有卡，保留到期时间和复习历史。
    @Insert("""
        INSERT INTO topic_review_cards(user_id,material_id,topic_id,locked_job_id,created_at,due_at)
        SELECT DISTINCT j.user_id,j.material_id,c.topic_id,j.id,#{now},DATE_ADD(j.updated_at, INTERVAL 1 DAY)
        FROM generation_jobs j JOIN generation_job_items i ON i.job_id=j.id AND i.user_id=j.user_id
        JOIN adaptive_generation_choices c ON c.user_id=i.user_id AND c.question_version_id=i.question_version_id
        WHERE j.user_id=#{user} AND j.id=#{job} AND j.status='SUCCEEDED'
        AND NOT EXISTS (SELECT 1 FROM topic_review_cards r WHERE r.user_id=j.user_id
            AND r.material_id=j.material_id AND r.topic_id=c.topic_id)
        """)
    int insertMissingReviewCards(@Param("user") long user, @Param("job") String job, @Param("now") LocalDateTime now);
    record Topic(String id, String chapter, String groupName, String name, String definition) {}
    record Source(String topicId, long blockId) {}
    record Previous(String topicId, String versionId, String content, String sourceQuote) {}
    record Observation(String topicId, String familyId, boolean correct, LocalDateTime createdAt) {}

    @Select("""
        SELECT id,chapter,group_name AS groupName,name,definition FROM adaptive_topics
        WHERE user_id=#{user} AND material_id=#{material} ORDER BY id
        """)
    List<Topic> topics(@Param("user") long user, @Param("material") long material);

    @Insert("""
        INSERT INTO adaptive_topics(id,user_id,material_id,topic_key,chapter,group_name,name,definition)
        VALUES(#{topic.id},#{user},#{material},#{key},#{topic.chapter},#{topic.groupName},#{topic.name},#{topic.definition})
        """)
    void insertTopic(@Param("user") long user, @Param("material") long material, @Param("key") String key,
                     @Param("topic") Topic topic);

    @Select("""
        SELECT block_id FROM adaptive_indexed_blocks
        WHERE user_id=#{user} AND material_id=#{material} AND parse_attempt=#{attempt}
        """)
    List<Long> indexed(@Param("user") long user, @Param("material") long material, @Param("attempt") long attempt);

    @Insert("""
        INSERT IGNORE INTO adaptive_indexed_blocks(user_id,material_id,parse_attempt,block_id)
        VALUES(#{user},#{material},#{attempt},#{block})
        """)
    void markIndexed(@Param("user") long user, @Param("material") long material,
                     @Param("attempt") long attempt, @Param("block") long block);

    @Insert("""
        INSERT INTO adaptive_topic_sources(user_id,material_id,parse_attempt,block_id,topic_id,quote)
        VALUES(#{user},#{material},#{attempt},#{block},#{topic},#{quote}) AS incoming
        ON DUPLICATE KEY UPDATE quote=incoming.quote
        """)
    void addSource(@Param("user") long user, @Param("material") long material, @Param("attempt") long attempt,
                   @Param("block") long block, @Param("topic") String topic, @Param("quote") String quote);

    @Select("""
        SELECT topic_id AS topicId,block_id AS blockId FROM adaptive_topic_sources
        WHERE user_id=#{user} AND material_id=#{material} AND parse_attempt=#{attempt} ORDER BY block_id,topic_id
        """)
    List<Source> sources(@Param("user") long user, @Param("material") long material, @Param("attempt") long attempt);

    @Select("SELECT topic_id FROM adaptive_topic_links WHERE user_id=#{user} AND concept_id=#{concept}")
    String linkedTopic(@Param("user") long user, @Param("concept") String concept);

    @Insert("INSERT INTO adaptive_topic_links(user_id,concept_id,topic_id) VALUES(#{user},#{concept},#{topic})")
    void link(@Param("user") long user, @Param("concept") String concept, @Param("topic") String topic);

    @Select("""
        SELECT l.topic_id AS topicId,q.id AS versionId,q.content,i.source_quote AS sourceQuote
        FROM adaptive_topics t
        JOIN adaptive_topic_links l ON l.topic_id=t.id AND l.user_id=t.user_id
        JOIN learning_questions q ON q.concept_id=l.concept_id AND q.user_id=l.user_id
        LEFT JOIN generation_job_items i ON i.user_id=q.user_id AND i.question_version_id=q.id
        WHERE t.user_id=#{user} AND t.material_id=#{material}
        ORDER BY q.created_at DESC,q.id DESC
        """)
    List<Previous> previous(@Param("user") long user, @Param("material") long material);

    @Select("""
        SELECT l.topic_id AS topicId,q.family_id AS familyId,e.is_correct AS correct,e.created_at AS createdAt
        FROM adaptive_topics t
        JOIN adaptive_topic_links l ON l.topic_id=t.id AND l.user_id=t.user_id
        JOIN learning_events e ON e.concept_id=l.concept_id AND e.user_id=l.user_id
        JOIN learning_questions q ON q.id=e.question_version_id AND q.user_id=e.user_id
        WHERE t.user_id=#{user} AND t.material_id=#{material} AND e.model_version=#{version}
        ORDER BY e.created_at,e.id
        """)
    List<Observation> observations(@Param("user") long user, @Param("material") long material, @Param("version") String version);

    @Insert("""
        INSERT INTO adaptive_generation_choices(user_id,question_version_id,topic_id,reason,policy_version)
        VALUES(#{user},#{question},#{topic},#{reason},#{policy})
        """)
    void recordChoice(@Param("user") long user, @Param("question") String question, @Param("topic") String topic,
                      @Param("reason") String reason, @Param("policy") String policy);
}
