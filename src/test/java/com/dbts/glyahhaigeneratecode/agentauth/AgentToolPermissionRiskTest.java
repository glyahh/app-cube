package com.dbts.glyahhaigeneratecode.agentauth;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** 高风险归类决定是否必须问人；规则只做底线，不替代审查。 */
class AgentToolPermissionRiskTest {
    private final ObjectMapper json = new ObjectMapper();

    private boolean high(String tool, String path) throws Exception {
        var action = json.createObjectNode().put("tool", tool).put("path", path);
        return AgentToolPermissionService.highRisk(action);
    }

    @Test
    void deleteAndUploadAreAlwaysHigh() throws Exception {
        assertTrue(high("delete", "src/a.js"));
        assertTrue(high("upload_files", "src/a.js"));
    }

    @Test
    void configAndScriptFilesAreHigh() throws Exception {
        assertTrue(high("write", "pom.xml"));
        assertTrue(high("write", "package.json"));
        assertTrue(high("write", "application.yml"));
        assertTrue(high("write", "README.md"));
        assertTrue(high("write", "scripts/run.sh"));
        assertTrue(high("write", ".github/workflow.yml"));
    }

    @Test
    void ordinarySourceIsNotHigh() throws Exception {
        assertFalse(high("write", "src/main.js"));
        assertFalse(high("edit", "src/components/App.vue"));
    }
}