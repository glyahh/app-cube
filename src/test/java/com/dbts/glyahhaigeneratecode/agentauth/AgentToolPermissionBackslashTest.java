package com.dbts.glyahhaigeneratecode.agentauth;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Windows 反斜杠路径必须先归一化再判风险。
 *
 * <p>实际执行侧在 Windows 上会用 `src\pom.xml` 这种写法；
 * 只按 `/` 切分会把文件名整段当成目录部分，高风险判定被绕过。
 */
class AgentToolPermissionBackslashTest {
    private final ObjectMapper json = new ObjectMapper();

    private boolean high(String path) throws Exception {
        return AgentToolPermissionService.highRisk(
                json.createObjectNode().put("tool", "write").put("path", path));
    }

    @Test
    void backslashConfigIsStillHigh() throws Exception {
        assertTrue(high("src\\pom.xml"));
        assertTrue(high("conf\\application.yml"));
    }

    @Test
    void backslashHiddenIsStillHigh() throws Exception {
        assertTrue(high("sub\\.env"));
        assertTrue(high("sub\\.github\\workflow.yml"));
    }
}