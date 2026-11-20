package com.learnloop.generation;

import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import java.io.IOException;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.*;
import tools.jackson.databind.json.JsonMapper;
import static org.junit.jupiter.api.Assertions.*;

/**
 * 真实回环 HTTP 验证个人密钥请求头隔离；所有 Key 均为虚构测试字符串。
 * 同一个单例 Client 被不同用户共用时不能把 Key 写进共享 properties、JSON 或错误信息。
 */
class GenerationPersonalKeyClientTest {
    private static final JsonMapper JSON = JsonMapper.builder().build();
    private static final String SERVICE_KEY = "learnloop-personal-key-test-service-secret-32";
    private static final String KEY_A = "sk-fixture-key-000000000000000000000001";
    private static final String KEY_B = "sk-fixture-key-000000000000000000000002";
    private static final String JOB_A = "11111111-1111-4111-8111-111111111111";
    private static final String JOB_B = "22222222-2222-4222-8222-222222222222";
    private static final String TOKEN = "33333333-3333-4333-8333-333333333333";
    private final ConcurrentLinkedQueue<Captured> captured = new ConcurrentLinkedQueue<>();
    private final AtomicInteger requests = new AtomicInteger();
    private HttpServer server;
    private GenerationClient client;
    private volatile int status = 200;
    private volatile String forcedBody;
    private record Captured(String jobId, String modelKey, String serviceKey, String body) { }

    @BeforeEach void startLoopback() throws IOException {
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/internal/generation/questions", this::handle);
        server.start();
        var properties = new GenerationProperties();
        properties.setBaseUrl("http://127.0.0.1:" + server.getAddress().getPort());
        properties.setServiceKey(SERVICE_KEY);
        client = new GenerationClient(properties);
    }
    @AfterEach void stopLoopback() { if (server != null) server.stop(0); }

    @Test void sharedClientKeepsConcurrentUsersHeadersSeparateAndLegacyCallHasNoPersonalHeader() throws Exception {
        try (var pool = Executors.newFixedThreadPool(2)) {
            CountDownLatch start = new CountDownLatch(1);
            var a = pool.submit(() -> { start.await(); return client.generate(request(JOB_A), KEY_A); });
            var b = pool.submit(() -> { start.await(); return client.generate(request(JOB_B), KEY_B); });
            start.countDown();
            assertEquals(JOB_A, a.get(10, TimeUnit.SECONDS).jobId());
            assertEquals(JOB_B, b.get(10, TimeUnit.SECONDS).jobId());
        }
        assertEquals(2, requests.get());
        var a = captured.stream().filter(r -> r.jobId().equals(JOB_A)).findFirst().orElseThrow();
        var b = captured.stream().filter(r -> r.jobId().equals(JOB_B)).findFirst().orElseThrow();
        assertEquals(KEY_A, a.modelKey()); assertEquals(KEY_B, b.modelKey());
        client.generate(request(JOB_A));
        assertEquals(3, requests.get());
        assertEquals(1, captured.stream().filter(r -> r.modelKey() == null).count());
        for (Captured sent : captured) {
            assertEquals(SERVICE_KEY, sent.serviceKey());
            assertFalse(sent.body().contains(KEY_A)); assertFalse(sent.body().contains(KEY_B));
            assertFalse(sent.body().contains(SERVICE_KEY));
            Map<?, ?> body = JSON.readValue(sent.body(), Map.class);
            assertFalse(body.containsKey("apiKey")); assertFalse(body.containsKey("modelKey"));
        }
    }

    @Test void explicitInvalidPersonalKeysFailLocallyWithoutHttpOrLegacyFallback() {
        for (String key : new String[]{null, "", "x".repeat(15), "x".repeat(513), "sk-test contains spaces", "sk-test-control-key\n", "sk-测试-key-xxxxxxxxxxxx"}) {
            GenerationException ex = assertThrows(GenerationException.class, () -> client.generate(request(JOB_A), key));
            assertEquals("MODEL_KEY_REQUIRED", ex.code());
            assertNull(ex.getCause());
            assertFalse(ex.getMessage().contains(SERVICE_KEY));
        }
        assertEquals(0, requests.get(), "提供非法个人 Key 不能转而发送不含个人头的共享配置请求");
        assertTrue(captured.isEmpty());
    }

    @Test void upstreamAndMalformedResponseErrorsNeverEchoEitherUsersOrInternalSecrets() {
        String leaked = KEY_A + " " + KEY_B + " " + SERVICE_KEY;
        status = 503;
        forcedBody = JSON.writeValueAsString(Map.of("errorCode", "MODEL_UPSTREAM_FAILED", "message", leaked));
        GenerationException upstream = assertThrows(GenerationException.class, () -> client.generate(request(JOB_A), KEY_A));
        assertEquals("MODEL_UPSTREAM_FAILED", upstream.code());
        status = 200; forcedBody = "not-json " + leaked;
        GenerationException malformed = assertThrows(GenerationException.class, () -> client.generate(request(JOB_B), KEY_B));
        assertEquals("GENERATION_INVALID_RESULT", malformed.code());
        for (GenerationException ex : List.of(upstream, malformed)) {
            assertNull(ex.getCause());
            for (String secret : List.of(KEY_A, KEY_B, SERVICE_KEY)) {
                assertFalse(ex.getMessage().contains(secret)); assertFalse(ex.toString().contains(secret));
            }
        }
        assertEquals(2, requests.get());
    }

    private GenerationProtocol.Request request(String job) {
        return new GenerationProtocol.Request("1", job, TOKEN, 3, "MEDIUM", "MEDIUM",
                List.of(new GenerationProtocol.Source(11, "事务的原子性要求操作全部成功或全部失败。")), List.of());
    }
    private void handle(HttpExchange exchange) throws IOException {
        try {
            requests.incrementAndGet();
            String body = new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8);
            Map<?, ?> parsed = JSON.readValue(body, Map.class);
            String job = (String) parsed.get("jobId");
            captured.add(new Captured(job, exchange.getRequestHeaders().getFirst("X-LearnLoop-Model-Key"),
                    exchange.getRequestHeaders().getFirst("X-LearnLoop-Service-Key"), body));
            String response = forcedBody;
            if (response == null) response = JSON.writeValueAsString(Map.of("schemaVersion", "1", "jobId", job,
                    "attemptToken", TOKEN, "model", "synthetic-test-model", "items", List.of(), "shortfallReason", "测试范围不足，未强行补题。"));
            byte[] bytes = response.getBytes(StandardCharsets.UTF_8);
            exchange.getResponseHeaders().set("Content-Type", "application/json");
            exchange.sendResponseHeaders(status, bytes.length);
            exchange.getResponseBody().write(bytes);
        } finally { exchange.close(); }
    }
}
