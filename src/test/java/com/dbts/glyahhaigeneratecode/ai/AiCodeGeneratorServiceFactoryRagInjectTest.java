package com.dbts.glyahhaigeneratecode.ai;

import cn.hutool.json.JSONObject;
import com.dbts.glyahhaigeneratecode.ai.tool.BaseTool;
import com.dbts.glyahhaigeneratecode.ai.tool.ToolManager;
import com.dbts.glyahhaigeneratecode.config.OpenAiOutputGuardrailsConfig;
import com.dbts.glyahhaigeneratecode.core.memory.ChatMemoryMessageUpdater;
import com.dbts.glyahhaigeneratecode.core.memory.ConversationMemoryInjectTexts;
import com.dbts.glyahhaigeneratecode.core.memory.MemoryRagInjectSupport;
import com.dbts.glyahhaigeneratecode.core.memory.MemorySessionInjectSupport;
import com.dbts.glyahhaigeneratecode.model.enums.CodeGenTypeEnum;
import com.dbts.glyahhaigeneratecode.service.ChatHistoryService;
import com.dbts.glyahhaigeneratecode.service.impl.CodeChunkRetrievalService;
import dev.langchain4j.agent.tool.P;
import dev.langchain4j.agent.tool.Tool;
import dev.langchain4j.data.message.ChatMessage;
import dev.langchain4j.data.message.SystemMessage;
import dev.langchain4j.data.message.UserMessage;
import dev.langchain4j.model.chat.ChatModel;
import dev.langchain4j.model.chat.StreamingChatModel;
import dev.langchain4j.memory.chat.MessageWindowChatMemory;
import dev.langchain4j.store.memory.chat.ChatMemoryStore;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.context.ApplicationContext;
import org.springframework.test.util.ReflectionTestUtils;

import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class AiCodeGeneratorServiceFactoryRagInjectTest {

    private static final Long APP_ID = 55_001L;

    @Mock
    private ChatModel chatModel;
    @Mock
    private ChatMemoryStore chatMemoryStore;
    @Mock
    private ChatHistoryService chatHistoryService;
    @Mock
    private ToolManager toolManager;
    @Mock
    private ApplicationContext applicationContext;
    @Mock
    private OpenAiOutputGuardrailsConfig outputGuardrailsConfig;
    @Mock
    private StreamingChatModel streamingChatModel;
    @Mock
    private CodeChunkRetrievalService codeChunkRetrievalService;

    private MemoryRagInjectSupport memoryRagInjectSupport;
    private ChatMemoryMessageUpdater messageUpdater;

    private aiCodeGeneratorServiceFactory factory;

    @BeforeEach
    void setUp() {
        factory = new aiCodeGeneratorServiceFactory();
        ReflectionTestUtils.setField(factory, "chatModel", chatModel);
        ReflectionTestUtils.setField(factory, "chatMemoryStore", chatMemoryStore);
        ReflectionTestUtils.setField(factory, "chatHistoryService", chatHistoryService);
        ReflectionTestUtils.setField(factory, "toolManager", toolManager);
        ReflectionTestUtils.setField(factory, "applicationContext", applicationContext);
        ReflectionTestUtils.setField(factory, "outputGuardrailsConfig", outputGuardrailsConfig);
        messageUpdater = new ChatMemoryMessageUpdater(chatMemoryStore);
        memoryRagInjectSupport = new MemoryRagInjectSupport(
                codeChunkRetrievalService, chatMemoryStore, messageUpdater);
        ReflectionTestUtils.setField(factory, "memoryRagInjectSupport", memoryRagInjectSupport);
        ReflectionTestUtils.setField(factory, "chatMemoryMessageUpdater", messageUpdater);

        lenient().when(chatHistoryService.getAiVisibleMemoryVersion(anyLong())).thenReturn("v1");
        lenient().when(chatHistoryService.loadConversationMemoryStateAndInject(
                anyLong(), any(), anyInt(), any(CodeGenTypeEnum.class))).thenReturn(0);
        lenient().when(applicationContext.getBean(eq("prototypeReasoningChatModel"), eq(StreamingChatModel.class)))
                .thenReturn(streamingChatModel);
        lenient().when(applicationContext.getBean(eq("prototypeStreamingChatModel"), eq(StreamingChatModel.class)))
                .thenReturn(streamingChatModel);

        AiCodeGeneratorServiceFactoryCacheTest.WriteFileStubTool writeFileTool =
                new AiCodeGeneratorServiceFactoryCacheTest.WriteFileStubTool();
        lenient().when(toolManager.getWriteFileOnlyTools()).thenReturn(new BaseTool[]{writeFileTool});
        lenient().when(toolManager.getAllTools()).thenReturn(new BaseTool[]{writeFileTool});
        lenient().when(toolManager.getHtmlMultiEditTools()).thenReturn(new BaseTool[]{writeFileTool});
    }

    @Test
    void findInjectInsertPosition_insertsAfterSessionStyle() {
        List<ChatMessage> msgs = new ArrayList<>();
        msgs.add(SystemMessage.from("[memory_file_note]\npath=a"));
        msgs.add(SystemMessage.from(MemorySessionInjectSupport.SESSION_PREFIX + "\nstyle"));
        msgs.add(UserMessage.from("hi"));

        int pos = aiCodeGeneratorServiceFactory.findInjectInsertPosition(msgs);

        assertEquals(2, pos);
    }

    @Test
    void fourArgumentEntry_injectsExplicitQueryIntoPreparedMemory() {
        MemoryRagInjectSupport support = mock(MemoryRagInjectSupport.class);
        aiCodeGeneratorServiceFactory delegatingFactory = new aiCodeGeneratorServiceFactory();
        ReflectionTestUtils.setField(delegatingFactory, "memoryRagInjectSupport", support);
        ReflectionTestUtils.setField(delegatingFactory, "chatMemoryStore", chatMemoryStore);
        ReflectionTestUtils.setField(delegatingFactory, "chatHistoryService", chatHistoryService);
        ReflectionTestUtils.setField(delegatingFactory, "toolManager", toolManager);
        ReflectionTestUtils.setField(delegatingFactory, "applicationContext", applicationContext);
        ReflectionTestUtils.setField(delegatingFactory, "chatModel", chatModel);

        delegatingFactory.getAiCodeGeneratorService(APP_ID, CodeGenTypeEnum.HTML, false, "raw user query");

        verify(support).injectForQuery(eq(APP_ID), eq("raw user query"), eq(CodeGenTypeEnum.HTML), any());
    }

    @Test
    void explicitQuery_thenPrepareChatMemory_injectsMemoryInjectTag() {
        List<ChatMessage> store = new ArrayList<>();
        store.add(SystemMessage.from("[memory_file_note]\nnote"));
        when(chatMemoryStore.getMessages(APP_ID)).thenAnswer(inv -> new ArrayList<>(store));
        doAnswer(inv -> {
            store.clear();
            store.addAll(inv.getArgument(1));
            return null;
        }).when(chatMemoryStore).updateMessages(eq(APP_ID), any());

        when(codeChunkRetrievalService.searchTopK(APP_ID, "fix login bug", CodeGenTypeEnum.VUE))
                .thenReturn(List.of(new CodeChunkRetrievalService.CodeChunkHit(
                        "src/App.vue", 0, "export default {}", "hash", "vue", 1, 5, 0.9f)));
        when(codeChunkRetrievalService.formatAsInject(any()))
                .thenReturn(ConversationMemoryInjectTexts.INJECT_TAG + "\nchunk body");

        factory.getAiCodeGeneratorService(APP_ID, CodeGenTypeEnum.VUE, false, "fix login bug");

        boolean hasInject = store.stream()
                .filter(SystemMessage.class::isInstance)
                .map(msg -> ((SystemMessage) msg).text())
                .anyMatch(text -> text != null && text.startsWith(ConversationMemoryInjectTexts.INJECT_TAG));
        assertTrue(hasInject);
        verify(codeChunkRetrievalService).searchTopK(APP_ID, "fix login bug", CodeGenTypeEnum.VUE);
    }

    @Test
    void explicitQuery_cacheHit_injectsCurrentQuery() {
        when(chatMemoryStore.getMessages(APP_ID)).thenReturn(new ArrayList<>(List.of(UserMessage.from("cached"))));

        factory.getAiCodeGeneratorService(APP_ID, CodeGenTypeEnum.VUE, false);

        when(codeChunkRetrievalService.searchTopK(APP_ID, "second round", CodeGenTypeEnum.VUE))
                .thenReturn(List.of());
        factory.getAiCodeGeneratorService(APP_ID, CodeGenTypeEnum.VUE, false, "second round");

        verify(codeChunkRetrievalService).searchTopK(APP_ID, "second round", CodeGenTypeEnum.VUE);
    }
}
