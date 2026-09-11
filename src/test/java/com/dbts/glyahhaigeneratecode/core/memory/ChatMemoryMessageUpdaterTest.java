package com.dbts.glyahhaigeneratecode.core.memory;

import dev.langchain4j.data.message.ChatMessage;
import dev.langchain4j.data.message.SystemMessage;
import dev.langchain4j.data.message.UserMessage;
import dev.langchain4j.memory.chat.MessageWindowChatMemory;
import dev.langchain4j.store.memory.chat.ChatMemoryStore;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;

class ChatMemoryMessageUpdaterTest {

    private static final Long APP_ID = 42L;

    @Test
    void update_writesOneSnapshotAndPreservesAllSystemMessages() {
        RecordingChatMemoryStore store = new RecordingChatMemoryStore();
        MessageWindowChatMemory memory = MessageWindowChatMemory.builder()
                .id(APP_ID)
                .chatMemoryStore(store)
                .maxMessages(20)
                .build();
        ChatMemoryMessageUpdater updater = new ChatMemoryMessageUpdater(store);
        List<ChatMessage> messages = new ArrayList<>(List.of(
                SystemMessage.from("[memory_policy]\npolicy"),
                SystemMessage.from("[memory_file_note]\nnote"),
                SystemMessage.from("<inject_prompt>\nstyle\n</inject_prompt>"),
                SystemMessage.from("[memory_inject]\nchunk"),
                UserMessage.from("hello")
        ));

        updater.update(memory, APP_ID, messages);
        messages.clear();

        assertEquals(1, store.updateCount);
        assertEquals(List.of(
                        "[memory_policy]\npolicy",
                        "[memory_file_note]\nnote",
                        "<inject_prompt>\nstyle\n</inject_prompt>",
                        "[memory_inject]\nchunk",
                        "hello"),
                memory.messages().stream().map(ChatMemoryMessageUpdaterTest::text).toList());
    }

    private static String text(ChatMessage message) {
        if (message instanceof SystemMessage systemMessage) {
            return systemMessage.text();
        }
        return ((UserMessage) message).singleText();
    }

    private static final class RecordingChatMemoryStore implements ChatMemoryStore {
        private List<ChatMessage> messages = List.of();
        private int updateCount;

        @Override
        public List<ChatMessage> getMessages(Object memoryId) {
            return messages;
        }

        @Override
        public void updateMessages(Object memoryId, List<ChatMessage> messages) {
            updateCount++;
            this.messages = messages;
        }

        @Override
        public void deleteMessages(Object memoryId) {
            messages = List.of();
        }
    }
}
