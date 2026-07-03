package com.dbts.glyahhaigeneratecode.config;

import com.dbts.glyahhaigeneratecode.Listener.ai.StreamingChatModelDiagnosticsListener;
import com.dbts.glyahhaigeneratecode.monitor.AiModelMonitorListener;
import dev.langchain4j.model.chat.StreamingChatModel;
import dev.langchain4j.model.openai.OpenAiChatRequestParameters;
import dev.langchain4j.model.openai.OpenAiStreamingChatModel;
import lombok.RequiredArgsConstructor;
import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Scope;

import java.time.Duration;
import java.util.List;

@Configuration
@ConfigurationProperties(prefix = "langchain4j.open-ai.streaming-chat-model")
@Data
@RequiredArgsConstructor
public class StreamChatModelConfig {
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
    public StreamingChatModel prototypeStreamingChatModel() {
        // 显式设置 reasoningEffort 为 null，不启用 reasoning/reasoning_effort 参数。
        // qwen3.6-plus 不是 reasoning 模型（已在 application.yml 注释中说明），
        // 但 DashScope API 默认启用 thinking 导致推理阶段 delta.content 为空。
        // 此参数确保 API 不会将非 reasoning 模型当作 reasoning 模型对待。
        OpenAiChatRequestParameters defaultParams = OpenAiChatRequestParameters.builder()
                .reasoningEffort(null)
                .build();

        return OpenAiStreamingChatModel.builder()
                .defaultRequestParameters(defaultParams)
                .thinkingDisabled(true)
                .apiKey(apiKey)
                .baseUrl(baseUrl)
                .modelName(modelName)
                .maxTokens(maxTokens)
                .temperature(temperature)
                .timeout(Duration.ofMinutes(5))
                // 如果logRequests为空，则设置为true
                .logRequests(logRequests == null || logRequests.isBlank() ? true : Boolean.parseBoolean(logRequests))
                // 如果logResponses为空，则设置为false
                .logResponses(logResponses == null || logResponses.isBlank() ? false : Boolean.parseBoolean(logResponses))
                .listeners(List.of(
                        new StreamingChatModelDiagnosticsListener(maxTokens),
                        aiModelMonitorListener
                ))
                .build();
    }
}
