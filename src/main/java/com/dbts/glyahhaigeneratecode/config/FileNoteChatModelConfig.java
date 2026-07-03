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
 * 工具写盘后批量 fileNote 摘要专用 {@link ChatModel}。
 */
@Configuration
@ConfigurationProperties(prefix = "langchain4j.open-ai.file-note-chat-model")
@Data
@RequiredArgsConstructor
public class FileNoteChatModelConfig {
    private final AiModelMonitorListener aiModelMonitorListener;

    private String baseUrl;

    private String apiKey;

    private String modelName;

    private Integer maxTokens = 2048;

    private Double temperature = 0.2;

    private Boolean logRequests = false;

    private Boolean logResponses = false;

    @Bean
    public ChatModel fileNoteChatModel() {
        return OpenAiChatModel.builder()
                .apiKey(apiKey)
                .modelName(modelName)
                .baseUrl(baseUrl)
                .maxTokens(maxTokens)
                .temperature(temperature) // 温度系数：0=严谨，1=随机创意
                .customHeaders(Map.of("X-DashScope-EnableThinking", "false")) // 关闭通义千问的“思考过程”返回
                .logRequests(logRequests)
                .logResponses(logResponses)
                .listeners(List.of(aiModelMonitorListener))
                .build();
    }
}
