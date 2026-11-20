package com.learnloop.modelsettings;

import com.learnloop.auth.LoginUser;
import jakarta.servlet.http.HttpServletRequest;
import java.io.IOException;
import java.util.Map;
import org.springframework.http.CacheControl;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.*;
import tools.jackson.core.StreamReadFeature;
import tools.jackson.databind.DeserializationFeature;
import tools.jackson.databind.json.JsonMapper;

/**
 * 首版只接受 DeepSeek 的一个 apiKey 字段，禁止网页传任意模型服务器地址。
 * 沿用 Spring Session 和 CSRF；密钥只在 PUT 请求体传入，不放 URL、日志或返回对象。
 */
@RestController
@RequestMapping("/api/settings")
public class ModelSettingsController {
    private final ModelCredentialService service;
    private final JsonMapper json = JsonMapper.builder().enable(StreamReadFeature.STRICT_DUPLICATE_DETECTION)
            .enable(DeserializationFeature.FAIL_ON_TRAILING_TOKENS).build();
    public ModelSettingsController(ModelCredentialService service) { this.service = service; }

    @GetMapping("/model")
    public ResponseEntity<ModelCredentialService.Settings> get(@AuthenticationPrincipal LoginUser user) {
        return safe(service.settings(user.getUserId()));
    }

    @PutMapping(value = "/model-key", consumes = "application/json")
    public ResponseEntity<ModelCredentialService.Settings> save(@AuthenticationPrincipal LoginUser user, HttpServletRequest request) {
        requireExpectedOwner(user, request);
        // 不先反序列化成无上限 String：连 chunked 请求也最多读取 1025 字节再明确拒绝。
        // 最长合法密钥 512 字节，1 KiB 足够容纳正常 JSON；非必要的转义/空白可由客户端简化。
        if (request.getContentLengthLong() > 1024) throw tooLarge();
        byte[] body;
        try { body = request.getInputStream().readNBytes(1025); }
        catch (IOException ex) { throw invalid(); }
        if (body.length > 1024) throw tooLarge();
        String key;
        try {
            Map<?, ?> parsed = json.readValue(body, Map.class);
            if (parsed == null || parsed.size() != 1 || !parsed.containsKey("apiKey") || !(parsed.get("apiKey") instanceof String)) throw invalid();
            key = (String) parsed.get("apiKey");
        } catch (RuntimeException ex) {
            // Jackson 异常可含原始输入片段，绝不向外传播其 message 或 cause。
            throw invalid();
        } finally { java.util.Arrays.fill(body, (byte) 0); }
        return safe(service.save(user.getUserId(), key));
    }

    @DeleteMapping("/model-key")
    public ResponseEntity<ModelCredentialService.Settings> delete(@AuthenticationPrincipal LoginUser user, HttpServletRequest request) {
        requireExpectedOwner(user, request);
        return safe(service.delete(user.getUserId()));
    }

    private static void requireExpectedOwner(LoginUser user, HttpServletRequest request) {
        // Session 才是认证依据；此头只是“页面最初加载的是哪个账号”的并发上下文栅栏。
        // 例如另一个标签页登出 A 后登入 B，A 的旧页面不能把 A 输入的 Key 保存到 B。
        // 必须在读取密钥正文前校验，并拒绝多个同名头、合并值、空白或带前导零的非标准编号。
        var values = java.util.Collections.list(request.getHeaders("X-LearnLoop-Expected-User"));
        if (values.size() != 1 || user == null || user.getUserId() == null
                || !Long.toString(user.getUserId()).equals(values.getFirst())) {
            throw new ModelCredentialException(409, "SESSION_CONTEXT_CHANGED", "登录账号上下文已变化，请刷新页面后再操作模型密钥。");
        }
    }

    private ResponseEntity<ModelCredentialService.Settings> safe(ModelCredentialService.Settings settings) {
        return ResponseEntity.ok().cacheControl(CacheControl.noStore()).body(settings);
    }
    private static ModelCredentialException invalid() {
        return new ModelCredentialException(400, "INVALID_MODEL_KEY_REQUEST", "请提交仅包含 apiKey 字符串的 JSON 对象。");
    }
    private static ModelCredentialException tooLarge() {
        return new ModelCredentialException(413, "MODEL_KEY_REQUEST_TOO_LARGE", "模型设置请求不能超过 1 KiB。");
    }
}
