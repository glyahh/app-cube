package com.dbts.glyahhaigeneratecode.core.memory;

import dev.langchain4j.data.message.ChatMessage;
import dev.langchain4j.memory.chat.MessageWindowChatMemory;
import dev.langchain4j.store.memory.chat.ChatMemoryStore;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

import java.util.List;

/**
 * ChatMemory 完整快照写入器。
 * <p>
 * {@link MessageWindowChatMemory} 是基于 {@link ChatMemoryStore} 的读取视图，消息真相源由 store 维护。
 * 因此更新时只允许提交一次完整快照，禁止通过 {@code clear + add} 逐条重放；后者不仅产生多次
 * 持久化写入，还会触发 LangChain4j 对 {@code SystemMessage} 的替换语义，造成记忆策略、文件说明、
 * 用户风格或 RAG 上下文互相覆盖。
 * </p>
 */
@Component
@RequiredArgsConstructor
public class ChatMemoryMessageUpdater {

    private final ChatMemoryStore chatMemoryStore;

    /**
     * 原子语义地提交完整消息快照。
     * <p>写入前复制列表，避免调用方在方法返回后修改原列表并污染 store 中的本轮快照。</p>
     *
     * @param memory   与 {@code appId} 对应的 memory 视图；保留该参数用于明确调用契约
     * @param appId    ChatMemory 标识
     * @param messages 待提交的完整有序消息列表
     */
    public void update(MessageWindowChatMemory memory, Long appId, List<ChatMessage> messages) {
        chatMemoryStore.updateMessages(appId, List.copyOf(messages));
    }
}
