package com.learnloop.learning;

import com.learnloop.answer.AnswerService;
import com.learnloop.answer.SubmitAnswerRequest;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.context.bean.override.mockito.MockitoSpyBean;
import org.springframework.transaction.support.TransactionTemplate;
import tools.jackson.databind.json.JsonMapper;
import static com.learnloop.learning.LearningApi.*;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

/**
 * 显式启用的真实 MySQL 学习闭环验收。数据库事务、唯一约束、行锁、租约均真实执行。
 *
 * <p>本文件只能连接本轮独立测试实例 13316；先校验 @@port，之后才创建任何数据。
 * 每个测试创建随机账号，清理只按本测试持有的 userId 删除，绝不清空表或访问 3306。</p>
 *
 * <p>BktClient 是明确的 HTTP 边界替身，仅用于核对 Java worker 的事务发布。
 * 这些测试不代表真实 Python/pyBKT HTTP 联调通过，实际库验证另行执行。</p>
 */
@EnabledIfEnvironmentVariable(named = "LEARNLOOP_RUN_LEARNING_DB_TESTS", matches = "true")
@SpringBootTest(properties = {
        "spring.datasource.url=jdbc:mysql://127.0.0.1:13316/learnloop?sslMode=DISABLED&allowPublicKeyRetrieval=true&serverTimezone=UTC&characterEncoding=UTF-8",
        "spring.datasource.username=root",
        "spring.datasource.password=",
        "spring.datasource.hikari.maximum-pool-size=6",
        "learnloop.learning.enabled=true",
        "learnloop.learning.worker-enabled=false",
        "learnloop.material.parse.recovery-enabled=false",
        "learnloop.material.storage-root=${java.io.tmpdir}/learnloop-learning-test-materials"
})
@org.springframework.context.annotation.Import(com.learnloop.support.ExperimentClientTestSupport.class)
class LearningDatabaseTest {
    @Autowired LearningService service;
    @Autowired AnswerService answers;
    @Autowired JdbcTemplate jdbc;
    @Autowired TransactionTemplate tx;
    @Autowired LearningWorker worker;
    @Autowired LearningProperties properties;
    @MockitoSpyBean LearningMapper mapper;
    @MockitoBean BktClient bkt;

    private final List<Long> ownedUsers = new ArrayList<>();
    private long userId;
    private long materialId;
    private long blockId;

    @Test
    void openingSameQuestionAgainReturnsTheExistingOpenAttempt() {
        // 题目中心深链不依赖浏览器本地票据；同一题已有 OPEN 尝试时必须恢复它。
        QuestionView question = boundQuestion(concept("恢复未完成尝试"), "从题目中心再次打开");
        AttemptView first = attempt(question);
        AttemptView second = service.createAttempt(userId, new CreateAttempt(question.versionId(), uuid()));
        assertEquals(first.attemptId(), second.attemptId());
        assertEquals(1, count("learning_attempts"));
    }

    @Test
    void oneSubmitKeyCannotBeReusedForAnotherAttempt() {
        // 同一个用户的请求键要跨 attempt 统一校验，而不只在数据库报唯一冲突。
        ConceptView concept = concept("跨尝试幂等");
        QuestionView first = boundQuestion(concept, "第一题");
        QuestionView second = boundQuestion(concept, "第二题");
        String key = uuid();
        service.submit(userId, attempt(first).attemptId(), new Submit("A", key, null));
        AttemptView other = attempt(second);
        assertStatus(409, () -> service.submit(userId, other.attemptId(), new Submit("A", key, null)));
        assertEquals(1, count("answer_records"));
        assertEquals("OPEN", service.getAttempt(userId, other.attemptId()).status());
    }

    @BeforeEach
    void createIsolatedFixtureOnlyAfterCheckingTestPort() {
        Integer port = jdbc.queryForObject("SELECT @@port", Integer.class);
        assertNotNull(port);
        assertEquals(13316, port.intValue(), "本测试禁止向原业务 3306 实例写入任何内容");
        properties.setWorkerEnabled(false);
        userId = newUser();
        String request = uuid();
        LocalDateTime now = now();
        jdbc.update("""
                INSERT INTO materials(user_id,original_name,file_type,size_bytes,sha256,storage_key,
                    request_id,parse_status,parse_attempt,block_count,text_char_count,updated_at)
                VALUES(?,'learning-database-test.txt','txt',1,?,?,?,'PARSED',1,1,12,?)
                """, userId, "a".repeat(64), uuid() + ".bin", request, now);
        materialId = jdbc.queryForObject("SELECT id FROM materials WHERE user_id=? AND request_id=?",
                Long.class, userId, request);
        jdbc.update("""
                INSERT INTO material_blocks(material_id,block_no,block_type,text_content,source_locator)
                VALUES(?,1,'text','事务提交后持久保存。',?)
                """, materialId, "{\"kind\":\"text\",\"lineStart\":1,\"lineEnd\":1,\"partIndex\":1}");
        blockId = jdbc.queryForObject("SELECT id FROM material_blocks WHERE material_id=? AND block_no=1",
                Long.class, materialId);
    }

    @AfterEach
    void deleteOnlyFixtureOwnersInForeignKeyOrder() throws InterruptedException {
        properties.setWorkerEnabled(false);
        // 第 12 例会显式唤醒异步 worker；先等待本测试用户的计算结束，再清理它的外键数据。
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(5);
        while (!ownedUsers.isEmpty() && System.nanoTime() < deadline
                && jdbc.queryForObject("SELECT COUNT(*) FROM learning_events WHERE user_id=? AND status='PROCESSING'",
                Integer.class, userId) > 0) Thread.sleep(10);
        reset(mapper, bkt);
        for (long owner : ownedUsers) {
            jdbc.update("DELETE FROM learning_events WHERE user_id=?", owner);
            jdbc.update("DELETE FROM learning_evidence_claims WHERE user_id=?", owner);
            jdbc.update("DELETE FROM learning_attempts WHERE user_id=?", owner);
            jdbc.update("DELETE FROM learning_mastery WHERE user_id=?", owner);
            jdbc.update("DELETE FROM learning_questions WHERE user_id=?", owner);
            jdbc.update("DELETE FROM learning_concepts WHERE user_id=?", owner);
            jdbc.update("DELETE FROM answer_records WHERE user_id=?", owner);
            jdbc.update("DELETE FROM questions WHERE user_id=?", owner);
            jdbc.update("DELETE b FROM material_blocks b JOIN materials m ON m.id=b.material_id WHERE m.user_id=?", owner);
            jdbc.update("DELETE FROM materials WHERE user_id=?", owner);
            jdbc.update("DELETE FROM users WHERE id=? AND username LIKE 'llm1db_%'", owner);
        }
        ownedUsers.clear();
    }

    @Test
    void immutableBindingAndCreateReplaysDoNotLeakAnswerBeforeSubmission() throws Exception {
        CreateConcept request = new CreateConcept(materialId, blockId, "持久性", "已提交事务的结果应持久保存。", "MEDIUM", uuid());
        ConceptView concept = service.createConcept(userId, request);
        assertEquals(concept.id(), service.createConcept(userId, request).id());
        long questionId = newQuestion("持久性属于哪项性质？");
        QuestionView question = service.bindQuestion(userId, questionId, new BindQuestion(concept.id(), null));
        assertEquals(question.versionId(), service.bindQuestion(userId, questionId, new BindQuestion(concept.id(), null)).versionId());
        CreateAttempt create = new CreateAttempt(question.versionId(), uuid());
        AttemptView attempt = service.createAttempt(userId, create);
        assertEquals(attempt.attemptId(), service.createAttempt(userId, create).attemptId());
        String json = JsonMapper.builder().build().writeValueAsString(service.getAttempt(userId, attempt.attemptId()));
        assertFalse(json.contains("correctAnswer"));
        assertFalse(json.contains("correctOption"));
        assertFalse(json.contains("contentDigest"));
        assertNull(service.states(userId, materialId).getFirst().probability(), "初始先验不得伪装成已测量的掌握度");
        jdbc.update("UPDATE questions SET option_a='修改后的选项' WHERE user_id=? AND id=?", userId, questionId);
        assertStatus(409, () -> service.bindQuestion(userId, questionId, new BindQuestion(concept.id(), null)));
        assertEquals("选项 A", mapper.findQuestionVersion(userId, question.versionId()).optionA(), "旧题变化不能改写被冻结的题目版本");
    }

    @Test
    void recordReviewUsesTheSubmittedSnapshotAndChecksOwnership() {
        QuestionView question = boundQuestion(concept("历史回看"), "作答时的题干");
        var saved = service.submit(userId, attempt(question).attemptId(), new Submit("B", uuid(), null));
        jdbc.update("UPDATE questions SET content='更新后的题干', option_a='更新后的选项', correct_answer='B' WHERE user_id=? AND id=?",
                userId, question.questionId());

        var detail = answers.getById(userId, saved.recordId());
        assertEquals(question.versionId(), detail.questionVersionId());
        assertEquals(question.content(), detail.content());
        assertEquals("选项 A", detail.optionA());
        assertEquals("B", detail.selectedOption());
        assertEquals("A", detail.correctOptionSnapshot());
        assertFalse(detail.correct());
        var missing = assertThrows(org.springframework.web.server.ResponseStatusException.class,
                () -> answers.getById(newUser(), saved.recordId()));
        assertEquals(404, missing.getStatusCode().value());
    }

    @Test
    void legacyRecordRetainsItsGradeAndLabelsTheCurrentQuestionAsUnversioned() {
        long questionId = newQuestion("原始旧题");
        var saved = answers.submit(userId, new SubmitAnswerRequest(questionId, "A"));
        jdbc.update("UPDATE questions SET content='现在的题干', correct_answer='B' WHERE user_id=? AND id=?",
                userId, questionId);

        var detail = answers.getById(userId, saved.recordId());
        assertNull(detail.questionVersionId());
        assertEquals("现在的题干", detail.content());
        assertEquals("A", detail.correctOptionSnapshot());
        assertTrue(detail.correct());
    }

    @Test
    void simultaneousIdenticalSubmissionsCreateExactlyOneAnswerAndOneEvent() throws Exception {
        QuestionView question = boundQuestion(concept("并发"), "同一请求并发发送");
        AttemptView attempt = attempt(question);
        Submit payload = new Submit("A", uuid(), "VERY_SURE");
        try (var pool = Executors.newFixedThreadPool(2)) {
            CountDownLatch start = new CountDownLatch(1);
            var one = pool.submit(() -> { start.await(); return service.submit(userId, attempt.attemptId(), payload); });
            var two = pool.submit(() -> { start.await(); return service.submit(userId, attempt.attemptId(), payload); });
            start.countDown();
            SubmissionView first = one.get(8, TimeUnit.SECONDS), second = two.get(8, TimeUnit.SECONDS);
            assertEquals(first.recordId(), second.recordId());
            assertEquals(first.eventId(), second.eventId());
            assertTrue(first.eligible());
        }
        assertEquals(1, count("answer_records"));
        assertEquals(1, count("learning_events"));
        assertEquals(1, count("learning_evidence_claims"));
        assertEquals(1, mapper.findMastery(userId, question.conceptId(), BktClient.MODEL_VERSION).nextSequence());
    }

    @Test
    void replayWithChangedOptionOrConfidenceReturnsConflictWithoutAdditionalWrite() {
        QuestionView question = boundQuestion(concept("载荷"), "幂等载荷不能偷偷改变");
        AttemptView attempt = attempt(question);
        String key = uuid();
        service.submit(userId, attempt.attemptId(), new Submit("A", key, "UNSURE"));
        assertStatus(409, () -> service.submit(userId, attempt.attemptId(), new Submit("B", key, "UNSURE")));
        assertStatus(409, () -> service.submit(userId, attempt.attemptId(), new Submit("A", key, "VERY_SURE")));
        assertEquals(1, count("answer_records"));
        assertEquals(1, count("learning_events"));
    }

    @Test
    void explicitRevealIsRecordedBeforeReturningAnswerAndExcludesBktEvidence() {
        QuestionView question = boundQuestion(concept("辅助"), "查看提示后仍允许保存答案");
        AttemptView attempt = attempt(question);
        assertEquals("A", service.reveal(userId, attempt.attemptId()).get("correctOption"));
        assertTrue(service.getAttempt(userId, attempt.attemptId()).assisted());
        SubmissionView answer = service.submit(userId, attempt.attemptId(), new Submit("A", uuid(), null));
        assertFalse(answer.eligible());
        assertEquals("ASSISTED", answer.exclusionReason());
        assertNull(answer.eventId());
        assertEquals(1, count("answer_records"));
        assertEquals(0, count("learning_events"));
    }

    @Test
    void answerAlreadyRecordedThroughLegacyPathCannotBecomeNewIndependentEvidence() {
        QuestionView question = boundQuestion(concept("历史"), "旧接口已答过的题");
        jdbc.update("INSERT INTO answer_records(user_id,question_id,selected_option,correct_option_snapshot,is_correct) VALUES(?,?,'A','A',TRUE)",
                userId, question.questionId());
        SubmissionView answer = service.submit(userId, attempt(question).attemptId(), new Submit("A", uuid(), null));
        assertEquals("PREVIOUSLY_ANSWERED", answer.exclusionReason());
        assertFalse(answer.eligible());
        assertEquals(2, count("answer_records"), "本次作答仍要进入真实答题历史");
        assertEquals(0, count("learning_events"));
    }

    @Test
    void distinctVariantsInSameFamilyContributeAtMostOneObservationPerUtcDay() {
        ConceptView concept = concept("变式");
        QuestionView original = boundQuestion(concept, "原题：哪项体现持久性？");
        QuestionView variant = service.bindQuestion(userId, newQuestion("变式：提交后重新启动仍有结果？"),
                new BindQuestion(concept.id(), original.versionId()));
        assertEquals(mapper.findQuestionVersion(userId, original.versionId()).familyId(),
                mapper.findQuestionVersion(userId, variant.versionId()).familyId());
        assertTrue(service.submit(userId, attempt(original).attemptId(), new Submit("A", uuid(), null)).eligible());
        SubmissionView second = service.submit(userId, attempt(variant).attemptId(), new Submit("B", uuid(), null));
        assertEquals("FAMILY_DAILY_CAP", second.exclusionReason());
        assertEquals(2, count("answer_records"));
        assertEquals(1, count("learning_events"));
        assertEquals(now().toLocalDate(), jdbc.queryForObject("SELECT evidence_date FROM learning_evidence_claims WHERE user_id=?",
                java.sql.Date.class, userId).toLocalDate());
    }

    @Test
    void expiredAttemptRejectsSubmissionWithoutFabricatingWrongAnswer() {
        QuestionView question = boundQuestion(concept("过期"), "过期尝试不算答错");
        AttemptView attempt = attempt(question);
        jdbc.update("UPDATE learning_attempts SET created_at=?,expires_at=? WHERE user_id=? AND id=?",
                now().minusHours(3), now().minusHours(1), userId, attempt.attemptId());
        assertStatus(409, () -> service.submit(userId, attempt.attemptId(), new Submit("A", uuid(), null)));
        assertEquals("EXPIRED", service.getAttempt(userId, attempt.attemptId()).status());
        assertEquals(0, count("answer_records"));
        assertEquals(0, count("learning_events"));
    }

    @Test
    void otherOwnerAndChangedParseAttemptCannotUseFrozenLearningVersion() {
        QuestionView question = boundQuestion(concept("归属"), "资料版本与身份同时校验");
        AttemptView attempt = attempt(question);
        long other = newUser();
        assertStatus(404, () -> service.getAttempt(other, attempt.attemptId()));
        assertStatus(404, () -> service.createAttempt(other, new CreateAttempt(question.versionId(), uuid())));
        assertStatus(404, () -> service.createConcept(other, new CreateConcept(materialId, blockId, "越权", "不能读取别人的资料", "FINE", uuid())));
        jdbc.update("UPDATE materials SET parse_attempt=2 WHERE user_id=? AND id=?", userId, materialId);
        assertStatus(409, () -> service.submit(userId, attempt.attemptId(), new Submit("A", uuid(), null)));
        assertStatus(409, () -> service.createAttempt(userId, new CreateAttempt(question.versionId(), uuid())));
        assertEquals(0, count("answer_records"));
    }

    @Test
    void failedEventInsertRollsBackActualAnswerClaimAndReservedSequenceTogether() {
        QuestionView question = boundQuestion(concept("回滚"), "必须在写入答案后制造失败");
        AttemptView attempt = attempt(question);
        doAnswer(invocation -> {
            // 同线程 JdbcTemplate 加入当前 Spring 事务，可看见故障发生前真实写入的临时数据。
            assertEquals(1, count("answer_records"));
            assertEquals(1, count("learning_evidence_claims"));
            assertEquals(1, mapper.findMastery(userId, question.conceptId(), BktClient.MODEL_VERSION).nextSequence());
            throw new IllegalStateException("controlled event insert failure");
        }).when(mapper).insertEvent(any(LearningRows.Event.class));
        assertThrows(IllegalStateException.class, () -> service.submit(userId, attempt.attemptId(), new Submit("A", uuid(), null)));
        reset(mapper);
        assertEquals(0, count("answer_records"));
        assertEquals(0, count("learning_evidence_claims"));
        assertEquals(0, count("learning_events"));
        assertEquals(0, mapper.findMastery(userId, question.conceptId(), BktClient.MODEL_VERSION).nextSequence());
        assertEquals("OPEN", service.getAttempt(userId, attempt.attemptId()).status());
        assertTrue(service.submit(userId, attempt.attemptId(), new Submit("A", uuid(), null)).eligible(), "回滚后同一尝试应仍可正常提交");
    }

    @Test
    void onlyQueueHeadCanBeClaimedAndOldLeaseTokenCannotApplyAfterReclaim() {
        ConceptView concept = concept("顺序");
        SubmissionView one = service.submit(userId, attempt(boundQuestion(concept, "第一条观测")).attemptId(), new Submit("A", uuid(), null));
        SubmissionView two = service.submit(userId, attempt(boundQuestion(concept, "第二条观测")).attemptId(), new Submit("B", uuid(), null));
        LocalDateTime t0 = now(), t2 = t0.plusSeconds(2);
        String oldToken = uuid(), newToken = uuid();
        assertTrue(mapper.listClaimableEvents(t0, 100).stream().noneMatch(e -> e.id().equals(two.eventId())), "队尾不得越过尚未应用的队首");
        tx.executeWithoutResult(status -> {
            mapper.lockMastery(userId, concept.id(), BktClient.MODEL_VERSION);
            assertEquals(0, mapper.claimEvent(userId, two.eventId(), uuid(), t0, t0.plusSeconds(30)));
            assertEquals(1, mapper.claimEvent(userId, one.eventId(), oldToken, t0, t0.plusSeconds(1)));
        });
        tx.executeWithoutResult(status -> {
            mapper.lockMastery(userId, concept.id(), BktClient.MODEL_VERSION);
            assertEquals(1, mapper.claimEvent(userId, one.eventId(), newToken, t2, t2.plusSeconds(30)));
            assertEquals(0, mapper.applyMastery(userId, concept.id(), BktClient.MODEL_VERSION,
                    0, 1, .5, one.eventId(), oldToken, t2));
            assertEquals(0, mapper.failEvent(userId, one.eventId(), oldToken, true, "STALE", t2, t2));
            double applied = formula(.2, true).masteryProbability();
            assertEquals(1, mapper.applyMastery(userId, concept.id(), BktClient.MODEL_VERSION,
                    0, 1, applied, one.eventId(), newToken, t2));
            assertEquals(1, mapper.markEventApplied(userId, one.eventId(), newToken, .2, applied, t2));
        });
        assertEquals("APPLIED", service.event(userId, one.eventId()).status());
        assertEquals(1, mapper.findMastery(userId, concept.id(), BktClient.MODEL_VERSION).evidenceCount());
        assertTrue(mapper.listClaimableEvents(t2, 100).stream().anyMatch(e -> e.id().equals(two.eventId())));
        assertEquals(0, mapper.markEventApplied(userId, one.eventId(), newToken, .2, .8, t2), "重复发布不能应用第二次");
    }

    @Test
    void pendingEventBlocksPersonalizedSelectionInsteadOfUsingStaleProfile() {
        ConceptView concept = concept("等待更新");
        service.submit(userId, attempt(boundQuestion(concept, "待计算的观测")).attemptId(), new Submit("B", uuid(), null));
        boundQuestion(concept, "未作答的候选题");
        LearningException error = assertThrows(LearningException.class, () -> service.select(userId, materialId, 3));
        assertEquals(409, error.status());
        assertEquals("PROFILE_UPDATING", error.code());
        assertTrue(service.states(userId, materialId).getFirst().updating());
        assertEquals(1, count("answer_records"), "阻止选题不应撤销已经保存的答案");
    }

    @Test
    void workerAppliesThreeMockedHttpResultsAndBktStateActuallyParticipatesInSelection() throws Exception {
        ConceptView concept = concept("实际参与选题");
        for (int i = 0; i < 3; i++) {
            QuestionView observation = boundQuestion(concept, "独立观测 " + i);
            service.submit(userId, attempt(observation).attemptId(), new Submit(i == 1 ? "B" : "A", uuid(), null));
        }
        QuestionView candidate = boundQuestion(concept, "掌握度更新后的新候选题");
        when(bkt.step(anyString(), anyDouble(), anyBoolean())).thenAnswer(invocation ->
                formula(invocation.getArgument(1, Double.class), invocation.getArgument(2, Boolean.class)));
        properties.setWorkerEnabled(true);
        try {
            worker.tick();
            long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(8);
            while (mapper.findMastery(userId, concept.id(), BktClient.MODEL_VERSION).evidenceCount() < 3
                    && System.nanoTime() < deadline) Thread.sleep(15);
            LearningRows.Mastery state = mapper.findMastery(userId, concept.id(), BktClient.MODEL_VERSION);
            assertEquals(3, state.evidenceCount(), "必须真实经过 worker 三次事务发布，不能手工填概率冒充闭环");
            assertEquals(3, state.appliedSequence());
            assertEquals(3, state.stateVersion());
            assertEquals(3, jdbc.queryForObject("SELECT COUNT(*) FROM learning_events WHERE user_id=? AND status='APPLIED'", Integer.class, userId));
            Selection selection = service.select(userId, materialId, 3);
            Recommendation recommendation = selection.questions().getFirst();
            assertEquals(candidate.versionId(), recommendation.question().versionId());
            assertEquals("BKT_WEAKNESS", recommendation.reasonCode());
            assertEquals(state.masteryProbability(), recommendation.probability(), 1e-12);
            assertEquals(3, recommendation.evidenceCount());
            verify(bkt, times(3)).step(anyString(), anyDouble(), anyBoolean());
        } finally {
            properties.setWorkerEnabled(false);
        }
    }

    @Test
    void revealedAnswerRemainsAssistedAfterExpiredAttemptIsReplaced() {
        QuestionView question = boundQuestion(concept("辅助不能洗白"), "已看过答案的题不因换尝试而变成独立证据");
        AttemptView original = attempt(question);
        service.reveal(userId, original.attemptId());
        // 同时回移创建与到期时刻，满足 expires_at>created_at 的真实数据库约束。
        // 只让当前随机用户的这一次尝试过期，不等待两小时，也不篡改其已记录的辅助事实。
        jdbc.update("UPDATE learning_attempts SET created_at=?,expires_at=? WHERE user_id=? AND id=?",
                now().minusHours(3), now().minusHours(1), userId, original.attemptId());
        AttemptView replacement = attempt(question);
        assertNotEquals(original.attemptId(), replacement.attemptId());
        assertEquals("EXPIRED", service.getAttempt(userId, original.attemptId()).status());
        assertTrue(replacement.assisted(), "新尝试必须继承同版本题目已暴露答案的事实");
        SubmissionView answer = service.submit(userId, replacement.attemptId(), new Submit("A", uuid(), "VERY_SURE"));
        assertFalse(answer.eligible());
        assertEquals("ASSISTED", answer.exclusionReason());
        assertEquals(1, count("answer_records"));
        assertEquals(0, count("learning_evidence_claims"));
        assertEquals(0, count("learning_events"));
    }

    @Test
    void pendingEventFromOldParseVersionDoesNotBlockNewValidConceptSelection() {
        ConceptView oldConcept = concept("版本更替");
        QuestionView oldQuestion = boundQuestion(oldConcept, "旧解析版本的独立观测");
        SubmissionView oldAnswer = service.submit(userId, attempt(oldQuestion).attemptId(), new Submit("B", uuid(), null));
        assertEquals("PROFILE_UPDATING", assertThrows(LearningException.class,
                () -> service.select(userId, materialId, 3)).code());

        // 模拟资料重新成功解析：保留同一块编号，但内容版本栅栏已从 1 变为 2。
        // 同名考点可在新版本重新确认；旧概念与旧事件继续留存作审计，不能静默迁移成新概念。
        jdbc.update("UPDATE materials SET parse_attempt=2 WHERE user_id=? AND id=?", userId, materialId);
        ConceptView currentConcept = concept("版本更替");
        assertNotEquals(oldConcept.id(), currentConcept.id());
        QuestionView currentQuestion = boundQuestion(currentConcept, "新解析版本的未答候选题");
        Selection selection = assertDoesNotThrow(() -> service.select(userId, materialId, 3));
        assertEquals(1, selection.questions().size(), "有效候选不足时不混入旧来源题目凑数");
        assertEquals(currentQuestion.versionId(), selection.questions().getFirst().question().versionId());
        assertEquals(currentConcept.id(), selection.questions().getFirst().question().conceptId());
        assertEquals(List.of(currentConcept.id()), service.concepts(userId, materialId).stream().map(ConceptView::id).toList());
        MasteryView historicalState = service.states(userId, materialId).stream()
                .filter(state -> state.conceptId().equals(oldConcept.id())).findFirst().orElseThrow();
        assertEquals("SOURCE_INVALID", historicalState.evidenceStatus());
        assertNull(historicalState.probability(), "失效来源不能展示为当前可用的掌握估计");
        assertEquals("PENDING", service.event(userId, oldAnswer.eventId()).status(), "允许新版本选题不能靠删除旧待办事件实现");
    }

    @Test
    void questionLibraryFiltersWithoutLeakingAnswers() {
        ConceptView concept = concept("题目中心筛选");
        QuestionView question = boundQuestion(concept, "题目中心的题干");
        LibraryPage all = service.library(userId, materialId, "ALL", 1, 20);
        LibraryQuestion unansweredRow = all.records().stream()
                .filter(row -> row.versionId().equals(question.versionId())).findFirst().orElseThrow();
        assertNull(unansweredRow.latestCorrect(), "未作答时不能伪造最近作答结果");
        assertEquals(question.optionA(), unansweredRow.optionA(), "题库需要返回选项供右侧直接作答");
        assertEquals(question.optionD(), unansweredRow.optionD(), "题库选项必须与题目版本一致");
        assertNotNull(unansweredRow.sourceLocator(), "人工绑定的题也应回落到概念来源位置");
        assertTrue(unansweredRow.sourceLocator().contains("lineStart"), "来源位置必须是可展示的定位 JSON");
        LibraryPage unanswered = service.library(userId, materialId, "UNANSWERED", 1, 20);
        assertTrue(unanswered.records().stream().anyMatch(row -> row.versionId().equals(question.versionId())));
        assertTrue(service.library(userId, materialId, "ANSWERED", 1, 20).records().isEmpty());

        service.submit(userId, attempt(question).attemptId(), new Submit("A", uuid(), null));
        LibraryPage answered = service.library(userId, materialId, "ANSWERED", 1, 20);
        LibraryQuestion row = answered.records().stream()
                .filter(item -> item.versionId().equals(question.versionId())).findFirst().orElseThrow();
        assertTrue(row.answered());
        assertEquals(Boolean.TRUE, row.latestCorrect());
        assertTrue(service.library(userId, materialId, "UNANSWERED", 1, 20).records()
                .stream().noneMatch(item -> item.versionId().equals(question.versionId())));
    }

    private ConceptView concept(String name) {
        return service.createConcept(userId, new CreateConcept(materialId, blockId, name,
                name + "：事务提交后的数据应持久保存。", "MEDIUM", uuid()));
    }

    private QuestionView boundQuestion(ConceptView concept, String content) {
        return service.bindQuestion(userId, newQuestion(content), new BindQuestion(concept.id(), null));
    }

    private AttemptView attempt(QuestionView question) {
        return service.createAttempt(userId, new CreateAttempt(question.versionId(), uuid()));
    }

    private long newQuestion(String content) {
        String uniqueContent = content + " " + uuid();
        jdbc.update("""
                INSERT INTO questions(user_id,content,option_a,option_b,option_c,option_d,correct_answer,explanation)
                VALUES(?,?,'选项 A','选项 B','选项 C','选项 D','A','受控测试解析')
                """, userId, uniqueContent);
        return jdbc.queryForObject("SELECT id FROM questions WHERE user_id=? AND content=?", Long.class, userId, uniqueContent);
    }

    private long newUser() {
        String name = "llm1db_" + UUID.randomUUID().toString().replace("-", "").substring(0, 18);
        jdbc.update("INSERT INTO users(username,password_hash) VALUES(?,'disabled-test-account')", name);
        long id = jdbc.queryForObject("SELECT id FROM users WHERE username=?", Long.class, name);
        ownedUsers.add(id);
        return id;
    }

    private int count(String table) {
        // table 只来自本测试文件中的常量；不接收接口、用户文本或任何外部输入。
        return jdbc.queryForObject("SELECT COUNT(*) FROM " + table + " WHERE user_id=?", Integer.class, userId);
    }

    private static void assertStatus(int expected, Runnable work) {
        assertEquals(expected, assertThrows(LearningException.class, work::run).status());
    }

    /** 固定参数模拟 HTTP 响应；用于验证 Java 事务编排，不用它声称真实 pyBKT 已调用。 */
    private static BktClient.Result formula(double prior, boolean correct) {
        double prediction = prior * .9 + (1 - prior) * .25;
        double posterior = correct ? prior * .9 / prediction : prior * .1 / (1 - prediction);
        return new BktClient.Result(prior, prediction, posterior, posterior + (1 - posterior) * .1, "mock-http-only");
    }

    private static String uuid() { return UUID.randomUUID().toString(); }
    private static LocalDateTime now() { return LocalDateTime.now(ZoneOffset.UTC); }
}
