package com.dbts.glyahhaigeneratecode.core.rag;

import co.elastic.clients.elasticsearch.ElasticsearchClient;
import co.elastic.clients.json.jackson.JacksonJsonpMapper;
import co.elastic.clients.transport.ElasticsearchTransport;
import co.elastic.clients.transport.rest_client.RestClientTransport;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.apache.http.HttpHost;
import org.apache.http.auth.AuthScope;
import org.apache.http.auth.UsernamePasswordCredentials;
import org.apache.http.client.CredentialsProvider;
import org.apache.http.impl.client.BasicCredentialsProvider;
import org.elasticsearch.client.RestClient;
import org.elasticsearch.client.RestClientBuilder;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.web.client.RestTemplate;

import java.net.URI;

/**
 * RAG 模块 Spring 配置：ES 客户端、Embedding 服务、切块器。
 * <p>索引/检索见 service.impl 层；索引建表见 {@link RagElasticsearchIndexInitializer}。</p>
 */
@Slf4j
@Configuration
@RequiredArgsConstructor
public class RagConfiguration {

    private final RagProperties properties;

    /**
     * RAG的客户端
     */
    @Bean
    public RestTemplate ragRestTemplate() {
        return new RestTemplate();
    }

    /**
     * ElasticSearch 客户端
     */
    // 应用关闭  → 自动调用 client.close() → 释放连接 → 再销毁 Bean
    // 否则链接泄漏,端口无法释放的问题
    @Bean(destroyMethod = "close")
    public ElasticsearchClient elasticsearchClient() {
        String uri = properties.getElasticsearch().getUris();
        HttpHost httpHost = HttpHost.create(uri);

        RestClientBuilder builder = RestClient.builder(httpHost);

        // 设置超时
        builder.setRequestConfigCallback(c -> c
                .setConnectTimeout(properties.getElasticsearch().getConnectTimeoutMs())
                .setSocketTimeout(properties.getElasticsearch().getSocketTimeoutMs())
        );

        // 解析 URI 中的用户信息
        URI parsedUri = URI.create(uri);
        String userInfo = parsedUri.getUserInfo();
        if (userInfo != null && !userInfo.isEmpty()) {
            String[] parts = userInfo.split(":", 2);
            if (parts.length == 2) {
                CredentialsProvider credentialsProvider = new BasicCredentialsProvider();
                credentialsProvider.setCredentials(
                        AuthScope.ANY,
                        new UsernamePasswordCredentials(parts[0], parts[1])
                );
                builder.setHttpClientConfigCallback(hc -> hc
                        .setDefaultCredentialsProvider(credentialsProvider)
                );
            }
        }

        RestClient restClient = builder.build();
        ElasticsearchTransport transport = new RestClientTransport(restClient, new JacksonJsonpMapper());
        return new ElasticsearchClient(transport);
    }

    /** Embedding 服务 */
    @Bean
    public EmbeddingService embeddingService(RestTemplate ragRestTemplate, ObjectMapper objectMapper) {
        return new EmbeddingService(
                properties.getEmbedding().getBaseUrl(),
                properties.getEmbedding().getApiKey(),
                properties.getEmbedding().getModelName(),
                properties.getEmbedding().getDimension(),
                properties.getEmbedding().getBatchSize(),
                properties.getEmbedding().getBatchMaxRetries(),
                properties.getEmbedding().getRetryBaseDelayMs(),
                ragRestTemplate,
                objectMapper
        );
    }

    /** 源码切块器 */
    @Bean
    public CodeChunker codeChunker() {
        return new CodeChunker(
                properties.getChunk().getMaxLines(),
                properties.getChunk().getOverlapLines(),
                properties.getChunk().getMaxFileLines()
        );
    }

}
