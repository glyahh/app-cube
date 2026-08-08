package com.dbts.glyahhaigeneratecode.core.rag.embedding;

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
 * OpenAI 兼容 Embedding API 适配器，负责请求组装、响应解析和向量维度校验。
 */
@Slf4j
public class OpenAiCompatibleEmbeddingClient implements EmbeddingClient {

    private final String baseUrl;
    private final String apiKey;
    private final String modelName;
    private final int dimension;
    private final RestTemplate restTemplate;
    private final ObjectMapper objectMapper;

    public OpenAiCompatibleEmbeddingClient(String baseUrl, String apiKey, String modelName, int dimension,
                                           RestTemplate restTemplate, ObjectMapper objectMapper) {
        this.baseUrl = baseUrl;
        this.apiKey = apiKey;
        this.modelName = modelName;
        this.dimension = dimension;
        this.restTemplate = restTemplate;
        this.objectMapper = objectMapper;
    }

    @Override
    public float[][] embedBatch(List<String> inputs) throws Exception {
        // 1. 组装 OpenAI 兼容请求。
        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(MediaType.APPLICATION_JSON);
        headers.setBearerAuth(apiKey);
        Map<String, Object> body = Map.of("model", modelName, "input", inputs);
        String url = baseUrl + (baseUrl.endsWith("/") ? "" : "/") + "embeddings";

        // 2. 发送请求并校验 HTTP 响应。
        HttpEntity<Map<String, Object>> request = new HttpEntity<>(body, headers);
        ResponseEntity<String> response = restTemplate.postForEntity(url, request, String.class);
        if (!response.getStatusCode().is2xxSuccessful() || StrUtil.isBlank(response.getBody())) {
            throw new IllegalStateException("embedding API 返回无效响应, status=" + response.getStatusCode());
        }

        // 3. 解析响应数据，按响应 index 恢复输入顺序。
        JsonNode root = objectMapper.readTree(response.getBody());
        JsonNode data = root.get("data");
        if (data == null || !data.isArray() || data.isEmpty()) {
            throw new IllegalArgumentException("embedding API 返回无有效 data 字段");
        }

        float[][] results = new float[inputs.size()][];
        boolean[] seen = new boolean[inputs.size()];
        int resolvedCount = 0;
        for (JsonNode item : data) {
            JsonNode indexNode = item.get("index");
            JsonNode embeddingArray = item.get("embedding");
            if (indexNode == null || !indexNode.canConvertToInt()
                    || embeddingArray == null || !embeddingArray.isArray()) {
                log.warn("忽略格式无效的 embedding 响应项");
                continue;
            }

            int index = indexNode.intValue();
            if (index < 0 || index >= inputs.size() || seen[index]) {
                log.warn("忽略越界或重复的 embedding index, index={}, inputSize={}", index, inputs.size());
                continue;
            }
            seen[index] = true;

            if (embeddingArray.size() != dimension) {
                log.warn("embedding 维度不匹配, index={}, expected={}, actual={}",
                        index, dimension, embeddingArray.size());
                continue;
            }

            float[] vector = toVector(embeddingArray, index);
            if (vector != null) {
                results[index] = vector;
                resolvedCount++;
            }
        }

        // 响应不完整时保留已成功向量，其余位置保持 null。
        if (resolvedCount != inputs.size()) {
            log.warn("embedding 批次响应不完整, expected={}, resolved={}, responseItems={}",
                    inputs.size(), resolvedCount, data.size());
        }
        return results;
    }

    private float[] toVector(JsonNode embeddingArray, int responseIndex) {
        // 1. 将 JSON 数组转换为固定维度向量。
        float[] vector = new float[dimension];
        for (int i = 0; i < dimension; i++) {
            JsonNode value = embeddingArray.get(i);
            if (value == null || !value.isNumber()) {
                log.warn("embedding 包含非数值元素, index={}, dimensionIndex={}", responseIndex, i);
                return null;
            }
            vector[i] = value.floatValue();
        }
        return vector;
    }
}
