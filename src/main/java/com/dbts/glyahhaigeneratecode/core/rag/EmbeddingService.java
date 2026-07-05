package com.dbts.glyahhaigeneratecode.core.rag;

import cn.hutool.core.util.StrUtil;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.client.RestTemplate;

import java.util.List;
import java.util.Map;

/**
 * Embedding 服务：调用 OpenAI 兼容的 embedding API 将文本转为向量。
 * <p>降级：API 不可用或抛异常时记录 warn 日志并返回 null，调用方应跳过索引/检索。</p>
 */
@Slf4j
public class EmbeddingService {

    private final String baseUrl;
    private final String apiKey;
    private final String modelName;
    private final int dimension;
    private final RestTemplate restTemplate;
    private final ObjectMapper objectMapper;

    public EmbeddingService(String baseUrl, String apiKey, String modelName, int dimension,
                            RestTemplate restTemplate, ObjectMapper objectMapper) {
        this.baseUrl = baseUrl;
        this.apiKey = apiKey;
        this.modelName = modelName;
        this.dimension = dimension;
        this.restTemplate = restTemplate;
        this.objectMapper = objectMapper;
    }

    /**
     * 对单段文本生成 embedding 向量。
     *
     * @param text 文本
     * @return float 数组；失败或 blank 时返回 null
     */
    public float[] embed(String text) {
        if (StrUtil.isBlank(text)) {
            return null;
        }
        try {
            float[] result = callEmbeddingApi(List.of(text));
            if (result == null || result.length == 0) {
                return null;
            }
            return result;
        } catch (Exception e) {
            log.warn("embedding API 调用失败，返回 null，textLen={}", text.length(), e);
            return null;
        }
    }

    /**
     * 批量生成 embedding，每段文本独立调用；失败段返回 null。
     * <p>TODO: 若 API 支持 batch input，可改造 callEmbeddingApi 并发请求减少串行开销</p>
     */
    public float[][] embedBatch(List<String> texts) {
        if (texts == null || texts.isEmpty()) {
            return new float[0][];
        }
        float[][] results = new float[texts.size()][];
        for (int i = 0; i < texts.size(); i++) {
            results[i] = embed(texts.get(i));
        }
        return results;
    }

    /**
     * 调用 OpenAI 兼容的 embedding API。
     * <p>经由 {@link JsonNode} 直接解析，避免 {@code objectMapper.convertValue} 的类型擦除问题。</p>
     */
    private float[] callEmbeddingApi(List<String> inputs) throws Exception {
        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(MediaType.APPLICATION_JSON);
        headers.setBearerAuth(apiKey);

        Map<String, Object> body = Map.of(
                "model", modelName,
                "input", inputs.size() == 1 ? inputs.getFirst() : inputs
        );

        String url = baseUrl + (baseUrl.endsWith("/") ? "" : "/") + "embeddings";
        HttpEntity<Map<String, Object>> request = new HttpEntity<>(body, headers);
        ResponseEntity<String> response = restTemplate.postForEntity(url, request, String.class);

        if (!response.getStatusCode().is2xxSuccessful() || StrUtil.isBlank(response.getBody())) {
            log.warn("embedding API 返回非 2xx, status={}", response.getStatusCode());
            return null;
        }

        JsonNode root = objectMapper.readTree(response.getBody());
        JsonNode data = root.get("data");
        if (data == null || !data.isArray() || data.isEmpty()) {
            log.warn("embedding API 返回无 data 字段");
            return null;
        }

        // 取第一条结果的 embedding（单 input 场景）
        JsonNode first = data.get(0);
        JsonNode embeddingArr = first.get("embedding");
        if (embeddingArr == null || !embeddingArr.isArray()) {
            return null;
        }

        float[] embedding = new float[embeddingArr.size()];
        for (int i = 0; i < embeddingArr.size(); i++) {
            embedding[i] = embeddingArr.get(i).floatValue();
        }

        // 维度校验
        if (embedding.length != dimension) {
            log.warn("embedding 维度不匹配, expected={}, actual={}", dimension, embedding.length);
        }
        return embedding;
    }

    /**
     * 返回全零向量作为降级占位。
     */
    public float[] emptyVector() {
        return new float[dimension];
    }

    public int getDimension() {
        return dimension;
    }
}
