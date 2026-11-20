package com.learnloop.learning;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.List;
import org.apache.ibatis.annotations.Insert;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;
import org.apache.ibatis.annotations.Update;

/**
 * Java 主业务持有学习数据的唯一写入入口。每个用户请求必须带 userId，值来自登录身份。
 *
 * <p>带 lock/claim/apply 的方法必须在 Service 短事务中组合使用；HTTP 算法调用必须放在事务外。
 * UPDATE 返回 0 表示条件失效或认领过期，调用方应拒绝迟到结果，不能当成更新成功。</p>
 */
@Mapper
public interface LearningMapper {
    @Select("""
        SELECT EXISTS(SELECT 1 FROM adaptive_topic_links a JOIN adaptive_topic_links b
          ON b.user_id=a.user_id AND b.topic_id=a.topic_id
          WHERE a.user_id=#{user} AND a.concept_id=#{first} AND b.concept_id=#{second})
        """)
    boolean sameAdaptiveTopic(@Param("user") long user, @Param("first") String first, @Param("second") String second);
    String CONCEPT_COLUMNS = "id,user_id AS userId,request_key AS requestKey,material_id AS materialId,"
            + "block_id AS blockId,material_sha256 AS materialSha256,parse_attempt AS parseAttempt,"
            + "name,definition,granularity,source_text AS sourceText,source_locator AS sourceLocator,created_at AS createdAt";
    String QUESTION_COLUMNS = "id,user_id AS userId,question_id AS questionId,concept_id AS conceptId,"
            + "family_id AS familyId,content,option_a AS optionA,option_b AS optionB,option_c AS optionC,"
            + "option_d AS optionD,correct_answer AS correctAnswer,content_digest AS contentDigest,created_at AS createdAt";
    String ATTEMPT_COLUMNS = "id,user_id AS userId,question_version_id AS questionVersionId,request_key AS requestKey,"
            + "request_digest AS requestDigest,status,expires_at AS expiresAt,assisted,assisted_at AS assistedAt,"
            + "submit_key AS submitKey,confidence,selected_option AS selectedOption,is_correct AS correct,"
            + "answer_record_id AS answerRecordId,event_id AS eventId,ineligibility_reason AS ineligibilityReason,"
            + "created_at AS createdAt,submitted_at AS submittedAt";
    String MASTERY_COLUMNS = "user_id AS userId,concept_id AS conceptId,model_version AS modelVersion,"
            + "mastery_probability AS masteryProbability,next_sequence AS nextSequence,applied_sequence AS appliedSequence,"
            + "state_version AS stateVersion,evidence_count AS evidenceCount,updated_at AS updatedAt";
    String EVENT_COLUMNS = "id,user_id AS userId,concept_id AS conceptId,question_version_id AS questionVersionId,"
            + "attempt_id AS attemptId,model_version AS modelVersion,sequence_no AS sequenceNo,is_correct AS correct,"
            + "status,prior_probability AS priorProbability,applied_probability AS appliedProbability,"
            + "claim_token AS claimToken,attempt_count AS attemptCount,retry_at AS retryAt,lease_until AS leaseUntil,"
            + "error_code AS errorCode,created_at AS createdAt,applied_at AS appliedAt";
    String CLAIM_COLUMNS = "id,user_id AS userId,question_version_id AS questionVersionId,family_id AS familyId,"
            + "evidence_date AS evidenceDate,attempt_id AS attemptId,created_at AS createdAt";

    /** 用户行锁用于串行化同一用户的创建、绑定和答题入口；不得在持锁时调用 Python。 */
    @Select("SELECT id FROM users WHERE id=#{userId} FOR UPDATE")
    Long lockUser(@Param("userId") long userId);

    @Select("SELECT EXISTS(SELECT 1 FROM materials WHERE user_id=#{userId} AND id=#{materialId} AND parse_status='PARSED')")
    boolean ownsParsedMaterial(@Param("userId") long userId, @Param("materialId") long materialId);

    /** 精确复用重复内容的题族；不同答案或选项不能只凭标题被合并。 */
    @Select("SELECT " + QUESTION_COLUMNS + " FROM learning_questions WHERE user_id=#{userId} AND concept_id=#{conceptId} AND content_digest=#{digest} ORDER BY id LIMIT 1")
    LearningRows.QuestionVersion findQuestionByDigest(@Param("userId") long userId,
            @Param("conceptId") String conceptId, @Param("digest") String digest);

    @Select("SELECT " + CONCEPT_COLUMNS + " FROM learning_concepts WHERE user_id=#{userId} AND material_id=#{materialId} AND block_id=#{blockId} AND parse_attempt=#{parseAttempt} AND granularity=#{granularity} AND name=#{name} LIMIT 1")
    LearningRows.Concept findConceptByAnchor(@Param("userId") long userId, @Param("materialId") long materialId,
            @Param("blockId") long blockId, @Param("parseAttempt") long parseAttempt,
            @Param("granularity") String granularity, @Param("name") String name);

    /**
     * 普通出题跨批次去重时，名称相同但粒度不同的旧考点也必须被视为已覆盖；
     * 否则模型换个粒度就能把同一事实重新建档。
     */
    @Select("SELECT " + CONCEPT_COLUMNS + " FROM learning_concepts WHERE user_id=#{userId} AND material_id=#{materialId} AND block_id=#{blockId} AND parse_attempt=#{parseAttempt} AND name=#{name} ORDER BY created_at,id LIMIT 1")
    LearningRows.Concept findConceptByAnchorAnyGranularity(@Param("userId") long userId,
            @Param("materialId") long materialId, @Param("blockId") long blockId,
            @Param("parseAttempt") long parseAttempt, @Param("name") String name);

    /** 共享锁阻止绑定事务中材料版本被并发替换；重新解析之后必须创建新概念来源。 */
    @Select("""
        SELECT m.id AS materialId,b.id AS blockId,m.sha256,m.parse_attempt AS parseAttempt,
               m.original_name AS originalName,b.text_content AS textContent,b.source_locator AS sourceLocator
        FROM materials m JOIN material_blocks b ON b.material_id=m.id
        WHERE m.user_id=#{userId} AND m.id=#{materialId} AND b.id=#{blockId} AND m.parse_status='PARSED'
        FOR SHARE
        """)
    LearningRows.Source findMaterialSource(@Param("userId") long userId,
            @Param("materialId") long materialId, @Param("blockId") long blockId);

    @Select("""
        SELECT id,user_id AS userId,content,option_a AS optionA,option_b AS optionB,
               option_c AS optionC,option_d AS optionD,correct_answer AS correctAnswer
        FROM questions WHERE user_id=#{userId} AND id=#{questionId} FOR SHARE
        """)
    LearningRows.ExistingQuestion findCurrentQuestion(@Param("userId") long userId,
            @Param("questionId") long questionId);

    @Insert("""
        INSERT INTO learning_concepts(id,user_id,request_key,material_id,block_id,material_sha256,
            parse_attempt,name,definition,granularity,source_text,source_locator,created_at)
        VALUES(#{id},#{userId},#{requestKey},#{materialId},#{blockId},#{materialSha256},
            #{parseAttempt},#{name},#{definition},#{granularity},#{sourceText},#{sourceLocator},#{createdAt})
        """)
    int insertConcept(LearningRows.Concept row);

    @Select("SELECT " + CONCEPT_COLUMNS + " FROM learning_concepts WHERE user_id=#{userId} AND request_key=#{requestKey}")
    LearningRows.Concept findConceptByRequest(@Param("userId") long userId, @Param("requestKey") String requestKey);

    @Select("SELECT " + CONCEPT_COLUMNS + " FROM learning_concepts WHERE user_id=#{userId} AND id=#{conceptId}")
    LearningRows.Concept findConcept(@Param("userId") long userId, @Param("conceptId") String conceptId);

    @Select("SELECT " + CONCEPT_COLUMNS + " FROM learning_concepts WHERE user_id=#{userId} AND material_id=#{materialId} ORDER BY created_at,id LIMIT #{limit}")
    List<LearningRows.Concept> listConcepts(@Param("userId") long userId, @Param("materialId") long materialId,
            @Param("limit") int limit);

    /** 只把所选正文块在当前解析版本下的考点交给模型，避免无关目录挤占协议上限。 */
    @Select("<script>SELECT " + CONCEPT_COLUMNS + " FROM learning_concepts "
            + "WHERE user_id=#{userId} AND material_id=#{materialId} "
            + "AND parse_attempt=#{parseAttempt} AND material_sha256=#{sha256} "
            + "AND block_id IN <foreach collection=\"blockIds\" item=\"block\" open=\"(\" separator=\",\" close=\")\">#{block}</foreach> "
            + "ORDER BY created_at DESC,id DESC LIMIT 200</script>")
    List<LearningRows.Concept> listConceptsForBlocks(@Param("userId") long userId,
            @Param("materialId") long materialId, @Param("parseAttempt") long parseAttempt,
            @Param("sha256") String sha256, @Param("blockIds") List<Long> blockIds);

    @Insert("""
        INSERT INTO learning_questions(id,user_id,question_id,concept_id,family_id,content,
            option_a,option_b,option_c,option_d,correct_answer,content_digest,created_at)
        VALUES(#{id},#{userId},#{questionId},#{conceptId},#{familyId},#{content},
            #{optionA},#{optionB},#{optionC},#{optionD},#{correctAnswer},#{contentDigest},#{createdAt})
        """)
    int insertQuestionVersion(LearningRows.QuestionVersion row);

    @Select("SELECT " + QUESTION_COLUMNS + " FROM learning_questions WHERE user_id=#{userId} AND question_id=#{questionId}")
    LearningRows.QuestionVersion findQuestionVersionByQuestion(@Param("userId") long userId,
            @Param("questionId") long questionId);

    /** 同考点变式复用该考点最早题目的 family，保证同一天不会因变式重复计入独立证据。 */
    @Select("SELECT " + QUESTION_COLUMNS + " FROM learning_questions WHERE user_id=#{userId} AND concept_id=#{conceptId} ORDER BY created_at,id LIMIT 1")
    LearningRows.QuestionVersion firstQuestionVersionForConcept(@Param("userId") long userId,
            @Param("conceptId") String conceptId);

    @Select("SELECT " + QUESTION_COLUMNS + " FROM learning_questions WHERE user_id=#{userId} AND id=#{versionId}")
    LearningRows.QuestionVersion findQuestionVersion(@Param("userId") long userId, @Param("versionId") String versionId);

    @Select("""
        SELECT q.id,q.user_id AS userId,q.question_id AS questionId,q.concept_id AS conceptId,
               q.family_id AS familyId,q.content,q.option_a AS optionA,q.option_b AS optionB,
               q.option_c AS optionC,q.option_d AS optionD,q.correct_answer AS correctAnswer,
               q.content_digest AS contentDigest,q.created_at AS createdAt
        FROM learning_questions q JOIN learning_concepts c ON c.id=q.concept_id AND c.user_id=q.user_id
        WHERE q.user_id=#{userId} AND c.material_id=#{materialId} ORDER BY q.created_at,q.id LIMIT #{limit}
        """)
    List<LearningRows.QuestionVersion> listQuestionVersions(@Param("userId") long userId,
            @Param("materialId") long materialId, @Param("limit") int limit);

    /**
     * 题目中心按题目实际保存时间倒序分页，旧任务晚完成时也能排到前面。
     * 批次关联只用于返回题目元信息，不参与列表顺序。
     * 来源必须与当前资料 SHA-256 和解析尝试一致，避免把已失效的题目放进可练习列表。
     */
    @Select("""
        <script>
        SELECT q.id AS id,q.user_id AS userId,q.question_id AS questionId,q.concept_id AS conceptId,
               q.content,q.option_a AS optionA,q.option_b AS optionB,q.option_c AS optionC,
               q.option_d AS optionD,q.created_at AS createdAt,c.material_id AS materialId,
               m.original_name AS materialName,c.name AS conceptName,
               i.job_id AS jobId,j.created_at AS jobCreatedAt,i.difficulty AS difficulty,
               COALESCE(i.source_locator,c.source_locator) AS sourceLocator,
               EXISTS(SELECT 1 FROM learning_attempts a
                      WHERE a.user_id=q.user_id AND a.question_version_id=q.id AND a.status='SUBMITTED') AS answered,
               (SELECT a.is_correct FROM learning_attempts a
                WHERE a.user_id=q.user_id AND a.question_version_id=q.id AND a.status='SUBMITTED'
                ORDER BY a.submitted_at DESC,a.id DESC LIMIT 1) AS latestCorrect
        FROM learning_questions q
        JOIN learning_concepts c ON c.id=q.concept_id AND c.user_id=q.user_id
        JOIN materials m ON m.id=c.material_id AND m.user_id=q.user_id
        JOIN material_blocks b ON b.id=c.block_id AND b.material_id=c.material_id
        LEFT JOIN generation_job_items i ON i.question_version_id=q.id AND i.user_id=q.user_id
        LEFT JOIN generation_jobs j ON j.id=i.job_id AND j.user_id=q.user_id
        WHERE q.user_id=#{userId}
          AND m.parse_status='PARSED' AND m.sha256=c.material_sha256 AND m.parse_attempt=c.parse_attempt
          <if test="materialId != null">AND c.material_id=#{materialId}</if>
          <if test="answered != null">
            <choose>
              <when test="answered">AND EXISTS(SELECT 1 FROM learning_attempts a
                  WHERE a.user_id=q.user_id AND a.question_version_id=q.id AND a.status='SUBMITTED')</when>
              <otherwise>AND NOT EXISTS(SELECT 1 FROM learning_attempts a
                  WHERE a.user_id=q.user_id AND a.question_version_id=q.id AND a.status='SUBMITTED')</otherwise>
            </choose>
          </if>
        ORDER BY q.created_at DESC,q.id DESC
        LIMIT #{size} OFFSET #{offset}
        </script>
        """)
    List<LearningRows.LibraryQuestion> library(@Param("userId") long userId,
            @Param("materialId") Long materialId, @Param("answered") Boolean answered,
            @Param("size") int size, @Param("offset") long offset);

    @Select("""
        <script>
        SELECT COUNT(*)
        FROM learning_questions q
        JOIN learning_concepts c ON c.id=q.concept_id AND c.user_id=q.user_id
        JOIN materials m ON m.id=c.material_id AND m.user_id=q.user_id
        JOIN material_blocks b ON b.id=c.block_id AND b.material_id=c.material_id
        WHERE q.user_id=#{userId}
          AND m.parse_status='PARSED' AND m.sha256=c.material_sha256 AND m.parse_attempt=c.parse_attempt
          <if test="materialId != null">AND c.material_id=#{materialId}</if>
          <if test="answered != null">
            <choose>
              <when test="answered">AND EXISTS(SELECT 1 FROM learning_attempts a
                  WHERE a.user_id=q.user_id AND a.question_version_id=q.id AND a.status='SUBMITTED')</when>
              <otherwise>AND NOT EXISTS(SELECT 1 FROM learning_attempts a
                  WHERE a.user_id=q.user_id AND a.question_version_id=q.id AND a.status='SUBMITTED')</otherwise>
            </choose>
          </if>
        </script>
        """)
    long countLibrary(@Param("userId") long userId, @Param("materialId") Long materialId,
            @Param("answered") Boolean answered);

    @Insert("""
        INSERT INTO learning_attempts(id,user_id,question_version_id,request_key,request_digest,
            status,expires_at,assisted,assisted_at,created_at)
        VALUES(#{id},#{userId},#{questionVersionId},#{requestKey},#{requestDigest},
            #{status},#{expiresAt},#{assisted},#{assistedAt},#{createdAt})
        """)
    int insertAttempt(LearningRows.Attempt row);

    @Select("SELECT " + ATTEMPT_COLUMNS + " FROM learning_attempts WHERE user_id=#{userId} AND request_key=#{requestKey}")
    LearningRows.Attempt findAttemptByRequest(@Param("userId") long userId, @Param("requestKey") String requestKey);

    @Select("SELECT " + ATTEMPT_COLUMNS + " FROM learning_attempts WHERE user_id=#{userId} AND submit_key=#{submitKey}")
    LearningRows.Attempt findAttemptBySubmitKey(@Param("userId") long userId, @Param("submitKey") String submitKey);

    @Select("SELECT " + ATTEMPT_COLUMNS + " FROM learning_attempts WHERE user_id=#{userId} AND id=#{attemptId} FOR UPDATE")
    LearningRows.Attempt lockAttempt(@Param("userId") long userId, @Param("attemptId") String attemptId);

    @Select("SELECT " + ATTEMPT_COLUMNS + " FROM learning_attempts WHERE user_id=#{userId} AND question_version_id=#{versionId} AND status='OPEN'")
    LearningRows.Attempt findOpenAttempt(@Param("userId") long userId, @Param("versionId") String versionId);

    /** 看过答案的暴露跨 attempt 保留；过期再开一次不能洗掉事前辅助。 */
    @Select("SELECT MIN(assisted_at) FROM learning_attempts WHERE user_id=#{userId} AND question_version_id=#{versionId} AND assisted=TRUE")
    LocalDateTime firstRevealAt(@Param("userId") long userId, @Param("versionId") String versionId);

    @Update("""
        UPDATE learning_attempts SET status='EXPIRED'
        WHERE user_id=#{userId} AND question_version_id=#{versionId} AND status='OPEN' AND expires_at<=#{now}
        """)
    int expireOpenForQuestion(@Param("userId") long userId, @Param("versionId") String versionId,
            @Param("now") LocalDateTime now);

    /** 必须先成功写 assisted，再返回解析/答案；否则客户端中断可能留下“未辅助”的假证据。 */
    @Update("""
        UPDATE learning_attempts SET assisted=TRUE,assisted_at=COALESCE(assisted_at,#{now})
        WHERE user_id=#{userId} AND id=#{attemptId} AND status='OPEN' AND expires_at>#{now}
        """)
    int markAssisted(@Param("userId") long userId, @Param("attemptId") String attemptId,
            @Param("now") LocalDateTime now);

    @Update("""
        UPDATE learning_attempts SET status='SUBMITTED',submit_key=#{submitKey},confidence=#{confidence},
            selected_option=#{selectedOption},is_correct=#{correct},answer_record_id=#{answerRecordId},
            event_id=#{eventId},ineligibility_reason=#{ineligibilityReason},submitted_at=#{submittedAt}
        WHERE user_id=#{userId} AND id=#{attemptId} AND status='OPEN' AND expires_at>#{submittedAt}
        """)
    int completeAttempt(@Param("userId") long userId, @Param("attemptId") String attemptId,
            @Param("submitKey") String submitKey, @Param("confidence") String confidence,
            @Param("selectedOption") String selectedOption, @Param("correct") boolean correct,
            @Param("answerRecordId") long answerRecordId, @Param("eventId") String eventId,
            @Param("ineligibilityReason") String ineligibilityReason, @Param("submittedAt") LocalDateTime submittedAt);

    @Update("UPDATE learning_attempts SET status='EXPIRED' WHERE user_id=#{userId} AND id=#{attemptId} AND status='OPEN'")
    int expireAttempt(@Param("userId") long userId, @Param("attemptId") String attemptId);

    /** 旧接口答过的原题不能被重新包装成“第一次独立答题”；检查发生在本次写旧答题表之前。 */
    @Select("SELECT EXISTS(SELECT 1 FROM answer_records WHERE user_id=#{userId} AND question_id=#{questionId})")
    boolean hasPreviousAnswer(@Param("userId") long userId, @Param("questionId") long questionId);

    @Insert("""
        INSERT INTO learning_mastery(user_id,concept_id,model_version,mastery_probability,
            next_sequence,applied_sequence,state_version,evidence_count,updated_at)
        VALUES(#{userId},#{conceptId},#{modelVersion},0.2,0,0,0,0,#{now})
        ON DUPLICATE KEY UPDATE user_id=user_id
        """)
    int ensureMastery(@Param("userId") long userId, @Param("conceptId") String conceptId,
            @Param("modelVersion") String modelVersion, @Param("now") LocalDateTime now);

    @Select("SELECT " + MASTERY_COLUMNS + " FROM learning_mastery WHERE user_id=#{userId} AND concept_id=#{conceptId} AND model_version=#{modelVersion} FOR UPDATE")
    LearningRows.Mastery lockMastery(@Param("userId") long userId, @Param("conceptId") String conceptId,
            @Param("modelVersion") String modelVersion);

    @Select("SELECT " + MASTERY_COLUMNS + " FROM learning_mastery WHERE user_id=#{userId} AND concept_id=#{conceptId} AND model_version=#{modelVersion}")
    LearningRows.Mastery findMastery(@Param("userId") long userId, @Param("conceptId") String conceptId,
            @Param("modelVersion") String modelVersion);

    @Select("""
        SELECT m.user_id AS userId,m.concept_id AS conceptId,m.model_version AS modelVersion,
               m.mastery_probability AS masteryProbability,m.next_sequence AS nextSequence,
               m.applied_sequence AS appliedSequence,m.state_version AS stateVersion,
               m.evidence_count AS evidenceCount,m.updated_at AS updatedAt
        FROM learning_mastery m JOIN learning_concepts c ON c.id=m.concept_id AND c.user_id=m.user_id
        WHERE m.user_id=#{userId} AND c.material_id=#{materialId} AND m.model_version=#{modelVersion}
        ORDER BY m.concept_id LIMIT #{limit}
        """)
    List<LearningRows.Mastery> listMasteryByMaterial(@Param("userId") long userId,
            @Param("materialId") long materialId, @Param("modelVersion") String modelVersion, @Param("limit") int limit);

    /** 同一事务先锁 mastery，读取 nextSequence+1，再预留并插入事件。分配序号不修改算法状态版本。 */
    @Update("""
        UPDATE learning_mastery SET next_sequence=next_sequence+1,updated_at=#{now}
        WHERE user_id=#{userId} AND concept_id=#{conceptId} AND model_version=#{modelVersion}
          AND next_sequence=#{expectedNextSequence}
        """)
    int reserveSequence(@Param("userId") long userId, @Param("conceptId") String conceptId,
            @Param("modelVersion") String modelVersion, @Param("expectedNextSequence") long expectedNextSequence,
            @Param("now") LocalDateTime now);

    @Insert("""
        INSERT INTO learning_evidence_claims(id,user_id,question_version_id,family_id,evidence_date,attempt_id,created_at)
        VALUES(#{id},#{userId},#{questionVersionId},#{familyId},#{evidenceDate},#{attemptId},#{createdAt})
        """)
    int insertEvidenceClaim(LearningRows.EvidenceClaim row);

    @Select("SELECT " + CLAIM_COLUMNS + " FROM learning_evidence_claims WHERE user_id=#{userId} AND question_version_id=#{versionId}")
    LearningRows.EvidenceClaim findEvidenceClaimByQuestion(@Param("userId") long userId,
            @Param("versionId") String versionId);

    @Select("SELECT " + CLAIM_COLUMNS + " FROM learning_evidence_claims WHERE user_id=#{userId} AND family_id=#{familyId} AND evidence_date=#{date}")
    LearningRows.EvidenceClaim findEvidenceClaimByFamilyDay(@Param("userId") long userId,
            @Param("familyId") String familyId, @Param("date") LocalDate date);

    @Insert("""
        INSERT INTO learning_events(id,user_id,concept_id,question_version_id,attempt_id,model_version,
            sequence_no,is_correct,status,prior_probability,applied_probability,claim_token,
            attempt_count,retry_at,lease_until,error_code,created_at,applied_at)
        VALUES(#{id},#{userId},#{conceptId},#{questionVersionId},#{attemptId},#{modelVersion},
            #{sequenceNo},#{correct},#{status},#{priorProbability},#{appliedProbability},#{claimToken},
            #{attemptCount},#{retryAt},#{leaseUntil},#{errorCode},#{createdAt},#{appliedAt})
        """)
    int insertEvent(LearningRows.Event row);

    /**
     * 跨用户后台扫描只能返回队首；事务内仍须重新锁 mastery/event 并验证条件，扫描不代表认领。
     * PROCESSING 租约过期允许重新认领，新的 token 将封住旧 HTTP 调用的迟到返回。
     */
    @Select("""
        SELECT e.id,e.user_id AS userId,e.concept_id AS conceptId,e.question_version_id AS questionVersionId,
               e.attempt_id AS attemptId,e.model_version AS modelVersion,e.sequence_no AS sequenceNo,
               e.is_correct AS correct,e.status,e.prior_probability AS priorProbability,
               e.applied_probability AS appliedProbability,e.claim_token AS claimToken,
               e.attempt_count AS attemptCount,e.retry_at AS retryAt,e.lease_until AS leaseUntil,
               e.error_code AS errorCode,e.created_at AS createdAt,e.applied_at AS appliedAt
        FROM learning_events e JOIN learning_mastery m ON m.user_id=e.user_id AND m.concept_id=e.concept_id
            AND m.model_version=e.model_version AND e.sequence_no=m.applied_sequence+1
        WHERE (e.status='PENDING' AND e.retry_at<=#{now})
           OR (e.status='PROCESSING' AND e.lease_until<=#{now})
        ORDER BY e.created_at,e.id LIMIT #{limit}
        """)
    List<LearningRows.Event> listClaimableEvents(@Param("now") LocalDateTime now, @Param("limit") int limit);

    @Select("SELECT " + EVENT_COLUMNS + " FROM learning_events WHERE user_id=#{userId} AND id=#{eventId} FOR UPDATE")
    LearningRows.Event lockEvent(@Param("userId") long userId, @Param("eventId") String eventId);

    @Select("SELECT " + EVENT_COLUMNS + " FROM learning_events WHERE user_id=#{userId} AND id=#{eventId}")
    LearningRows.Event findEvent(@Param("userId") long userId, @Param("eventId") String eventId);

    @Update("""
        UPDATE learning_events e SET status='PROCESSING',claim_token=#{token},attempt_count=attempt_count+1,
            lease_until=#{leaseUntil},error_code=NULL
        WHERE e.user_id=#{userId} AND e.id=#{eventId}
          AND ((e.status='PENDING' AND e.retry_at<=#{now}) OR (e.status='PROCESSING' AND e.lease_until<=#{now}))
          AND EXISTS(SELECT 1 FROM learning_mastery m WHERE m.user_id=e.user_id AND m.concept_id=e.concept_id
              AND m.model_version=e.model_version AND e.sequence_no=m.applied_sequence+1)
        """)
    int claimEvent(@Param("userId") long userId, @Param("eventId") String eventId,
            @Param("token") String token, @Param("now") LocalDateTime now, @Param("leaseUntil") LocalDateTime leaseUntil);

    /**
     * 结果应用同时检查概念版本、下一序号和认领 token；调用方必须在同一事务 markEventApplied。
     * 该条件阻止重复投递、乱序返回和租约重领后的旧结果再次增加掌握度。
     */
    @Update("""
        UPDATE learning_mastery m SET mastery_probability=#{probability},applied_sequence=#{sequenceNo},
            state_version=state_version+1,evidence_count=evidence_count+1,updated_at=#{now}
        WHERE m.user_id=#{userId} AND m.concept_id=#{conceptId} AND m.model_version=#{modelVersion}
          AND m.state_version=#{expectedVersion} AND m.applied_sequence+1=#{sequenceNo}
          AND EXISTS(SELECT 1 FROM learning_events e WHERE e.id=#{eventId} AND e.user_id=m.user_id
              AND e.concept_id=m.concept_id AND e.model_version=m.model_version AND e.sequence_no=#{sequenceNo}
              AND e.status='PROCESSING' AND e.claim_token=#{token} AND e.lease_until>#{now})
        """)
    int applyMastery(@Param("userId") long userId, @Param("conceptId") String conceptId,
            @Param("modelVersion") String modelVersion, @Param("expectedVersion") long expectedVersion,
            @Param("sequenceNo") long sequenceNo, @Param("probability") double probability,
            @Param("eventId") String eventId, @Param("token") String token, @Param("now") LocalDateTime now);

    @Update("""
        UPDATE learning_events SET status='APPLIED',prior_probability=#{prior},applied_probability=#{applied},
            claim_token=NULL,lease_until=NULL,error_code=NULL,applied_at=#{now}
        WHERE user_id=#{userId} AND id=#{eventId} AND status='PROCESSING' AND claim_token=#{token} AND lease_until>#{now}
        """)
    int markEventApplied(@Param("userId") long userId, @Param("eventId") String eventId,
            @Param("token") String token, @Param("prior") double prior, @Param("applied") double applied,
            @Param("now") LocalDateTime now);

    /** 一般故障进入带退避时间的 PENDING；达到重试上限才 FAILED，队尾不得越过失败的队首。 */
    @Update("""
        UPDATE learning_events SET status=CASE WHEN #{terminal} THEN 'FAILED' ELSE 'PENDING' END,
            claim_token=NULL,lease_until=NULL,error_code=#{errorCode},retry_at=#{retryAt}
        WHERE user_id=#{userId} AND id=#{eventId} AND status='PROCESSING' AND claim_token=#{token} AND lease_until>#{now}
        """)
    int failEvent(@Param("userId") long userId, @Param("eventId") String eventId,
            @Param("token") String token, @Param("terminal") boolean terminal, @Param("errorCode") String errorCode,
            @Param("retryAt") LocalDateTime retryAt, @Param("now") LocalDateTime now);

    /** 人工重试只恢复队列并清空旧 token；不回填旧 HTTP 的算法结果。 */
    @Update("""
        UPDATE learning_events SET status='PENDING',claim_token=NULL,lease_until=NULL,
            attempt_count=0,error_code=NULL,retry_at=#{now}
        WHERE user_id=#{userId} AND id=#{eventId} AND status='FAILED'
        """)
    int retryFailedEvent(@Param("userId") long userId, @Param("eventId") String eventId,
            @Param("now") LocalDateTime now);
}
