package com.dbts.glyahhaigeneratecode.agentauth;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;

/** 规范化序列化必须与 Python 端一致，否则两侧动作哈希对不上。 */
class AgentCanonicalJsonTest {
    private final ObjectMapper json = new ObjectMapper();

    @Test
    void sortingMakesFieldOrderIrrelevant() throws Exception {
        var first = json.readTree("{\"b\":2,\"a\":1}");
        var second = json.readTree("{\"a\":1,\"b\":2}");
        assertEquals(AgentCanonicalJson.hash(first), AgentCanonicalJson.hash(second));
    }

    @Test
    void differentContentYieldsDifferentHash() throws Exception {
        var first = json.readTree("{\"a\":1}");
        var second = json.readTree("{\"a\":2}");
        assertNotEquals(AgentCanonicalJson.hash(first), AgentCanonicalJson.hash(second));
    }

    @Test
    void hashIsLowercaseSha256() throws Exception {
        assertTrue(AgentCanonicalJson.hash(json.readTree("{}")).matches("[0-9a-f]{64}"));
    }

    /** 与 Python canonical_bytes 的空对象结果逐字节一致。 */
    @Test
    void emptyObjectMatchesPython() throws Exception {
        assertEquals("{}", new String(AgentCanonicalJson.canonicalBytes(json.readTree("{}")),
                java.nio.charset.StandardCharsets.UTF_8));
    }

    private static void assertTrue(boolean value) {
        org.junit.jupiter.api.Assertions.assertTrue(value);
    }
}