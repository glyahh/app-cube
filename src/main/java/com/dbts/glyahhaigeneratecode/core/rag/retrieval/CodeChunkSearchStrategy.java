package com.dbts.glyahhaigeneratecode.core.rag.retrieval;

import com.dbts.glyahhaigeneratecode.core.rag.model.RagCodeChunkCandidate;

import java.io.IOException;
import java.util.List;

/**
 * 代码块检索策略，负责把业务查询转换为候选结果。
 */
public interface CodeChunkSearchStrategy {

    List<RagCodeChunkCandidate> search(Long appId, String userQuery) throws IOException;
}
