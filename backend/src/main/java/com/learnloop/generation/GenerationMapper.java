package com.learnloop.generation;

import java.time.LocalDateTime;
import java.util.List;
import org.apache.ibatis.annotations.*;
import static com.learnloop.generation.GenerationRows.*;

/** 所有公开读取均限定 userId。跨用户扫描仅供内部 worker，认领仍须短事务重新校验。 */
@Mapper
public interface GenerationMapper {
    String COLUMNS = "id,user_id AS userId,material_id AS materialId,request_key AS requestKey,request_digest AS requestDigest,"
            + "status,requested_count AS requestedCount,difficulty,granularity,snapshot_json AS snapshotJson,attempt_count AS attemptCount,"
            + "claim_token AS claimToken,lease_until AS leaseUntil,error_code AS errorCode,model,shortfall_reason AS shortfallReason,created_at AS createdAt,updated_at AS updatedAt";
    @Select("SELECT " + COLUMNS + " FROM generation_jobs WHERE user_id=#{user} AND id=#{id}")
    Job find(@Param("user") long user, @Param("id") String id);
    @Select("SELECT " + COLUMNS + " FROM generation_jobs WHERE user_id=#{user} AND id=#{id} FOR UPDATE")
    Job lock(@Param("user") long user, @Param("id") String id);
    @Select("SELECT " + COLUMNS + " FROM generation_jobs WHERE user_id=#{user} AND request_key=#{key}")
    Job byRequest(@Param("user") long user, @Param("key") String key);
    @Select("SELECT EXISTS(SELECT 1 FROM generation_jobs WHERE user_id=#{user} AND status IN ('PENDING','PROCESSING'))")
    boolean hasActive(@Param("user") long user);
    @Insert("""
        INSERT INTO generation_jobs(id,user_id,material_id,request_key,request_digest,status,requested_count,
            difficulty,granularity,snapshot_json,attempt_count,created_at,updated_at)
        VALUES(#{id},#{userId},#{materialId},#{requestKey},#{requestDigest},#{status},#{requestedCount},
            #{difficulty},#{granularity},#{snapshotJson},#{attemptCount},#{createdAt},#{updatedAt})
        """)
    int insert(Job job);
    @Select("SELECT " + COLUMNS + " FROM generation_jobs WHERE status='PENDING' ORDER BY created_at,id LIMIT 10")
    List<Job> pending();
    @Update("""
        UPDATE generation_jobs SET status='PROCESSING',attempt_count=attempt_count+1,claim_token=#{token},
            lease_until=#{lease},error_code=NULL,updated_at=#{now}
        WHERE id=#{id} AND user_id=#{user} AND status='PENDING'
        """)
    int claim(@Param("user") long user, @Param("id") String id, @Param("token") String token,
              @Param("lease") LocalDateTime lease, @Param("now") LocalDateTime now);
    /** 超时只标记失败，不自动再次调用收费模型；用户明确点击 retry 才能启动新尝试。 */
    @Update("""
        UPDATE generation_jobs SET status='FAILED',claim_token=NULL,lease_until=NULL,
            error_code='GENERATION_INTERRUPTED',updated_at=#{now}
        WHERE status='PROCESSING' AND lease_until<=#{now}
        """)
    int expireLeases(@Param("now") LocalDateTime now);
    @Update("""
        UPDATE generation_jobs SET status='FAILED',claim_token=NULL,lease_until=NULL,error_code=#{code},updated_at=#{now}
        WHERE user_id=#{user} AND id=#{id} AND status='PROCESSING' AND claim_token=#{token}
        """)
    int fail(@Param("user") long user, @Param("id") String id, @Param("token") String token,
             @Param("code") String code, @Param("now") LocalDateTime now);
    @Update("""
        UPDATE generation_jobs SET status='SUCCEEDED',claim_token=NULL,lease_until=NULL,error_code=NULL,
            model=#{model},shortfall_reason=#{reason},updated_at=#{now}
        WHERE user_id=#{user} AND id=#{id} AND status='PROCESSING' AND claim_token=#{token} AND lease_until>#{now}
        """)
    int succeed(@Param("user") long user, @Param("id") String id, @Param("token") String token,
                @Param("model") String model, @Param("reason") String reason, @Param("now") LocalDateTime now);
    @Update("""
        UPDATE generation_jobs SET status='PENDING',error_code=NULL,updated_at=#{now}
        WHERE user_id=#{user} AND id=#{id} AND status='FAILED'
        """)
    int retry(@Param("user") long user, @Param("id") String id, @Param("now") LocalDateTime now);
    @Insert("""
        INSERT INTO questions(user_id,content,option_a,option_b,option_c,option_d,correct_answer,explanation)
        VALUES(#{userId},#{item.content},#{item.optionA},#{item.optionB},#{item.optionC},#{item.optionD},#{item.correctAnswer},#{item.explanation})
        """)
    @Options(useGeneratedKeys = true, keyProperty = "id")
    int insertQuestion(NewQuestion question);
    @Insert("""
        INSERT INTO generation_job_items(job_id,item_no,user_id,question_version_id,block_id,source_quote,source_locator,difficulty)
        VALUES(#{jobId},#{itemNo},#{userId},#{questionVersionId},#{blockId},#{sourceQuote},#{sourceLocator},#{difficulty})
        """)
    int insertItem(Published item);
    @Select("""
        SELECT job_id AS jobId,item_no AS itemNo,user_id AS userId,question_version_id AS questionVersionId,
               block_id AS blockId,source_quote AS sourceQuote,source_locator AS sourceLocator,difficulty
        FROM generation_job_items WHERE user_id=#{user} AND job_id=#{id} ORDER BY item_no
        """)
    List<Published> items(@Param("user") long user, @Param("id") String id);
    @Select("""
        SELECT job_id AS jobId,item_no AS itemNo,user_id AS userId,question_version_id AS questionVersionId,
               block_id AS blockId,source_quote AS sourceQuote,source_locator AS sourceLocator,difficulty
        FROM generation_job_items WHERE user_id=#{user} AND question_version_id=#{versionId} LIMIT 1
        """)
    Published itemForQuestion(@Param("user") long user, @Param("versionId") String versionId);
    @Select("""
        SELECT i.block_id AS blockId,i.source_quote AS sourceQuote,q.content AS content,q.concept_id AS conceptId
        FROM generation_job_items i
        JOIN learning_questions q ON q.id=i.question_version_id AND q.user_id=i.user_id
        JOIN learning_concepts c ON c.id=q.concept_id AND c.user_id=q.user_id
        WHERE i.user_id=#{user} AND q.concept_id=#{conceptId}
        ORDER BY q.created_at DESC,q.id DESC LIMIT 50
        """)
    List<KnownQuestionRow> knownQuestionsForConcept(@Param("user") long user, @Param("conceptId") String conceptId);
    @Select("""
        <script>
        SELECT i.block_id AS blockId,i.source_quote AS sourceQuote,q.content AS content,q.concept_id AS conceptId
        FROM generation_job_items i
        JOIN learning_questions q ON q.id=i.question_version_id AND q.user_id=i.user_id
        JOIN learning_concepts c ON c.id=q.concept_id AND c.user_id=q.user_id
        WHERE i.user_id=#{user} AND c.material_id=#{materialId} AND c.parse_attempt=#{parseAttempt}
          AND c.material_sha256=#{sha256}
          AND i.block_id IN
          <foreach collection="blockIds" item="block" open="(" separator="," close=")">#{block}</foreach>
        ORDER BY q.created_at DESC,q.id DESC LIMIT 200
        </script>
        """)
    List<KnownQuestionRow> knownQuestionsForBlocks(@Param("user") long user, @Param("materialId") long materialId,
            @Param("parseAttempt") long parseAttempt, @Param("sha256") String sha256,
            @Param("blockIds") List<Long> blockIds);
}
