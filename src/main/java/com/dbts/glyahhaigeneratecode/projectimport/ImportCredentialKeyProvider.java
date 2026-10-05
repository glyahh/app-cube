package com.dbts.glyahhaigeneratecode.projectimport;

import com.dbts.glyahhaigeneratecode.exception.ErrorCode;
import com.dbts.glyahhaigeneratecode.exception.MyException;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.stereotype.Component;
import javax.crypto.SecretKey;
import javax.crypto.spec.SecretKeySpec;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.util.Base64;
import java.util.HashMap;

/** 独立部署密钥文件；未配置时仅阻断凭据操作，不生成默认密钥。 */
@Component
public class ImportCredentialKeyProvider {
    private final ImportProperties properties;
    private final ObjectMapper json;
    public ImportCredentialKeyProvider(ImportProperties properties, ObjectMapper json) {
        this.properties = properties; this.json = json;
    }

    public ImportCredentialCipher cipher() {
        try {
            // 1. 密钥不存仓库或工作区，拒绝链接及过大配置。
            Path configured = properties.getCredentialKeyRingFile();
            if (configured == null) throw unavailable();
            Path file = configured.toAbsolutePath().normalize();
            if (file.startsWith(Path.of("").toAbsolutePath().normalize())) throw unavailable();
            for (Path current = file; current != null; current = current.getParent()) {
                if (Files.isSymbolicLink(current) || !Files.exists(current, LinkOption.NOFOLLOW_LINKS) || !current.toRealPath().equals(current)) throw unavailable();
            }
            if (!Files.isRegularFile(file, LinkOption.NOFOLLOW_LINKS) || Files.size(file) > 16384) throw unavailable();
            var tree = json.readTree(Files.readAllBytes(file));
            String active = tree.path("activeKeyId").asText();
            if (!active.matches("[a-zA-Z0-9_-]{1,40}") || !tree.path("keys").isObject() || tree.path("keys").size() > 16) throw unavailable();
            // 2. 只保留明确的 AES-256 密钥，未知 keyId 不尝试其他密钥。
            var keys = new HashMap<String, SecretKey>();
            var fields = tree.path("keys").fields();
            while (fields.hasNext()) {
                var entry = fields.next(); byte[] bytes = Base64.getDecoder().decode(entry.getValue().asText());
                try {
                    if (!entry.getKey().matches("[a-zA-Z0-9_-]{1,40}") || bytes.length != 32) throw unavailable();
                    keys.put(entry.getKey(), new SecretKeySpec(bytes, "AES"));
                } finally { java.util.Arrays.fill(bytes, (byte) 0); }
            }
            if (!keys.containsKey(active)) throw unavailable();
            return new ImportCredentialCipher(active, keys);
        } catch (Exception error) { throw unavailable(); }
    }

    private static MyException unavailable() {
        return new MyException(ErrorCode.OPERATION_ERROR, "凭据密钥未配置或不可用，请联系管理员。");
    }
}
