package com.dbts.glyahhaigeneratecode.ai;

import dev.langchain4j.data.message.AiMessage;
import dev.langchain4j.data.message.UserMessage;
import dev.langchain4j.memory.chat.MessageWindowChatMemory;
import dev.langchain4j.model.chat.ChatModel;
import dev.langchain4j.model.chat.StreamingChatModel;
import dev.langchain4j.model.chat.request.ChatRequest;
import dev.langchain4j.model.chat.response.ChatResponse;
import dev.langchain4j.model.chat.response.StreamingChatResponseHandler;
import dev.langchain4j.service.AiServices;
import dev.langchain4j.service.TokenStream;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.fail;
import static org.mockito.Mockito.mock;

class AiCodeGeneratorUserMessageBindingTest {

    private static final long APP_ID = 1001L;

    @ParameterizedTest
    @ValueSource(strings = {
            "<template><p>{{ item }}</p></template>",
            "<template><p>{{ user.name }}</p></template>",
            "const config = { theme: 'dark', nested: { enabled: true } };",
            "modify the page title"
    })
    void vueModifyMessageTreatsSourceCodeAsLiteralText(String sourceCode) {
        CapturingStreamingChatModel streamingModel = new CapturingStreamingChatModel();
        aiCodeGeneratorService service = AiServices.builder(aiCodeGeneratorService.class)
                .chatModel(mock(ChatModel.class))
                .streamingChatModel(streamingModel)
                .chatMemoryProvider(memoryId -> MessageWindowChatMemory.withMaxMessages(20))
                .build();

        TokenStream tokenStream = assertDoesNotThrow(
                () -> service.modifyCodeVueFileStream(APP_ID, sourceCode));
        tokenStream
                .onPartialResponse(ignored -> { })
                .onError(error -> fail("Unexpected streaming error", error))
                .onCompleteResponse(ignored -> { })
                .start();

        ChatRequest request = streamingModel.lastRequest.get();
        assertNotNull(request);
        UserMessage userMessage = request.messages().stream()
                .filter(UserMessage.class::isInstance)
                .map(UserMessage.class::cast)
                .reduce((first, second) -> second)
                .orElseThrow();
        assertEquals(sourceCode, userMessage.singleText());
    }

    private static final class CapturingStreamingChatModel implements StreamingChatModel {

        private final AtomicReference<ChatRequest> lastRequest = new AtomicReference<>();

        @Override
        public void doChat(ChatRequest chatRequest, StreamingChatResponseHandler handler) {
            lastRequest.set(chatRequest);
            handler.onCompleteResponse(ChatResponse.builder()
                    .aiMessage(AiMessage.from("ok"))
                    .build());
        }
    }
}
