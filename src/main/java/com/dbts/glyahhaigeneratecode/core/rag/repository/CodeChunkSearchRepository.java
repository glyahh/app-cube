package com.dbts.glyahhaigeneratecode.core.rag.repository;

import com.dbts.glyahhaigeneratecode.core.rag.model.RagCodeChunkCandidate;

import java.io.IOException;
import java.util.List;

/**
 * 代码块候选检索仓储，隔离检索应用层与 Elasticsearch 客户端细节。
 */
public interface CodeChunkSearchRepository {

    List<RagCodeChunkCandidate> search(CodeChunkSearchCriteria criteria) throws IOException;
}
