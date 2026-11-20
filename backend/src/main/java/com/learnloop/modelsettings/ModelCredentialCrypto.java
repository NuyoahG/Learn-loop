package com.learnloop.modelsettings;

import java.nio.charset.StandardCharsets;
import java.security.SecureRandom;
import java.util.Arrays;
import java.util.Base64;
import javax.crypto.Cipher;
import javax.crypto.spec.GCMParameterSpec;
import javax.crypto.spec.SecretKeySpec;
import org.springframework.stereotype.Component;

/**
 * 每次保存均生成新的 96 位随机 nonce，以 AES-256-GCM 同时加密和校验完整性。
 * AAD 包含协议版本及数据库用户 ID：把某人的整行密文复制给其他人也不能解密。
 * 首版只支持密钥格式 v1；未来更换主密钥必须提供显式迁移，不能覆盖不可读旧数据。
 */
@Component
public class ModelCredentialCrypto {
    static final String FORMAT = "AES256_GCM_V1";
    private final ModelSettingsProperties properties;
    private final SecureRandom random = new SecureRandom();
    public ModelCredentialCrypto(ModelSettingsProperties properties) { this.properties = properties; }

    /** 不使用自动生成 toString 的 record 暴露敏感数组。 */
    public static final class Encrypted {
        private final byte[] nonce;
        private final byte[] ciphertext;
        Encrypted(byte[] nonce, byte[] ciphertext) { this.nonce = nonce; this.ciphertext = ciphertext; }
        public byte[] nonce() { return nonce.clone(); }
        public byte[] ciphertext() { return ciphertext.clone(); }
        @Override public String toString() { return "Encrypted[REDACTED]"; }
    }

    public Encrypted encrypt(long userId, String apiKey) {
        validateApiKey(apiKey);
        byte[] plain = apiKey.getBytes(StandardCharsets.US_ASCII);
        byte[] nonce = new byte[12];
        random.nextBytes(nonce);
        try { return new Encrypted(nonce, process(Cipher.ENCRYPT_MODE, userId, nonce, plain)); }
        finally { Arrays.fill(plain, (byte) 0); }
    }

    public String decrypt(long userId, String format, byte[] nonce, byte[] ciphertext) {
        if (!FORMAT.equals(format) || nonce == null || nonce.length != 12 || ciphertext == null
                || ciphertext.length < 32 || ciphertext.length > 528) throw unreadable();
        byte[] plain = process(Cipher.DECRYPT_MODE, userId, nonce, ciphertext);
        try {
            String key = new String(plain, StandardCharsets.US_ASCII);
            validateApiKey(key);
            return key;
        } finally { Arrays.fill(plain, (byte) 0); }
    }

    /** 提前验证主密钥，配置错误时不访问/改写数据库；绝不自动生成一个临时密钥。 */
    public void requireMasterKey() {
        byte[] decoded = masterKey();
        Arrays.fill(decoded, (byte) 0);
    }

    static void validateApiKey(String key) {
        if (key == null || key.length() < 16 || key.length() > 512
                || !key.chars().allMatch(c -> c >= 33 && c <= 126)) {
            throw new ModelCredentialException(400, "INVALID_MODEL_KEY", "API Key 须为 16—512 个不含空格的可见 ASCII 字符。");
        }
    }

    private byte[] process(int mode, long userId, byte[] nonce, byte[] input) {
        if (userId <= 0) throw unreadable();
        byte[] master = masterKey();
        try {
            Cipher cipher = Cipher.getInstance("AES/GCM/NoPadding");
            cipher.init(mode, new SecretKeySpec(master, "AES"), new GCMParameterSpec(128, nonce));
            cipher.updateAAD(("learnloop:model-key:v1:user:" + userId).getBytes(StandardCharsets.US_ASCII));
            return cipher.doFinal(input);
        } catch (Exception ex) {
            // 不保留原始密码学异常，避免日志打印敏感上下文；损坏/错主密钥统一返回安全错误。
            throw unreadable();
        } finally { Arrays.fill(master, (byte) 0); }
    }

    private byte[] masterKey() {
        byte[] decoded = null;
        try {
            String configured = properties.getMasterKey();
            if (configured == null || configured.isBlank()) throw new IllegalArgumentException();
            decoded = Base64.getDecoder().decode(configured);
            if (decoded.length != 32 || !Base64.getEncoder().encodeToString(decoded).equals(configured)) throw new IllegalArgumentException();
            return decoded;
        } catch (IllegalArgumentException ex) {
            if (decoded != null) Arrays.fill(decoded, (byte) 0);
            throw new ModelCredentialException(503, "MODEL_MASTER_KEY_NOT_CONFIGURED", "服务器尚未配置有效的模型密钥加密主密钥。");
        }
    }

    private static ModelCredentialException unreadable() {
        return new ModelCredentialException(503, "MODEL_KEY_UNREADABLE", "已保存的模型密钥无法读取，请检查服务器主密钥与迁移状态。");
    }
}
