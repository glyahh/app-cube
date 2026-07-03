package com.dbts.glyahhaigeneratecode.config;

import com.dbts.glyahhaigeneratecode.monitor.AiModelMonitorListener;
import dev.langchain4j.model.chat.ChatModel;
import dev.langchain4j.model.openai.OpenAiChatModel;
import lombok.Data;
import lombok.RequiredArgsConstructor;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.util.List;
import java.util.Map;

/**
 * 工作流「代码质检」专用同步 {@link ChatModel}：与主 {@code openAiChatModel} 解耦，
 * 默认使用更快的小模型与较低输出上限，降低二次质检 HTTP 耗时（见计划 qc-latency）。
 */
@Configuration
@ConfigurationProperties(prefix = "langchain4j.open-ai.code-exam-chat-model")
@Data
@RequiredArgsConstructor
public class CodeExamChatModelConfig {
    private final AiModelMonitorListener aiModelMonitorListener;

    private String baseUrl;

    private String apiKey;

    private String modelName;

    /** 质检仅需短 JSON，不宜过大以免拖慢首 token */
    private Integer maxTokens = 2048;

    private Double temperature = 0.1;

    private Boolean logRequests = false;

    private Boolean logResponses = false;

    @Bean
    public ChatModel codeExamChatModel() {
        return OpenAiChatModel.builder()
                .apiKey(apiKey)
                .modelName(modelName)
                .baseUrl(baseUrl)
                .maxTokens(maxTokens)
                .temperature(temperature)
                .customHeaders(Map.of("X-DashScope-EnableThinking", "false"))
                .logRequests(logRequests)
                .logResponses(logResponses)
                .listeners(List.of(aiModelMonitorListener))
                .build();
    }
}
