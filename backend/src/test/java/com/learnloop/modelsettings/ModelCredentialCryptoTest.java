package com.learnloop.modelsettings;

import java.nio.charset.StandardCharsets;
import java.util.Arrays;
import java.util.Base64;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

/** 不使用真实 API Key：验证认证加密、用户绑定、nonce 随机性与配置损坏的拒绝行为。 */
class ModelCredentialCryptoTest {
    private static final String TEST_KEY = "sk-fixture-key-000000000000000000000004";
    private ModelSettingsProperties properties() {
        ModelSettingsProperties p = new ModelSettingsProperties();
        p.setMasterKey(Base64.getEncoder().encodeToString(new byte[32]));
        return p;
    }

    @Test void roundTripUsesAuthenticatedEncryptionWithoutPlaintextPersistence() {
        var crypto = new ModelCredentialCrypto(properties());
        var encrypted = crypto.encrypt(11, TEST_KEY);
        assertEquals(12, encrypted.nonce().length);
        assertEquals(TEST_KEY.length() + 16, encrypted.ciphertext().length);
        assertFalse(new String(encrypted.ciphertext(), StandardCharsets.ISO_8859_1).contains(TEST_KEY));
        assertEquals(TEST_KEY, crypto.decrypt(11, ModelCredentialCrypto.FORMAT, encrypted.nonce(), encrypted.ciphertext()));
        assertFalse(encrypted.toString().contains(TEST_KEY));
    }
    @Test void everySaveHasFreshNonceAndDifferentCiphertext() {
        var crypto = new ModelCredentialCrypto(properties());
        var one = crypto.encrypt(1, TEST_KEY);
        var two = crypto.encrypt(1, TEST_KEY);
        assertFalse(Arrays.equals(one.nonce(), two.nonce()));
        assertFalse(Arrays.equals(one.ciphertext(), two.ciphertext()));
    }
    @Test void copyingRowToAnotherUserFailsAuthentication() {
        var crypto = new ModelCredentialCrypto(properties());
        var encrypted = crypto.encrypt(1, TEST_KEY);
        assertEquals("MODEL_KEY_UNREADABLE", assertThrows(ModelCredentialException.class,
                () -> crypto.decrypt(2, ModelCredentialCrypto.FORMAT, encrypted.nonce(), encrypted.ciphertext())).code());
    }
    @Test void corruptCiphertextAndWrongMasterFailClosed() {
        ModelSettingsProperties p = properties();
        var crypto = new ModelCredentialCrypto(p);
        var encrypted = crypto.encrypt(1, TEST_KEY);
        byte[] changed = encrypted.ciphertext(); changed[0] ^= 1;
        assertThrows(ModelCredentialException.class, () -> crypto.decrypt(1, ModelCredentialCrypto.FORMAT, encrypted.nonce(), changed));
        byte[] other = new byte[32]; Arrays.fill(other, (byte) 1);
        p.setMasterKey(Base64.getEncoder().encodeToString(other));
        assertThrows(ModelCredentialException.class, () -> crypto.decrypt(1, ModelCredentialCrypto.FORMAT, encrypted.nonce(), encrypted.ciphertext()));
    }
    @Test void malformedMasterKeyIsConfigurationErrorAndNotGeneratedSilently() {
        for (String value : new String[]{"", "not base64", Base64.getEncoder().encodeToString(new byte[16])}) {
            ModelSettingsProperties p = properties(); p.setMasterKey(value);
            var crypto = new ModelCredentialCrypto(p);
            var ex = assertThrows(ModelCredentialException.class, crypto::requireMasterKey);
            assertEquals("MODEL_MASTER_KEY_NOT_CONFIGURED", ex.code());
            assertFalse(ex.getMessage().contains(value.isEmpty() ? "not base64" : value));
        }
    }
    @Test void keyValidationRejectsWhitespaceControlsNonAsciiAndWrongSizes() {
        for (String value : new String[]{"", "x".repeat(15), "x".repeat(513), "sk-this contains-space", "sk-test-newline-key\n", "sk-中文-key-xxxxxxxx"}) {
            var ex = assertThrows(ModelCredentialException.class, () -> ModelCredentialCrypto.validateApiKey(value));
            assertEquals("INVALID_MODEL_KEY", ex.code());
            assertNull(ex.getCause());
        }
        ModelCredentialCrypto.validateApiKey("x".repeat(16));
        ModelCredentialCrypto.validateApiKey("x".repeat(512));
    }
    @Test void unsupportedVersionAndMalformedEnvelopeCannotBeDecrypted() {
        var crypto = new ModelCredentialCrypto(properties());
        var encrypted = crypto.encrypt(1, TEST_KEY);
        assertThrows(ModelCredentialException.class, () -> crypto.decrypt(1, "unknown-v2", encrypted.nonce(), encrypted.ciphertext()));
        assertThrows(ModelCredentialException.class, () -> crypto.decrypt(1, ModelCredentialCrypto.FORMAT, new byte[11], encrypted.ciphertext()));
        assertThrows(ModelCredentialException.class, () -> crypto.decrypt(1, ModelCredentialCrypto.FORMAT, encrypted.nonce(), new byte[31]));
    }
    @Test void encryptedEnvelopeDefensivelyCopiesArraysAtBoundary() {
        var crypto = new ModelCredentialCrypto(properties());
        var encrypted = crypto.encrypt(1, TEST_KEY);
        byte[] exposed = encrypted.ciphertext(); exposed[0] ^= 1;
        assertEquals(TEST_KEY, crypto.decrypt(1, ModelCredentialCrypto.FORMAT, encrypted.nonce(), encrypted.ciphertext()));
    }
}
