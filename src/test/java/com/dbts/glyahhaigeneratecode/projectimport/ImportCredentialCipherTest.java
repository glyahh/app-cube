package com.dbts.glyahhaigeneratecode.projectimport;

import org.junit.jupiter.api.Test;
import javax.crypto.spec.SecretKeySpec;
import java.util.Map;
import java.util.UUID;
import static org.junit.jupiter.api.Assertions.*;

class ImportCredentialCipherTest {
    private final UUID id = UUID.randomUUID();
    private final SecretKeySpec key = new SecretKeySpec(new byte[32], "AES");
    private ImportCredentialCipher cipher() { return new ImportCredentialCipher("v1", Map.of("v1", key)); }

    @Test void roundTripUsesUniqueNonceAndNeverStoresPlaintext() {
        var cipher = cipher();
        var first = cipher.encrypt(12, "GitHub", id, 1, "TEST_SECRET_ONLY");
        var second = cipher.encrypt(12, "GitHub", id, 1, "TEST_SECRET_ONLY");
        assertEquals("TEST_SECRET_ONLY", cipher.decrypt(12, "GitHub", id, 1, first));
        assertNotEquals(first.nonce(), second.nonce());
        assertNotEquals(first.ciphertext(), second.ciphertext());
        assertFalse(first.toString().contains("TEST_SECRET_ONLY"));
    }

    @Test void authenticatedContextRejectsCrossOwnerProviderIdAndSemanticVersion() {
        var cipher = cipher();
        var secret = cipher.encrypt(12, "GitHub", id, 1, "TEST_SECRET_ONLY");
        assertThrows(RuntimeException.class, () -> cipher.decrypt(13, "GitHub", id, 1, secret));
        assertThrows(RuntimeException.class, () -> cipher.decrypt(12, "Gitee", id, 1, secret));
        assertThrows(RuntimeException.class, () -> cipher.decrypt(12, "GitHub", UUID.randomUUID(), 1, secret));
        assertThrows(RuntimeException.class, () -> cipher.decrypt(12, "GitHub", id, 2, secret));
    }

    @Test void tamperedCiphertextAndWrongKeyFailClosedWithoutSecretInError() {
        var cipher = cipher();
        var secret = cipher.encrypt(12, "GitHub", id, 1, "TEST_SECRET_ONLY");
        byte[] changed = java.util.Base64.getDecoder().decode(secret.ciphertext()); changed[0] ^= 1;
        var tampered = new ImportCredentialCipher.Encrypted(secret.keyId(), secret.nonce(), java.util.Base64.getEncoder().encodeToString(changed));
        assertThrows(RuntimeException.class, () -> cipher.decrypt(12, "GitHub", id, 1, tampered));
        byte[] other = new byte[32]; other[0] = 1;
        var wrong = new ImportCredentialCipher("v1", Map.of("v1", new SecretKeySpec(other, "AES")));
        var error = assertThrows(RuntimeException.class, () -> wrong.decrypt(12, "GitHub", id, 1, secret));
        assertFalse(error.getMessage().contains("TEST_SECRET_ONLY"));
    }

    @Test void rotationReadsPriorKeyWithoutChangingSemanticVersion() {
        var secret = cipher().encrypt(12, "GitHub", id, 1, "TEST_SECRET_ONLY");
        byte[] next = new byte[32]; next[0] = 2;
        var rotating = new ImportCredentialCipher("v2", Map.of("v1", key, "v2", new SecretKeySpec(next, "AES")));
        String plain = rotating.decrypt(12, "GitHub", id, 1, secret);
        var rewrapped = rotating.encrypt(12, "GitHub", id, 1, plain);
        assertEquals("v2", rewrapped.keyId());
        assertEquals(plain, rotating.decrypt(12, "GitHub", id, 1, rewrapped));
    }

    @Test void missingKeyAndMalformedEnvelopeNeverFallBack() {
        assertThrows(RuntimeException.class, () -> new ImportCredentialCipher("missing", Map.of()).encrypt(12, "GitHub", id, 1, "x"));
        var secret = new ImportCredentialCipher.Encrypted("unknown", "bad", "bad");
        assertThrows(RuntimeException.class, () -> cipher().decrypt(12, "GitHub", id, 1, secret));
    }

    @Test void rejectsEmptyOversizedControlCharactersAndInvalidContext() {
        for (String token : new String[]{"", " ", "a\nb", "a\rb", "a\u0000b", "x".repeat(4097)}) {
            assertThrows(RuntimeException.class, () -> cipher().encrypt(12, "GitHub", id, 1, token));
        }
        assertThrows(RuntimeException.class, () -> cipher().encrypt(0, "GitHub", id, 1, "x"));
        assertThrows(RuntimeException.class, () -> cipher().encrypt(12, "Other", id, 1, "x"));
        assertThrows(RuntimeException.class, () -> cipher().encrypt(12, "GitHub", id, 0, "x"));
    }
}
