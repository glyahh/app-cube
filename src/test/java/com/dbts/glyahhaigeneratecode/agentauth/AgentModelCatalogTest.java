package com.dbts.glyahhaigeneratecode.agentauth;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 目录解析必须把真实模型名透出，且配置项名称与模型名不能混用。
 * 界面显示的是 model，不是 name。
 */
class AgentModelCatalogTest {
    private final ObjectMapper json = new ObjectMapper();

    @Test
    void exposesRealModelNameAndEfforts() throws Exception {
        // 与 Python core.model_profiles 的 safe() 输出保持一致。
        var directory = json.readTree("""
                {"profiles":[{"id":"default","name":"OpenCode Go","model":"deepseek-v4.1-flash",
                  "provider":"opencode-go","efforts":["low","medium","high"],"configured":true}]}
                """);

        List<AgentModelCatalog.Model> models = AgentModelCatalog.parse(directory);

        assertEquals(1, models.size());
        var model = models.getFirst();
        assertEquals("default", model.id());
        assertEquals("OpenCode Go", model.name());
        assertEquals("deepseek-v4.1-flash", model.model());
        assertEquals(List.of("low", "medium", "high"), model.efforts());
        assertTrue(model.configured());
    }

    @Test
    void fallsBackToEmptyModelForLegacyDirectory() throws Exception {
        var directory = json.readTree("""
                {"profiles":[{"id":"legacy","name":"legacy-config","provider":"p","efforts":[],"configured":true}]}
                """);

        var model = AgentModelCatalog.parse(directory).getFirst();

        assertEquals("", model.model());
        assertEquals("legacy-config", model.name());
    }
}