package com.dbts.glyahhaigeneratecode.agentauth;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;

/**
 * 跨语言稳定序列化：递归字典序、无空白、UTF-8。
 *
 * <p>必须与 Python 端 `canonical_bytes` 产出相同字节，否则两侧动作哈希不一致。
 */
public final class AgentCanonicalJson {
    private static final ObjectMapper MAPPER = new ObjectMapper();

    private AgentCanonicalJson() {
    }

    public static byte[] canonicalBytes(JsonNode value) {
        try {
            return MAPPER.writeValueAsString(sort(value)).getBytes(StandardCharsets.UTF_8);
        } catch (java.io.IOException error) {
            throw new IllegalStateException(error);
        }
    }

    public static String hash(JsonNode value) {
        try {
            byte[] digest = MessageDigest.getInstance("SHA-256").digest(canonicalBytes(value));
            return java.util.HexFormat.of().formatHex(digest);
        } catch (NoSuchAlgorithmException error) {
            throw new IllegalStateException(error);
        }
    }

    /** Jackson 默认保留字段插入顺序，这里显式按字典序重建对象。 */
    private static JsonNode sort(JsonNode value) {
        if (value == null || value.isNull() || value.isValueNode()) return value;
        if (value.isArray()) {
            var array = MAPPER.createArrayNode();
            value.forEach(child -> array.add(sort(child)));
            return array;
        }
        var sorted = MAPPER.createObjectNode();
        var names = new java.util.ArrayList<String>();
        value.fieldNames().forEachRemaining(names::add);
        java.util.Collections.sort(names);
        for (String name : names) ((ObjectNode) sorted).set(name, sort(value.get(name)));
        return sorted;
    }
}