package com.dbts.glyahhaigeneratecode.core.memory;

import cn.hutool.core.util.StrUtil;
import com.dbts.glyahhaigeneratecode.model.enums.CodeGenTypeEnum;
import com.dbts.glyahhaigeneratecode.service.impl.CodeChunkRetrievalService;
import dev.langchain4j.data.message.ChatMessage;
import dev.langchain4j.data.message.SystemMessage;
import dev.langchain4j.memory.chat.MessageWindowChatMemory;
import dev.langchain4j.store.memory.chat.ChatMemoryStore;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.List;

/**
 * RAG 工程记忆注入器。
 * <p>
 * 组件保持无状态：query 必须随当前生成调用显式传入，不按 app 暂存。这样可以避免同一应用的并发请求
 * 覆盖 query，也不会在 workflow 前置节点失败后留下可被下一轮误消费的状态。
 * </p>
 * <p>
 * 注入采用“读取完整快照、移除旧注入、定位插入点、单次写回”的替换策略。检索无命中、格式化为空
 * 或依赖异常时清理旧注入并降级，不阻断代码生成主链路。
 * </p>
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class MemoryRagInjectSupport {

    private final CodeChunkRetrievalService codeChunkRetrievalService;
    private final ChatMemoryStore chatMemoryStore;
    private final ChatMemoryMessageUpdater messageUpdater;

    /**
     * 根据本轮原始用户 query 更新 {@code [memory_inject]}。
     *
     * @param appId      应用标识
     * @param userQuery  本轮原始用户输入，不应包含 Loop、风格等增强提示
     * @param codeGenType 工程类型，用于定位和校验当前磁盘制品
     * @param memory     当前 AI service 绑定的 ChatMemory 视图
     */
    public void injectForQuery(Long appId,
                               String userQuery,
                               CodeGenTypeEnum codeGenType,
                               MessageWindowChatMemory memory) {
        // 1. 参数不足时不访问外部依赖，也不产生跨请求状态。
        if (appId == null || appId <= 0 || StrUtil.isBlank(userQuery)
                || codeGenType == null || memory == null) {
            return;
        }

        // 2. 检索门面负责召回、磁盘版本过滤和格式化；本组件只治理消息生命周期。
        try {
            List<CodeChunkRetrievalService.CodeChunkHit> hits =
                    codeChunkRetrievalService.searchTopK(appId, userQuery, codeGenType);
            if (hits == null || hits.isEmpty()) {
                removeOldInjectIfPresent(appId, memory);
                return;
            }
            String injectText = codeChunkRetrievalService.formatAsInject(hits);
            if (StrUtil.isBlank(injectText)) {
                removeOldInjectIfPresent(appId, memory);
                return;
            }

            // 3. 在副本上替换旧注入，最后一次性提交完整快照。
            List<ChatMessage> messages = readMessages(appId);
            if (messages == null) {
                return;
            }
            messages.removeIf(MemoryRagInjectSupport::isRagInjectMessage);
            messages.add(findInjectInsertPosition(messages), SystemMessage.from(injectText));
            messageUpdater.update(memory, appId, messages);
            log.debug("RAG code chunks 注入完成，appId={}, hits={}", appId, hits.size());
        } catch (Exception e) {
            log.warn("RAG code chunks 注入失败，清理旧注入并降级，appId={}", appId, e);
            removeOldInjectIfPresent(appId, memory);
        }
    }

    private List<ChatMessage> readMessages(Long appId) {
        try {
            List<ChatMessage> storedMessages = chatMemoryStore.getMessages(appId);
            return storedMessages == null ? new ArrayList<>() : new ArrayList<>(storedMessages);
        } catch (Exception e) {
            log.warn("读取 ChatMemoryStore 失败，跳过 RAG 注入，appId={}", appId, e);
            return null;
        }
    }

    private void removeOldInjectIfPresent(Long appId, MessageWindowChatMemory memory) {
        List<ChatMessage> messages = readMessages(appId);
        if (messages != null && messages.removeIf(MemoryRagInjectSupport::isRagInjectMessage)) {
            messageUpdater.update(memory, appId, messages);
        }
    }

    private static boolean isRagInjectMessage(ChatMessage message) {
        return message instanceof SystemMessage systemMessage
                && systemMessage.text() != null
                && systemMessage.text().startsWith(ConversationMemoryInjectTexts.INJECT_TAG);
    }

    /**
     * 定位 RAG 注入位置。
     * <p>注入必须位于连续 memory 系统消息之后、第一条业务消息之前，以保持系统上下文优先级稳定。</p>
     *
     * @param messages 完整有序消息列表
     * @return 新 RAG 系统消息的插入下标
     */
    public static int findInjectInsertPosition(List<ChatMessage> messages) {
        int lastMemoryTag = -1;
        for (int i = 0; i < messages.size(); i++) {
            if (messages.get(i) instanceof SystemMessage systemMessage) {
                String text = systemMessage.text();
                if (text != null && isMemoryTag(text)) {
                    lastMemoryTag = i;
                }
            } else {
                break;
            }
        }
        return lastMemoryTag + 1;
    }

    private static boolean isMemoryTag(String text) {
        return text.startsWith("[memory_policy]")
                || text.startsWith("[memory_index]")
                || text.startsWith("[memory_file_note]")
                || text.startsWith(MemorySessionInjectSupport.SESSION_PREFIX)
                || text.startsWith(ConversationMemoryInjectTexts.INJECT_TAG);
    }
}
