package com.learnloop.generation;

import static org.junit.jupiter.api.Assertions.assertAll;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import java.io.IOException;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.json.JsonMapper;

/**
 * 使用真实回环 HTTP 验证 Java/Python 出题协议，不依赖真实模型、生产数据库或本机服务。
 *
 * <p>这些测试检查的是“候选题能否安全进入发布步骤”的边界；原文引用存在并不能证明
 * 模型给出的答案事实上正确。因此不把协议通过误写成模型质量评测通过。</p>
 */
class GenerationClientTest {
    private static final JsonMapper JSON = JsonMapper.builder().build();
    private static final String JOB_ID = "11111111-1111-4111-8111-111111111111";
    private static final String ATTEMPT_TOKEN = "22222222-2222-4222-8222-222222222222";
    private static final String CONCEPT_ID = "33333333-3333-4333-8333-333333333333";
    // 仅供测试的内部服务凭据，与任何实际模型 API Key 或本机配置无关。
    private static final String SERVICE_KEY = "learnloop-generation-test-only-service-key-32";
    private static final String UPSTREAM_SECRET = "synthetic-provider-secret-must-never-leak";
    private static final String PATH = "/internal/generation/questions";
    private static final String QUOTE = "事务是访问并可能更新数据库中各种数据项的一个程序执行单元";
    private static final String SOURCE = QUOTE + "。原子性要求事务中的操作要么全部完成，要么全部不完成。";
    private static final int BODY_LIMIT = 128 * 1024;

    private final AtomicReference<CapturedRequest> captured = new AtomicReference<>();
    private final AtomicInteger requestCount = new AtomicInteger();
    private final AtomicInteger redirectTargetCount = new AtomicInteger();
    private HttpServer server;
    private GenerationProperties properties;
    private GenerationClient client;
    private String baseUrl;
    // HTTP 服务线程读取测试指定的应答，volatile 保证每轮修改立即可见。
    private volatile int responseStatus;
    private volatile String responseBody;
    private volatile String redirectLocation;
    private volatile boolean chunked;

    @BeforeEach
    void startLoopbackServer() throws IOException {
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/", this::handleRequest);
        server.start();
        baseUrl = "http://127.0.0.1:" + server.getAddress().getPort();
        properties = new GenerationProperties();
        properties.setBaseUrl(baseUrl);
        properties.setServiceKey(SERVICE_KEY);
        client = new GenerationClient(properties);
        responseStatus = 200;
        responseBody = JSON.writeValueAsString(validResult());
    }

    @AfterEach
    void stopLoopbackServer() {
        if (server != null) server.stop(0);
    }

    @Test
    void tutorCoachDecodesVerifiedProtocolAndRejectsUnknownTeachingActions() {
        responseBody=JSON.writeValueAsString(Map.of("answer","对比两个事务的可见范围。","teaching","COMPARE",
            "citations",List.of("SOURCE:8"),"trace",List.of(Map.of("tool","search_material","count",1))));
        var answer=client.tutorCoach("隔离是什么","{}","a".repeat(64),UPSTREAM_SECRET);
        assertEquals("COMPARE",answer.teaching());
        assertEquals(1,answer.trace().getFirst().count());
        assertEquals(List.of("SOURCE:8"),answer.citations());
        responseBody=responseBody.replace("COMPARE","WRITE_MASTERY");
        assertThrows(GenerationException.class,() -> client.tutorCoach("隔离是什么","{}","a".repeat(64),UPSTREAM_SECRET));
    }

    @Test
    void tutorPlannerAcceptsSingleSummaryDecisionTrace() {
        responseBody=JSON.writeValueAsString(Map.of("ids",List.of(CONCEPT_ID),"reason","先复习到期内容。","trace",List.of("SUMMARY")));
        assertEquals(List.of("SUMMARY"),client.tutorPlan(Map.of("minutes",4),UPSTREAM_SECRET).trace());
    }

    @Test
    void sendsFrozenSourcesAndKnownConceptsUsingAuthenticatedPost() {
        GenerationProtocol.Result result = client.generate(validRequest());

        assertAll(
                () -> assertEquals(JOB_ID, result.jobId()),
                () -> assertEquals(ATTEMPT_TOKEN, result.attemptToken()),
                () -> assertEquals("test-model-v1", result.model()),
                () -> assertEquals(1, result.items().size()),
                () -> assertEquals(CONCEPT_ID, result.items().getFirst().conceptId()),
                () -> assertEquals("B", result.items().getFirst().correctAnswer()),
                () -> assertEquals(1, requestCount.get()));
        CapturedRequest request = captured.get();
        assertNotNull(request);
        Map<?, ?> body = JSON.readValue(request.body(), Map.class);
        Map<?, ?> source = (Map<?, ?>) ((List<?>) body.get("sources")).getFirst();
        assertAll(
                () -> assertEquals("POST", request.method()),
                () -> assertEquals(PATH, request.path()),
                () -> assertEquals("application/json", request.contentType()),
                () -> assertEquals(SERVICE_KEY, request.serviceKey()),
                () -> assertEquals("1", body.get("schemaVersion")),
                () -> assertEquals(JOB_ID, body.get("jobId")),
                () -> assertEquals(3, body.get("count")),
                () -> assertEquals("MEDIUM", body.get("difficulty")),
                () -> assertEquals("FINE", body.get("granularity")),
                () -> assertEquals(SOURCE, source.get("text")),
                () -> assertFalse(request.body().contains(SERVICE_KEY), "内部凭据只应进入请求头"),
                () -> assertFalse(body.containsKey("userId"), "Python 不负责声明业务数据所有者"));
    }

    @Test
    void readsLegacySnapshotWithoutModeAsNew() {
        String legacy = """
                {"materialSha256":"%s","parseAttempt":1,
                 "sources":[{"blockId":11,"text":"旧任务冻结的正文。","locator":"第1行"}]}
                """.formatted("a".repeat(64));
        GenerationRows.Snapshot snapshot = JSON.readValue(legacy, GenerationRows.Snapshot.class);
        assertEquals(1, snapshot.sources().size());
        assertFalse("VARIANT".equals(snapshot.mode()), "旧快照缺省必须按 NEW 处理，不能误判为变式");
        assertEquals(null, snapshot.targetConceptId());
    }

    @Test
    void sendsVariantTargetAndKnownQuestionsToPython() {
        GenerationProtocol.Request variant = new GenerationProtocol.Request("1", JOB_ID, ATTEMPT_TOKEN, 1,
                "MEDIUM", "FINE", validRequest().sources(), validRequest().knownConcepts(), "VARIANT", CONCEPT_ID,
                List.of(new GenerationProtocol.KnownQuestion(11, QUOTE, "旧题：数据库事务描述的执行单位是什么？")));
        GenerationProtocol.Result result = client.generate(variant);
        assertEquals(CONCEPT_ID, result.items().getFirst().conceptId());
        Map<?, ?> body = JSON.readValue(captured.get().body(), Map.class);
        Map<?, ?> known = (Map<?, ?>) ((List<?>) body.get("knownQuestions")).getFirst();
        assertAll(
                () -> assertEquals("VARIANT", body.get("mode")),
                () -> assertEquals(1, body.get("count")),
                () -> assertEquals(CONCEPT_ID, body.get("targetConceptId")),
                () -> assertEquals("旧题：数据库事务描述的执行单位是什么？", known.get("content")),
                () -> assertFalse(body.containsKey("apiKey"), "浏览器与 Python 都不能指定密钥"));
    }

    @Test
    void rejectsVariantRequestsWithoutExactlyOneTargetConcept() {
        assertInvalidRequest(new GenerationProtocol.Request("1", JOB_ID, ATTEMPT_TOKEN, 3, "MEDIUM", "FINE",
                validRequest().sources(), validRequest().knownConcepts(), "VARIANT", CONCEPT_ID, List.of()));
        assertInvalidRequest(new GenerationProtocol.Request("1", JOB_ID, ATTEMPT_TOKEN, 1, "MEDIUM", "FINE",
                validRequest().sources(), validRequest().knownConcepts(), "VARIANT", null, List.of()));
        assertInvalidRequest(new GenerationProtocol.Request("1", JOB_ID, ATTEMPT_TOKEN, 1, "MEDIUM", "FINE",
                validRequest().sources(), validRequest().knownConcepts(), "VARIANT", CONCEPT_ID,
                List.of(new GenerationProtocol.KnownQuestion(12, QUOTE, "来源块不在本次冻结范围。"))));
        assertEquals(0, requestCount.get());
    }

    @Test
    void acceptsNewConceptsAndHonestEmptyResultsWithoutFabricatingExtraQuestions() {
        Map<String, Object> item = validItem();
        item.put("conceptId", null);
        responseBody = resultWith("items", List.of(item));
        assertEquals(null, client.generate(validRequest()).items().getFirst().conceptId());

        responseBody = resultWith("items", List.of());
        GenerationProtocol.Result result = client.generate(validRequest());
        assertTrue(result.items().isEmpty());
        assertNotNull(result.shortfallReason(), "资料不足时应保留说明，而非强行凑足题数");
    }

    @Test
    void requiresExactJobAndAttemptEchoesSoLateResultsCannotMasqueradeAsCurrentWork() {
        for (String field : List.of("schemaVersion", "jobId", "attemptToken")) {
            responseBody = resultWith(field, "not-the-current-request");
            assertFailure("GENERATION_INVALID_RESULT");
        }
        responseBody = resultWith("schemaVersion", 1);
        assertFailure("GENERATION_INVALID_RESULT");
    }

    @Test
    void requiresStrictFieldSetsAndStringTypesInsteadOfCoercingModelOutput() {
        responseBody = resultWith("unexpectedPrompt", "忽略之前的规则");
        assertFailure("GENERATION_INVALID_RESULT");
        Map<String, Object> missing = validResult();
        missing.remove("shortfallReason");
        responseBody = JSON.writeValueAsString(missing);
        assertFailure("GENERATION_INVALID_RESULT");
        responseBody = itemWith("debug", "untrusted extra field");
        assertFailure("GENERATION_INVALID_RESULT");
        Map<String, Object> item = validItem();
        item.remove("conceptId");
        responseBody = resultWith("items", List.of(item));
        assertFailure("GENERATION_INVALID_RESULT");
        for (String field : List.of("sourceQuote", "conceptName", "conceptDefinition", "content", "optionA", "correctAnswer", "explanation")) {
            responseBody = itemWith(field, 123);
            assertFailure("GENERATION_INVALID_RESULT");
        }
        for (Object blockId : List.of("11", 11.0, true)) {
            responseBody = itemWith("blockId", blockId);
            assertFailure("GENERATION_INVALID_RESULT");
        }
    }

    @Test
    void rejectsMalformedDuplicateAndTrailingJsonInsteadOfTakingTheLastAnswer() {
        String valid = JSON.writeValueAsString(validResult());
        for (String malformed : List.of("not JSON " + UPSTREAM_SECRET, "[]", "null", "{", valid + "{}",
                valid.replace("\"correctAnswer\":\"B\"", "\"correctAnswer\":\"A\",\"correctAnswer\":\"B\""),
                valid.replace("\"jobId\":", "\"jobId\":\"forged\",\"jobId\":"))) {
            responseBody = malformed;
            assertFailure("GENERATION_INVALID_RESULT");
        }
    }

    @Test
    void rejectsUnknownBlocksAndQuotesThatDoNotExistInTheFrozenSource() {
        responseBody = itemWith("blockId", 999);
        assertFailure("GENERATION_INVALID_RESULT");
        responseBody = itemWith("sourceQuote", "这是一段模型凭空生成且不存在于原文中的解释");
        assertFailure("GENERATION_INVALID_RESULT");
        responseBody = itemWith("sourceQuote", "事务");
        assertFailure("GENERATION_INVALID_RESULT");
        responseBody = itemWith("sourceQuote", " ".repeat(8));
        assertFailure("GENERATION_INVALID_RESULT");
    }

    @Test
    void cannotInventStableConceptIdsOrMoveAKnownConceptToAnotherBlock() {
        responseBody = itemWith("conceptId", "44444444-4444-4444-8444-444444444444");
        assertFailure("GENERATION_INVALID_RESULT");
        responseBody = itemWith("conceptName", "偷偷改名后的考点");
        assertFailure("GENERATION_INVALID_RESULT");

        // 即使第二个正文块本身有效，也不能借用第一个块上已有考点的稳定 ID。
        GenerationProtocol.Request twoBlocks = new GenerationProtocol.Request("1", JOB_ID, ATTEMPT_TOKEN, 3,
                "MEDIUM", "FINE", List.of(new GenerationProtocol.Source(11, SOURCE), new GenerationProtocol.Source(12, SOURCE)),
                validRequest().knownConcepts());
        responseBody = itemWith("blockId", 12);
        assertFailure(twoBlocks, "GENERATION_INVALID_RESULT");
    }

    @Test
    void enforcesOneQuestionPerConceptAndRejectsDuplicateQuestionStems() {
        Map<String, Object> duplicate = validItem();
        responseBody = resultWith("items", List.of(validItem(), duplicate));
        assertFailure("GENERATION_INVALID_RESULT");

        // 去掉第二项的 ID 不能绕过“同一个正文块、同一个考点只出一道”的约束。
        duplicate.put("conceptId", null);
        duplicate.put("conceptName", " 事务 ");
        duplicate.put("content", "事务在数据库中扮演何种角色？");
        responseBody = resultWith("items", List.of(validItem(), duplicate));
        assertFailure("GENERATION_INVALID_RESULT");

        duplicate.put("conceptName", "原子性");
        duplicate.put("content", validItem().get("content"));
        responseBody = resultWith("items", List.of(validItem(), duplicate));
        assertFailure("GENERATION_INVALID_RESULT");
    }

    @Test
    void requiresFourDistinctNonemptyOptionsAndASingleUppercaseAnswerLetter() {
        for (Object answer : new Object[] {"", "a", "AB", "E", null}) {
            responseBody = itemWith("correctAnswer", answer);
            assertFailure("GENERATION_INVALID_RESULT");
        }
        responseBody = itemWith("optionD", " ");
        assertFailure("GENERATION_INVALID_RESULT");
        responseBody = itemWith("optionB", validItem().get("optionA"));
        assertFailure("GENERATION_INVALID_RESULT");
        responseBody = itemWith("optionB", "  " + validItem().get("optionA") + "  ");
        assertFailure("GENERATION_INVALID_RESULT");
    }

    @Test
    void rejectsMissingOrUnboundedShortfallExplanationsAndTooManyItems() {
        for (Object reason : new Object[] {null, "", " ", "原".repeat(301)}) {
            responseBody = resultWith("shortfallReason", reason);
            assertFailure("GENERATION_INVALID_RESULT");
        }
        responseBody = resultWith("items", List.of(validItem(), validItem(), validItem(), validItem()));
        assertFailure("GENERATION_INVALID_RESULT");
    }

    @Test
    void enforcesTextLimitsInUnicodeCodePointsRatherThanUtf16CodeUnits() {
        Map<String, Integer> limits = Map.of("conceptName", 120, "conceptDefinition", 1000,
                "content", 2000, "optionA", 1000, "explanation", 1000);
        for (Map.Entry<String, Integer> entry : limits.entrySet()) {
            // conceptName 改成新名字时不携带旧 ID，否则会先触发稳定考点名称校验。
            Map<String, Object> item = validItem();
            item.put("conceptId", null);
            item.put(entry.getKey(), "🧠".repeat(entry.getValue()));
            responseBody = resultWith("items", List.of(item));
            assertEquals(1, client.generate(validRequest()).items().size());
            item.put(entry.getKey(), "🧠".repeat(entry.getValue() + 1));
            responseBody = resultWith("items", List.of(item));
            assertFailure("GENERATION_INVALID_RESULT");
        }
        responseBody = resultWith("model", "模".repeat(129));
        assertFailure("GENERATION_INVALID_RESULT");
    }

    @Test
    void rejectsNullRequiredFieldsAndWrongRootContainerTypes() {
        for (String field : List.of("model", "items")) {
            responseBody = resultWith(field, null);
            assertFailure("GENERATION_INVALID_RESULT");
        }
        responseBody = resultWith("items", Map.of("item", validItem()));
        assertFailure("GENERATION_INVALID_RESULT");
        responseBody = resultWith("items", java.util.Arrays.asList((Object) null));
        assertFailure("GENERATION_INVALID_RESULT");
        responseBody = itemWith("conceptDefinition", null);
        assertFailure("GENERATION_INVALID_RESULT");
    }

    @Test
    void acceptsExactly128KibibytesAndRejectsLargerChunkedResponses() {
        String valid = JSON.writeValueAsString(validResult());
        responseBody = valid + " ".repeat(BODY_LIMIT - valid.getBytes(StandardCharsets.UTF_8).length);
        assertEquals(1, client.generate(validRequest()).items().size());
        // 分块传输没有可信的 Content-Length；必须按网络实际累计字节执行相同上限。
        chunked = true;
        responseBody += " ";
        GenerationException failure = assertThrows(GenerationException.class, () -> client.generate(validRequest()));
        assertEquals(503, failure.status());
        assertFalse(failure.getMessage().contains(SERVICE_KEY));
    }

    @Test
    void hidesUpstreamFailureBodiesAndNeverTreatsOtherHttpStatusesAsSuccess() {
        responseBody = "Authorization: Bearer " + UPSTREAM_SECRET + " " + SERVICE_KEY;
        for (int status : List.of(201, 400, 401, 429, 500, 503)) {
            responseStatus = status;
            assertFailure("GENERATION_UNAVAILABLE");
        }
    }

    @Test
    void neverFollowsRedirectsOrLeaksTheInternalCredentialToAnotherEndpoint() {
        responseStatus = 307;
        redirectLocation = baseUrl + "/redirect-target";
        assertFailure("GENERATION_UNAVAILABLE");
        assertAll(() -> assertEquals(1, requestCount.get()), () -> assertEquals(0, redirectTargetCount.get()));
    }

    @Test
    void refusesNonLoopbackTargetsEmbeddedPathsAndInvalidServiceKeysBeforeConnecting() {
        for (String invalid : List.of("https://127.0.0.1:8001", "http://localhost:8001", "http://192.0.2.1:8001",
                "http://127.0.0.1", "http://127.0.0.1:0", "http://127.0.0.1:65536", "http://user@127.0.0.1:8001",
                baseUrl + "/unexpected", baseUrl + "?debug=1", baseUrl + "#fragment")) {
            properties.setBaseUrl(invalid);
            assertFailure("GENERATION_NOT_CONFIGURED");
        }
        properties.setBaseUrl(baseUrl);
        for (String invalid : List.of("", "short", " ".repeat(40), "a".repeat(32) + "\n", "a".repeat(32) + "中")) {
            properties.setServiceKey(invalid);
            assertFailure("GENERATION_NOT_CONFIGURED");
        }
        properties.setServiceKey(null);
        assertFailure("GENERATION_NOT_CONFIGURED");
        assertEquals(0, requestCount.get());
    }

    @Test
    void permitsAConfiguredLoopbackRootWithATrailingSlash() {
        properties.setBaseUrl(baseUrl + "/");
        assertEquals(1, client.generate(validRequest()).items().size());
        assertEquals(PATH, captured.get().path());
    }

    @Test
    void rejectsInvalidCountsEnumsAndNonCanonicalRequestIdsBeforeConnecting() {
        for (int count : List.of(0, 1, 4, 11)) {
            assertInvalidRequest(new GenerationProtocol.Request("1", JOB_ID, ATTEMPT_TOKEN, count, "MEDIUM", "FINE",
                    validRequest().sources(), validRequest().knownConcepts()));
        }
        for (String id : List.of("not-a-uuid", "1-1-1-1-1")) {
            assertInvalidRequest(new GenerationProtocol.Request("1", id, ATTEMPT_TOKEN, 3, "MEDIUM", "FINE",
                    validRequest().sources(), validRequest().knownConcepts()));
        }
        assertInvalidRequest(new GenerationProtocol.Request("2", JOB_ID, ATTEMPT_TOKEN, 3, "MEDIUM", "FINE",
                validRequest().sources(), validRequest().knownConcepts()));
        assertInvalidRequest(new GenerationProtocol.Request("1", JOB_ID, ATTEMPT_TOKEN, 3, "easy", "FINE",
                validRequest().sources(), validRequest().knownConcepts()));
        assertInvalidRequest(new GenerationProtocol.Request("1", JOB_ID, ATTEMPT_TOKEN, 3, "MEDIUM", "detail",
                validRequest().sources(), validRequest().knownConcepts()));
        assertEquals(0, requestCount.get());
    }

    @Test
    void boundsSourceCountTotalCharactersAndKnownConceptIdentityBeforeConnecting() {
        assertInvalidRequest(requestWithSources(List.of()));
        assertInvalidRequest(requestWithSources(List.of(new GenerationProtocol.Source(11, " "))));
        assertInvalidRequest(requestWithSources(List.of(new GenerationProtocol.Source(0, SOURCE))));
        assertInvalidRequest(requestWithSources(List.of(new GenerationProtocol.Source(11, SOURCE), new GenerationProtocol.Source(11, SOURCE))));
        assertInvalidRequest(requestWithSources(List.of(new GenerationProtocol.Source(11, "原".repeat(6001)),
                new GenerationProtocol.Source(12, "原".repeat(6000)))));
        List<GenerationProtocol.Source> tooMany = new ArrayList<>();
        for (int i = 1; i <= 11; i++) tooMany.add(new GenerationProtocol.Source(i, SOURCE));
        assertInvalidRequest(requestWithSources(tooMany));
        GenerationProtocol.KnownConcept foreignBlock = new GenerationProtocol.KnownConcept(CONCEPT_ID, 999, "事务");
        assertInvalidRequest(requestWithKnown(List.of(foreignBlock)));
        assertInvalidRequest(requestWithKnown(List.of(validRequest().knownConcepts().getFirst(), validRequest().knownConcepts().getFirst())));
        assertInvalidRequest(requestWithKnown(List.of(new GenerationProtocol.KnownConcept("not-a-uuid", 11, "事务"))));
        assertEquals(0, requestCount.get());
    }

    @Test
    void acceptsAllThreeSupportedCountsAndExactlyTwelveThousandSourceCodePoints() {
        for (int count : List.of(3, 5, 10)) {
            GenerationProtocol.Request request = new GenerationProtocol.Request("1", JOB_ID, ATTEMPT_TOKEN, count,
                    "MEDIUM", "FINE", validRequest().sources(), validRequest().knownConcepts());
            assertEquals(1, client.generate(request).items().size());
        }
        String fullSource = SOURCE + "🧠".repeat(12000 - SOURCE.codePointCount(0, SOURCE.length()));
        GenerationProtocol.Request boundary = requestWithSources(List.of(new GenerationProtocol.Source(11, fullSource)));
        assertEquals(1, client.generate(boundary).items().size());
        assertInvalidRequest(requestWithSources(List.of(new GenerationProtocol.Source(11, fullSource + "🧠"))));
    }

    @Test
    void boundsSourceQuotesAndAcceptsAtMostTwoHundredKnownConcepts() {
        String quote = "据".repeat(1000);
        GenerationProtocol.Request longQuoteSource = requestWithSources(List.of(new GenerationProtocol.Source(11, quote + "据")));
        responseBody = itemWith("sourceQuote", quote);
        assertEquals(1, client.generate(longQuoteSource).items().size());
        responseBody = itemWith("sourceQuote", quote + "据");
        assertFailure(longQuoteSource, "GENERATION_INVALID_RESULT");

        // 200 个既有考点是协议允许的上限；增加第 201 个应在连接 Python 前拒绝。
        responseBody = JSON.writeValueAsString(validResult());
        List<GenerationProtocol.KnownConcept> known = new ArrayList<>(validRequest().knownConcepts());
        for (int i = 1; i < 200; i++) {
            known.add(new GenerationProtocol.KnownConcept(
                    "55555555-5555-4555-8555-" + String.format(java.util.Locale.ROOT, "%012d", i), 11, "考点" + i));
        }
        assertEquals(1, client.generate(requestWithKnown(known)).items().size());
        int sent = requestCount.get();
        known.add(new GenerationProtocol.KnownConcept("66666666-6666-4666-8666-666666666666", 11, "第201个考点"));
        assertInvalidRequest(requestWithKnown(known));
        assertEquals(sent, requestCount.get());
    }

    @Test
    void extensionLabelRequiresHardDifficultyAtJavaBoundary() {
        responseBody=itemWith("content","【扩展题】给定额外前提后如何判断事务？");
        assertFailure("GENERATION_INVALID_RESULT");
        var hard=new GenerationProtocol.Request("1",JOB_ID,ATTEMPT_TOKEN,3,"HARD","FINE",validRequest().sources(),validRequest().knownConcepts());
        assertEquals(1,client.generate(hard).items().size());
    }

    /** 仅按测试配置返回正文；避免在 HTTP 回调线程断言，防止断言异常令调用方一直等待。 */
    private void handleRequest(HttpExchange exchange) throws IOException {
        try (exchange) {
            requestCount.incrementAndGet();
            if ("/redirect-target".equals(exchange.getRequestURI().getPath())) redirectTargetCount.incrementAndGet();
            captured.set(new CapturedRequest(exchange.getRequestMethod(), exchange.getRequestURI().getPath(),
                    exchange.getRequestHeaders().getFirst("Content-Type"),
                    exchange.getRequestHeaders().getFirst("X-LearnLoop-Service-Key"),
                    new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8)));
            exchange.getResponseHeaders().set("Content-Type", "application/json");
            if (redirectLocation != null) exchange.getResponseHeaders().set("Location", redirectLocation);
            byte[] body = responseBody.getBytes(StandardCharsets.UTF_8);
            exchange.sendResponseHeaders(responseStatus, chunked ? 0 : body.length);
            try { exchange.getResponseBody().write(body); }
            catch (IOException ignored) { /* 越界后客户端主动取消连接，是该场景的预期行为。 */ }
        }
    }

    private void assertFailure(String code) { assertFailure(validRequest(), code); }

    private void assertFailure(GenerationProtocol.Request request, String code) {
        GenerationException failure = assertThrows(GenerationException.class, () -> client.generate(request));
        assertAll(() -> assertEquals(503, failure.status()), () -> assertEquals(code, failure.code()),
                () -> assertFalse(failure.getMessage().contains(SERVICE_KEY)),
                () -> assertFalse(failure.getMessage().contains(UPSTREAM_SECRET)),
                () -> assertEquals(null, failure.getCause(), "错误链不应附带第三方原始正文"));
    }

    private void assertInvalidRequest(GenerationProtocol.Request request) {
        assertFailure(request, "GENERATION_INVALID_REQUEST");
    }

    private GenerationProtocol.Request validRequest() {
        return new GenerationProtocol.Request("1", JOB_ID, ATTEMPT_TOKEN, 3, "MEDIUM", "FINE",
                List.of(new GenerationProtocol.Source(11, SOURCE)),
                List.of(new GenerationProtocol.KnownConcept(CONCEPT_ID, 11, "事务")));
    }

    private GenerationProtocol.Request requestWithSources(List<GenerationProtocol.Source> sources) {
        return new GenerationProtocol.Request("1", JOB_ID, ATTEMPT_TOKEN, 3, "MEDIUM", "FINE", sources, validRequest().knownConcepts());
    }

    private GenerationProtocol.Request requestWithKnown(List<GenerationProtocol.KnownConcept> known) {
        return new GenerationProtocol.Request("1", JOB_ID, ATTEMPT_TOKEN, 3, "MEDIUM", "FINE", validRequest().sources(), known);
    }

    private String resultWith(String field, Object value) {
        Map<String, Object> result = validResult(); result.put(field, value); return JSON.writeValueAsString(result);
    }

    private String itemWith(String field, Object value) {
        Map<String, Object> item = validItem(); item.put(field, value); return resultWith("items", List.of(item));
    }

    private Map<String, Object> validResult() {
        Map<String, Object> value = new LinkedHashMap<>();
        value.put("schemaVersion", "1"); value.put("jobId", JOB_ID); value.put("attemptToken", ATTEMPT_TOKEN);
        value.put("model", "test-model-v1"); value.put("items", List.of(validItem()));
        value.put("shortfallReason", "这段资料只足以支撑一个独立考点，未强行凑题。");
        return value;
    }

    private Map<String, Object> validItem() {
        Map<String, Object> item = new LinkedHashMap<>();
        item.put("conceptId", CONCEPT_ID); item.put("blockId", 11); item.put("sourceQuote", QUOTE);
        item.put("conceptName", "事务"); item.put("conceptDefinition", "数据库中作为一个整体执行的一组操作。");
        item.put("content", "数据库事务描述的执行单位是什么？");
        item.put("optionA", "只有读取行为的单个语句"); item.put("optionB", "访问并可能更新数据的程序执行单元");
        item.put("optionC", "数据库文件的物理大小"); item.put("optionD", "数据库用户的登录次数");
        item.put("correctAnswer", "B"); item.put("explanation", "原文将事务定义为访问并可能更新数据的程序执行单元。");
        return item;
    }

    private record CapturedRequest(String method, String path, String contentType, String serviceKey, String body) { }
}
