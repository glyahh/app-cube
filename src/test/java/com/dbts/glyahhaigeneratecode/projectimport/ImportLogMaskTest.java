package com.dbts.glyahhaigeneratecode.projectimport;

import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** 执行日志遮盖规则：只动密钥形态，保留可诊断内容。 */
class ImportLogMaskTest {

    @Test
    void 遮盖常见密钥形态并保留其余诊断信息() {
        String text = String.join("\n",
                "__GLYAHH_GUARD_OK__ 0f1e2d3c4b5a6978",
                "{\"errno\": 28, \"size\": 1003487232}",
                "token sk-live1234567890abcdefghij printed",
                "GIT_ASKPASS_TOKEN=ghp_ABCDEFGHIJKLMNOPQRSTUVWXYZ012345",
                "Authorization: Bearer abcdefghijklmnop",
                "PASSWORD=hunter2secret",
                "installed");
        String masked = ImportLogMask.mask(text);
        assertTrue(masked.contains("__GLYAHH_GUARD_OK__ 0f1e2d3c4b5a6978"));
        assertTrue(masked.contains("{\"errno\": 28, \"size\": 1003487232}"));
        assertTrue(masked.contains("installed"));
        assertFalse(masked.contains("sk-live1234567890abcdefghij"));
        assertFalse(masked.contains("ghp_ABCDEFGHIJKLMNOPQRSTUVWXYZ012345"));
        assertFalse(masked.contains("abcdefghijklmnop"));
        assertFalse(masked.contains("hunter2secret"));
    }

    @Test
    void 遮盖平台令牌前缀与赋值形式() {
        String text = "pat=github_pat_11ABCDEFG0abcdefghijklmnopqrstuv\nother=glpat-abcdefghijklmnop";
        String masked = ImportLogMask.mask(text);
        assertFalse(masked.contains("github_pat_11ABCDEFG0abcdefghijklmnopqrstuv"));
        assertFalse(masked.contains("glpat-abcdefghijklmnop"));
    }

    @Test
    void 空值与非密钥文本不变() {
        assertEquals("", ImportLogMask.mask(""));
        assertEquals("build failed: exit 3", ImportLogMask.mask("build failed: exit 3"));
    }

    @Test
    void 引号赋值与JSON值均遮盖() {
        String text = "PASSWORD='private secret'\n{\"api_key\":\"fake_json_secret\",\"errno\":28}";
        String masked = ImportLogMask.mask(text);
        assertFalse(masked.contains("private secret"));
        assertFalse(masked.contains("fake_json_secret"));
        assertTrue(masked.contains("\"errno\":28"));
    }

    @Test void 不完整引号值遮盖且重复键名不导致长时间回溯() {
        assertEquals("PASSWORD=***", ImportLogMask.mask("PASSWORD='unfinished private value"));
        String harmless = "TOKEN".repeat(20000);
        org.junit.jupiter.api.Assertions.assertTimeout(java.time.Duration.ofSeconds(2), () ->
                assertEquals(harmless, ImportLogMask.mask(harmless)));
        assertEquals("COUNT=28 TOKEN=***", ImportLogMask.mask("COUNT=28 TOKEN=private"));
    }
}
