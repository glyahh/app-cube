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

import java.util.ArrayList;
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
    private final int batchSize;
    private final int batchMaxRetries;
    private final int retryBaseDelayMs;
    private final RestTemplate restTemplate;
    private final ObjectMapper objectMapper;

    public EmbeddingService(String baseUrl, String apiKey, String modelName, int dimension,
                            int batchSize, int batchMaxRetries, int retryBaseDelayMs,
                            RestTemplate restTemplate, ObjectMapper objectMapper) {
        // 1. 保存 API 和模型配置。
        this.baseUrl = baseUrl;
        this.apiKey = apiKey;
        this.modelName = modelName;
        this.dimension = dimension;

        // 2. 修正非法配置值。
        this.batchSize = Math.max(1, batchSize);
        // 最多补发一次，0 表示不补发。
        this.batchMaxRetries = batchMaxRetries > 0 ? 1 : 0;
        this.retryBaseDelayMs = Math.max(0, retryBaseDelayMs);

        // 3. 保存请求与 JSON 处理组件。
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
        // 1. 空文本不调用 API。
        if (StrUtil.isBlank(text)) {
            return null;
        }

        // 2. 复用批量接口生成单条向量。
        float[][] results = embedBatch(List.of(text));
        return results.length == 0 ? null : results[0];
    }

    /**
     * 批量生成 embedding。返回数组与输入等长、同序，空文本或失败项为 null。
     */
    public float[][] embedBatch(List<String> texts) {
        // 1. 处理空输入，并创建与输入等长的结果数组。
        if (texts == null || texts.isEmpty()) {
            return new float[0][];
        }

        float[][] results = new float[texts.size()][];

        // 2. 过滤空文本，同时保留原下标。
        List<IndexedText> validInputs = new ArrayList<>(texts.size());
        for (int i = 0; i < texts.size(); i++) {
            if (!StrUtil.isBlank(texts.get(i))) {
                validInputs.add(new IndexedText(i, texts.get(i)));
            }
        }

        // 3. 按配置大小分批调用 API。
        for (int start = 0; start < validInputs.size(); start += batchSize) {
            int end = Math.min(start + batchSize, validInputs.size());
            processBatch(validInputs.subList(start, end), results);
        }
        return results;
    }

    /** 处理一批文本；请求失败时保留 null，让主流程继续执行。 */
    private void processBatch(List<IndexedText> batch, float[][] results) {
        // 1. 跳过空批次，并提取本批次文本。
        if (batch.isEmpty()) {
            return;
        }

        List<String> inputs = batch.stream().map(IndexedText::text).toList();
        try {
            // 2. 调用 API，首次失败时补发一次。
            float[][] batchResults = callEmbeddingApiWithRetry(inputs);

            // 3. 按原下标写回总结果。
            for (int i = 0; i < batch.size(); i++) {
                results[batch.get(i).originalIndex()] = batchResults[i];
            }
        } catch (Exception e) {
            // 失败批次保持 null，不阻断主流程。
            log.warn("embedding 批次两次调用均失败，已降级为 null, batchSize={}, reason={}",
                    batch.size(), e.getMessage());
        }
    }

    /** 首次请求失败后，使用相同输入再请求一次。 */
    private float[][] callEmbeddingApiWithRetry(List<String> inputs) throws Exception {
        int retries = 0;
        while (true) {
            try {
                // 1. 发送当前批次。
                return callEmbeddingApi(inputs);
            } catch (Exception e) {
                // 2. 补发机会用完后抛给上层降级。
                if (retries >= batchMaxRetries) {
                    throw e;
                }
                retries++;
                log.warn("embedding 批次调用失败，原批次补发一次, batchSize={}, delayMs={}",
                        inputs.size(), retryBaseDelayMs);

                // 3. 等待后原样补发。
                sleep(retryBaseDelayMs);
                if (Thread.currentThread().isInterrupted()) {
                    // 线程已中断，不再补发。
                    throw e;
                }
            }
        }
    }

    /** 调用 embedding API，并把返回的 JSON 转换为向量。 */
    private float[][] callEmbeddingApi(List<String> inputs) throws Exception {
        // 1. 组装请求头和请求体。
        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(MediaType.APPLICATION_JSON);
        headers.setBearerAuth(apiKey);

        Map<String, Object> body = Map.of(
                "model", modelName,
                "input", inputs
        );

        // 2. 发送 embedding 请求。
        String url = baseUrl + (baseUrl.endsWith("/") ? "" : "/") + "embeddings";
        HttpEntity<Map<String, Object>> request = new HttpEntity<>(body, headers);
        ResponseEntity<String> response = restTemplate.postForEntity(url, request, String.class);

        // 3. 校验 HTTP 响应。
        if (!response.getStatusCode().is2xxSuccessful() || StrUtil.isBlank(response.getBody())) {
            throw new IllegalStateException("embedding API 返回无效响应, status=" + response.getStatusCode());
        }

        // 4. 解析响应中的 data 数组。
        JsonNode root = objectMapper.readTree(response.getBody());
        JsonNode data = root.get("data");
        if (data == null || !data.isArray() || data.isEmpty()) {
            throw new IllegalArgumentException("embedding API 返回无有效 data 字段");
        }

        // 5. 按响应 index 恢复输入顺序。
        float[][] results = new float[inputs.size()][];
        boolean[] seen = new boolean[inputs.size()];
        int resolvedCount = 0;
        for (JsonNode item : data) {
            // 无效响应项直接跳过。
            JsonNode indexNode = item.get("index");
            JsonNode embeddingArr = item.get("embedding");
            if (indexNode == null || !indexNode.canConvertToInt()
                    || embeddingArr == null || !embeddingArr.isArray()) {
                log.warn("忽略格式无效的 embedding 响应项");
                continue;
            }

            int index = indexNode.intValue();
            if (index < 0 || index >= inputs.size() || seen[index]) {
                log.warn("忽略越界或重复的 embedding index, index={}, inputSize={}", index, inputs.size());
                continue;
            }
            seen[index] = true;

            // 向量维度必须与 ES mapping 一致。
            if (embeddingArr.size() != dimension) {
                log.warn("embedding 维度不匹配, index={}, expected={}, actual={}",
                        index, dimension, embeddingArr.size());
                continue;
            }

            float[] embedding = toVector(embeddingArr, index);
            if (embedding == null) {
                continue;
            }

            results[index] = embedding;
            resolvedCount++;
        }

        // 6. 记录缺失项，成功结果仍可继续使用。
        if (resolvedCount != inputs.size()) {
            log.warn("embedding 批次响应不完整, expected={}, resolved={}, responseItems={}",
                    inputs.size(), resolvedCount, data.size());
        }
        return results;
    }

    /** 将 JSON 数组转换为向量；任一元素非数值时拒绝整条结果，避免静默写入错误的零值。 */
    private float[] toVector(JsonNode embeddingArray, int responseIndex) {
        // 1. 将 JSON 数组转换为固定维度的 float[]。
        float[] vector = new float[dimension];
        for (int i = 0; i < dimension; i++) {
            JsonNode value = embeddingArray.get(i);
            // 出现非数值元素时整条向量作废。
            if (value == null || !value.isNumber()) {
                log.warn("embedding 包含非数值元素, index={}, dimensionIndex={}", responseIndex, i);
                return null;
            }
            vector[i] = value.floatValue();
        }
        return vector;
    }

    private static void sleep(long delayMs) {
        // 1. 无需等待时直接返回。
        if (delayMs <= 0) {
            return;
        }
        try {
            // 2. 等待后再补发请求。
            Thread.sleep(delayMs);
        } catch (InterruptedException e) {
            // 保留中断状态交给上层处理。
            Thread.currentThread().interrupt();
        }
    }

    /** 保存有效文本及其在原输入列表中的位置。 */
    private record IndexedText(int originalIndex, String text) {
    }

}
