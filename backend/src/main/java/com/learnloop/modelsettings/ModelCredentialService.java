package com.learnloop.modelsettings;

import com.learnloop.learning.LearningMapper;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * 登录用户各自保存自己的 API Key，绝不回落到其他用户或服务器共享密钥。
 * 浏览器只获得 configured 布尔值；解密方法只供后台任务调用，禁止暴露为 HTTP 接口。
 */
@Service
public class ModelCredentialService {
    private final ModelSettingsProperties properties;
    private final ModelCredentialCrypto crypto;
    private final ModelCredentialMapper mapper;
    private final LearningMapper users;
    public record Settings(String provider, String model, boolean configured) { }
    public ModelCredentialService(ModelSettingsProperties properties, ModelCredentialCrypto crypto,
                                  ModelCredentialMapper mapper, LearningMapper users) {
        this.properties = properties; this.crypto = crypto; this.mapper = mapper; this.users = users;
    }

    public boolean isEnabled() { return properties.isEnabled(); }

    public Settings settings(long user) {
        enabled();
        ModelCredentialMapper.Stored stored = mapper.find(user);
        // 不把“行存在”误认为“可用”：主密钥换错/密文被破坏时立即报错，不静默覆盖旧数据。
        if (stored != null) decode(stored);
        return new Settings("DeepSeek", properties.getModel(), stored != null);
    }

    @Transactional
    public Settings save(long user, String apiKey) {
        enabled();
        ModelCredentialCrypto.validateApiKey(apiKey);
        lockUser(user);
        ModelCredentialMapper.Stored old = mapper.find(user);
        if (old != null) decode(old);
        ModelCredentialCrypto.Encrypted encrypted = crypto.encrypt(user, apiKey);
        mapper.save(user, ModelCredentialCrypto.FORMAT, encrypted.nonce(), encrypted.ciphertext(), LocalDateTime.now(ZoneOffset.UTC));
        return new Settings("DeepSeek", properties.getModel(), true);
    }

    @Transactional
    public Settings delete(long user) {
        enabled();
        lockUser(user);
        mapper.delete(user);
        // 删除只影响后续任务取钥，已经发出的供应商请求不能被撤销；不会宣称完成供应商侧吊销。
        return new Settings("DeepSeek", properties.getModel(), false);
    }

    /** 建任务前检查可读取；Worker 在真正调用前再次读取，删除后的排队任务会安全失败。 */
    public void requireConfigured(long user) { requireKey(user); }

    public String requireKey(long user) {
        enabled();
        ModelCredentialMapper.Stored stored = mapper.find(user);
        if (stored == null) throw new ModelCredentialException(409, "MODEL_KEY_REQUIRED", "请先在模型设置中保存你自己的 DeepSeek API Key。");
        return decode(stored);
    }

    private String decode(ModelCredentialMapper.Stored stored) {
        return crypto.decrypt(stored.userId(), stored.cipherFormat(), stored.nonce(), stored.ciphertext());
    }
    private void lockUser(long user) {
        // 与任务创建/认领采用同一个用户行锁，序列化同一用户的密钥修改与任务状态决策。
        if (user <= 0 || users.lockUser(user) == null) throw new ModelCredentialException(401, "LOGIN_REQUIRED", "请先登录。");
    }
    private void enabled() {
        if (!properties.isEnabled()) throw new ModelCredentialException(503, "MODEL_SETTINGS_NOT_ENABLED", "模型设置尚未启用，请先完成服务器配置和数据库迁移。");
        crypto.requireMasterKey();
    }
}
