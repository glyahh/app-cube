package com.dbts.glyahhaigeneratecode.core.rag;

import cn.hutool.core.util.StrUtil;
import com.dbts.glyahhaigeneratecode.core.rag.embedding.EmbeddingClient;
import com.dbts.glyahhaigeneratecode.core.rag.embedding.OpenAiCompatibleEmbeddingClient;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.extern.slf4j.Slf4j;
import org.springframework.web.client.RestTemplate;

import java.util.ArrayList;
import java.util.List;

/**
 * Embedding 应用服务，负责空输入过滤、批次划分、一次重试和失败降级。
 */
@Slf4j
public class EmbeddingService {

    private final int batchSize;
    private final int batchMaxRetries;
    private final int retryBaseDelayMs;
    private final EmbeddingClient embeddingClient;

    /**
     * 保留原有公开构造方式，兼容现有调用方和测试。
     */
    public EmbeddingService(String baseUrl, String apiKey, String modelName, int dimension,
                            int batchSize, int batchMaxRetries, int retryBaseDelayMs,
                            RestTemplate restTemplate, ObjectMapper objectMapper) {
        this(batchSize, batchMaxRetries, retryBaseDelayMs,
                new OpenAiCompatibleEmbeddingClient(
                        baseUrl, apiKey, modelName, dimension, restTemplate, objectMapper));
    }

    public EmbeddingService(int batchSize, int batchMaxRetries, int retryBaseDelayMs,
                            EmbeddingClient embeddingClient) {
        // 1. 修正批次与重试配置，保持原有最多补发一次的语义。
        this.batchSize = Math.max(1, batchSize);
        this.batchMaxRetries = batchMaxRetries > 0 ? 1 : 0;
        this.retryBaseDelayMs = Math.max(0, retryBaseDelayMs);

        // 2. 保存外部向量化客户端。
        this.embeddingClient = embeddingClient;
    }

    /**
     * 对单段文本生成向量，空文本或失败时返回 null。
     */
    public float[] embed(String text) {
        // 1. 空文本不调用外部 API。
        if (StrUtil.isBlank(text)) {
            return null;
        }

        // 2. 复用批量入口并返回首条结果。
        float[][] results = embedBatch(List.of(text));
        return results.length == 0 ? null : results[0];
    }

    /**
     * 批量生成向量，结果与输入等长同序，空文本或失败项为 null。
     */
    public float[][] embedBatch(List<String> texts) {
        // 1. 处理空输入，并创建与输入等长的结果数组。
        if (texts == null || texts.isEmpty()) {
            return new float[0][];
        }
        float[][] results = new float[texts.size()][];

        // 2. 过滤空文本，同时保留原始下标。
        List<IndexedText> validInputs = new ArrayList<>(texts.size());
        for (int i = 0; i < texts.size(); i++) {
            if (!StrUtil.isBlank(texts.get(i))) {
                validInputs.add(new IndexedText(i, texts.get(i)));
            }
        }

        // 3. 按配置大小分批调用客户端。
        for (int start = 0; start < validInputs.size(); start += batchSize) {
            int end = Math.min(start + batchSize, validInputs.size());
            processBatch(validInputs.subList(start, end), results);
        }
        return results;
    }

    private void processBatch(List<IndexedText> batch, float[][] results) {
        // 1. 提取当前批次文本。
        if (batch.isEmpty()) {
            return;
        }
        List<String> inputs = batch.stream().map(IndexedText::text).toList();

        try {
            // 2. 调用客户端，首次失败时按配置补发一次。
            float[][] batchResults = callEmbeddingClientWithRetry(inputs);

            // 3. 按原始下标写回总结果。
            for (int i = 0; i < batch.size(); i++) {
                results[batch.get(i).originalIndex()] = batchResults[i];
            }
        } catch (Exception e) {
            log.warn("embedding 批次调用失败，已降级为 null, batchSize={}, reason={}",
                    batch.size(), e.getMessage());
        }
    }

    private float[][] callEmbeddingClientWithRetry(List<String> inputs) throws Exception {
        int retries = 0;
        while (true) {
            try {
                // 1. 发送当前批次。
                return embeddingClient.embedBatch(inputs);
            } catch (Exception e) {
                // 2. 补发机会用完后交给上层执行 fail-soft。
                if (retries >= batchMaxRetries) {
                    throw e;
                }
                retries++;
                log.warn("embedding 批次调用失败，原批次补发一次, batchSize={}, delayMs={}",
                        inputs.size(), retryBaseDelayMs);

                // 3. 等待后原样补发，中断时不再请求。
                sleep(retryBaseDelayMs);
                if (Thread.currentThread().isInterrupted()) {
                    throw e;
                }
            }
        }
    }

    private static void sleep(long delayMs) {
        if (delayMs <= 0) {
            return;
        }
        try {
            Thread.sleep(delayMs);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }

    private record IndexedText(int originalIndex, String text) {
    }
}
