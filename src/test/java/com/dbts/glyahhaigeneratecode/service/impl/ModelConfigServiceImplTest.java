package com.dbts.glyahhaigeneratecode.service.impl;

import com.dbts.glyahhaigeneratecode.exception.MyException;
import com.dbts.glyahhaigeneratecode.service.ModelConfigService.ConfigVO;
import com.dbts.glyahhaigeneratecode.service.ModelConfigService.SaveDTO;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Comparator;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ModelConfigServiceImplTest {

    @Test
    void emptyConfigAndFirstProviderWriteEnv() throws IOException {
        String original = System.getProperty("user.dir");
        Path root = Files.createTempDirectory("model-config");
        try {
            System.setProperty("user.dir", root.toString());
            ModelConfigServiceImpl service = new ModelConfigServiceImpl(new ObjectMapper(), event -> { });

            ConfigVO initial = service.config();
            assertNull(initial.defaultId());
            assertTrue(initial.providers().isEmpty());

            ConfigVO saved = service.save(new SaveDTO(null, "My Provider",
                    "https://api.example.com/v1", "my-model", List.of("my-model"), "sk-test",
                    128_000, 8_192, List.of("low", "high"), true));
            assertEquals("my-provider", saved.defaultId());
            assertTrue(saved.providers().getFirst().configured());

            String env = Files.readString(root.resolve(".env"));
            assertTrue(env.contains("AGENT_MODEL_ID=my-model"));
            assertTrue(env.contains("AGENT_MODEL_BASE_URL=https://api.example.com/v1"));
            assertTrue(env.contains("AGENT_MODEL_API_KEY=sk-test"));
            assertTrue(env.contains("AGENT_CONTEXT_WINDOW=128000"));
            assertTrue(env.contains("AGENT_MODEL_REQUEST_KWARGS_JSON={\"max_tokens\":8192}"));
            JsonNode profiles = new ObjectMapper().readTree(
                    Files.readAllLines(root.resolve(".env")).stream()
                            .filter(line -> line.startsWith("AGENT_MODEL_PROFILES_JSON="))
                            .findFirst().orElseThrow()
                            .substring("AGENT_MODEL_PROFILES_JSON=".length()));
            assertEquals("default", profiles.get(0).path("id").asText());
            assertEquals("low", profiles.get(0).path("efforts").fieldNames().next());
        } finally {
            System.setProperty("user.dir", original);
            cleanup(root);
        }
    }

    @Test
    void modelListFallbackForCommonProviders() throws IOException {
        String original = System.getProperty("user.dir");
        Path root = Files.createTempDirectory("model-config-models");
        try {
            System.setProperty("user.dir", root.toString());
            ModelConfigServiceImpl service = new ModelConfigServiceImpl(new ObjectMapper(), event -> { });
            var deepseek = service.models(new com.dbts.glyahhaigeneratecode.service.ModelConfigService.ModelsDTO(
                    null, "https://api.deepseek.com", ""));
            assertTrue(deepseek.models().contains("deepseek-chat"));
            var go = service.models(new com.dbts.glyahhaigeneratecode.service.ModelConfigService.ModelsDTO(
                    null, "https://opencode.ai/inference/go/v1", ""));
            assertTrue(go.models().contains("deepseek-v4.1-flash"));
        } finally {
            System.setProperty("user.dir", original);
            cleanup(root);
        }
    }

    @Test
    void multipleSelectedModelsCreateProfiles() throws IOException {
        String original = System.getProperty("user.dir");
        Path root = Files.createTempDirectory("model-config-multi");
        try {
            System.setProperty("user.dir", root.toString());
            ModelConfigServiceImpl service = new ModelConfigServiceImpl(new ObjectMapper(), event -> { });
            ConfigVO saved = service.save(new SaveDTO(null, "Multi", "https://api.example.com/v1",
                    "model-a", List.of("model-a", "model-b"), "sk-multi",
                    128_000, 8_192, List.of("low"), true));
            assertEquals(List.of("model-a", "model-b"), saved.providers().getFirst().models());

            JsonNode profiles = new ObjectMapper().readTree(
                    Files.readAllLines(root.resolve(".env")).stream()
                            .filter(line -> line.startsWith("AGENT_MODEL_PROFILES_JSON="))
                            .findFirst().orElseThrow()
                            .substring("AGENT_MODEL_PROFILES_JSON=".length()));
            assertEquals(2, profiles.size());
            JsonNode extra = profiles.get(1);
            assertEquals("model-b", extra.path("model").asText());
            assertEquals("AGENT_MODEL_API_KEY", extra.path("apiKeyEnv").asText());
            assertEquals("AGENT_MODEL_BASE_URL", extra.path("baseUrlEnv").asText());
        } finally {
            System.setProperty("user.dir", original);
            cleanup(root);
        }
    }

    @Test
    void extraProviderAndDefaultSwitchKeepKeysInEnv() throws IOException {
        String original = System.getProperty("user.dir");
        Path root = Files.createTempDirectory("model-config-extra");
        try {
            System.setProperty("user.dir", root.toString());
            ModelConfigServiceImpl service = new ModelConfigServiceImpl(new ObjectMapper(), event -> { });
            service.save(new SaveDTO(null, "Primary", "https://api.example.com/v1",
                    "primary-model", List.of("primary-model"), "sk-primary", 128_000, 8_192, List.of("low"), true));
            ConfigVO added = service.save(new SaveDTO(null, "Secondary", "https://api.example.com/v1",
                    "secondary-model", List.of("secondary-model"), "sk-secondary", 128_000, 8_192, List.of("low"), false));
            assertEquals(2, added.providers().size());
            assertEquals("primary", added.defaultId());
            String env = Files.readString(root.resolve(".env"));
            assertTrue(env.contains("BYOK_SECONDARY_API_KEY=sk-secondary"));

            ConfigVO switched = service.setDefault("secondary");
            assertEquals("secondary", switched.defaultId());
            env = Files.readString(root.resolve(".env"));
            assertTrue(env.contains("AGENT_MODEL_ID=secondary-model"));
            assertTrue(env.contains("AGENT_MODEL_API_KEY=sk-secondary"));

            service.setDefault("primary");
            service.delete("secondary");
            env = Files.readString(root.resolve(".env"));
            assertFalse(env.contains("BYOK_SECONDARY"));
        } finally {
            System.setProperty("user.dir", original);
            cleanup(root);
        }
    }

    @Test
    void deletingLastProviderClearsModelEnv() throws IOException {
        String original = System.getProperty("user.dir");
        Path root = Files.createTempDirectory("model-config-delete");
        try {
            System.setProperty("user.dir", root.toString());
            ModelConfigServiceImpl service = new ModelConfigServiceImpl(new ObjectMapper(), event -> { });
            service.save(new SaveDTO(null, "Only", "https://api.example.com/v1",
                    "only-model", List.of("only-model"), "sk-only", 128_000, 8_192, List.of(), true));
            ConfigVO empty = service.delete("only");
            assertNull(empty.defaultId());
            assertTrue(empty.providers().isEmpty());
            String env = Files.readString(root.resolve(".env"));
            assertTrue(env.contains("AGENT_MODEL_ID="));
            assertTrue(env.contains("AGENT_MODEL_API_KEY="));
            assertTrue(env.contains("AGENT_MODEL_PROFILES_JSON="));
        } finally {
            System.setProperty("user.dir", original);
            cleanup(root);
        }
    }

    @Test
    void invalidURLIsRejected() throws IOException {
        String original = System.getProperty("user.dir");
        Path root = Files.createTempDirectory("model-config-invalid");
        try {
            System.setProperty("user.dir", root.toString());
            ModelConfigServiceImpl service = new ModelConfigServiceImpl(new ObjectMapper(), event -> { });
            assertThrows(MyException.class, () -> service.save(new SaveDTO(null, "Bad",
                    "ftp://example.com", "model", List.of("model"), "sk", 1000, 100, List.of(), true)));
        } finally {
            System.setProperty("user.dir", original);
            cleanup(root);
        }
    }

    private static void cleanup(Path root) throws IOException {
        try (var paths = Files.walk(root)) {
            paths.sorted(Comparator.reverseOrder()).forEach(path -> {
                try {
                    Files.deleteIfExists(path);
                } catch (IOException ignored) {
                    // 涓存椂鐩綍娓呯悊澶辫触涓嶅奖鍝嶆柇瑷€
                }
            });
        }
    }
}
