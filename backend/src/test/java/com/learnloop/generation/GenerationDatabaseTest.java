package com.learnloop.generation;

import com.learnloop.learning.LearningApi;
import com.learnloop.learning.LearningService;
import com.learnloop.review.ReviewService;
import com.learnloop.tutor.TutorPlanService;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.dao.DataAccessResourceFailureException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.bean.override.mockito.MockitoSpyBean;
import tools.jackson.databind.json.JsonMapper;
import static com.learnloop.generation.GenerationApi.*;
import static com.learnloop.generation.GenerationProtocol.*;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

/**
 * 真实 MySQL 持久任务验收；显式开关与端口断言防止误写原业务库。
 * Result 是明确的模型边界替身，这些测试不声称调用了真实商业模型。
 * 重点验证幂等、所有权、来源冻结、租约栅栏和“整批发布或整批回滚”。
 */
@EnabledIfEnvironmentVariable(named = "LEARNLOOP_RUN_GENERATION_DB_TESTS", matches = "true")
@SpringBootTest(properties = {
        "spring.datasource.url=jdbc:mysql://127.0.0.1:13316/learnloop?sslMode=DISABLED&allowPublicKeyRetrieval=true&serverTimezone=UTC&characterEncoding=UTF-8",
        "spring.datasource.username=root", "spring.datasource.password=", "spring.datasource.hikari.maximum-pool-size=6",
        "learnloop.learning.enabled=true", "learnloop.learning.worker-enabled=false",
        "learnloop.generation.enabled=true", "learnloop.generation.worker-enabled=false",
        "learnloop.material.parse.recovery-enabled=false",
        "learnloop.material.storage-root=${java.io.tmpdir}/learnloop-generation-test-materials"
})
@org.springframework.context.annotation.Import(com.learnloop.support.ExperimentClientTestSupport.class)
class GenerationDatabaseTest {
    private static final String FIRST_SENTENCE = "事务提交之后，所有修改应当持久保存。";
    private static final String SECOND_SENTENCE = "隔离性用于避免并发事务之间的干扰。";
    private static final String SOURCE = FIRST_SENTENCE + SECOND_SENTENCE;
    @Autowired GenerationService service;
    @Autowired LearningService learning;
    @Autowired ReviewService review;
    @Autowired TutorPlanService tutorPlans;
    @Autowired com.learnloop.tutor.LearningSummaryService summaries;
    @Autowired com.learnloop.tutor.LearningSummaryMapper summaryMapper;
    @Autowired com.learnloop.journal.JournalService journal;
    @Autowired JdbcTemplate jdbc;
    @Autowired org.mybatis.spring.SqlSessionTemplate sessions;
    @MockitoSpyBean GenerationMapper mapper;
    @MockitoSpyBean GenerationClient scheduler;
    private final List<Long> users = new ArrayList<>();
    private long user;
    private long material;
    private long block;

    @BeforeEach
    void fixture() {
        assertEquals(13316, jdbc.queryForObject("SELECT @@port", Integer.class));
        new org.springframework.jdbc.datasource.init.ResourceDatabasePopulator(new org.springframework.core.io.ClassPathResource(
                "db/manual/stage4_7_generation_quality.sql")).execute(jdbc.getDataSource());
        user = newUser();
        String request = uuid();
        jdbc.update("""
            INSERT INTO materials(user_id,original_name,file_type,size_bytes,sha256,storage_key,request_id,
                parse_status,parse_attempt,block_count,text_char_count,updated_at)
            VALUES(?,'generation-test.txt','txt',1,?,?,?,'PARSED',1,1,40,?)
            """, user, "a".repeat(64), uuid() + ".bin", request, now());
        material = jdbc.queryForObject("SELECT id FROM materials WHERE user_id=? AND request_id=?", Long.class, user, request);
        jdbc.update("""
            INSERT INTO material_blocks(material_id,block_no,block_type,text_content,source_locator)
            VALUES(?,1,'text',?,?)
            """, material, SOURCE, "{\"kind\":\"text\",\"lineStart\":1,\"lineEnd\":1,\"partIndex\":1}");
        block = jdbc.queryForObject("SELECT id FROM material_blocks WHERE material_id=?", Long.class, material);
    }

    @AfterEach
    void cleanupOnlyOurOwners() {
        reset(mapper);
        for (long owner : users) {
            jdbc.update("DELETE FROM tutor_plans WHERE user_id=?", owner);
            jdbc.update("DELETE FROM generation_job_items WHERE user_id=?", owner);
            jdbc.update("DELETE FROM generation_jobs WHERE user_id=?", owner);
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
            jdbc.update("DELETE FROM users WHERE id=? AND username LIKE 'llgen_%'", owner);
        }
        users.clear();
    }

    @Test
    void tutorFeedbackIsPrivateAndIdempotent() {
        service.create(user, request(uuid()));
        var claimed = service.claimNext();
        assertTrue(service.publish(claimed, result(claimed, List.of(
                item(null, "持久性", "已经提交的修改如何保存？", FIRST_SENTENCE)))));
        var version = service.get(user, claimed.job().id()).questions().getFirst().question().versionId();
        var attempt = learning.createAttempt(user, new LearningApi.CreateAttempt(version, uuid())).attemptId();
        assertFalse(learning.submit(user, attempt, new LearningApi.Submit("B", uuid(), null)).correct());

        var tutor = sessions.getMapper(com.learnloop.tutor.TutorMapper.class);
        assertEquals(attempt, tutor.wrongPractice(user, attempt));
        assertNull(tutor.wrongPractice(newUser(), attempt));
        var feedback = new com.learnloop.tutor.TutorMapper.Feedback("OTHER", "把提交看成了回滚");
        tutor.saveFeedback(user, "PRACTICE", attempt, feedback);
        tutor.saveFeedback(user, "PRACTICE", attempt, feedback);
        assertEquals(feedback, tutor.feedback(user, "PRACTICE", attempt));
        assertEquals(1, count("tutor_feedback"));
        assertEquals(feedback.detail(), tutor.recentErrors(user).getFirst().detail());
        assertTrue(tutor.marks(user, null, "事务").isEmpty());
        assertTrue(tutor.notes(user, null, "事务").isEmpty());
        assertTrue(tutor.due(user, null).isEmpty());
        tutor.respond(user, "reason:MISREAD", "CORRECTED");
        tutor.respond(user, "reason:MISREAD", "LATER");
        assertEquals(List.of("reason:MISREAD"), tutor.corrected(user));
    }

    @Test
    void explicitRequestFreezesSourceAndExactReplayDoesNotCreateMoreJobs() {
        CreateJob request = request(uuid());
        JobView job = service.create(user, request);
        assertEquals("PENDING", job.status());
        assertEquals(job.jobId(), service.create(user, request).jobId());
        assertEquals(0, count("questions"));
        assertEquals(1, count("generation_jobs"));
        assertStatus(409, () -> service.create(user, new CreateJob(material, List.of(block), 5, "MEDIUM", "MEDIUM", request.requestKey())));
        assertStatus(409, () -> service.create(user, request(uuid())));
    }

    @Test
    void concurrentExactRequestsShareSinglePersistentJob() throws Exception {
        CreateJob request = request(uuid());
        try (var pool = Executors.newFixedThreadPool(2)) {
            CountDownLatch start = new CountDownLatch(1);
            var one = pool.submit(() -> { start.await(); return service.create(user, request); });
            var two = pool.submit(() -> { start.await(); return service.create(user, request); });
            start.countDown();
            assertEquals(one.get(5, TimeUnit.SECONDS).jobId(), two.get(5, TimeUnit.SECONDS).jobId());
        }
        assertEquals(1, count("generation_jobs"));
    }

    @Test
    void ownerAndBlockOwnershipAreChecked() {
        long other = newUser();
        JobView job = service.create(user, request(uuid()));
        assertStatus(404, () -> service.get(other, job.jobId()));
        assertStatus(404, () -> service.retry(other, job.jobId()));
        assertStatus(404, () -> service.create(other, request(uuid())));
    }

    @Test
    void invalidRangeAndOversizeTextAreRejectedInsteadOfSilentlyTruncated() {
        assertStatus(400, () -> service.create(user, new CreateJob(material, List.of(block, block), 3, "EASY", "FINE", uuid())));
        assertStatus(400, () -> service.create(user, new CreateJob(material, List.of(block), 4, "EASY", "FINE", uuid())));
        jdbc.update("UPDATE material_blocks SET text_content=? WHERE id=?", "字".repeat(12001), block);
        assertStatus(400, () -> service.create(user, request(uuid())));
        assertEquals(0, count("generation_jobs"));
    }

    @Test
    void publicationIsAtomicIdempotentAndReturnsOnlySafeQuestions() throws Exception {
        JobView job = service.create(user, request(uuid()));
        GenerationService.Claimed claimed = service.claimNext();
        assertEquals(job.jobId(), claimed.job().id());
        Result result = result(claimed, List.of(item(null, "持久性", "提交后结果应当如何保存？")));
        assertTrue(service.publish(claimed, result));
        assertFalse(service.publish(claimed, result));
        JobView saved = service.get(user, job.jobId());
        assertEquals("SUCCEEDED", saved.status());
        assertEquals(1, saved.generatedCount());
        assertNotNull(saved.shortfallReason());
        assertEquals(1, count("questions"));
        assertEquals(1, count("learning_questions"));
        assertEquals(1, count("learning_concepts"));
        assertEquals(1, count("learning_mastery"));
        String json = JsonMapper.builder().build().writeValueAsString(saved);
        assertFalse(json.contains("correctAnswer"));
        assertFalse(json.contains("explanation"));
        assertFalse(json.contains("conceptDefinition"));
        assertFalse(json.contains("snapshotJson"));
        assertFalse(json.contains("sourceQuote"), "作答前不能自动展示含答案的精确原文引用");
        assertEquals("A", learning.submit(user, learning.createAttempt(user,
                new LearningApi.CreateAttempt(saved.questions().getFirst().question().versionId(), uuid())).attemptId(),
                new LearningApi.Submit("A", uuid(), null)).correctOption());
        assertEquals(1, count("learning_events"), "生成题复用真实有效答题/BKT 事件链路");
    }

    @Test
    void newBatchFiltersCoveredConceptsAndDuplicateFactsAcrossBatches() {
        service.create(user, request(uuid()));
        GenerationService.Claimed first = service.claimNext();
        assertTrue(service.publish(first, result(first, List.of(
                item(null, "持久性", "已经提交的修改如何保存？", FIRST_SENTENCE)))));
        String conceptId = service.get(user, first.job().id()).questions().getFirst().question().conceptId();

        service.create(user, request(uuid()));
        GenerationService.Claimed second = service.claimNext();
        assertTrue(second.request().knownConcepts().stream()
                .anyMatch(concept -> concept.conceptId().equals(conceptId) && concept.name().equals("持久性")));
        assertFalse(second.request().knownQuestions().isEmpty(), "跨批次必须把已出题交给模型避重");
        assertTrue(service.publish(second, result(second, List.of(
                item(null, "持久性", "断电后已提交结果应怎样？", SECOND_SENTENCE),
                item(null, "隔离性", "并发事务如何避免相互影响？", FIRST_SENTENCE),
                item(null, "原子性", "已经提交的修改如何保存？（请选择最符合原文的一项）", SECOND_SENTENCE)))));
        JobView saved = service.get(user, second.job().id());
        assertEquals("SUCCEEDED", saved.status());
        assertEquals(0, saved.generatedCount());
        assertTrue(saved.shortfallReason().contains("已过滤 3 道"));
        assertEquals(1, count("learning_concepts"));
        assertEquals(1, count("learning_questions"));
    }

    @Test
    void variantCreatesOneQuestionForTargetConceptInSameFamily() {
        service.create(user, request(uuid()));
        GenerationService.Claimed first = service.claimNext();
        assertTrue(service.publish(first, result(first, List.of(
                item(null, "持久性", "已经提交的修改如何保存？", FIRST_SENTENCE)))));
        LearningApi.QuestionView original = service.get(user, first.job().id()).questions().getFirst().question();

        CreateVariant request = new CreateVariant(original.versionId(), uuid());
        JobView variantJob = service.createVariant(user, request);
        assertEquals(variantJob.jobId(), service.createVariant(user, request).jobId());
        assertEquals(1, variantJob.requestedCount());
        assertEquals("VARIANT", variantJob.mode());
        assertEquals(original.conceptId(), variantJob.targetConceptId());

        GenerationService.Claimed variant = service.claimNext();
        assertEquals("VARIANT", variant.request().mode());
        assertEquals(1, variant.request().count());
        assertEquals(original.conceptId(), variant.request().targetConceptId());
        assertFalse(variant.request().knownQuestions().isEmpty());

        Item candidate = item(original.conceptId(), "持久性", "哪种情况下修改能够保留？", SECOND_SENTENCE);
        assertTrue(service.publish(variant, result(variant, List.of(candidate))));
        JobView saved = service.get(user, variantJob.jobId());
        assertEquals("SUCCEEDED", saved.status());
        assertEquals(1, saved.generatedCount());
        assertEquals(2, count("learning_questions"));
        assertEquals(1, count("learning_concepts"));
        assertEquals(1, jdbc.queryForObject(
                "SELECT COUNT(DISTINCT family_id) FROM learning_questions WHERE user_id=?", Integer.class, user));
        // 任务创建时间和实际出题时间可能相反，列表分页以实际保存的新题为先。
        String variantVersion = saved.questions().getFirst().question().versionId();
        LocalDateTime generatedAt = now();
        jdbc.update("UPDATE learning_questions SET created_at=? WHERE id=? AND user_id=?",
                generatedAt.minusHours(1), original.versionId(), user);
        jdbc.update("UPDATE learning_questions SET created_at=? WHERE id=? AND user_id=?",
                generatedAt, variantVersion, user);
        jdbc.update("UPDATE generation_jobs SET created_at=? WHERE id=? AND user_id=?",
                generatedAt.plusHours(1), first.job().id(), user);
        assertEquals(variantVersion, learning.library(user, material, "ALL", 1, 1).records().getFirst().versionId());
        assertEquals(original.versionId(), learning.library(user, material, "ALL", 2, 1).records().getFirst().versionId());
    }

    @Test
    void variantRejectsNearDuplicateOfExistingQuestion() {
        service.create(user, request(uuid()));
        GenerationService.Claimed first = service.claimNext();
        String stem = "已经提交的修改如何保存？";
        assertTrue(service.publish(first, result(first, List.of(item(null, "持久性", stem, FIRST_SENTENCE)))));
        LearningApi.QuestionView original = service.get(user, first.job().id()).questions().getFirst().question();

        service.createVariant(user, new CreateVariant(original.versionId(), uuid()));
        GenerationService.Claimed variant = service.claimNext();
        Item duplicate = item(original.conceptId(), "持久性", stem, SECOND_SENTENCE);
        GenerationException error = assertThrows(GenerationException.class,
                () -> service.publish(variant, result(variant, List.of(duplicate))));
        assertEquals("GENERATION_VARIANT_TOO_SIMILAR", error.code());
        assertEquals(1, count("learning_questions"));
    }

    @Test
    void variantRequiresOwnedQuestionAndIsBoundToTheOriginalRequestKey() {
        service.create(user, request(uuid()));
        GenerationService.Claimed first = service.claimNext();
        service.publish(first, result(first, List.of(item(null, "持久性", "已提交的修改如何保存？", FIRST_SENTENCE))));
        LearningApi.QuestionView original = service.get(user, first.job().id()).questions().getFirst().question();
        long other = newUser();

        assertStatus(404, () -> service.createVariant(other, new CreateVariant(original.versionId(), uuid())));
        service.createVariant(user, new CreateVariant(original.versionId(), uuid()));
        assertStatus(409, () -> service.createVariant(user, new CreateVariant(original.versionId(), uuid())));
        assertEquals(2, count("generation_jobs"));
    }

    @Test
    void inventedQuoteRejectsWholePublication() {
        service.create(user, request(uuid()));
        GenerationService.Claimed claimed = service.claimNext();
        Item valid = item(null, "持久性", "测试题？");
        Item bad = new Item(null, block, "这句话完全没有出现在原文之中。", valid.conceptName(), valid.conceptDefinition(),
                valid.content(), valid.optionA(), valid.optionB(), valid.optionC(), valid.optionD(), valid.correctAnswer(), valid.explanation());
        assertStatus(503, () -> service.publish(claimed, result(claimed, List.of(bad))));
        assertEquals(0, count("questions"));
        assertEquals(0, count("learning_concepts"));
    }

    @Test
    void reparseDuringModelCallCannotPublishOldSource() {
        service.create(user, request(uuid()));
        GenerationService.Claimed claimed = service.claimNext();
        jdbc.update("UPDATE materials SET parse_attempt=parse_attempt+1 WHERE id=?", material);
        GenerationException error = assertThrows(GenerationException.class,
                () -> service.publish(claimed, result(claimed, List.of(item(null, "持久性", "旧来源测试？")))));
        assertEquals("GENERATION_SOURCE_CHANGED", error.code());
        assertEquals(0, count("questions"));
        service.fail(claimed, error.code());
        assertStatus(409, () -> service.retry(user, claimed.job().id()));
    }

    @Test
    void sourceTextMutationWithUnchangedVersionIsAlsoRejected() {
        service.create(user, request(uuid()));
        GenerationService.Claimed claimed = service.claimNext();
        jdbc.update("UPDATE material_blocks SET text_content='资料被错误地原地改写。' WHERE id=?", block);
        assertStatus(409, () -> service.publish(claimed, result(claimed, List.of(item(null, "持久性", "测试题？")))));
        assertEquals(0, count("questions"));
    }

    @Test
    void failedSecondItemRollsBackFirstQuestionConceptAndMastery() {
        service.create(user, request(uuid()));
        GenerationService.Claimed claimed = service.claimNext();
        AtomicInteger calls = new AtomicInteger();
        doAnswer(call -> {
            if (calls.incrementAndGet() == 2) throw new DataAccessResourceFailureException("controlled publication failure");
            // Mapper 是接口代理，Mockito 的 callRealMethod 不存在具体方法；从同一 SqlSession
            // 取得未包装的 MyBatis 代理，仍参与当前 Spring 事务，第一项真实写库。
            return sessions.getMapper(GenerationMapper.class).insertItem(call.getArgument(0));
        }).when(mapper).insertItem(any());
        assertThrows(DataAccessResourceFailureException.class, () -> service.publish(claimed,
                result(claimed, List.of(item(null, "持久性", "提交后的性质是什么？", FIRST_SENTENCE),
                        item(null, "隔离性", "并发事务怎样避免相互干扰？", SECOND_SENTENCE)))));
        assertEquals(0, count("questions"));
        assertEquals(0, count("learning_questions"));
        assertEquals(0, count("learning_concepts"));
        assertEquals(0, count("learning_mastery"));
        assertEquals(0, count("generation_job_items"));
        service.fail(claimed, "GENERATION_PROCESSING_FAILED");
        assertEquals("FAILED", service.get(user, claimed.job().id()).status());
    }

    @Test
    void expiredCallIsNotAutomaticallyReplayedAndOldTokenCannotPublishAfterManualRetry() {
        JobView job = service.create(user, request(uuid()));
        GenerationService.Claimed old = service.claimNext();
        jdbc.update("UPDATE generation_jobs SET lease_until=? WHERE id=?", now().minusSeconds(1), job.jobId());
        service.expireInterrupted();
        assertNull(service.claimNext(), "过期模型请求只失败，不能自动重复付费");
        assertEquals("GENERATION_INTERRUPTED", service.get(user, job.jobId()).errorCode());
        assertEquals(1, service.get(user, job.jobId()).attemptCount());
        service.retry(user, job.jobId());
        GenerationService.Claimed fresh = service.claimNext();
        assertNotEquals(old.request().attemptToken(), fresh.request().attemptToken());
        assertFalse(service.publish(old, result(old, List.of(item(null, "持久性", "旧尝试不能落库？")))));
        service.fail(old, "OLD_RESPONSE_FAILURE");
        assertEquals("PROCESSING", service.get(user, job.jobId()).status());
        assertTrue(service.publish(fresh, result(fresh, List.of(item(null, "持久性", "新尝试才允许落库？")))));
        assertEquals(2, service.get(user, job.jobId()).attemptCount());
        assertEquals(1, count("questions"));
    }

    @Test
    void insufficientEvidenceCanReturnZeroQuestionsWithExplicitReason() {
        service.create(user, request(uuid()));
        GenerationService.Claimed claimed = service.claimNext();
        assertTrue(service.publish(claimed, result(claimed, List.of())));
        JobView job = service.get(user, claimed.job().id());
        assertEquals("SUCCEEDED", job.status());
        assertEquals(0, job.generatedCount());
        assertNotNull(job.shortfallReason());
        assertEquals(0, count("questions"));
    }

    @Test
    void changedSourceBeforeClaimFailsWithoutAnyModelRequest() {
        JobView job = service.create(user, request(uuid()));
        jdbc.update("UPDATE materials SET parse_attempt=parse_attempt+1 WHERE id=?", material);
        assertNull(service.claimNext(), "没有认领结果就不会进入模型调用");
        assertEquals("FAILED", service.get(user, job.jobId()).status());
        assertEquals("GENERATION_SOURCE_CHANGED", service.get(user, job.jobId()).errorCode());
        assertEquals(0, count("questions"));
    }

    @Test
    void publishedTopicsGetReviewCardsAndExplicitVariantsKeepTheReviewProgress() {
        try {
            var create = new CreateJob(material, List.of(block), 3, "MEDIUM", "MEDIUM", uuid(), "COVERAGE");
            service.create(user, create);
            var claimed = service.claimNext();
            var catalogRequest = service.catalogRequest(claimed);
            var entries = List.of(
                    new AdaptiveCatalog.Entry(null, block, FIRST_SENTENCE, "事务", "ACID", "持久性", "提交后保存", List.of()),
                    new AdaptiveCatalog.Entry(null, block, SECOND_SENTENCE, "事务", "ACID", "隔离性", "并发不互扰", List.of()));
            var first = service.selectAdaptive(claimed, catalogRequest, new AdaptiveCatalog.CatalogResult(entries));
            assertTrue(service.publish(first, result(first, List.of())));
            assertEquals(0, count("topic_review_cards"), "零题不能提前建立复习卡");
            service.create(user, new CreateJob(material, List.of(block), 3, "MEDIUM", "MEDIUM", uuid(), "COVERAGE"));
            first = service.selectAdaptive(service.claimNext(), null, null);
            var durable = first.request().knownConcepts().stream().filter(c -> c.name().equals("持久性")).findFirst().orElseThrow();
            assertTrue(service.publish(first, result(first, List.of(item(durable.conceptId(), durable.name(), "提交后如何保存？", FIRST_SENTENCE)))));
            assertEquals(1, count("adaptive_generation_choices"));
            assertEquals(1, count("topic_review_cards"));
            assertEquals(durable.conceptId(), jdbc.queryForObject("SELECT topic_id FROM topic_review_cards WHERE user_id=?", String.class, user));
            assertNull(service.groups(user, material).getFirst().probability());
            assertTrue(service.groups(newUser(), material).isEmpty());

            var original = service.get(user, first.job().id()).questions().getFirst().question();
            service.createVariant(user, new CreateVariant(original.versionId(), uuid()));
            var second = service.claimNext();
            assertEquals("VARIANT", second.request().mode());
            assertTrue(service.publish(second, result(second, List.of(item(original.conceptId(), durable.name(),
                    "系统在确认交易后断电，重启后应看到什么结果？", FIRST_SENTENCE)))));
            assertEquals(1, count("topic_review_cards"));
            assertEquals(1, jdbc.queryForObject("SELECT COUNT(DISTINCT family_id) FROM learning_questions WHERE user_id=?", Integer.class, user));

            jdbc.update("UPDATE topic_review_cards SET due_at=? WHERE user_id=?", now().minusMinutes(1), user);
            String markId = (uuid() + uuid()).replace("-", "");
            jdbc.update("""
                INSERT INTO reading_highlights(id,user_id,material_id,parse_attempt,block_id,start_offset,end_offset,
                    quote,question,answer,detail,color,created_at)
                VALUES(?,?,?,1,?,0,?,?, '', '原文解释','BRIEF','LEMON',?)
                """, markId, user, material, block, FIRST_SENTENCE.length(), FIRST_SENTENCE, now());
            var study = journal.study(user, material, markId);
            assertEquals(1, study.page());
            assertTrue(study.topics().stream().anyMatch(topic -> topic.id().equals(durable.conceptId()) && topic.suggested()));
            var edit = new com.learnloop.journal.JournalApi.StudyEdit("CONFUSED", durable.conceptId());
            assertThrows(RuntimeException.class, () -> journal.saveStudy(newUser(), material, markId, edit));
            assertThrows(RuntimeException.class, () -> journal.saveStudy(user, material, markId,
                    new com.learnloop.journal.JournalApi.StudyEdit("CONFUSED", uuid())));
            var updated = journal.saveStudy(user, material, markId, edit);
            assertEquals("CONFUSED", updated.intent());
            assertEquals(durable.conceptId(), updated.topicId());
            var tutorMarks = sessions.getMapper(com.learnloop.tutor.TutorMapper.class);
            assertEquals(durable.conceptId(), tutorMarks.marks(user, material, "").getFirst().topicId());
            jdbc.update("UPDATE materials SET parse_attempt=2 WHERE id=?", material);
            assertTrue(tutorMarks.marks(user, material, "").isEmpty());
            assertThrows(RuntimeException.class, () -> journal.saveStudy(user, material, markId, edit));
            jdbc.update("UPDATE materials SET parse_attempt=1 WHERE id=?", material);
            assertNull(journal.saveStudy(user, material, markId,
                    new com.learnloop.journal.JournalApi.StudyEdit("HIGHLIGHT", null)).topicId());
            journal.deleteHighlight(user, material, markId);
            assertEquals(0, jdbc.queryForObject("SELECT COUNT(*) FROM reading_highlight_study WHERE highlight_id=?", Integer.class, markId));
            var due = review.today(user);
            assertEquals(1, due.remaining());
            assertEquals(durable.conceptId(), due.topicId());
            var draft = tutorPlans.draft(user, new TutorPlanService.Input(6, material));
            assertEquals("DRAFT", draft.status());
            assertEquals(draft.id(), tutorPlans.latest(user).id());
            assertEquals(0, count("learning_attempts"));
            assertThrows(org.springframework.web.server.ResponseStatusException.class, () -> tutorPlans.open(user, draft.id()));
            assertThrows(org.springframework.web.server.ResponseStatusException.class, () -> tutorPlans.confirm(newUser(), draft.id()));
            assertEquals("ACTIVE", tutorPlans.confirm(user, draft.id()).status());
            assertEquals("ACTIVE", tutorPlans.confirm(user, draft.id()).status());
            var step = tutorPlans.open(user, draft.id());
            assertEquals("REVIEW", step.kind());
            assertEquals(due.versionId(), step.item().versionId());
            assertThrows(org.springframework.web.server.ResponseStatusException.class,
                    () -> tutorPlans.advance(user, draft.id(), new TutorPlanService.Advance(0)));
            var answer = review.answer(user, due.topicId(), due.versionId(), "B", due.dueAt());
            assertEquals("A", answer.correctAnswer());
            assertEquals(answer, review.answer(user, due.topicId(), due.versionId(), "A", due.dueAt()));
            doThrow(new GenerationException(503, "FSRS_UNAVAILABLE", "test failure")).doAnswer(call ->
                    new GenerationClient.ReviewSchedule("{}", call.<Instant>getArgument(2).plus(Duration.ofDays(2))))
                    .when(scheduler).scheduleReview(isNull(), eq(3), any());
            assertThrows(org.springframework.web.server.ResponseStatusException.class, () -> review.rate(user, answer.attemptId(), 3));
            assertEquals(due.dueAt(), review.today(user).dueAt());
            var rating = review.rate(user, answer.attemptId(), 3);
            assertEquals(rating, review.rate(user, answer.attemptId(), 3));
            assertNull(review.today(user));
            var reviewEvidence=summaries.load(user,material).attempts();
            assertEquals(1,reviewEvidence.size());
            assertEquals("REVIEW",reviewEvidence.getFirst().kind());
            assertEquals("B",reviewEvidence.getFirst().selected());
            assertEquals("A",reviewEvidence.getFirst().correctAnswer());
            assertFalse(reviewEvidence.getFirst().correct());
            assertTrue(summaryMapper.attempts(newUser(),null).isEmpty());
            assertThrows(org.springframework.web.server.ResponseStatusException.class, () -> review.rate(user, answer.attemptId(), 4));
            assertEquals(1, count("topic_review_attempts"));
            assertEquals(0, count("learning_events"));
            verify(scheduler, times(2)).scheduleReview(isNull(), eq(3), any());
            assertTrue(tutorPlans.open(user, draft.id()).finished());
            var done = tutorPlans.advance(user, draft.id(), new TutorPlanService.Advance(0));
            assertEquals("COMPLETED", done.status());
            assertEquals(done, tutorPlans.advance(user, draft.id(), new TutorPlanService.Advance(0)));
            var practice = tutorPlans.draft(user, new TutorPlanService.Input(6, material));
            tutorPlans.confirm(user, practice.id());
            var attempt = tutorPlans.open(user, practice.id());
            assertEquals("PRACTICE", attempt.kind());
            assertEquals(attempt.attemptId(), tutorPlans.open(user, practice.id()).attemptId());
            learning.submit(user, attempt.attemptId(), new LearningApi.Submit("A", uuid(), null));
            assertEquals("COMPLETED", tutorPlans.advance(user, practice.id(), new TutorPlanService.Advance(0)).status());
            var summary=summaries.load(user,material);
            assertEquals(2,summary.attempts().size());
            assertEquals(2,summary.attempts().stream().map(com.learnloop.tutor.LearningSummaryMapper.Attempt::kind).distinct().count());
            assertEquals(1,count("learning_events"),"读取摘要不新增 BKT 事件，复习不冒充独立练习");
            String pdfId=uuid(),noteId=uuid();
            jdbc.update("INSERT INTO reading_notes(id,user_id,material_id,quote,question,content,revision,updated_at) VALUES(?,?,?,'原文选区','阅读笔记','这里没理解',?,?)",noteId,user,material,uuid(),now());
            jdbc.update("INSERT INTO pdf_annotations(id,user_id,material_id,page_number,quote,rects,color,revision,create_hash,note_id,created_at,updated_at) VALUES(?,?,?,1,'原文选区','[]','LEMON',?,?,?, ?,?)",pdfId,user,material,uuid(),"a".repeat(64),noteId,now(),now());
            var notes=summaryMapper.reading(user,material);
            assertEquals(1,notes.stream().filter(r->r.kind().equals("PDF_ANNOTATION")).count());
            assertTrue(notes.stream().anyMatch(r->r.id().equals(pdfId)&&r.content().equals("这里没理解")));
            assertTrue(notes.stream().noneMatch(r->r.id().equals(noteId)),"同一PDF批注和同步笔记只保留一份证据");
            assertTrue(summaryMapper.reading(newUser(),null).isEmpty());
            jdbc.update("UPDATE pdf_annotations SET deleted=1 WHERE id=?",pdfId);
            assertTrue(summaryMapper.reading(user,material).stream().noneMatch(r->r.id().equals(pdfId)||r.id().equals(noteId)));
            jdbc.update("DELETE FROM topic_review_attempts WHERE user_id=?", user);
            jdbc.update("DELETE FROM topic_review_cards WHERE user_id=?", user);
            service.restoreReviewCards(user);
            service.create(user, new CreateJob(material, List.of(block), 3, "MEDIUM", "MEDIUM", uuid(), "WEAKNESS"));
            var restored = service.selectAdaptive(service.claimNext(), null, null);
            assertTrue(restored.request().reinforcementIds().isEmpty());
            assertEquals(List.of("隔离性"), restored.request().knownConcepts().stream().map(KnownConcept::name).toList(),
                    "同一页中已提取但未出题的考点仍可选中，不受已有复习卡限制");
            assertEquals(first.job().id(), jdbc.queryForObject("SELECT locked_job_id FROM topic_review_cards WHERE user_id=?", String.class, user));
            assertTrue(restored.plan().notice().contains("新考点不足"));
        } finally {
            for (String table : List.of("topic_review_attempts", "topic_review_cards", "adaptive_generation_choices", "adaptive_topic_links", "adaptive_topic_sources",
                    "adaptive_indexed_blocks", "adaptive_topics")) jdbc.update("DELETE FROM " + table + " WHERE user_id=?", user);
        }
    }

    @Test
    void laterPagesCanPublishNewTopicsAndBackfillOnlyMissingReviewCards() {
        try {
            service.create(user, new CreateJob(material, List.of(block), 3, "MEDIUM", "MEDIUM", uuid(), "COVERAGE"));
            var claimed = service.claimNext();
            var first = service.selectAdaptive(claimed, service.catalogRequest(claimed), new AdaptiveCatalog.CatalogResult(List.of(
                    new AdaptiveCatalog.Entry(null, block, FIRST_SENTENCE, "事务", "ACID", "持久性", "提交后保存", List.of()))));
            var durable = first.request().knownConcepts().getFirst();
            assertTrue(service.publish(first, result(first, List.of(
                    item(durable.conceptId(), durable.name(), "提交完成后数据如何保留？", FIRST_SENTENCE)))));
            LocalDateTime priorDue = now().plusDays(10).truncatedTo(java.time.temporal.ChronoUnit.MICROS);
            jdbc.update("UPDATE topic_review_cards SET due_at=? WHERE user_id=?", priorDue, user);

            // 第一页已有复习卡，随后选择此前未出题的第二页：必须提取并发布新的考点。
            jdbc.update("""
                INSERT INTO material_blocks(material_id,block_no,block_type,text_content,source_locator)
                VALUES(?,2,'text',?,?)
                """, material, SECOND_SENTENCE, "{\"kind\":\"text\",\"lineStart\":2,\"lineEnd\":2,\"partIndex\":1}");
            long nextBlock = jdbc.queryForObject("SELECT id FROM material_blocks WHERE material_id=? AND block_no=2", Long.class, material);
            jdbc.update("UPDATE materials SET block_count=2 WHERE id=? AND user_id=?", material, user);
            var create = new CreateJob(material, List.of(nextBlock), 3, "HARD", "FINE", uuid(), "COVERAGE");
            var laterJob = service.create(user, create);
            assertEquals(laterJob.jobId(), service.create(user, create).jobId());
            claimed = service.claimNext();
            var catalog = service.catalogRequest(claimed);
            assertEquals(List.of(nextBlock), catalog.sources().stream().map(Source::blockId).toList());
            var later = service.selectAdaptive(claimed, catalog, new AdaptiveCatalog.CatalogResult(List.of(
                    new AdaptiveCatalog.Entry(null, nextBlock, SECOND_SENTENCE, "事务", "ACID", "隔离性", "并发不互扰", List.of()))));
            assertEquals(List.of("隔离性"), later.request().knownConcepts().stream().map(KnownConcept::name).toList());
            var isolation = later.request().knownConcepts().getFirst();
            Item question = new Item(isolation.conceptId(), nextBlock, SECOND_SENTENCE, isolation.name(), isolation.definition(),
                    "两个事务同时执行时，应由哪项性质防止相互干扰？", "隔离性", "丢弃数据", "忽略并发", "禁止提交", "A", "隔离性避免并发事务相互干扰。");
            var published = result(later, List.of(question));
            assertTrue(service.publish(later, published));
            assertFalse(service.publish(later, published));
            assertEquals(2, count("topic_review_cards"));
            assertEquals(2, count("adaptive_generation_choices"));
            assertEquals(priorDue, jdbc.queryForObject("SELECT due_at FROM topic_review_cards WHERE user_id=? AND topic_id=?",
                    LocalDateTime.class, user, durable.conceptId()));

            // 模拟旧版只有首批卡的资料：非首批题可以主动生成变式；补卡只补缺失项。
            jdbc.update("DELETE FROM topic_review_cards WHERE user_id=? AND topic_id=?", user, isolation.conceptId());
            var laterQuestion = service.get(user, later.job().id()).questions().getFirst().question();
            var variant = service.createVariant(user, new CreateVariant(laterQuestion.versionId(), uuid()));
            assertEquals("VARIANT", variant.mode());
            var variantClaimed = service.claimNext();
            service.fail(variantClaimed, "TEST_NOT_SENT_TO_MODEL");
            service.restoreReviewCards(user);
            service.restoreReviewCards(user);
            assertEquals(2, count("topic_review_cards"));
            assertEquals(later.job().id(), jdbc.queryForObject("SELECT locked_job_id FROM topic_review_cards WHERE user_id=? AND topic_id=?",
                    String.class, user, isolation.conceptId()));
            assertEquals(priorDue, jdbc.queryForObject("SELECT due_at FROM topic_review_cards WHERE user_id=? AND topic_id=?",
                    LocalDateTime.class, user, durable.conceptId()));

            service.create(user, new CreateJob(material, List.of(block, nextBlock), 3, "HARD", "FINE", uuid(), "COVERAGE"));
            claimed = service.claimNext();
            assertNull(service.catalogRequest(claimed), "已提取页面复用考点目录");
            assertNull(service.selectAdaptive(claimed, null, null), "覆盖模式仍跳过已有题目的考点");
            assertEquals(2, count("learning_questions"));
            assertEquals(2, count("topic_review_cards"));
            assertEquals(0, jdbc.queryForObject("SELECT COUNT(*) FROM topic_review_cards WHERE user_id=?", Integer.class, newUser()));
        } finally {
            for (String table : List.of("topic_review_cards", "adaptive_generation_choices", "adaptive_topic_links", "adaptive_topic_sources",
                    "adaptive_indexed_blocks", "adaptive_topics")) jdbc.update("DELETE FROM " + table + " WHERE user_id=?", user);
        }
    }

    @Test
    void catalogKeepsIdentityAndAliasesAcrossRefreshAndGranularityChanges() {
        try {
            service.create(user,new CreateJob(material,List.of(block),3,"MEDIUM","COARSE",uuid(),"COVERAGE"));
            var claimed=service.claimNext();
            var entries=List.of(
                    new AdaptiveCatalog.Entry(null,block,FIRST_SENTENCE,"事务","ACID","持久性","提交后保存",List.of(),List.of("事务持久性"),"COARSE"),
                    new AdaptiveCatalog.Entry(null,block,SECOND_SENTENCE,"事务","ACID","隔离性","并发不互扰",List.of(),List.of(),"MEDIUM"),
                    new AdaptiveCatalog.Entry(null,block,FIRST_SENTENCE,"事务","ACID","提交时点","保留发生在提交以后",List.of(),List.of(),"FINE"));
            var core=service.selectAdaptive(claimed,service.catalogRequest(claimed),new AdaptiveCatalog.CatalogResult(entries));
            assertEquals(List.of("持久性"),core.request().knownConcepts().stream().map(KnownConcept::name).toList());
            String durable=core.request().knownConcepts().getFirst().conceptId();
            assertTrue(service.publish(core,result(core,List.of())));

            service.create(user,new CreateJob(material,List.of(block),3,"HARD","MEDIUM",uuid(),"COVERAGE",true));
            claimed=service.claimNext();
            assertNotNull(service.catalogRequest(claimed),"用户明确刷新才重新提取已缓存目录");
            var refreshed=List.of(new AdaptiveCatalog.Entry(durable,block,FIRST_SENTENCE,"改写的章节","改写的知识组",
                    "提交后不丢失","提交后保存",List.of(),List.of("事务持久性"),"FINE"));
            var standard=service.selectAdaptive(claimed,service.catalogRequest(claimed),new AdaptiveCatalog.CatalogResult(refreshed));
            assertEquals(2,standard.request().knownConcepts().size());
            assertEquals("持久性",standard.request().knownConcepts().stream().filter(c->c.conceptId().equals(durable)).findFirst().orElseThrow().name());
            assertEquals(3,count("adaptive_topics"),"重提取和换档不得复制同一个考点");
            assertEquals("COARSE",jdbc.queryForObject("SELECT granularity FROM adaptive_topic_profiles WHERE user_id=? AND topic_id=?",String.class,user,durable));
            assertTrue(jdbc.queryForObject("SELECT aliases_json FROM adaptive_topic_profiles WHERE user_id=? AND topic_id=?",String.class,user,durable).contains("提交后不丢失"));
            assertTrue(service.publish(standard,result(standard,List.of())));

            service.create(user,new CreateJob(material,List.of(block),3,"HARD","FINE",uuid(),"COVERAGE"));
            claimed=service.claimNext();
            assertNull(service.catalogRequest(claimed));
            var fine=service.selectAdaptive(claimed,null,null);
            assertEquals(3,fine.request().knownConcepts().size());
            assertTrue(service.publish(fine,result(fine,List.of())));
        } finally { clearAdaptiveFixture(); }
    }

    @Test
    void uncertainEmptyCatalogRetriesAndNonKnowledgeCanBeExplicitlyRefreshed() {
        try {
            service.create(user,new CreateJob(material,List.of(block),3,"MEDIUM","MEDIUM",uuid(),"COVERAGE"));
            var claimed=service.claimNext();
            assertNull(service.selectAdaptive(claimed,service.catalogRequest(claimed),new AdaptiveCatalog.CatalogResult(List.of())));
            assertTrue(service.get(user,claimed.job().id()).shortfallReason().contains("重新提取"));
            service.create(user,new CreateJob(material,List.of(block),3,"MEDIUM","MEDIUM",uuid(),"COVERAGE"));
            claimed=service.claimNext();
            assertNotNull(service.catalogRequest(claimed),"不确定的空结果不能永久缓存");
            var noKnowledge=new AdaptiveCatalog.CatalogResult(List.of(),List.of(new AdaptiveCatalog.BlockResult(block,"NO_KNOWLEDGE","仅包含目录介绍")));
            assertNull(service.selectAdaptive(claimed,service.catalogRequest(claimed),noKnowledge));

            service.create(user,new CreateJob(material,List.of(block),3,"MEDIUM","MEDIUM",uuid(),"COVERAGE"));
            claimed=service.claimNext();
            assertNull(service.catalogRequest(claimed),"明确非知识页可缓存，但用户能手动刷新");
            assertNull(service.selectAdaptive(claimed,null,null));
            String key=uuid();
            service.create(user,new CreateJob(material,List.of(block),3,"MEDIUM","MEDIUM",key,"COVERAGE",true));
            assertStatus(409,()->service.create(user,new CreateJob(material,List.of(block),3,"MEDIUM","MEDIUM",key,"COVERAGE",false)));
            claimed=service.claimNext();
            var request=service.catalogRequest(claimed);
            assertNotNull(request);
            var selected=service.selectAdaptive(claimed,request,new AdaptiveCatalog.CatalogResult(List.of(
                    new AdaptiveCatalog.Entry(null,block,FIRST_SENTENCE,"事务","ACID","持久性","提交后保存",List.of()))));
            var target=selected.request().knownConcepts().getFirst();
            assertTrue(service.publish(selected,result(selected,List.of(item(target.conceptId(),target.name(),"已提交的事务在重启后应当如何？",FIRST_SENTENCE)))));
            assertEquals(1,count("topic_review_cards"));
        } finally { clearAdaptiveFixture(); }
    }

    @Test
    void sameNamedDifferentAbilitiesAreNotMergedWithoutExplicitIdentity() {
        try {
            service.create(user,new CreateJob(material,List.of(block),3,"MEDIUM","MEDIUM",uuid(),"COVERAGE"));
            var claimed=service.claimNext();
            var selected=service.selectAdaptive(claimed,service.catalogRequest(claimed),new AdaptiveCatalog.CatalogResult(List.of(
                    new AdaptiveCatalog.Entry(null,block,FIRST_SENTENCE,"事务","ACID","事务性质","提交后保存",List.of()),
                    new AdaptiveCatalog.Entry(null,block,SECOND_SENTENCE,"事务","ACID","事务性质","并发不互扰",List.of()))));
            assertEquals(2,count("adaptive_topics"));
            assertEquals(2,selected.request().knownConcepts().stream().map(KnownConcept::conceptId).distinct().count());
            assertEquals(2,selected.request().knownConcepts().stream().map(KnownConcept::name).distinct().count(),
                    "同名异义不能在后续发布时误绑到同一个学习概念");
            assertTrue(service.publish(selected,result(selected,List.of())));
        } finally { clearAdaptiveFixture(); }
    }

    private void clearAdaptiveFixture() {
        for(String table:List.of("topic_review_attempts","topic_review_cards","adaptive_generation_choices","adaptive_topic_links",
                "adaptive_topic_sources","adaptive_indexed_blocks","adaptive_topics")) jdbc.update("DELETE FROM "+table+" WHERE user_id=?",user);
    }

    private CreateJob request(String key) { return new CreateJob(material, List.of(block), 3, "MEDIUM", "MEDIUM", key); }
    private Item item(String concept, String name, String stem) {
        return item(concept, name, stem, SOURCE);
    }
    private Item item(String concept, String name, String stem, String quote) {
        return new Item(concept, block, quote, name, "由原文支持的概念定义。", stem,
                "持久保存", "随机丢弃", "只存在内存中", "无需提交", "A", "原文明确说明提交后应持久保存。");
    }
    private Result result(GenerationService.Claimed claimed, List<Item> items) {
        return new Result("1", claimed.job().id(), claimed.request().attemptToken(), "test-model-boundary-only", items,
                items.size() < claimed.request().count() ? "当前选区有可靠依据的独立考点不足。" : null);
    }
    private long newUser() {
        String name = "llgen_" + uuid().replace("-", "").substring(0, 18);
        jdbc.update("INSERT INTO users(username,password_hash) VALUES(?,'disabled-test-account')", name);
        long id = jdbc.queryForObject("SELECT id FROM users WHERE username=?", Long.class, name);
        users.add(id); return id;
    }
    private int count(String table) { return jdbc.queryForObject("SELECT COUNT(*) FROM " + table + " WHERE user_id=?", Integer.class, user); }
    private static String uuid() { return UUID.randomUUID().toString(); }
    private static LocalDateTime now() { return LocalDateTime.now(ZoneOffset.UTC); }
    private static void assertStatus(int status, Runnable action) { assertEquals(status, assertThrows(GenerationException.class, action::run).status()); }
}
