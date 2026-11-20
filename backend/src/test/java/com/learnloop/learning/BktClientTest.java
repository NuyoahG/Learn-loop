package com.learnloop.learning;

import static org.junit.jupiter.api.Assertions.assertAll;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import java.io.IOException;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
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
 * Java/Python 协议边界的测试，不启动 Spring、不连接数据库，也不调用真实模型服务。
 *
 * 使用 JDK 自带的 HTTP 服务而非 mock HttpClient，才能覆盖真实的请求编码、HTTP 状态、
 * 重定向策略和响应大小限制。服务仅绑定 127.0.0.1，并由操作系统分配空闲端口，
 * 避免依赖开发机现有的 Python 服务和固定端口。
 */
class BktClientTest {
    private static final String EVENT_ID = "answer-event-test-001";
    // 纯测试常量：满足内部服务凭据的格式要求，与任何本机/真实账号均无关联。
    private static final String TEST_SERVICE_KEY = "learnloop-test-only-service-key-32-characters";
    private static final String STEP_PATH = "/internal/algorithms/bkt/step";
    private static final double EPSILON = 1e-12;
    private static final JsonMapper JSON = JsonMapper.builder().build();

    private final AtomicReference<CapturedRequest> captured = new AtomicReference<>();
    private final AtomicInteger requestCount = new AtomicInteger();
    private final AtomicInteger redirectTargetCount = new AtomicInteger();
    private HttpServer server;
    private LearningProperties properties;
    private BktClient client;
    private String baseUrl;
    // Handler 在 HTTP 线程执行，volatile 让每次测试设置的响应对该线程可见。
    private volatile int responseStatus;
    private volatile String responseBody;
    private volatile String redirectLocation;

    @BeforeEach
    void startLoopbackServer() throws IOException {
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/", this::handleRequest);
        server.start();
        baseUrl = "http://127.0.0.1:" + server.getAddress().getPort();
        properties = new LearningProperties();
        properties.setBktBaseUrl(baseUrl);
        properties.setServiceKey(TEST_SERVICE_KEY);
        client = new BktClient(properties);
        responseStatus = 200;
        responseBody = correctObservationJson();
    }

    @AfterEach
    void stopLoopbackServer() {
        if (server != null) server.stop(0);
    }

    @Test
    void correctObservationUsesAuthenticatedPostAndReturnsMasteryRatherThanPrediction() {
        BktClient.Result result = client.step(EVENT_ID, .2, true);

        // 这组数来自准备阶段的独立数学样例；直接断言固定结果，避免测试重写生产公式。
        // 预测答对概率 .38、观察后后验 .473684... 与学习后的掌握概率 .526315... 含义不同。
        assertAll(
                () -> assertEquals(.2, result.priorMastery(), EPSILON),
                () -> assertEquals(.38, result.predictedCorrect(), EPSILON),
                () -> assertEquals(.4736842105263158, result.posteriorMastery(), EPSILON),
                () -> assertEquals(.5263157894736842, result.masteryProbability(), EPSILON),
                () -> assertEquals("1.4.3", result.libraryVersion()),
                () -> assertEquals(1, requestCount.get()));

        CapturedRequest request = captured.get();
        assertNotNull(request);
        Map<?, ?> body = JSON.readValue(request.body(), Map.class);
        assertAll(
                () -> assertEquals("POST", request.method()),
                () -> assertEquals(STEP_PATH, request.path()),
                () -> assertEquals("application/json", request.contentType()),
                () -> assertEquals(TEST_SERVICE_KEY, request.serviceKey()),
                () -> assertEquals("1", body.get("schemaVersion")),
                () -> assertEquals(EVENT_ID, body.get("eventId")),
                () -> assertEquals("bkt-fixed-v1", body.get("modelVersion")),
                () -> assertEquals(.2, assertInstanceOf(Number.class, body.get("priorMastery")).doubleValue()),
                () -> assertEquals(Boolean.TRUE, body.get("correct")),
                () -> assertFalse(request.body().contains(TEST_SERVICE_KEY), "密钥只能放在内部请求头中"));
    }

    @Test
    void incorrectObservationProducesTheIndependentWrongAnswerFixture() {
        responseBody = """
                {"schemaVersion":"1","eventId":"answer-event-test-001",
                 "modelVersion":"bkt-fixed-v1","libraryVersion":"1.4.3",
                 "priorMastery":0.2,"predictedCorrect":0.38,
                 "posteriorMastery":0.03225806451612903,
                 "masteryProbability":0.12903225806451613}
                """;

        BktClient.Result result = client.step(EVENT_ID, .2, false);

        assertAll(
                () -> assertEquals(.03225806451612903, result.posteriorMastery(), EPSILON),
                () -> assertEquals(.12903225806451613, result.masteryProbability(), EPSILON),
                () -> assertEquals(Boolean.FALSE, JSON.readValue(captured.get().body(), Map.class).get("correct")));
    }

    @Test
    void rejectsResponseWhoseSchemaEventModelOrLibraryDoesNotMatchTheRequest() {
        // 即使数字完全正确，也不能把另一个事件或另一个模型版本的结果写入当前用户状态。
        Map<String, Object> wrongIdentity = Map.of(
                "schemaVersion", "2", "eventId", "another-answer-event",
                "modelVersion", "untrusted-model-v9", "libraryVersion", "9.9.9");
        for (Map.Entry<String, Object> entry : wrongIdentity.entrySet()) {
            responseBody = responseWith(entry.getKey(), entry.getValue());
            assertFailure("BKT_INVALID_RESULT");
        }
        responseBody = responseWith("schemaVersion", 1);
        assertFailure("BKT_INVALID_RESULT"); // 协议版本也不能把数字 1 自动转换为字符串 "1"。
    }

    @Test
    void requiresEveryProbabilityToBeANumberInsteadOfANumericStringOrNull() {
        for (String field : probabilityFields()) {
            responseBody = responseWith(field, "0.5263157894736842");
            assertFailure("BKT_INVALID_RESULT");
            responseBody = responseWith(field, null);
            assertFailure("BKT_INVALID_RESULT");
            Map<String, Object> missingField = validResponse();
            missingField.remove(field);
            responseBody = JSON.writeValueAsString(missingField);
            assertFailure("BKT_INVALID_RESULT");
        }
    }

    @Test
    void rejectsProbabilitiesOutsideTheUnitIntervalAndNanText() {
        for (String field : probabilityFields()) {
            responseBody = responseWith(field, -.001);
            assertFailure("BKT_INVALID_RESULT");
            responseBody = responseWith(field, 1.001);
            assertFailure("BKT_INVALID_RESULT");
            responseBody = responseWith(field, "NaN");
            assertFailure("BKT_INVALID_RESULT");
        }
    }

    @Test
    void refusesAnOverflowingNumericLiteralEvenIfTheJsonParserAcceptsIt() {
        // 1e400 是合法的 JSON 数字写法，但不能成为有限的 double 概率。
        // 若解析器先拒绝，表现为服务不可用；若解析成功，语义校验也必须拒绝。
        responseBody = correctObservationJson().replace(
                "\"masteryProbability\":0.5263157894736842", "\"masteryProbability\":1e400");
        LearningException failure = assertThrows(LearningException.class, () -> client.step(EVENT_ID, .2, true));
        assertEquals(503, failure.status());
        assertTrue(List.of("BKT_INVALID_RESULT", "BKT_UNAVAILABLE").contains(failure.code()));
    }

    @Test
    void rejectsPlausibleButSemanticallyWrongProbabilities() {
        // 所有替代值均处于 [0,1]，用于证明只做范围校验不足以保障模型状态正确。
        for (String field : probabilityFields()) {
            responseBody = responseWith(field, .5);
            assertFailure("BKT_INVALID_RESULT");
        }
        // 常见集成错误：把预测答对概率或观察后后验误当成学习转移后的掌握概率。
        responseBody = responseWith("masteryProbability", .38);
        assertFailure("BKT_INVALID_RESULT");
        responseBody = responseWith("masteryProbability", .4736842105263158);
        assertFailure("BKT_INVALID_RESULT");
    }

    @Test
    void acceptsAResponseAtEightKibibytesButRejectsAnyLargerBody() {
        String valid = correctObservationJson();
        // JSON 后的空白不改变其语义，因此拒绝的原因必须来自正文大小限制。
        responseBody = valid + " ".repeat(8192 - valid.getBytes(StandardCharsets.UTF_8).length);
        assertEquals(.5263157894736842, client.step(EVENT_ID, .2, true).masteryProbability(), EPSILON);
        responseBody += " ";
        LearningException failure = assertThrows(LearningException.class, () -> client.step(EVENT_ID, .2, true));
        assertEquals(503, failure.status());
    }

    @Test
    void non200ResponsesCannotBeUsedEvenIfTheirBodiesLookValid() {
        for (int status : List.of(201, 400, 401, 429, 500, 503)) {
            responseStatus = status;
            assertFailure("BKT_UNAVAILABLE");
        }
    }

    @Test
    void doesNotFollowRedirectsOrForwardTheInternalCredentialToAnotherEndpoint() {
        responseStatus = 307;
        redirectLocation = baseUrl + "/redirect-target";

        assertFailure("BKT_UNAVAILABLE");

        assertAll(
                () -> assertEquals(1, requestCount.get()),
                () -> assertEquals(0, redirectTargetCount.get(), "不应访问重定向目标"));
    }

    @Test
    void refusesInvalidBaseUrlsBeforeMakingAnyHttpRequest() {
        // 地址只允许在服务端配置为回环 HTTP 根地址；不依赖 DNS，也不能附带路径/凭据。
        for (String invalid : List.of(
                "https://127.0.0.1:8001", "http://localhost:8001", "http://192.0.2.1:8001",
                "http://127.0.0.1", "http://127.0.0.1:0", "http://user@127.0.0.1:8001",
                baseUrl + "/unexpected", baseUrl + "?debug=1", baseUrl + "#fragment")) {
            properties.setBktBaseUrl(invalid);
            assertFailure("BKT_NOT_CONFIGURED");
        }
        assertEquals(0, requestCount.get());
    }

    @Test
    void permitsTheLoopbackRootWithATrailingSlash() {
        properties.setBktBaseUrl(baseUrl + "/");
        assertEquals(.5263157894736842, client.step(EVENT_ID, .2, true).masteryProbability(), EPSILON);
        assertEquals(STEP_PATH, captured.get().path());
    }

    @Test
    void refusesMissingShortOrNonAsciiServiceKeysBeforeConnecting() {
        for (String invalid : List.of("", "too-short", " ".repeat(40), "a".repeat(31) + "\n", "a".repeat(32) + "中")) {
            properties.setServiceKey(invalid);
            assertFailure("BKT_NOT_CONFIGURED");
        }
        properties.setServiceKey(null);
        assertFailure("BKT_NOT_CONFIGURED");
        assertEquals(0, requestCount.get());
    }

    @Test
    void refusesInvalidPriorStateBeforeConnecting() {
        for (double prior : new double[] {-.001, 1.001, Double.NaN, Double.POSITIVE_INFINITY, Double.NEGATIVE_INFINITY}) {
            LearningException failure = assertThrows(LearningException.class, () -> client.step(EVENT_ID, prior, true));
            assertEquals("BKT_INVALID_STATE", failure.code());
            assertEquals(503, failure.status());
        }
        assertEquals(0, requestCount.get());
    }

    /** 捕获请求后按测试指定的场景应答，不在 HTTP 工作线程里做断言，避免断言失败导致客户端挂起。 */
    private void handleRequest(HttpExchange exchange) throws IOException {
        try (exchange) {
            requestCount.incrementAndGet();
            if ("/redirect-target".equals(exchange.getRequestURI().getPath())) redirectTargetCount.incrementAndGet();
            captured.set(new CapturedRequest(
                    exchange.getRequestMethod(), exchange.getRequestURI().getPath(),
                    exchange.getRequestHeaders().getFirst("Content-Type"),
                    exchange.getRequestHeaders().getFirst("X-LearnLoop-Service-Key"),
                    new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8)));
            exchange.getResponseHeaders().set("Content-Type", "application/json");
            if (redirectLocation != null) exchange.getResponseHeaders().set("Location", redirectLocation);
            byte[] bytes = responseBody.getBytes(StandardCharsets.UTF_8);
            exchange.sendResponseHeaders(responseStatus, bytes.length);
            exchange.getResponseBody().write(bytes);
        }
    }

    private void assertFailure(String expectedCode) {
        LearningException failure = assertThrows(LearningException.class, () -> client.step(EVENT_ID, .2, true));
        assertAll(
                () -> assertEquals(503, failure.status()),
                () -> assertEquals(expectedCode, failure.code()),
                () -> assertFalse(failure.getMessage().contains(TEST_SERVICE_KEY)));
    }

    private String responseWith(String field, Object value) {
        Map<String, Object> response = validResponse();
        response.put(field, value);
        return JSON.writeValueAsString(response);
    }

    private Map<String, Object> validResponse() {
        Map<?, ?> source = JSON.readValue(correctObservationJson(), Map.class);
        Map<String, Object> copy = new LinkedHashMap<>();
        source.forEach((key, value) -> copy.put((String) key, value));
        return copy;
    }

    private static List<String> probabilityFields() {
        return List.of("priorMastery", "predictedCorrect", "posteriorMastery", "masteryProbability");
    }

    private static String correctObservationJson() {
        return """
                {"schemaVersion":"1","eventId":"answer-event-test-001",
                 "modelVersion":"bkt-fixed-v1","libraryVersion":"1.4.3",
                 "priorMastery":0.2,"predictedCorrect":0.38,
                 "posteriorMastery":0.4736842105263158,
                 "masteryProbability":0.5263157894736842}
                """;
    }

    private record CapturedRequest(String method, String path, String contentType, String serviceKey, String body) { }
}
