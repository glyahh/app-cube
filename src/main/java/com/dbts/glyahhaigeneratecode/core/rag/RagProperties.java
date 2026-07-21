package com.dbts.glyahhaigeneratecode.core.rag;

import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.context.annotation.Configuration;

/** 工程记忆 RAG，绑定 {@code rag.*}。 */
@Data
@ConfigurationProperties(prefix = "rag")
@Configuration
public class RagProperties {

    private boolean enabled = true;
    private Elasticsearch elasticsearch = new Elasticsearch();
    private Embedding embedding = new Embedding();
    private Chunk chunk = new Chunk();
    private Retrieval retrieval = new Retrieval();

    @Data
    public static class Elasticsearch {
        private String uris = "http://localhost:9201";
        private String indexName = "code_chunks_v4";
        private int connectTimeoutMs = 3000;
        private int socketTimeoutMs = 10000;
    }

    @Data
    public static class Embedding {
        private String baseUrl;
        private String apiKey;
        private String modelName = "text-embedding-v4";
        private int dimension = 1024;
    }

    @Data
    public static class Chunk {
        private int maxLines = 200;
        private int overlapLines = 20;
        private int maxFileLines = 5000;
    }

    @Data
    public static class Retrieval {
        private int topK = 5;
        private int charBudget = 8000;
    }
}
