package com.dbts.glyahhaigeneratecode.projectimport;

import com.dbts.glyahhaigeneratecode.exception.ErrorCode;
import com.dbts.glyahhaigeneratecode.exception.MyException;
import javax.crypto.Cipher;
import javax.crypto.SecretKey;
import javax.crypto.spec.GCMParameterSpec;
import java.nio.charset.StandardCharsets;
import java.security.SecureRandom;
import java.util.Arrays;
import java.util.Base64;
import java.util.Map;
import java.util.UUID;

/** 仅供受管导入读取；密文绑定身份和版本，不提供通用解密接口。 */
public final class ImportCredentialCipher {
    public record Encrypted(String keyId, String nonce, String ciphertext) { }
    private final String activeKeyId;
    private final Map<String, SecretKey> keys;
    private final SecureRandom random = new SecureRandom();

    public ImportCredentialCipher(String activeKeyId, Map<String, ? extends SecretKey> keys) {
        this.activeKeyId = activeKeyId;
        this.keys = Map.copyOf(keys);
    }

    public Encrypted encrypt(long owner, String provider, UUID id, long version, String token) {
        validate(owner, provider, id, version);
        if (token == null || token.isBlank() || token.length() > 4096 || token.chars().anyMatch(c -> c < 33 || c > 126)) {
            throw new MyException(ErrorCode.PARAMS_ERROR, "Token 无效，长度上限 4096，不能包含空白或控制字符。");
        }
        byte[] plain = token.getBytes(StandardCharsets.UTF_8);
        try {
            // 1. 每次生成独立 nonce，身份和语义版本作为认证上下文。
            byte[] nonce = new byte[12]; random.nextBytes(nonce);
            Cipher cipher = Cipher.getInstance("AES/GCM/NoPadding");
            cipher.init(Cipher.ENCRYPT_MODE, key(activeKeyId), new GCMParameterSpec(128, nonce));
            cipher.updateAAD(aad(owner, provider, id, version));
            // 2. 只返回带认证标签的密文，不把明文写入记录。
            return new Encrypted(activeKeyId, Base64.getEncoder().encodeToString(nonce), Base64.getEncoder().encodeToString(cipher.doFinal(plain)));
        } catch (Exception error) { throw unavailable(); }
        finally { Arrays.fill(plain, (byte) 0); }
    }

    public String decrypt(long owner, String provider, UUID id, long version, Encrypted encrypted) {
        validate(owner, provider, id, version);
        byte[] plain = null;
        try {
            byte[] nonce = Base64.getDecoder().decode(encrypted.nonce());
            byte[] bytes = Base64.getDecoder().decode(encrypted.ciphertext());
            if (nonce.length != 12 || bytes.length < 17 || bytes.length > 4112) throw unavailable();
            Cipher cipher = Cipher.getInstance("AES/GCM/NoPadding");
            cipher.init(Cipher.DECRYPT_MODE, key(encrypted.keyId()), new GCMParameterSpec(128, nonce));
            cipher.updateAAD(aad(owner, provider, id, version));
            plain = cipher.doFinal(bytes);
            return new String(plain, StandardCharsets.UTF_8);
        } catch (Exception error) { throw unavailable(); }
        finally { if (plain != null) Arrays.fill(plain, (byte) 0); }
    }

    private SecretKey key(String id) {
        SecretKey key = keys.get(id);
        if (key == null || !"AES".equals(key.getAlgorithm()) || key.getEncoded().length != 32) throw unavailable();
        return key;
    }

    private static byte[] aad(long owner, String provider, UUID id, long version) {
        return ("project-import-credential:1:" + owner + ":" + provider + ":" + id + ":" + version).getBytes(StandardCharsets.UTF_8);
    }

    private static void validate(long owner, String provider, UUID id, long version) {
        if (owner <= 0 || id == null || version <= 0 || !("GitHub".equals(provider) || "Gitee".equals(provider))) {
            throw new MyException(ErrorCode.PARAMS_ERROR, "凭据标识无效。");
        }
    }

    private static MyException unavailable() {
        return new MyException(ErrorCode.OPERATION_ERROR, "凭据加密配置或密文校验失败，已阻止使用。");
    }
}
