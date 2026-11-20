package com.learnloop.modelsettings;

import com.learnloop.generation.GenerationApi;
import com.learnloop.generation.GenerationException;
import com.learnloop.generation.GenerationService;
import java.util.ArrayList;
import java.util.Base64;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import static org.junit.jupiter.api.Assertions.*;

/**
 * 仅在显式开启时写隔离的 13316 测试库，测试数据均为虚构密钥。
 * 重点覆盖用户隔离、认证密文落库、原子替换、错主密钥拒绝覆盖和出题前的密钥门禁。
 */
@EnabledIfEnvironmentVariable(named = "LEARNLOOP_RUN_MODEL_SETTINGS_DB_TESTS", matches = "true")
@SpringBootTest(properties = {
        "spring.datasource.url=jdbc:mysql://127.0.0.1:13316/learnloop?sslMode=DISABLED&allowPublicKeyRetrieval=true&serverTimezone=UTC&characterEncoding=UTF-8",
        "spring.datasource.username=root", "spring.datasource.password=", "spring.datasource.hikari.maximum-pool-size=6",
        "learnloop.learning.enabled=true", "learnloop.learning.worker-enabled=false",
        "learnloop.generation.enabled=true", "learnloop.generation.worker-enabled=false",
        "learnloop.model-settings.enabled=true", "learnloop.model-settings.master-key=AAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAA=",
        "learnloop.model-settings.model=test-model-only", "learnloop.material.parse.recovery-enabled=false",
        "learnloop.material.storage-root=${java.io.tmpdir}/learnloop-model-settings-test-materials"
})
@org.springframework.context.annotation.Import(com.learnloop.support.ExperimentClientTestSupport.class)
class ModelCredentialDatabaseTest {
    private static final String MASTER = "AAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAA=";
    private static final String ONE = "sk-fixture-key-000000000000000000000005";
    private static final String TWO = "sk-fixture-key-000000000000000000000006";
    @Autowired ModelCredentialService service;
    @Autowired ModelSettingsProperties properties;
    @Autowired GenerationService generation;
    @Autowired JdbcTemplate jdbc;
    private final List<Long> users = new ArrayList<>();
    private long user;

    @BeforeEach void setup() {
        assertEquals(13316, jdbc.queryForObject("SELECT @@port", Integer.class));
        properties.setEnabled(true); properties.setMasterKey(MASTER);
        user = newUser();
    }
    @AfterEach void cleanupOnlyTestUsers() {
        properties.setEnabled(true); properties.setMasterKey(MASTER);
        for (long owner : users) {
            jdbc.update("DELETE FROM model_credentials WHERE user_id=?", owner);
            jdbc.update("DELETE FROM users WHERE id=? AND username LIKE 'llkey_%'", owner);
        }
        users.clear();
    }
    private long newUser() {
        // users.username 上限 32；6 字符测试前缀 + 24 位随机十六进制，共 30 字符。
        String name = "llkey_" + UUID.randomUUID().toString().replace("-", "").substring(0, 24);
        jdbc.update("INSERT INTO users(username,password_hash) VALUES(?,?)", name, "test-hash");
        long id = jdbc.queryForObject("SELECT id FROM users WHERE username=?", Long.class, name);
        users.add(id); return id;
    }
    private byte[] ciphertext(long owner) {
        return jdbc.queryForObject("SELECT ciphertext FROM model_credentials WHERE user_id=?", byte[].class, owner);
    }

    @Test void absentKeyIsHonestAndRequiredBeforeGeneration() {
        assertFalse(service.settings(user).configured());
        var ex = assertThrows(ModelCredentialException.class, () -> service.requireKey(user));
        assertEquals("MODEL_KEY_REQUIRED", ex.code());
        var job = new GenerationApi.CreateJob(1, List.of(1L), 3, "MEDIUM", "MEDIUM", UUID.randomUUID().toString());
        assertEquals("MODEL_KEY_REQUIRED", assertThrows(GenerationException.class, () -> generation.create(user, job)).code());
        assertEquals(0, jdbc.queryForObject("SELECT COUNT(*) FROM generation_jobs WHERE user_id=?", Integer.class, user));
    }
    @Test void storesOnlyCiphertextAndSafeSettings() {
        var result = service.save(user, ONE);
        assertTrue(result.configured()); assertEquals("DeepSeek", result.provider());
        assertEquals("test-model-only", result.model()); assertFalse(result.toString().contains(ONE));
        assertEquals(ONE, service.requireKey(user));
        byte[] cipher = ciphertext(user);
        assertNotEquals(ONE, new String(cipher, java.nio.charset.StandardCharsets.ISO_8859_1));
        assertEquals(ONE.length() + 16, cipher.length);
        assertEquals(12, jdbc.queryForObject("SELECT OCTET_LENGTH(nonce) FROM model_credentials WHERE user_id=?", Integer.class, user));
    }
    @Test void anotherUserNeverUsesOrDeletesThisUsersKey() {
        long other = newUser(); service.save(user, ONE);
        assertFalse(service.settings(other).configured());
        assertThrows(ModelCredentialException.class, () -> service.requireKey(other));
        service.delete(other);
        assertEquals(ONE, service.requireKey(user));
        service.save(other, TWO);
        assertEquals(TWO, service.requireKey(other)); assertEquals(ONE, service.requireKey(user));
    }
    @Test void replacementCreatesFreshNonceAndSingleRow() {
        service.save(user, ONE);
        byte[] first = ciphertext(user);
        byte[] nonce = jdbc.queryForObject("SELECT nonce FROM model_credentials WHERE user_id=?", byte[].class, user);
        service.save(user, TWO);
        assertEquals(TWO, service.requireKey(user));
        assertFalse(java.util.Arrays.equals(first, ciphertext(user)));
        assertFalse(java.util.Arrays.equals(nonce, jdbc.queryForObject("SELECT nonce FROM model_credentials WHERE user_id=?", byte[].class, user)));
        assertEquals(1, jdbc.queryForObject("SELECT COUNT(*) FROM model_credentials WHERE user_id=?", Integer.class, user));
    }
    @Test void sameKeySaveAlsoRotatesNonce() {
        service.save(user, ONE); byte[] first = ciphertext(user);
        service.save(user, ONE); assertFalse(java.util.Arrays.equals(first, ciphertext(user)));
    }
    @Test void wrongMasterCannotReadOrSilentlyOverwriteExistingKey() {
        service.save(user, ONE); byte[] first = ciphertext(user);
        properties.setMasterKey(Base64.getEncoder().encodeToString("1".repeat(32).getBytes(java.nio.charset.StandardCharsets.US_ASCII)));
        assertEquals("MODEL_KEY_UNREADABLE", assertThrows(ModelCredentialException.class, () -> service.settings(user)).code());
        assertEquals("MODEL_KEY_UNREADABLE", assertThrows(ModelCredentialException.class, () -> service.save(user, TWO)).code());
        assertArrayEquals(first, ciphertext(user));
        properties.setMasterKey(MASTER); assertEquals(ONE, service.requireKey(user));
    }
    @Test void rowCopiedToOtherUserCannotDecryptBecauseAadIncludesOwner() {
        long other = newUser(); service.save(user, ONE);
        jdbc.update("INSERT INTO model_credentials(user_id,cipher_format,nonce,ciphertext,created_at,updated_at) SELECT ?,cipher_format,nonce,ciphertext,created_at,updated_at FROM model_credentials WHERE user_id=?", other, user);
        assertEquals("MODEL_KEY_UNREADABLE", assertThrows(ModelCredentialException.class, () -> service.requireKey(other)).code());
    }
    @Test void deleteIsIdempotentAndInvalidInputCannotReplaceSavedKey() {
        service.save(user, ONE);
        assertThrows(ModelCredentialException.class, () -> service.save(user, "bad key"));
        assertEquals(ONE, service.requireKey(user));
        assertFalse(service.delete(user).configured());
        assertFalse(service.delete(user).configured());
        assertThrows(ModelCredentialException.class, () -> service.requireKey(user));
    }
    @Test void disabledOrInvalidMasterNeverChangesRows() {
        service.save(user, ONE); byte[] first = ciphertext(user);
        properties.setEnabled(false);
        assertEquals("MODEL_SETTINGS_NOT_ENABLED", assertThrows(ModelCredentialException.class, () -> service.save(user, TWO)).code());
        properties.setEnabled(true); properties.setMasterKey("");
        assertEquals("MODEL_MASTER_KEY_NOT_CONFIGURED", assertThrows(ModelCredentialException.class, () -> service.delete(user)).code());
        assertArrayEquals(first, ciphertext(user));
    }
    @Test void concurrentSavesSerializeThroughOwnerRowAndLeaveOneValidCredential() throws Exception {
        try (var pool = Executors.newFixedThreadPool(2)) {
            CountDownLatch ready = new CountDownLatch(1);
            var one = pool.submit(() -> { ready.await(); return service.save(user, ONE); });
            var two = pool.submit(() -> { ready.await(); return service.save(user, TWO); });
            ready.countDown();
            assertTrue(one.get(5, TimeUnit.SECONDS).configured()); assertTrue(two.get(5, TimeUnit.SECONDS).configured());
        }
        assertTrue(List.of(ONE, TWO).contains(service.requireKey(user)));
        assertEquals(1, jdbc.queryForObject("SELECT COUNT(*) FROM model_credentials WHERE user_id=?", Integer.class, user));
    }
}
