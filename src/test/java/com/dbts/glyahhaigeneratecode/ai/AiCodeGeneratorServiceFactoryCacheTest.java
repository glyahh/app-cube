package com.dbts.glyahhaigeneratecode.ai;

import cn.hutool.json.JSONObject;
import com.dbts.glyahhaigeneratecode.ai.tool.BaseTool;
import com.dbts.glyahhaigeneratecode.ai.tool.ToolManager;
import com.dbts.glyahhaigeneratecode.config.OpenAiOutputGuardrailsConfig;
import com.dbts.glyahhaigeneratecode.core.memory.ChatMemoryMessageUpdater;
import com.dbts.glyahhaigeneratecode.core.memory.MemoryRagInjectSupport;
import com.dbts.glyahhaigeneratecode.model.enums.CodeGenTypeEnum;
import com.dbts.glyahhaigeneratecode.service.ChatHistoryService;
import dev.langchain4j.agent.tool.P;
import dev.langchain4j.agent.tool.Tool;
import dev.langchain4j.data.message.UserMessage;
import dev.langchain4j.model.chat.ChatModel;
import dev.langchain4j.model.chat.StreamingChatModel;
import dev.langchain4j.store.memory.chat.ChatMemoryStore;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.context.ApplicationContext;
import org.springframework.test.util.ReflectionTestUtils;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNotSame;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * 回归：首轮 ephemeral service 不得污染 Caffeine；后续轮次应命中 full-tool 缓存实例。
 */
@ExtendWith(MockitoExtension.class)
class AiCodeGeneratorServiceFactoryCacheTest {

    private static final Long APP_ID = 42_001L;

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
    private MemoryRagInjectSupport memoryRagInjectSupport;
    @Mock
    private ChatMemoryMessageUpdater chatMemoryMessageUpdater;

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
        ReflectionTestUtils.setField(factory, "memoryRagInjectSupport", memoryRagInjectSupport);
        ReflectionTestUtils.setField(factory, "chatMemoryMessageUpdater", chatMemoryMessageUpdater);

        lenient().when(chatMemoryStore.getMessages(anyLong()))
                .thenReturn(List.of(UserMessage.from("cached")));
        lenient().when(chatHistoryService.getAiVisibleMemoryVersion(anyLong()))
                .thenReturn("v1");
        lenient().when(chatHistoryService.loadConversationMemoryStateAndInject(
                anyLong(), any(), anyInt(), any(CodeGenTypeEnum.class))).thenReturn(0);
        lenient().when(applicationContext.getBean(eq("prototypeReasoningChatModel"), eq(StreamingChatModel.class)))
                .thenReturn(streamingChatModel);
        lenient().when(applicationContext.getBean(eq("prototypeStreamingChatModel"), eq(StreamingChatModel.class)))
                .thenReturn(streamingChatModel);

        WriteFileStubTool writeFileTool = new WriteFileStubTool();
        ReadFileStubTool readFileTool = new ReadFileStubTool();
        lenient().when(toolManager.getWriteFileOnlyTools()).thenReturn(new BaseTool[]{writeFileTool});
        lenient().when(toolManager.getAllTools()).thenReturn(new BaseTool[]{writeFileTool, readFileTool});
        lenient().when(toolManager.getHtmlMultiEditTools()).thenReturn(new BaseTool[]{readFileTool});
    }

    @Test
    void firstRoundVueServiceMustNotEnterCache_secondRoundUsesCachedFullToolService() {
        aiCodeGeneratorService firstRound = factory.getAiCodeGeneratorService(APP_ID, CodeGenTypeEnum.VUE, true);
        assertNotNull(firstRound);
        assertNull(factory.getCachedServiceForTest(APP_ID, CodeGenTypeEnum.VUE));

        aiCodeGeneratorService secondRound = factory.getAiCodeGeneratorService(APP_ID, CodeGenTypeEnum.VUE, false);
        assertNotNull(secondRound);
        assertNotSame(firstRound, secondRound);
        assertSame(secondRound, factory.getCachedServiceForTest(APP_ID, CodeGenTypeEnum.VUE));

        aiCodeGeneratorService thirdRound = factory.getAiCodeGeneratorService(APP_ID, CodeGenTypeEnum.VUE, false);
        assertSame(secondRound, thirdRound);
    }

    @Test
    void cachedServiceWithSameMemoryVersionReusesInstanceWithoutReloadingHistory() {
        aiCodeGeneratorService secondRound = factory.getAiCodeGeneratorService(APP_ID, CodeGenTypeEnum.VUE, false);
        aiCodeGeneratorService thirdRound = factory.getAiCodeGeneratorService(APP_ID, CodeGenTypeEnum.VUE, false);

        assertSame(secondRound, thirdRound);
        verify(chatHistoryService, times(2)).getAiVisibleMemoryVersion(APP_ID);
        verify(chatHistoryService, times(1)).loadConversationMemoryStateAndInject(
                eq(APP_ID), any(), anyInt(), eq(CodeGenTypeEnum.VUE));
    }

    @Test
    void cachedServiceWithChangedMemoryVersionRebuildsMemoryAndRefreshesBindingVersion() {
        when(chatHistoryService.getAiVisibleMemoryVersion(APP_ID))
                .thenReturn("v1")
                .thenReturn("v2")
                .thenReturn("v2");

        aiCodeGeneratorService secondRound = factory.getAiCodeGeneratorService(APP_ID, CodeGenTypeEnum.VUE, false);
        aiCodeGeneratorService thirdRound = factory.getAiCodeGeneratorService(APP_ID, CodeGenTypeEnum.VUE, false);
        aiCodeGeneratorService fourthRound = factory.getAiCodeGeneratorService(APP_ID, CodeGenTypeEnum.VUE, false);

        assertNotSame(secondRound, thirdRound);
        assertSame(thirdRound, fourthRound);
        verify(chatHistoryService, times(2)).loadConversationMemoryStateAndInject(
                eq(APP_ID), any(), anyInt(), eq(CodeGenTypeEnum.VUE));
    }

    @Test
    void cachedServiceWithEmptyRedisStillInvalidatesAndRebuilds() {
        when(chatMemoryStore.getMessages(APP_ID))
                .thenReturn(List.of())
                .thenReturn(List.of(UserMessage.from("rebuilt")));

        aiCodeGeneratorService secondRound = factory.getAiCodeGeneratorService(APP_ID, CodeGenTypeEnum.HTML, false);
        aiCodeGeneratorService thirdRound = factory.getAiCodeGeneratorService(APP_ID, CodeGenTypeEnum.HTML, false);

        assertNotSame(secondRound, thirdRound);
        verify(chatHistoryService, times(2)).loadConversationMemoryStateAndInject(
                eq(APP_ID), any(), anyInt(), eq(CodeGenTypeEnum.HTML));
    }

    @Test
    void firstRoundHtmlServiceMustNotEnterCache_secondRoundUsesCachedEditToolService() {
        aiCodeGeneratorService firstRound = factory.getAiCodeGeneratorService(APP_ID, CodeGenTypeEnum.HTML, true);
        assertNotNull(firstRound);
        assertNull(factory.getCachedServiceForTest(APP_ID, CodeGenTypeEnum.HTML));

        aiCodeGeneratorService secondRound = factory.getAiCodeGeneratorService(APP_ID, CodeGenTypeEnum.HTML, false);
        assertNotNull(secondRound);
        assertNotSame(firstRound, secondRound);
        assertSame(secondRound, factory.getCachedServiceForTest(APP_ID, CodeGenTypeEnum.HTML));
    }

    @Test
    void firstRoundMultiFileServiceMustNotEnterCache_secondRoundUsesCachedEditToolService() {
        aiCodeGeneratorService firstRound = factory.getAiCodeGeneratorService(APP_ID, CodeGenTypeEnum.MULTI_FILE, true);
        assertNotNull(firstRound);
        assertNull(factory.getCachedServiceForTest(APP_ID, CodeGenTypeEnum.MULTI_FILE));

        aiCodeGeneratorService secondRound = factory.getAiCodeGeneratorService(APP_ID, CodeGenTypeEnum.MULTI_FILE, false);
        assertNotNull(secondRound);
        assertNotSame(firstRound, secondRound);
        assertSame(secondRound, factory.getCachedServiceForTest(APP_ID, CodeGenTypeEnum.MULTI_FILE));
    }

    /** LangChain4j 构建 service 所需的最小 @Tool 桩（每个工具独立类，避免 tool 名冲突） */
    static class WriteFileStubTool extends BaseTool {
        @Override
        public String getToolName() {
            return "writeFile";
        }

        @Override
        public String getDisplayName() {
            return "writeFile";
        }

        @Tool("writeFile")
        public String writeFile(@P("path") String path) {
            return path;
        }

        @Override
        public String generateToolExecutedResult(JSONObject arguments) {
            return "writeFile";
        }
    }

    static class ReadFileStubTool extends BaseTool {
        @Override
        public String getToolName() {
            return "readFile";
        }

        @Override
        public String getDisplayName() {
            return "readFile";
        }

        @Tool("readFile")
        public String readFile(@P("path") String path) {
            return path;
        }

        @Override
        public String generateToolExecutedResult(JSONObject arguments) {
            return "readFile";
        }
    }
}
