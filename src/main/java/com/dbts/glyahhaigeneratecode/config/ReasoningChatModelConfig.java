package com.dbts.glyahhaigeneratecode.config;

import com.dbts.glyahhaigeneratecode.monitor.AiModelMonitorListener;
import dev.langchain4j.model.chat.StreamingChatModel;
import dev.langchain4j.model.openai.OpenAiStreamingChatModel;
import lombok.Data;
import lombok.RequiredArgsConstructor;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Scope;

import java.time.Duration;
import java.util.List;

@Configuration
@ConfigurationProperties(prefix = "langchain4j.open-ai.reasoning-streaming-chat-model")
@Data
@RequiredArgsConstructor
public class ReasoningChatModelConfig {
    private final AiModelMonitorListener aiModelMonitorListener;

    private String apiKey;

    private String baseUrl;

    private String modelName;

    private Integer maxTokens;

    private Double temperature;

    private String logRequests;

    private String logResponses;

    @Bean
    // 返回多例的Bean
    @Scope("prototype")
    public StreamingChatModel prototypeReasoningChatModel() {
        return OpenAiStreamingChatModel.builder()
                .apiKey(apiKey)
                .baseUrl(baseUrl)
                .modelName(modelName)
                .maxTokens(maxTokens)
                .temperature(temperature)
                .timeout(Duration.ofMinutes(5))
                .logRequests(true)
                .logResponses(true)
                .listeners(List.of(aiModelMonitorListener))
                .build();
    }
}
