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
    void 只对合法UTF8内容做遮盖回写() {
        assertTrue(ImportLogMask.roundTrips("正常输出".getBytes(StandardCharsets.UTF_8)));
        assertFalse(ImportLogMask.roundTrips(new byte[]{(byte) 0xff, (byte) 0xfe, 0x00}));
    }
}
