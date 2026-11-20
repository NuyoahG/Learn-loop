package com.learnloop.generation;

import java.io.ByteArrayOutputStream;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.ByteBuffer;
import java.time.Duration;
import java.time.Instant;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.Flow;
import org.springframework.stereotype.Component;
import tools.jackson.core.StreamReadFeature;
import tools.jackson.databind.DeserializationFeature;
import tools.jackson.databind.json.JsonMapper;
import static com.learnloop.generation.GenerationProtocol.*;

/**
 * Java 到 Python 的出题边界；模型平台密钥始终由 Python 持有。
 *
 * <p>模型只能提出候选题，不能声明资料所有者、改写学习状态或直接写库。这里校验 HTTP 和
 * JSON 协议，业务 Service 还要在发布短事务中重新核对资料版本与所有权。即使 Python
 * 实现或模型发生故障，也不能把不存在的原文依据、迟到任务结果静默存成正式题目。</p>
 */
@Component
public class GenerationClient {
    private static final int MAX_BYTES = 128 * 1024;
    private static final Set<String> RESULT_FIELDS = Set.of("schemaVersion", "jobId", "attemptToken", "model", "items", "shortfallReason");//契约字段
    private static final Set<String> ITEM_FIELDS = Set.of("conceptId", "blockId", "sourceQuote", "conceptName", "conceptDefinition",
            "content", "optionA", "optionB", "optionC", "optionD", "correctAnswer", "explanation");
    private final GenerationProperties properties;
    // 拒绝重复键和尾随 JSON；否则两个 correctAnswer 中“最后一个胜出”会掩盖协议歧义。
    private final JsonMapper json = JsonMapper.builder().enable(StreamReadFeature.STRICT_DUPLICATE_DETECTION)
            .enable(DeserializationFeature.FAIL_ON_TRAILING_TOKENS).build();
    private final HttpClient http = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(2))
            .version(HttpClient.Version.HTTP_1_1).followRedirects(HttpClient.Redirect.NEVER).build();

    public GenerationClient(GenerationProperties properties) { this.properties = properties; }

    /** 必须在数据库事务之外调用；外部模型延迟不能长时间占用用户行锁。 */
    public Result generate(Request payload) {
        return generateInternal(payload, null);
    }

    /**
     * 网页个人密钥模式：调用者已按用户解密，密钥只存在本次 HTTP 请求头中。
     * 不改变共享 properties，不把 Key 放进任务快照、JSON 或日志，
     * 不同用户并发也不会串钥。显式提供非法值时必须失败，
     * 不能悄悄回落到服务器的共享环境密钥。
     */
    public Result generate(Request payload, String apiKey) {
        if (apiKey == null || apiKey.length() < 16 || apiKey.length() > 512 ||
                !apiKey.chars().allMatch(c -> c >= 33 && c <= 126)) {
            throw failure("MODEL_KEY_REQUIRED");
        }
        return generateInternal(payload, apiKey);
    }

    private Result generateInternal(Request payload, String apiKey) {
        validateRequest(payload);
        Result result = decode(send("/internal/generation/questions", payload, apiKey));
        validateResult(payload, result);
        return result;
    }

    AdaptiveCatalog.CatalogResult catalog(AdaptiveCatalog.CatalogRequest payload, String apiKey) {
        try {
            // 正文与旧题优先。目录过大时裁剪候选提示，数据库里的稳定 ID 与历史仍完整保留。
            List<AdaptiveCatalog.CatalogTopic> topics = new ArrayList<>(payload.topics());
            AdaptiveCatalog.CatalogRequest request = new AdaptiveCatalog.CatalogRequest(payload.sources(), topics, payload.legacy());
            while (!topics.isEmpty() && json.writeValueAsBytes(request).length > 110 * 1024) topics.removeLast();
            var result = json.readValue(send("/internal/generation/catalog", request, apiKey), AdaptiveCatalog.CatalogResult.class);
            require(result != null && result.entries() != null && result.entries().size() <= 60);
            // 只接受实际发给模型的稳定 ID，即使数据库里还有其他目录项。
            Set<String> allowed = new HashSet<>();
            topics.forEach(topic -> allowed.add(topic.id()));
            require(result.entries().stream().allMatch(e -> e != null && (e.existingId() == null || allowed.contains(e.existingId()))));
            return result;
        } catch (GenerationException ex) { throw ex; }
        catch (RuntimeException ex) { throw failure("MODEL_OUTPUT_INVALID"); }
    }

    /** 阅读只接收纯文本回答，复用相同的限长、超时、服务认证及用户 Key 传输。 */
    public String explain(String source, String quote, String question, String detail, String apiKey) {
        if (apiKey == null) throw failure("MODEL_KEY_REQUIRED");
        try {
            byte[] response = send("/internal/reading/answer",
                    Map.of("source", source, "quote", quote, "question", question, "detail", detail), apiKey);
            Map<?, ?> value = json.readValue(response, Map.class);
            require(value != null && value.keySet().equals(Set.of("answer")));
            String answer = string(value, "answer");
            require(validText(answer, 1, 5000));
            return answer;
        } catch (GenerationException ex) { throw ex; }
        catch (RuntimeException ex) { throw failure("MODEL_OUTPUT_INVALID"); }
    }

    /** 私人老师与出题共用已认证的内部模型通道，浏览器接触不到用户密钥。 */
    public String tutorAnswer(String message, String context, String apiKey) {
        if (apiKey == null) throw failure("MODEL_KEY_REQUIRED");
        try {
            byte[] response = send("/internal/tutor/answer", Map.of("message", message, "context", context), apiKey);
            Map<?, ?> value = json.readValue(response, Map.class);
            require(value != null && value.keySet().equals(Set.of("answer")));
            String answer = string(value, "answer");
            require(validText(answer, 1, 2000));
            return answer;
        } catch (GenerationException ex) { throw ex; }
        catch (RuntimeException ex) { throw failure("MODEL_OUTPUT_INVALID"); }
    }

    public record TutorPlan(List<String> ids, String reason, List<String> trace) {}

    public record TutorCoach(String answer,String teaching,List<String> citations,
                             List<com.learnloop.tutor.TutorRetrievalService.Trace> trace) {}

    public TutorCoach tutorCoach(String message,String context,String token,String apiKey) {
        if(apiKey==null) throw failure("MODEL_KEY_REQUIRED");
        try {
            var result=json.readValue(send("/internal/tutor/answer",
                Map.of("message",message,"context",context,"toolToken",token),apiKey),TutorCoach.class);
            require(result!=null && validText(result.answer(),1,2000) && result.teaching()!=null
                && Set.of("EXPLAIN","COMPARE","HINT","REVIEW").contains(result.teaching())
                && result.citations()!=null && result.citations().size()<=6
                && result.citations().stream().allMatch(ref -> validText(ref,1,100))
                && result.trace()!=null && result.trace().size()<=2
                && result.trace().stream().allMatch(row -> row!=null && row.tool()!=null
                    && Set.of("attempt_detail","search_material","search_notes").contains(row.tool())
                    && row.count()>=0 && row.count()<=3));
            return result;
        } catch(GenerationException ex) { throw ex; }
        catch(RuntimeException ex) { throw failure("MODEL_OUTPUT_INVALID"); }
    }

    public TutorPlan tutorPlan(Object request, String apiKey) {
        if (apiKey == null) throw failure("MODEL_KEY_REQUIRED");
        try {
            var result = json.readValue(send("/internal/tutor/plan", request, apiKey), TutorPlan.class);
            require(result != null && result.ids() != null && !result.ids().isEmpty() && result.ids().size() <= 5
                    && result.ids().stream().allMatch(id -> id != null && id.matches("[0-9a-f-]{36}"))
                    && new HashSet<>(result.ids()).size() == result.ids().size()
                    && validText(result.reason(), 1, 240) && result.trace() != null && !result.trace().isEmpty()
                    && result.trace().size() <= 3 && new HashSet<>(result.trace()).size() == result.trace().size()
                    && result.trace().stream().allMatch(tool -> tool != null && Set.of("DUE", "PRACTICE", "MASTERY", "SUMMARY").contains(tool)));
            return result;
        } catch (GenerationException ex) { throw ex; }
        catch (RuntimeException ex) { throw failure("MODEL_OUTPUT_INVALID"); }
    }

    public record ReviewSchedule(String state, Instant dueAt) {}

    public ReviewSchedule scheduleReview(String state, int rating, Instant reviewedAt) {
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("state", state);
        payload.put("rating", rating);
        payload.put("reviewedAt", reviewedAt.toString());
        try {
            Map<?, ?> reply = json.readValue(send("/internal/algorithms/fsrs/review", payload, null), Map.class);
            require(reply != null && reply.keySet().equals(Set.of("state", "dueAt")));
            String next = string(reply, "state");
            Instant due = Instant.parse(string(reply, "dueAt"));
            require(next.length() <= 2000 && json.readTree(next).isObject() && due.isAfter(reviewedAt)
                    && due.isBefore(reviewedAt.plus(Duration.ofDays(36600))));
            return new ReviewSchedule(next, due);
        } catch (RuntimeException ex) { throw failure("FSRS_UNAVAILABLE"); }
    }

    private byte[] send(String path, Object payload, String apiKey) {
        try {
            URI base = URI.create(properties.getBaseUrl());
            String key = properties.getServiceKey();
            // 资料、网页、模型返回值均无权指定目标地址；首版仅允许本机内部服务。
            if (!"http".equals(base.getScheme()) || !"127.0.0.1".equals(base.getHost()) || base.getPort() < 1
                    || base.getPort() > 65535 || base.getUserInfo() != null || base.getQuery() != null
                    || base.getFragment() != null || !(base.getPath().isEmpty() || "/".equals(base.getPath()))
                    || key == null || key.length() < 32 || !key.chars().allMatch(c -> c >= 33 && c <= 126)) {
                throw failure("GENERATION_NOT_CONFIGURED");
            }
            byte[] body = json.writeValueAsBytes(payload);
            if (body.length > MAX_BYTES) throw failure("GENERATION_INVALID_REQUEST");
            // 用户 Key 只出现在这条内部请求头里；共享环境密钥仍作为未启用个人设置时的回退。
            HttpRequest.Builder builder = HttpRequest.newBuilder(base.resolve(path))
                    .timeout(Duration.ofSeconds(70)).header("Content-Type", "application/json")
                    .header("X-LearnLoop-Service-Key", key);
            // 只传任务标识以关联内部诊断，不传用户身份、资料正文或认领 token。
            String traceJob = org.slf4j.MDC.get("generationJob");
            String traceAttempt = org.slf4j.MDC.get("generationAttempt");
            if (traceJob != null && traceJob.matches("[0-9a-f-]{36}") && traceAttempt != null && traceAttempt.matches("[0-9]{1,9}")) {
                builder.header("X-LearnLoop-Job", traceJob).header("X-LearnLoop-Attempt", traceAttempt);
            }
            if (apiKey != null) builder.header("X-LearnLoop-Model-Key", apiKey);
            HttpRequest request = builder.POST(HttpRequest.BodyPublishers.ofByteArray(body)).build();
            var pending = http.sendAsync(request, ignored -> new LimitedBody());
            HttpResponse<byte[]> response;
            try { response = pending.get(75, TimeUnit.SECONDS); }
            catch (Exception ex) { pending.cancel(true); throw ex; }
            if (response.statusCode() != 200) {
                // 仅传递内部服务预先约定的错误码；不传播上游文案、请求、密钥或 SQL。
                // 例如“没有配置密钥”和“模型输出不合格”必须可区分，便于手动处理。
                String code = "GENERATION_UNAVAILABLE";
                try {
                    Map<?, ?> error = json.readValue(response.body(), Map.class);
                    if (error != null && Set.of("MODEL_NOT_CONFIGURED", "MODEL_TIMEOUT", "MODEL_OUTPUT_INVALID",
                            "MODEL_UPSTREAM_FAILED", "MODEL_RESPONSE_TOO_LARGE", "GENERATION_BUSY",
                            "GENERATION_NOT_ENABLED", "SERVICE_UNAUTHORIZED").contains(error.get("errorCode"))) {
                        code = (String) error.get("errorCode");
                    }
                } catch (RuntimeException ignored) { /* 非契约错误正文只丢弃，不回显。 */ }
                throw failure(code);
            }
            return response.body();
        } catch (GenerationException ex) { throw ex; }
        catch (InterruptedException ex) { Thread.currentThread().interrupt(); throw failure("GENERATION_INTERRUPTED"); }
        catch (Exception ex) { throw failure("GENERATION_UNAVAILABLE"); }
    }

    /**
     * 请求由 Java 冻结的正文构造，而非把浏览器传来的任意 prompt 原样转发。
     * 字符上限按 Unicode 码点计数，与 Python len 一致，避免中文/补充平面字符边界不一致。
     */
    public static void validateRequest(Request r) {
        try {
            require(r != null && "1".equals(r.schemaVersion()) && uuid(r.jobId()) && uuid(r.attemptToken()));
            String mode = r.mode() == null ? "NEW" : r.mode();
            require(Set.of("NEW", "VARIANT", "TARGETED").contains(mode));
            require("VARIANT".equals(mode)
                    ? r.count() == 1 && uuid(r.targetConceptId())
                    : r.targetConceptId() == null && ("TARGETED".equals(mode)
                            ? r.count() >= 1 && r.count() <= 10 : Set.of(3, 5, 10).contains(r.count())));
            require(Set.of("EASY", "MEDIUM", "HARD").contains(r.difficulty()));
            require(Set.of("COARSE", "MEDIUM", "FINE").contains(r.granularity()));
            require(r.sources() != null && !r.sources().isEmpty() && r.sources().size() <= 10);
            Map<Long, Source> sources = new HashMap<>();
            int characters = 0;
            for (Source source : r.sources()) {
                require(source != null && source.blockId() > 0 && validText(source.text(), 1, 12000));
                require(sources.put(source.blockId(), source) == null);
                characters += length(source.text());
            }
            require(characters <= 12000 && r.knownConcepts() != null && r.knownConcepts().size() <= 200);
            Set<String> known = new HashSet<>();
            for (KnownConcept concept : r.knownConcepts()) {
                require(concept != null && uuid(concept.conceptId()) && known.add(concept.conceptId())
                        && sources.containsKey(concept.blockId()) && validText(concept.name(), 1, 120)
                        && concept.definition() != null && length(concept.definition()) <= 1000);
            }
            if ("VARIANT".equals(mode)) require(known.contains(r.targetConceptId()));
            require(r.reinforcementIds() != null && new HashSet<>(r.reinforcementIds()).size() == r.reinforcementIds().size());
            if ("TARGETED".equals(mode)) require(known.size() == r.count() && known.containsAll(r.reinforcementIds()));
            else require(r.reinforcementIds().isEmpty());
            require(r.knownQuestions() != null && r.knownQuestions().size() <= 200);
            for (KnownQuestion question : r.knownQuestions()) {
                require(question != null && sources.containsKey(question.blockId())
                        && validText(question.sourceQuote(), 8, 1000)
                        && validText(question.content(), 1, 2000));
            }
        } catch (RuntimeException ex) { throw failure("GENERATION_INVALID_REQUEST"); }
    }

    /**
     * 发布前可再次调用本函数：测试替身与内部客户端都不能绕过相同的业务协议检查。
     * 原文存在只证明引用可定位，不能证明题目事实正确；后续质量评测仍需人工样本检查。
     */
    public static void validateResult(Request request, Result result) {
        validateRequest(request);
        try {
            require(result != null && "1".equals(result.schemaVersion()) && request.jobId().equals(result.jobId())
                    && request.attemptToken().equals(result.attemptToken()) && validText(result.model(), 1, 128));
            require(result.items() != null && result.items().size() <= request.count());
            require(result.shortfallReason() == null || validText(result.shortfallReason(), 1, 300));
            require(result.items().size() == request.count() || result.shortfallReason() != null);
            Map<Long, Source> sources = new HashMap<>();
            request.sources().forEach(source -> sources.put(source.blockId(), source));
            Map<String, KnownConcept> known = new HashMap<>();
            request.knownConcepts().forEach(concept -> known.put(concept.conceptId(), concept));
            Set<String> concepts = new HashSet<>(), contents = new HashSet<>();
            int extensions=0;
            for (Item item : result.items()) {
                require(item != null && sources.containsKey(item.blockId()));
                require(validText(item.sourceQuote(), 8, 1000) && sources.get(item.blockId()).text().contains(item.sourceQuote()));
                require(validText(item.conceptName(), 1, 120) && validText(item.conceptDefinition(), 1, 1000));
                String conceptKey = item.blockId() + ":" + normalized(item.conceptName());
                if ("TARGETED".equals(request.mode())) require(item.conceptId() != null);
                if (item.conceptId() != null) {
                    KnownConcept concept = known.get(item.conceptId());
                    require(concept != null && concept.blockId() == item.blockId() && concept.name().strip().equals(item.conceptName().strip()));
                    conceptKey = item.conceptId();
                }
                // 名称和 ID 两套去重同时执行，防止一项带旧 ID、一项不带 ID 重复出题。
                require(concepts.add("anchor:" + item.blockId() + ":" + normalized(item.conceptName())));
                require(concepts.add("identity:" + conceptKey));
                require(validText(item.content(), 1, 2000) && contents.add(normalized(item.content())));
                if(item.content().startsWith("【扩展题】")) require("HARD".equals(request.difficulty()) && ++extensions<=1);
                require(validText(item.explanation(), 1, 1000));
                Set<String> options = new HashSet<>();
                for (String option : Arrays.asList(item.optionA(), item.optionB(), item.optionC(), item.optionD())) {
                    require(validText(option, 1, 1000) && options.add(normalized(option)));
                }
                require(Set.of("A", "B", "C", "D").contains(item.correctAnswer()));
            }
            if ("VARIANT".equals(request.mode())) {
                require(result.items().size() == 1);
                Item item = result.items().getFirst();
                KnownConcept target = known.get(request.targetConceptId());
                require(item.conceptId() != null && item.conceptId().equals(request.targetConceptId())
                        && target != null && target.blockId() == item.blockId()
                        && target.name().strip().equals(item.conceptName().strip()));
            }
        } catch (RuntimeException ex) { throw failure("GENERATION_INVALID_RESULT"); }
    }

    /** 手动检查字段集与数值类型；不用 Jackson 宽松把字符串 ID / 小数强转为 long。 */
    private Result decode(byte[] data) {
        try {
            Map<?, ?> value = json.readValue(data, Map.class);
            require(value != null && value.keySet().equals(RESULT_FIELDS));
            Object rawItems = value.get("items");
            require(rawItems instanceof List<?>);
            List<Item> items = new ArrayList<>();
            for (Object raw : (List<?>) rawItems) {
                require(raw instanceof Map<?, ?>);
                Map<?, ?> item = (Map<?, ?>) raw;
                require(item.keySet().equals(ITEM_FIELDS));
                Object rawBlock = item.get("blockId");
                require(rawBlock instanceof Integer || rawBlock instanceof Long);
                items.add(new Item(nullableString(item, "conceptId"), ((Number) rawBlock).longValue(),
                        string(item, "sourceQuote"), string(item, "conceptName"), string(item, "conceptDefinition"),
                        string(item, "content"), string(item, "optionA"), string(item, "optionB"), string(item, "optionC"),
                        string(item, "optionD"), string(item, "correctAnswer"), string(item, "explanation")));
            }
            return new Result(string(value, "schemaVersion"), string(value, "jobId"), string(value, "attemptToken"),
                    string(value, "model"), List.copyOf(items), nullableString(value, "shortfallReason"));
        } catch (RuntimeException ex) { throw failure("GENERATION_INVALID_RESULT"); }
    }

    private static String string(Map<?, ?> map, String name) {
        require(map.get(name) instanceof String); return (String) map.get(name);
    }
    private static String nullableString(Map<?, ?> map, String name) {
        return map.get(name) == null ? null : string(map, name);
    }
    private static int length(String value) { return value.codePointCount(0, value.length()); }
    private static boolean validText(String value, int minimum, int maximum) {
        return value != null && !value.isBlank() && length(value) >= minimum && length(value) <= maximum;
    }
    private static String normalized(String value) {
        return java.text.Normalizer.normalize(value.strip(), java.text.Normalizer.Form.NFKC).toLowerCase(Locale.ROOT);
    }
    private static boolean uuid(String value) {
        if (value == null) return false;
        try { return UUID.fromString(value).toString().equals(value); } catch (IllegalArgumentException ex) { return false; }
    }
    private static void require(boolean condition) { if (!condition) throw new IllegalArgumentException("Invalid generation protocol"); }
    private static GenerationException failure(String code) {
        return new GenerationException(503, code, "题目生成暂未完成，请检查模型配置或稍后手动重试。");
    }

    /** 按实际网络分块累计字节，越界立即取消，不能信任 Content-Length 或先读完再检查。 */
    private static final class LimitedBody implements HttpResponse.BodySubscriber<byte[]> {
        private final CompletableFuture<byte[]> result = new CompletableFuture<>();
        private final ByteArrayOutputStream buffer = new ByteArrayOutputStream();
        private Flow.Subscription subscription;
        public CompletionStage<byte[]> getBody() { return result; }
        public void onSubscribe(Flow.Subscription value) { subscription = value; value.request(1); }
        public void onNext(List<ByteBuffer> chunks) {
            for (ByteBuffer chunk : chunks) {
                if (chunk.remaining() > MAX_BYTES - buffer.size()) {
                    subscription.cancel(); result.completeExceptionally(failure("GENERATION_INVALID_RESULT")); return;
                }
                byte[] bytes = new byte[chunk.remaining()]; chunk.get(bytes); buffer.writeBytes(bytes);
            }
            subscription.request(1);
        }
        public void onError(Throwable error) { result.completeExceptionally(error); }
        public void onComplete() { result.complete(buffer.toByteArray()); }
    }
}
