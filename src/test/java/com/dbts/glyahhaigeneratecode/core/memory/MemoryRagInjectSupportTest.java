package com.dbts.glyahhaigeneratecode.core.memory;

import com.dbts.glyahhaigeneratecode.model.enums.CodeGenTypeEnum;
import com.dbts.glyahhaigeneratecode.service.impl.CodeChunkRetrievalService;
import dev.langchain4j.data.message.ChatMessage;
import dev.langchain4j.data.message.SystemMessage;
import dev.langchain4j.data.message.UserMessage;
import dev.langchain4j.memory.chat.MessageWindowChatMemory;
import dev.langchain4j.store.memory.chat.ChatMemoryStore;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InOrder;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class MemoryRagInjectSupportTest {

    private static final Long APP_ID = 55_001L;
    private static final String OLD_INJECT = ConversationMemoryInjectTexts.INJECT_TAG + "\nold";
    private static final String NEW_INJECT = ConversationMemoryInjectTexts.INJECT_TAG + "\nnew";

    @Mock
    private CodeChunkRetrievalService retrievalService;
    @Mock
    private ChatMemoryStore chatMemoryStore;
    @Mock
    private ChatMemoryMessageUpdater messageUpdater;
    @Mock
    private MessageWindowChatMemory memory;

    private MemoryRagInjectSupport support;

    @BeforeEach
    void setUp() {
        support = new MemoryRagInjectSupport(retrievalService, chatMemoryStore, messageUpdater);
    }

    @Test
    void injectForQuery_processesEachQueryWithoutSharedPendingState() {
        when(retrievalService.searchTopK(APP_ID, "first", CodeGenTypeEnum.HTML)).thenReturn(List.of());
        when(retrievalService.searchTopK(APP_ID, "second", CodeGenTypeEnum.VUE)).thenReturn(List.of());
        when(chatMemoryStore.getMessages(APP_ID)).thenReturn(List.of());

        support.injectForQuery(APP_ID, "first", CodeGenTypeEnum.HTML, memory);
        support.injectForQuery(APP_ID, "second", CodeGenTypeEnum.VUE, memory);

        verify(retrievalService).searchTopK(APP_ID, "first", CodeGenTypeEnum.HTML);
        verify(retrievalService).searchTopK(APP_ID, "second", CodeGenTypeEnum.VUE);
    }

    @Test
    void injectForQuery_rejectsInvalidParameters() {
        support.injectForQuery(null, "query", CodeGenTypeEnum.VUE, memory);
        support.injectForQuery(0L, "query", CodeGenTypeEnum.VUE, memory);
        support.injectForQuery(APP_ID, " ", CodeGenTypeEnum.VUE, memory);
        support.injectForQuery(APP_ID, "query", null, memory);
        support.injectForQuery(APP_ID, "query", CodeGenTypeEnum.VUE, null);

        verifyNoInteractions(retrievalService, chatMemoryStore, messageUpdater);
    }

    @Test
    void injectForQuery_usesOriginalQueryAndTypeThenInsertsAfterMemoryTags() {
        CodeChunkRetrievalService.CodeChunkHit hit = hit();
        List<ChatMessage> stored = List.of(
                SystemMessage.from("[memory_file_note]\nfile"),
                SystemMessage.from(MemorySessionInjectSupport.SESSION_PREFIX + "\nstyle"),
                SystemMessage.from(OLD_INJECT),
                UserMessage.from("history")
        );
        when(retrievalService.searchTopK(APP_ID, "fix login", CodeGenTypeEnum.MULTI_FILE))
                .thenReturn(List.of(hit));
        when(retrievalService.formatAsInject(List.of(hit))).thenReturn(NEW_INJECT);
        when(chatMemoryStore.getMessages(APP_ID)).thenReturn(stored);
        support.injectForQuery(APP_ID, "fix login", CodeGenTypeEnum.MULTI_FILE, memory);

        ArgumentCaptor<List<ChatMessage>> captor = ArgumentCaptor.forClass(List.class);
        verify(messageUpdater).update(eq(memory), eq(APP_ID), captor.capture());
        List<ChatMessage> updated = captor.getValue();
        assertEquals(List.of(
                "[memory_file_note]\nfile",
                MemorySessionInjectSupport.SESSION_PREFIX + "\nstyle",
                NEW_INJECT,
                "history"
        ), updated.stream().map(MemoryRagInjectSupportTest::text).toList());
    }

    @Test
    void injectForQuery_noHitsRemovesOldInject() {
        when(retrievalService.searchTopK(APP_ID, "query", CodeGenTypeEnum.VUE)).thenReturn(List.of());
        when(chatMemoryStore.getMessages(APP_ID))
                .thenReturn(List.of(SystemMessage.from(OLD_INJECT), UserMessage.from("history")));
        support.injectForQuery(APP_ID, "query", CodeGenTypeEnum.VUE, memory);

        ArgumentCaptor<List<ChatMessage>> captor = ArgumentCaptor.forClass(List.class);
        verify(messageUpdater).update(eq(memory), eq(APP_ID), captor.capture());
        assertEquals(List.of("history"), captor.getValue().stream()
                .map(MemoryRagInjectSupportTest::text)
                .toList());
    }

    @Test
    void injectForQuery_blankFormattedTextRemovesOldInject() {
        CodeChunkRetrievalService.CodeChunkHit hit = hit();
        when(retrievalService.searchTopK(APP_ID, "query", CodeGenTypeEnum.VUE)).thenReturn(List.of(hit));
        when(retrievalService.formatAsInject(List.of(hit))).thenReturn(" ");
        when(chatMemoryStore.getMessages(APP_ID))
                .thenReturn(List.of(SystemMessage.from(OLD_INJECT), UserMessage.from("history")));
        support.injectForQuery(APP_ID, "query", CodeGenTypeEnum.VUE, memory);

        ArgumentCaptor<List<ChatMessage>> captor = ArgumentCaptor.forClass(List.class);
        verify(messageUpdater).update(eq(memory), eq(APP_ID), captor.capture());
        assertEquals(List.of("history"), captor.getValue().stream()
                .map(MemoryRagInjectSupportTest::text)
                .toList());
    }

    @Test
    void injectForQuery_storeReadFailureDoesNotWrite() {
        CodeChunkRetrievalService.CodeChunkHit hit = hit();
        when(retrievalService.searchTopK(APP_ID, "query", CodeGenTypeEnum.VUE)).thenReturn(List.of(hit));
        when(retrievalService.formatAsInject(List.of(hit))).thenReturn(NEW_INJECT);
        when(chatMemoryStore.getMessages(APP_ID)).thenThrow(new IllegalStateException("store unavailable"));
        support.injectForQuery(APP_ID, "query", CodeGenTypeEnum.VUE, memory);

        verifyNoInteractions(messageUpdater);
    }

    @Test
    void injectForQuery_retrievalFailureIsFailSoftAndClearsOldInject() {
        when(retrievalService.searchTopK(APP_ID, "query", CodeGenTypeEnum.VUE))
                .thenThrow(new IllegalStateException("ES unavailable"));
        when(chatMemoryStore.getMessages(APP_ID))
                .thenReturn(List.of(SystemMessage.from(OLD_INJECT), UserMessage.from("history")));

        support.injectForQuery(APP_ID, "query", CodeGenTypeEnum.VUE, memory);

        verify(retrievalService, times(1)).searchTopK(APP_ID, "query", CodeGenTypeEnum.VUE);
        verify(messageUpdater).update(eq(memory), eq(APP_ID), any());
    }

    @Test
    void injectForQuery_searchCompletesBeforeStoreReadAndWrite() {
        CodeChunkRetrievalService.CodeChunkHit hit = hit();
        when(retrievalService.searchTopK(APP_ID, "query", CodeGenTypeEnum.VUE)).thenReturn(List.of(hit));
        when(retrievalService.formatAsInject(List.of(hit))).thenReturn(NEW_INJECT);
        when(chatMemoryStore.getMessages(APP_ID)).thenReturn(new ArrayList<>());
        support.injectForQuery(APP_ID, "query", CodeGenTypeEnum.VUE, memory);

        InOrder order = inOrder(retrievalService, chatMemoryStore, messageUpdater);
        order.verify(retrievalService).searchTopK(APP_ID, "query", CodeGenTypeEnum.VUE);
        order.verify(retrievalService).formatAsInject(List.of(hit));
        order.verify(chatMemoryStore).getMessages(APP_ID);
        order.verify(messageUpdater).update(eq(memory), eq(APP_ID), any());
    }

    private static CodeChunkRetrievalService.CodeChunkHit hit() {
        return new CodeChunkRetrievalService.CodeChunkHit(
                "src/App.vue", 0, "content", "hash", "vue", 1, 5, 0.9f);
    }

    private static String text(ChatMessage message) {
        if (message instanceof SystemMessage systemMessage) {
            return systemMessage.text();
        }
        return ((UserMessage) message).singleText();
    }
}
