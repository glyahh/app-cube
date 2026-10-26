package com.dbts.glyahhaigeneratecode.projectimport;

import com.dbts.glyahhaigeneratecode.agent.AgentProperties;
import com.dbts.glyahhaigeneratecode.agent.AgentRunRequest;
import com.dbts.glyahhaigeneratecode.exception.MyException;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;

import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

class ImportAgentPermissionSettingsTest {
    private final ObjectMapper json = new ObjectMapper();

    @Test void catalogOnlyProjectsPublicMetadataAndRejectsInvalidCapabilities() throws Exception {
        var directory = json.readTree("""
                {"profiles":[{"id":"default","name":"主模型","provider":"OpenAI-compatible",
                "efforts":[],"configured":true,"apiKey":"synthetic-test-only","baseUrl":"http://internal.invalid"}]}
                """);
        var models = ImportAgentPermissionServiceImpl.projectModels(directory);
        String result = json.writeValueAsString(models);
        assertFalse(result.contains("apiKey"));
        assertFalse(result.contains("baseUrl"));
        assertFalse(result.contains("synthetic-test-only"));
        assertThrows(MyException.class, () -> ImportAgentPermissionServiceImpl.requireProfile(models, "missing", null));
        assertThrows(MyException.class, () -> ImportAgentPermissionServiceImpl.requireProfile(models, "default", "high"));
        assertDoesNotThrow(() -> ImportAgentPermissionServiceImpl.requireProfile(models, "default", null));
        assertThrows(MyException.class, () -> ImportAgentPermissionServiceImpl.projectModels(json.readTree("{\"profiles\":[]}")));
        ((com.fasterxml.jackson.databind.node.ObjectNode) directory.path("profiles").get(0)).putArray("efforts").add("extreme");
        assertThrows(MyException.class, () -> ImportAgentPermissionServiceImpl.projectModels(directory));
    }

    @Test void revisionAndFrozenFieldsKeepStringPrecisionAndNonImportContract() throws Exception {
        for (String value : List.of("", "01", "-1", "1.0", "9223372036854775808")) {
            assertThrows(MyException.class, () -> ImportAgentPermissionServiceImpl.revision(value));
        }
        assertEquals(Long.MAX_VALUE, ImportAgentPermissionServiceImpl.revision("9223372036854775807"));
        var plain = AgentRunRequest.builder().runId("201").build();
        assertFalse(json.readTree(plain.toFrameJson(json)).has("importModelProfileId"));
        var imported = plain.toBuilder().importTaskId("source").importPolicyRevision("9223372036854775807")
                .importPermissionMode("MANUAL").importModelProfileId("default").build();
        var frame = json.readTree(imported.toFrameJson(json));
        assertTrue(frame.path("importPolicyRevision").isTextual());
        assertEquals("default", frame.path("importModelProfileId").asText());
        assertTrue(frame.path("importThinkingEffort").isNull());
    }

    @com.dbts.glyahhaigeneratecode.ExternalTest
    void realConfiguredCatalogAndIndependentToolFreeReview() {
        var pool = new ImportAgentReviewPool(new AgentProperties(), json);
        var client = new ImportAgentModelClient(new AgentProperties(), json, pool);
        var catalog = ImportAgentPermissionServiceImpl.projectModels(client.catalog());
        assertTrue(catalog.stream().anyMatch(ImportAgentPermissionService.ModelVO::configured));
        String hash = "a".repeat(64);
        var action = json.createObjectNode().put("tool", "edit").put("path", "src/greeting.txt")
                .put("before", "hello").put("after", "hello world");
        com.fasterxml.jackson.databind.JsonNode review;
        try { review = client.review("ACTION", hash, action); }
        finally { pool.close(); }
        assertEquals(hash, review.path("objectHash").asText());
        assertTrue(List.of("ORDINARY", "HIGH").contains(review.path("risk").asText()));
        assertFalse(review.path("reason").asText().isBlank());
    }
}
