package com.dbts.glyahhaigeneratecode.service.impl;

import com.dbts.glyahhaigeneratecode.agent.ModelConfigChangedEvent;
import com.dbts.glyahhaigeneratecode.exception.ErrorCode;
import com.dbts.glyahhaigeneratecode.exception.MyException;
import com.dbts.glyahhaigeneratecode.service.ModelConfigService;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.stereotype.Service;

import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.time.Duration;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

/** 实例级 BYOK 配置；模型元数据落 data/，密钥只写 .env。 */
@Slf4j
@Service
@RequiredArgsConstructor
public class ModelConfigServiceImpl implements ModelConfigService {

    private static final int DEFAULT_CONTEXT = 128_000;
    private static final int DEFAULT_OUTPUT = 8_192;
    // 与目录侧共用同一份定义：两处各写一遍时，加一档力度只改一处就会漂移。
    private static final java.util.Set<String> EFFORTS =
            java.util.Set.copyOf(com.dbts.glyahhaigeneratecode.agentauth.AgentModelCatalog.EFFORTS);
    private static final String ID_PATTERN = "[a-zA-Z0-9][a-zA-Z0-9_-]{0,63}";

    private final ObjectMapper json;
    private final ApplicationEventPublisher events;
    private final Object lock = new Object();

    @Override
    public ConfigVO config() {
        synchronized (lock) {
            return toVO(load(), readEnv());
        }
    }

    @Override
    public ConfigVO save(SaveDTO input) {
        synchronized (lock) {
            FileConfig config = load();
            Map<String, String> env = readEnv();
            validate(input);
            String id = input.id() == null ? "" : input.id().trim();
            StoredProvider existing = id.isEmpty()
                    ? config.providers.stream()
                            .filter(provider -> provider.name.equalsIgnoreCase(input.name().trim()))
                            .findFirst().orElse(null)
                    : find(config, id);
            if (!id.isEmpty() && existing == null && !id.matches(ID_PATTERN)) {
                throw new MyException(ErrorCode.PARAMS_ERROR, "模型 ID 格式无效。");
            }
            if (id.isEmpty()) {
                id = existing == null ? uniqueId(config, slug(input.name())) : existing.id;
            }

            Map<String, String> keys = new HashMap<>();
            for (StoredProvider item : config.providers) {
                keys.put(item.id, storedKey(config, item, env));
            }
            String supplied = input.apiKey() == null ? "" : input.apiKey().trim();
            if (!supplied.isEmpty()) {
                keys.put(id, supplied);
            } else {
                keys.putIfAbsent(id, "");
            }

            StoredProvider provider = existing == null ? new StoredProvider() : existing;
            List<String> models = selectedModels(input);
            if (models.isEmpty()) {
                throw new MyException(ErrorCode.PARAMS_ERROR, "至少选择一个模型。");
            }
            String defaultModel = input.model() == null || input.model().isBlank()
                    ? models.getFirst() : input.model().trim();
            if (!models.contains(defaultModel)) {
                throw new MyException(ErrorCode.PARAMS_ERROR, "默认模型必须已在勾选列表中。");
            }
            provider.id = id;
            provider.name = input.name().trim();
            provider.baseUrl = input.baseUrl().trim();
            provider.model = defaultModel;
            provider.models = models;
            provider.contextWindow = input.contextWindow() == null ? DEFAULT_CONTEXT : input.contextWindow();
            provider.maxOutputTokens = input.maxOutputTokens() == null ? DEFAULT_OUTPUT : input.maxOutputTokens();
            provider.efforts = input.efforts() == null ? new ArrayList<>() : new ArrayList<>(input.efforts());
            if (existing == null) {
                config.providers.add(provider);
            }

            if (Boolean.TRUE.equals(input.makeDefault()) || config.defaultId == null) {
                config.defaultId = id;
            }
            if (find(config, config.defaultId) == null) {
                config.defaultId = id;
            }
            long totalModels = config.providers.stream().mapToLong(item -> item.models.size()).sum();
            if (totalModels > 32) {
                throw new MyException(ErrorCode.PARAMS_ERROR, "所有提供商勾选的模型总数不能超过 32 个。");
            }
            if (keys.getOrDefault(config.defaultId, "").isBlank()) {
                throw new MyException(ErrorCode.PARAMS_ERROR, "默认模型需要填写 API Key。");
            }

            writeEnv(config, keys);
            saveFile(config);
            events.publishEvent(new ModelConfigChangedEvent());
            return toVO(config, readEnv());
        }
    }

    @Override
    public ConfigVO delete(String id) {
        synchronized (lock) {
            FileConfig config = load();
            Map<String, String> env = readEnv();
            StoredProvider provider = find(config, id);
            if (provider == null) {
                throw new MyException(ErrorCode.NOT_FOUND_ERROR, "模型不存在。");
            }
            if (id.equals(config.defaultId)) {
                if (config.providers.size() > 1) {
                    throw new MyException(ErrorCode.PARAMS_ERROR, "默认模型不能删除，请先切换默认模型。");
                }
                config.providers.remove(provider);
                config.defaultId = null;
                clearModelEnv();
                saveFile(config);
                events.publishEvent(new ModelConfigChangedEvent());
                return toVO(config, readEnv());
            }
            Map<String, String> keys = new HashMap<>();
            for (StoredProvider item : config.providers) {
                keys.put(item.id, storedKey(config, item, env));
            }
            config.providers.remove(provider);
            keys.remove(id);
            if (keys.getOrDefault(config.defaultId, "").isBlank()) {
                throw new MyException(ErrorCode.PARAMS_ERROR, "默认模型缺少 API Key，无法重新生成配置。");
            }
            writeEnv(config, keys);
            saveFile(config);
            events.publishEvent(new ModelConfigChangedEvent());
            return toVO(config, readEnv());
        }
    }

    @Override
    public ConfigVO setDefault(String id) {
        synchronized (lock) {
            FileConfig config = load();
            Map<String, String> env = readEnv();
            StoredProvider provider = find(config, id);
            if (provider == null) {
                throw new MyException(ErrorCode.NOT_FOUND_ERROR, "模型不存在。");
            }
            Map<String, String> keys = new HashMap<>();
            for (StoredProvider item : config.providers) {
                keys.put(item.id, storedKey(config, item, env));
            }
            if (keys.getOrDefault(id, "").isBlank()) {
                throw new MyException(ErrorCode.PARAMS_ERROR, "该模型还没有填写 API Key。");
            }
            config.defaultId = id;
            writeEnv(config, keys);
            saveFile(config);
            events.publishEvent(new ModelConfigChangedEvent());
            return toVO(config, readEnv());
        }
    }

    @Override
    public TestVO test(TestDTO input) {
        String baseUrl = input.baseUrl() == null ? "" : input.baseUrl().trim();
        String model = input.model() == null ? "" : input.model().trim();
        String key = input.apiKey() == null ? "" : input.apiKey().trim();
        if (key.isEmpty() && input.id() != null && !input.id().isBlank()) {
            FileConfig config = load();
            StoredProvider provider = find(config, input.id());
            if (provider != null) {
                key = storedKey(config, provider, readEnv());
                if (baseUrl.isEmpty()) {
                    baseUrl = provider.baseUrl;
                }
                if (model.isEmpty()) {
                    model = provider.model;
                }
            }
        }
        if (key.isEmpty()) {
            return new TestVO(false, "API Key 为空。");
        }
        if (!isHttpUrl(baseUrl) || model.isEmpty()) {
            return new TestVO(false, "Base URL 或模型 ID 无效。");
        }
        String url = baseUrl.replaceAll("/+$", "") + "/chat/completions";
        try {
            String body = json.writeValueAsString(Map.of(
                    "model", model,
                    "messages", List.of(Map.of("role", "user", "content", "ping")),
                    "max_tokens", 8,
                    "stream", false));
            HttpRequest.Builder builder = HttpRequest.newBuilder(URI.create(url))
                    .timeout(Duration.ofSeconds(20))
                    .header("Authorization", "Bearer " + key)
                    .header("Content-Type", "application/json");
            if (isOpencodeGo(baseUrl)) {
                builder.header("x-opencode-session", UUID.randomUUID().toString());
            }
            HttpRequest request = builder.POST(HttpRequest.BodyPublishers.ofString(body)).build();
            HttpClient client = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(10)).build();
            HttpResponse<String> response = client.send(request, HttpResponse.BodyHandlers.ofString());
            if (response.statusCode() >= 400) {
                return new TestVO(false, "HTTP " + response.statusCode() + "："
                        + safeSnippet(response.body(), key));
            }
            return new TestVO(true, "HTTP " + response.statusCode() + "，模型可访问。");
        } catch (IOException | InterruptedException error) {
            if (error instanceof InterruptedException) {
                Thread.currentThread().interrupt();
            }
            return new TestVO(false, "连接失败：" + error.getClass().getSimpleName());
        }
    }

    @Override
    public ModelsVO models(ModelsDTO input) {
        if (input == null) {
            return new ModelsVO(List.of(), "参数不能为空。");
        }
        String baseUrl = input.baseUrl() == null ? "" : input.baseUrl().trim();
        String key = input.apiKey() == null ? "" : input.apiKey().trim();
        if (input.id() != null && !input.id().isBlank()) {
            FileConfig config = load();
            StoredProvider provider = find(config, input.id());
            if (provider != null) {
                if (baseUrl.isEmpty()) {
                    baseUrl = provider.baseUrl;
                }
                if (key.isEmpty()) {
                    key = storedKey(config, provider, readEnv());
                }
            }
        }
        if (!isHttpUrl(baseUrl)) {
            return new ModelsVO(List.of(), "Base URL 必须是 http(s) 地址。");
        }
        List<String> fallback = fallbackModels(baseUrl);
        if (key.isEmpty()) {
            return new ModelsVO(fallback, fallback.isEmpty()
                    ? "API Key 为空，无法获取模型列表。" : "API Key 为空，已返回内置模型列表。");
        }
        try {
            String url = baseUrl.replaceAll("/+$", "") + "/models";
            HttpRequest.Builder builder = HttpRequest.newBuilder(URI.create(url))
                    .timeout(Duration.ofSeconds(15))
                    .header("Authorization", "Bearer " + key)
                    .GET();
            if (isOpencodeGo(baseUrl)) {
                builder.header("x-opencode-session", UUID.randomUUID().toString());
            }
            HttpResponse<String> response = HttpClient.newBuilder()
                    .connectTimeout(Duration.ofSeconds(10)).build()
                    .send(builder.build(), HttpResponse.BodyHandlers.ofString());
            if (response.statusCode() >= 400) {
                return new ModelsVO(fallback, "HTTP " + response.statusCode() + "："
                        + safeSnippet(response.body(), key));
            }
            JsonNode data = json.readTree(response.body()).path("data");
            List<String> models = new ArrayList<>();
            if (data.isArray()) {
                for (JsonNode item : data) {
                    String id = item.path("id").asText("");
                    if (!id.isBlank() && !models.contains(id)) {
                        models.add(id);
                    }
                }
            }
            if (models.isEmpty()) {
                return new ModelsVO(fallback, fallback.isEmpty()
                        ? "供应商没有返回可用模型。" : "供应商没有返回模型，已显示内置列表。");
            }
            return new ModelsVO(models, "已获取 " + models.size() + " 个模型。");
        } catch (IOException | InterruptedException error) {
            if (error instanceof InterruptedException) {
                Thread.currentThread().interrupt();
            }
            return new ModelsVO(fallback, fallback.isEmpty()
                    ? "获取模型列表失败：" + error.getClass().getSimpleName()
                    : "获取模型列表失败，已显示内置列表。");
        }
    }

    private static List<String> fallbackModels(String baseUrl) {
        if (baseUrl.contains("api.deepseek.com")) {
            return List.of("deepseek-chat", "deepseek-reasoner");
        }
        if (isOpencodeGo(baseUrl)) {
            return List.of("deepseek-v4.1-flash", "deepseek-v4-flash");
        }
        return List.of();
    }

    private static boolean isOpencodeGo(String baseUrl) {
        return baseUrl.toLowerCase(Locale.ROOT).contains("opencode.ai/inference/go");
    }

    private FileConfig load() {
        Path file = configFile();
        if (!Files.isRegularFile(file)) {
            FileConfig config = defaultConfig();
            saveFile(config);
            return config;
        }
        try {
            FileConfig config = json.readValue(file.toFile(), FileConfig.class);
            normalize(config);
            return config;
        } catch (IOException error) {
            log.warn("读取模型配置失败", error);
            throw new MyException(ErrorCode.OPERATION_ERROR, "模型配置读取失败，请检查 data/model-config.json。");
        }
    }

    private static FileConfig defaultConfig() {
        return new FileConfig();
    }

    private static void normalize(FileConfig config) {
        if (config.providers == null) {
            config.providers = new ArrayList<>();
        }
        if (config.providers.isEmpty()) {
            config.defaultId = null;
            return;
        }
        Set<String> ids = new HashSet<>();
        for (StoredProvider provider : config.providers) {
            if (provider.id == null || !provider.id.matches(ID_PATTERN) || !ids.add(provider.id)) {
                throw new MyException(ErrorCode.OPERATION_ERROR, "模型 ID 重复或格式无效。");
            }
            if (provider.efforts == null) {
                provider.efforts = new ArrayList<>();
            }
            List<String> models = new ArrayList<>();
            if (provider.models != null) {
                for (String model : provider.models) {
                    if (model != null && !model.trim().isEmpty() && !models.contains(model.trim())) {
                        models.add(model.trim());
                    }
                }
            }
            if (models.isEmpty() && provider.model != null && !provider.model.trim().isEmpty()) {
                models.add(provider.model.trim());
            }
            if (models.isEmpty()) {
                throw new MyException(ErrorCode.OPERATION_ERROR, "模型 " + provider.id + " 没有可用模型。");
            }
            if (provider.model == null || provider.model.trim().isEmpty()) {
                provider.model = models.getFirst();
            } else {
                provider.model = provider.model.trim();
                if (!models.contains(provider.model)) {
                    models.add(0, provider.model);
                }
            }
            provider.models = models;
        }
        if (config.defaultId == null || config.providers.stream().noneMatch(p -> p.id.equals(config.defaultId))) {
            config.defaultId = config.providers.getFirst().id;
        }
    }

    private ConfigVO toVO(FileConfig config, Map<String, String> env) {
        List<ProviderVO> providers = config.providers.stream()
                .map(provider -> new ProviderVO(provider.id, provider.name, provider.baseUrl, provider.model,
                        List.copyOf(provider.models), provider.contextWindow, provider.maxOutputTokens,
                        List.copyOf(provider.efforts), !storedKey(config, provider, env).isBlank(),
                        provider.id.equals(config.defaultId)))
                .toList();
        return new ConfigVO(config.defaultId, providers);
    }

    private static StoredProvider find(FileConfig config, String id) {
        return config.providers.stream().filter(provider -> provider.id.equals(id)).findFirst().orElse(null);
    }

    private static String storedKey(FileConfig config, StoredProvider provider, Map<String, String> env) {
        if (!provider.id.equals(config.defaultId)) {
            return env.getOrDefault(apiKeyEnv(provider), "").trim();
        }
        String currentModel = env.getOrDefault("AGENT_MODEL_ID", "").trim();
        String currentBase = env.getOrDefault("AGENT_MODEL_BASE_URL", "").trim();
        if (currentModel.equals(provider.model) && sameBase(currentBase, provider.baseUrl)) {
            String key = env.getOrDefault("AGENT_MODEL_API_KEY", "").trim();
            if (!key.isBlank()) {
                return key;
            }
        }
        String byok = env.getOrDefault(apiKeyEnv(provider), "").trim();
        return byok;
    }

    private void clearModelEnv() {
        Map<String, String> updates = new LinkedHashMap<>();
        for (String key : List.of(
                "AGENT_MODEL_ID",
                "AGENT_MODEL_BASE_URL",
                "AGENT_MODEL_API_KEY",
                "AGENT_CONTEXT_WINDOW",
                "AGENT_MAX_OUTPUT_TOKENS",
                "AGENT_MODEL_REQUEST_KWARGS_JSON",
                "AGENT_SUMMARIZER_MODEL_ID",
                "AGENT_SUMMARIZER_BASE_URL",
                "AGENT_SUMMARIZER_API_KEY",
                "AGENT_SUMMARIZER_CONTEXT_WINDOW",
                "AGENT_MODEL_PROFILES_JSON",
                "AGENT_REVIEW_MODEL_PROFILE_ID")) {
            updates.put(key, "");
        }
        writeEnvFile(updates);
    }

    private void writeEnv(FileConfig config, Map<String, String> keys) {
        StoredProvider defaultProvider = find(config, config.defaultId);
        String defaultKey = keys.getOrDefault(config.defaultId, "");
        Map<String, String> updates = new LinkedHashMap<>();
        updates.put("AGENT_MODEL_ID", defaultProvider.model);
        updates.put("AGENT_MODEL_BASE_URL", defaultProvider.baseUrl);
        updates.put("AGENT_MODEL_API_KEY", defaultKey);
        updates.put("AGENT_CONTEXT_WINDOW", Integer.toString(defaultProvider.contextWindow));
        updates.put("AGENT_MAX_OUTPUT_TOKENS", Integer.toString(defaultProvider.maxOutputTokens));
        try {
            updates.put("AGENT_MODEL_REQUEST_KWARGS_JSON",
                    json.writeValueAsString(Map.of("max_tokens", defaultProvider.maxOutputTokens)));
        } catch (IOException error) {
            throw new MyException(ErrorCode.OPERATION_ERROR, "生成模型请求参数失败。");
        }
        updates.put("AGENT_SUMMARIZER_MODEL_ID", defaultProvider.model);
        updates.put("AGENT_SUMMARIZER_BASE_URL", defaultProvider.baseUrl);
        updates.put("AGENT_SUMMARIZER_API_KEY", defaultKey);
        updates.put("AGENT_SUMMARIZER_CONTEXT_WINDOW", Integer.toString(defaultProvider.contextWindow));
        updates.put("AGENT_REVIEW_MODEL_PROFILE_ID", "default");

        List<Map<String, Object>> profiles = new ArrayList<>();
        profiles.add(defaultProfile(defaultProvider));
        Set<String> profileIds = new HashSet<>();
        profileIds.add("default");
        for (StoredProvider provider : config.providers) {
            boolean isDefault = provider.id.equals(config.defaultId);
            if (!isDefault) {
                updates.put(apiKeyEnv(provider), keys.getOrDefault(provider.id, ""));
                updates.put(baseUrlEnv(provider), provider.baseUrl);
            }
            for (String model : provider.models) {
                if (isDefault && model.equals(provider.model)) {
                    continue;
                }
                String profileId = profileId(provider, model, profileIds);
                profiles.add(modelProfile(profileId, provider, model, isDefault));
            }
        }
        try {
            updates.put("AGENT_MODEL_PROFILES_JSON", json.writeValueAsString(profiles));
        } catch (IOException error) {
            throw new MyException(ErrorCode.OPERATION_ERROR, "生成模型目录失败。");
        }
        writeEnvFile(updates);
    }

    private static Map<String, Object> defaultProfile(StoredProvider provider) {
        Map<String, Object> value = new LinkedHashMap<>();
        value.put("id", "default");
        value.put("name", provider.name);
        value.put("provider", provider.id);
        value.put("efforts", efforts(provider.efforts));
        return value;
    }

    private static Map<String, Object> modelProfile(String id, StoredProvider provider,
                                                     String model, boolean defaultProvider) {
        Map<String, Object> value = new LinkedHashMap<>();
        value.put("id", id);
        value.put("name", truncate(provider.name + " · " + model, 120));
        value.put("provider", provider.id);
        value.put("model", model);
        value.put("apiKeyEnv", defaultProvider ? "AGENT_MODEL_API_KEY" : apiKeyEnv(provider));
        value.put("baseUrlEnv", defaultProvider ? "AGENT_MODEL_BASE_URL" : baseUrlEnv(provider));
        value.put("contextWindow", provider.contextWindow);
        value.put("kwargs", Map.of("max_tokens", provider.maxOutputTokens));
        value.put("efforts", efforts(provider.efforts));
        return value;
    }

    private static String profileId(StoredProvider provider, String model, Set<String> used) {
        String base = slug(provider.id + "-" + model);
        String candidate = base;
        for (int index = 2; !used.add(candidate); index++) {
            candidate = base + "-" + index;
        }
        return candidate;
    }

    private static String truncate(String value, int max) {
        return value.length() <= max ? value : value.substring(0, max);
    }

    private static Map<String, Object> efforts(List<String> values) {
        Map<String, Object> result = new LinkedHashMap<>();
        for (String effort : values) {
            result.put(effort, Map.of("reasoning_effort", effort));
        }
        return result;
    }

    private void writeEnvFile(Map<String, String> updates) {
        Path envFile = repoRoot().resolve(".env");
        List<String> lines;
        try {
            lines = Files.exists(envFile) ? Files.readAllLines(envFile) : new ArrayList<>();
        } catch (IOException error) {
            throw new MyException(ErrorCode.OPERATION_ERROR, "读取 .env 失败，未修改模型配置。");
        }
        Map<String, String> pending = new LinkedHashMap<>(updates);
        List<String> output = new ArrayList<>();
        for (String line : lines) {
            String trimmed = line.trim();
            if (trimmed.isEmpty() || trimmed.startsWith("#") || !trimmed.contains("=")) {
                output.add(line);
                continue;
            }
            String key = trimmed.substring(0, trimmed.indexOf('=')).trim();
            if (pending.containsKey(key)) {
                output.add(key + "=" + pending.remove(key));
            } else if (!key.startsWith("BYOK_")) {
                output.add(line);
            }
        }
        pending.forEach((key, value) -> output.add(key + "=" + value));
        try {
            if (Files.exists(envFile)) {
                Files.copy(envFile, envFile.resolveSibling(".env.bak"), StandardCopyOption.REPLACE_EXISTING);
            }
            Path temp = Files.createTempFile(envFile.getParent(), ".env.", ".tmp");
            Files.write(temp, output);
            Files.move(temp, envFile, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
        } catch (IOException error) {
            throw new MyException(ErrorCode.OPERATION_ERROR, "写入 .env 失败，模型配置未生效。");
        }
    }

    private void saveFile(FileConfig config) {
        Path file = configFile();
        try {
            Files.createDirectories(file.getParent());
            Path temp = Files.createTempFile(file.getParent(), "model-config.", ".json");
            json.writerWithDefaultPrettyPrinter().writeValue(temp.toFile(), config);
            Files.move(temp, file, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
        } catch (IOException error) {
            throw new MyException(ErrorCode.OPERATION_ERROR, "保存模型配置失败。");
        }
    }

    private static Map<String, String> readEnv() {
        Path envFile = repoRoot().resolve(".env");
        Map<String, String> values = new HashMap<>();
        if (!Files.isRegularFile(envFile)) {
            return values;
        }
        try {
            for (String line : Files.readAllLines(envFile)) {
                String trimmed = line.trim();
                if (trimmed.isEmpty() || trimmed.startsWith("#") || !trimmed.contains("=")) {
                    continue;
                }
                int index = trimmed.indexOf('=');
                values.put(trimmed.substring(0, index).trim(), trimmed.substring(index + 1).trim());
            }
        } catch (IOException error) {
            log.warn("读取 .env 失败", error);
        }
        return values;
    }

    private static Path repoRoot() {
        return Path.of(System.getProperty("user.dir")).toAbsolutePath().normalize();
    }

    private static Path configFile() {
        return repoRoot().resolve("data/model-config.json");
    }

    private static String apiKeyEnv(StoredProvider provider) {
        return "BYOK_" + envId(provider.id) + "_API_KEY";
    }

    private static String baseUrlEnv(StoredProvider provider) {
        return "BYOK_" + envId(provider.id) + "_BASE_URL";
    }

    private static String envId(String id) {
        return id.toUpperCase(Locale.ROOT).replaceAll("[^A-Z0-9]+", "_");
    }

    private static boolean sameBase(String left, String right) {
        return left.trim().replaceAll("/+$", "").equals(right.trim().replaceAll("/+$", ""));
    }

    private static String slug(String name) {
        String value = name.toLowerCase(Locale.ROOT).replaceAll("[^a-z0-9]+", "-")
                .replaceAll("^-+|-+$", "");
        return value.isBlank() ? "provider" : value.substring(0, Math.min(value.length(), 48));
    }

    private static String uniqueId(FileConfig config, String base) {
        Set<String> ids = config.providers.stream().map(provider -> provider.id).collect(java.util.stream.Collectors.toSet());
        if (!ids.contains(base)) {
            return base;
        }
        for (int index = 2; ; index++) {
            String candidate = base + "-" + index;
            if (!ids.contains(candidate)) {
                return candidate;
            }
        }
    }

    private static void validate(SaveDTO input) {
        if (input == null) {
            throw new MyException(ErrorCode.PARAMS_ERROR, "模型配置不能为空。");
        }
        if (input.name() == null || input.name().trim().isEmpty() || input.name().length() > 120) {
            throw new MyException(ErrorCode.PARAMS_ERROR, "模型名称不能为空且不超过 120 字符。");
        }
        if (!isHttpUrl(input.baseUrl())) {
            throw new MyException(ErrorCode.PARAMS_ERROR, "Base URL 必须是 http(s) 地址。");
        }
        List<String> models = selectedModels(input);
        if (models.isEmpty()) {
            throw new MyException(ErrorCode.PARAMS_ERROR, "至少选择一个模型。");
        }
        if (models.size() > 100) {
            throw new MyException(ErrorCode.PARAMS_ERROR, "一次最多配置 100 个模型。");
        }
        for (String model : models) {
            if (model.length() > 256) {
                throw new MyException(ErrorCode.PARAMS_ERROR, "模型 ID 不能超过 256 字符。");
            }
        }
        if (input.model() != null && input.model().length() > 256) {
            throw new MyException(ErrorCode.PARAMS_ERROR, "模型 ID 不能超过 256 字符。");
        }
        int context = input.contextWindow() == null ? DEFAULT_CONTEXT : input.contextWindow();
        int output = input.maxOutputTokens() == null ? DEFAULT_OUTPUT : input.maxOutputTokens();
        if (context < 1024 || context > 100_000_000) {
            throw new MyException(ErrorCode.PARAMS_ERROR, "上下文窗口必须在 1024 到 100000000 之间。");
        }
        if (output < 1 || output > context) {
            throw new MyException(ErrorCode.PARAMS_ERROR, "最大输出必须大于 0 且不超过上下文窗口。");
        }
        if (input.efforts() != null) {
            for (String effort : input.efforts()) {
                if (!EFFORTS.contains(effort)) {
                    throw new MyException(ErrorCode.PARAMS_ERROR, "思考力度只能是 low / medium / high / xhigh / max。");
                }
            }
        }
    }

    private static List<String> selectedModels(SaveDTO input) {
        List<String> models = new ArrayList<>();
        if (input.models() != null) {
            for (String model : input.models()) {
                if (model != null && !model.trim().isEmpty() && !models.contains(model.trim())) {
                    models.add(model.trim());
                }
            }
        }
        if (models.isEmpty() && input.model() != null && !input.model().trim().isEmpty()) {
            models.add(input.model().trim());
        }
        return models;
    }

    private static boolean isHttpUrl(String value) {
        if (value == null || value.isBlank()) {
            return false;
        }
        try {
            URI uri = URI.create(value.trim());
            return ("http".equalsIgnoreCase(uri.getScheme()) || "https".equalsIgnoreCase(uri.getScheme()))
                    && uri.getHost() != null && uri.getUserInfo() == null
                    && uri.getQuery() == null && uri.getFragment() == null;
        } catch (IllegalArgumentException error) {
            return false;
        }
    }

    private static String safeSnippet(String value, String key) {
        if (value == null) {
            return "";
        }
        String masked = value.replace(key, "***");
        return masked.length() <= 300 ? masked : masked.substring(0, 300);
    }

    public static class FileConfig {
        public int version = 1;
        public String defaultId;
        public List<StoredProvider> providers = new ArrayList<>();

        public FileConfig() {
        }
    }

    public static class StoredProvider {
        public String id;
        public String name;
        public String baseUrl;
        public String model;
        public List<String> models = new ArrayList<>();
        public int contextWindow;
        public int maxOutputTokens;
        public List<String> efforts = new ArrayList<>();

        public StoredProvider() {
        }
    }
}
