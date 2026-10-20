package com.dbts.glyahhaigeneratecode.agent;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 每个错误码都要有能被用户采取行动的中文提示。
 *
 * <p>全都落到 default 时，用户看到同一句「请稍后重试」，
 * 而真实原因可能是数据库、Docker 或模型配置——重试永远不会好。
 */
class AgentErrorMessageTest {
    private static final String FALLBACK = "本次处理未完成，请稍后重试";

    /** 与 agent/core/events.py 的 ErrorCode 保持一致。 */
    private static final List<String> CODES = List.of(
            "WORKSPACE_INIT_FAILED", "MODEL_CALL_FAILED", "MODEL_QUOTA_EXCEEDED",
            "RUN_DEADLINE_EXCEEDED", "TOOL_EXECUTION_FAILED", "SANDBOX_UNAVAILABLE",
            "IMPORT_EDIT_NOT_READY", "SANDBOX_BUSY", "SANDBOX_CREATE_FAILED",
            "SANDBOX_EXEC_FAILED", "SANDBOX_TIMEOUT", "SANDBOX_TERMINATED",
            "CHECKPOINT_ERROR");

    @Test
    void everyCodeHasSpecificMessage() {
        for (String code : CODES) {
            String message = UserFacingOutputHandler.errorMessage(code);
            assertNotBlank(message);
            assertFalse(message.equals(FALLBACK), code + " 仍落到兜底文案，用户无法判断该改什么");
        }
    }

    @Test
    void environmentFailuresDoNotSuggestRetry() {
        // 数据库与 Docker 的问题重试不会好，提示里不该出现「稍后重试」。
        for (String code : List.of("WORKSPACE_INIT_FAILED", "SANDBOX_UNAVAILABLE", "MODEL_CALL_FAILED")) {
            String message = UserFacingOutputHandler.errorMessage(code);
            assertFalse(message.contains("稍后重试"), code + " 不该建议稍后重试：" + message);
        }
    }

    private static void assertNotBlank(String value) {
        assertTrue(value != null && !value.isBlank(), "错误提示不能为空");
    }
}