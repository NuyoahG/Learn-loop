package com.learnloop.learning;

import java.io.ByteArrayOutputStream;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.Flow;
import java.util.concurrent.TimeUnit;
import org.springframework.stereotype.Component;
import tools.jackson.databind.json.JsonMapper;

/**
 * Java 到 Python 的无状态 BKT 计算客户端。
 *
 * 数据库事务外调用：网络故障不能使已经保存的答案丢失。
 * 用户身份、答题资格和事件顺序由 Java 确定；Python 只计算一个有效观测。
 * 响应限长、禁止重定向、连接和整次读取均限时，避免后台 worker 无限等待。
 */
@Component("learningBktClient")
public class BktClient {
    public static final String MODEL_VERSION = "bkt-fixed-v1";
    private static final int MAX_RESPONSE = 8192;
    private final LearningProperties properties;
    private final JsonMapper json = JsonMapper.builder().build();
    private final HttpClient http = HttpClient.newBuilder()
            .connectTimeout(Duration.ofSeconds(2))
            .version(HttpClient.Version.HTTP_1_1)
            .followRedirects(HttpClient.Redirect.NEVER).build();

    /** 字段分别对应观察前预测、观察后后验、学习转移后的掌握估计。 */
    public record Result(double priorMastery, double predictedCorrect,
                         double posteriorMastery, double masteryProbability,
                         String libraryVersion) { }

    public BktClient(LearningProperties properties) { this.properties = properties; }

    public Result step(String eventId, double prior, boolean correct) {
        try {
            URI base = URI.create(properties.getBktBaseUrl());
            String key = properties.getServiceKey();
            // 第一版只允许回环 HTTP；浏览器和资料内容不能指定此地址。
            if (!"http".equals(base.getScheme()) || !"127.0.0.1".equals(base.getHost())
                    || base.getPort() < 1 || base.getUserInfo() != null || base.getQuery() != null
                    || base.getFragment() != null || !(base.getPath().isEmpty() || "/".equals(base.getPath()))
                    || key == null || key.length() < 32 || !key.chars().allMatch(c -> c >= 33 && c <= 126)) {
                throw unavailable("BKT_NOT_CONFIGURED");
            }
            if (!Double.isFinite(prior) || prior < 0 || prior > 1) throw unavailable("BKT_INVALID_STATE");
            byte[] body = json.writeValueAsBytes(Map.of("schemaVersion", "1", "eventId", eventId,
                    "modelVersion", MODEL_VERSION, "priorMastery", prior, "correct", correct));
            HttpRequest request = HttpRequest.newBuilder(base.resolve("/internal/algorithms/bkt/step"))
                    .timeout(Duration.ofSeconds(6)).header("Content-Type", "application/json")
                    .header("X-LearnLoop-Service-Key", key)
                    .POST(HttpRequest.BodyPublishers.ofByteArray(body)).build();
            var future = http.sendAsync(request, ignored -> new LimitedBody());
            HttpResponse<byte[]> response;
            try { response = future.get(7, TimeUnit.SECONDS); }
            catch (Exception ex) { future.cancel(true); throw ex; }
            if (response.statusCode() != 200) throw unavailable("BKT_UNAVAILABLE");
            Map<?, ?> value = json.readValue(response.body(), Map.class);
            if (!"1".equals(value.get("schemaVersion")) || !eventId.equals(value.get("eventId"))
                    || !MODEL_VERSION.equals(value.get("modelVersion"))
                    || !"1.4.3".equals(value.get("libraryVersion"))) throw unavailable("BKT_INVALID_RESULT");
            Result result = new Result(number(value, "priorMastery"), number(value, "predictedCorrect"),
                    number(value, "posteriorMastery"), number(value, "masteryProbability"), "1.4.3");
            // 除结构检查外，用固定版本公式复核语义，防止对方返回“下一题预测”冒充掌握度。
            double prediction = prior * .9 + (1 - prior) * .25;
            double posterior = correct ? prior * .9 / prediction : prior * .1 / (1 - prediction);
            double expected = posterior + (1 - posterior) * .1;
            if (!close(result.priorMastery(), prior) || !close(result.predictedCorrect(), prediction)
                    || !close(result.posteriorMastery(), posterior) || !close(result.masteryProbability(), expected)) {
                throw unavailable("BKT_INVALID_RESULT");
            }
            return result;
        } catch (LearningException ex) { throw ex; }
        catch (InterruptedException ex) {
            Thread.currentThread().interrupt();
            throw unavailable("BKT_INTERRUPTED");
        } catch (Exception ex) { throw unavailable("BKT_UNAVAILABLE"); }
    }

    private static double number(Map<?, ?> object, String field) {
        Object raw = object.get(field);
        if (!(raw instanceof Number number)) throw unavailable("BKT_INVALID_RESULT");
        double value = number.doubleValue();
        if (!Double.isFinite(value) || value < 0 || value > 1) throw unavailable("BKT_INVALID_RESULT");
        return value;
    }

    private static boolean close(double a, double b) { return Math.abs(a - b) <= 1e-9; }
    private static LearningException unavailable(String code) {
        return new LearningException(503, code, "掌握度计算暂未完成，答案已保存，可稍后重试。");
    }

    /** 收到响应时逐块计数，超过 8 KiB 立即取消，不等待读完任意大的正文。 */
    private static final class LimitedBody implements HttpResponse.BodySubscriber<byte[]> {
        private final CompletableFuture<byte[]> result = new CompletableFuture<>();
        private final ByteArrayOutputStream buffer = new ByteArrayOutputStream();
        private Flow.Subscription subscription;
        public CompletionStage<byte[]> getBody() { return result; }
        public void onSubscribe(Flow.Subscription value) { subscription = value; value.request(1); }
        public void onNext(List<ByteBuffer> chunks) {
            for (ByteBuffer chunk : chunks) {
                if (chunk.remaining() > MAX_RESPONSE - buffer.size()) {
                    subscription.cancel(); result.completeExceptionally(unavailable("BKT_INVALID_RESULT")); return;
                }
                byte[] bytes = new byte[chunk.remaining()]; chunk.get(bytes); buffer.writeBytes(bytes);
            }
            subscription.request(1);
        }
        public void onError(Throwable ex) { result.completeExceptionally(ex); }
        public void onComplete() { result.complete(buffer.toByteArray()); }
    }
}
