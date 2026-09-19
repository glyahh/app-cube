package com.dbts.glyahhaigeneratecode.runtime;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 异常检测的判错口径、去重指纹与脱敏。
 *
 * <p>这里锁住的是「什么才算错」这条线：判宽了会把正常的 error 级日志当成崩溃弹窗，
 * 判窄了真崩溃又没人管。
 */
@DisplayName("运行异常检测")
class RuntimeIncidentDetectorTest {

    @Test
    @DisplayName("指纹抹掉时间戳/数字/路径，同一处错误稳定收敛")
    void fingerprintIsStableAcrossNoise() {
        String first = RuntimeIncidentDetector.fingerprint(
                "2026-09-16 12:00:01 ERROR failed at line 42 in /app/src/main.js");
        String second = RuntimeIncidentDetector.fingerprint(
                "2026-09-16 12:34:56 ERROR failed at line 97 in /app/src/other.js");

        // 时间、行号、路径都变了，但错误本身是同一处 —— 指纹必须一致，否则去重失效
        assertEquals(first, second);
    }

    @Test
    @DisplayName("不同错误指纹不同")
    void fingerprintDiffersForDifferentErrors() {
        assertNotEquals(
                RuntimeIncidentDetector.fingerprint("ModuleNotFoundError: No module named 'flask'"),
                RuntimeIncidentDetector.fingerprint("EADDRINUSE: address already in use"));
    }

    @Test
    @DisplayName("脱敏抹掉凭据，保留可定位的信息")
    void sanitizeRemovesSecrets() {
        String raw = "connecting postgres://user:s3cret@localhost:5432/db with token=abc123XYZ";
        String cleaned = RuntimeIncidentDetector.sanitize(raw);

        assertFalse(cleaned.contains("s3cret"), "连接串里的密码应被隐去");
        assertFalse(cleaned.contains("abc123XYZ"), "token 值应被隐去");
        assertTrue(cleaned.contains("[已隐去]"));
    }

    @Test
    @DisplayName("脱敏不误伤正常文本")
    void sanitizeKeepsNormalText() {
        String raw = "listening on port 8080, ready to serve";
        assertEquals(raw, RuntimeIncidentDetector.sanitize(raw));
    }
}
