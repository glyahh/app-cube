package com.dbts.glyahhaigeneratecode.agent;

import com.dbts.glyahhaigeneratecode.model.enums.ChatHistoryMessageTypeEnum;
import com.dbts.glyahhaigeneratecode.service.ChatHistoryService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;
import reactor.core.publisher.Flux;

import java.util.concurrent.atomic.AtomicBoolean;

/**
 * 把一轮对话落到 {@code chat_history}。
 *
 * <p>节奏刻意和现有流式链路保持一致：**USER 行在开始前落，AI 行只在终态落一次**。
 * 不要每个 {@code text.delta} 都写库 —— 那是每轮几十到几百次 UPDATE，
 * 现有 {@code StreamHandlerExecutor} 也是流结束才落 AI 行。
 *
 * <p>这条链路在 Java 侧独占写入，Python 不碰 {@code biz.*}：
 * {@code isDelete} 逻辑删除、雪花 ID 生成、{@code auditAction} 语义都是
 * MyBatis-Flex 在 ORM 层实现的，裸 SQL 会绕过它们。
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class AgentRunPersistence {

    private final ChatHistoryService chatHistoryService;

    /**
     * 落 USER 行并拿到本轮 ID。
     *
     * <p>这个 ID 就是协议里的 {@code roundId}，同时充当落库幂等键。
     *
     * @return 本轮消息主键；失败返回 null（调用方要能接受 null）
     */
    public Long beginRound(Long appId, Long userId, String message, Long loopId) {
        try {
            return chatHistoryService.addChatMessageAndReturnId(
                    appId,
                    message,
                    ChatHistoryMessageTypeEnum.USER.getValue(),
                    userId,
                    // Phase 1 还没接提示词审查；用 SKIP/NONE 表示「未审查」，
                    // 不要写成 ALLOW —— 那会谎称审查通过了。
                    "SKIP",
                    "NONE",
                    loopId);
        } catch (Exception e) {
            // 落库失败不该拦住生成。记日志继续，本轮没有 roundId。
            log.error("落 USER 消息失败：appId={}, userId={}", appId, userId, e);
            return null;
        }
    }

    /**
     * 在事件流上挂累积与终态落库。
     *
     * <p>用 {@code Flux.defer} 包一层，保证每次订阅有独立的累积缓冲 ——
     * 否则同一段流被订阅两次时正文会串。
     */
    public Flux<AgentEvent> tap(Flux<AgentEvent> events, Long appId, Long userId, Long roundId) {
        if (roundId == null) {
            return events;
        }
        return Flux.defer(() -> {
            StringBuilder buffer = new StringBuilder();
            AtomicBoolean persisted = new AtomicBoolean(false);

            return events
                    .doOnNext(event -> accumulate(event, buffer))
                    .doOnComplete(() -> persistOnce(appId, userId, roundId, buffer, persisted))
                    .doOnError(error -> persistOnce(appId, userId, roundId, buffer, persisted));
        });
    }

    private void accumulate(AgentEvent event, StringBuilder buffer) {
        if (event.isTextDelta()) {
            buffer.append(event.textContent());
        } else if (AgentEvent.TEXT_MESSAGE.equals(event.event())) {
            // 终稿是权威文本，整体替换而不是追加 —— 增量可能因为网络或过滤有缺口
            buffer.setLength(0);
            buffer.append(event.frame().path("content").asText(""));
        }
    }

    private void persistOnce(Long appId, Long userId, Long roundId, StringBuilder buffer, AtomicBoolean persisted) {
        // 正常完成与异常可能都触发，只落一次
        if (!persisted.compareAndSet(false, true)) {
            return;
        }
        String text = buffer.toString();
        if (text.isBlank()) {
            log.warn("本轮没有正文可落库：appId={}, roundId={}", appId, roundId);
            return;
        }
        try {
            chatHistoryService.addChatMessage(appId, text, ChatHistoryMessageTypeEnum.AI.getValue(), userId);
        } catch (Exception e) {
            // 落 AI 行失败不该影响已经推给前端的流
            log.error("落 AI 消息失败：appId={}, roundId={}", appId, roundId, e);
        }
    }
}
