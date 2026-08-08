package com.dbts.glyahhaigeneratecode.core.rag.embedding;

import java.util.List;

/**
 * 单批文本向量化客户端边界。异常由应用服务统一重试和降级。
 */
@FunctionalInterface
public interface EmbeddingClient {

    float[][] embedBatch(List<String> inputs) throws Exception;
}
