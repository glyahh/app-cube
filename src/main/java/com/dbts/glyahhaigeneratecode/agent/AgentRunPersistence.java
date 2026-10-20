package com.dbts.glyahhaigeneratecode.agent;

import com.dbts.glyahhaigeneratecode.mapper.AgentRunMapper;
import com.dbts.glyahhaigeneratecode.mapper.AppMapper;
import com.dbts.glyahhaigeneratecode.model.Entity.AgentRun;
import com.dbts.glyahhaigeneratecode.model.enums.ChatHistoryMessageTypeEnum;
import com.dbts.glyahhaigeneratecode.service.ChatHistoryService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;
import reactor.core.publisher.Flux;
import reactor.core.publisher.SignalType;
import reactor.core.publisher.SynchronousSink;

import java.time.LocalDateTime;
import java.util.concurrent.atomic.AtomicBoolean;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * 把一轮对话落到 {@code chat_history}，并把执行过程落到 {@code agent_run}。
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
    private final AgentRunMapper agentRunMapper;
    private final AppMapper appMapper;
    private final org.springframework.context.ApplicationEventPublisher eventPublisher;
    private final ObjectMapper objectMapper = new ObjectMapper();

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
     * 记一行执行台账。
     *
     * <p>在 run 真正跑起来**之前**写。它因此天然承担「崩溃留痕」：进程被 kill、
     * 流中断、前端关页面，这一行都还留着 {@code status=RUNNING} 与 {@code started_at}，
     * 「起了头没收尾」的 run 靠这两个字段就能认出来。
     *
     * <p>id 用协议里的 {@code runId}（而不是让 ORM 另生成一个），这样拿事件里的 runId
     * 能直接反查到这一行。
     */
    public void recordRun(AgentRunRequest request) {
        try {
            if (request.getRoundId() == null) {
                // round_id 是 NOT NULL，也是幂等键。没有它就记不了台账。
                log.warn("没有 roundId，跳过 agent_run 台账：runId={}", request.getRunId());
                return;
            }
            // 用 insertSelective 而不是 insert：后者会把没赋值的列以 null 写进去，
            // 撞上 create_time / update_time 的 NOT NULL。选择性插入让数据库的
            // DEFAULT now() 生效 —— 与 UserServiceImpl 里注册用户时的做法一致。
            agentRunMapper.insertSelective(AgentRun.builder()
                    .id(Long.valueOf(request.getRunId()))
                    .appId(Long.valueOf(request.getAppId()))
                    .userId(Long.valueOf(request.getUserId()))
                    .roundId(Long.valueOf(request.getRoundId()))
                    .threadId(request.getThreadId())
                    .traceId(request.getTraceId())
                    .codeGenType(request.getCodeGenType())
                    .workspaceName(request.getWorkspaceName())
                    .status("RUNNING")
                    .startedAt(LocalDateTime.now())
                    .lastEventSeq(0)
                    .reconcileTries(0)
                    .build());
            eventPublisher.publishEvent(new ProjectTitleRequested(request));
        } catch (Exception e) {
            // 台账写不进去不该拦住生成 —— 它是可观测性，不是业务前置条件
            log.error("落 agent_run 台账失败：runId={}", request.getRunId(), e);
        }
    }

    /**
     * 在事件流上挂累积与终态落库。
     *
     * <p>用 {@code Flux.defer} 包一层，保证每次订阅有独立的累积缓冲 ——
     * 否则同一段流被订阅两次时正文会串。
     */
    public Flux<AgentEvent> tap(Flux<AgentEvent> events, AgentRunRequest request) {
        Long appId = Long.valueOf(request.getAppId());
        Long userId = Long.valueOf(request.getUserId());
        Long roundId = request.getRoundId() == null ? null : Long.valueOf(request.getRoundId());
        String runId = request.getRunId();

        return Flux.defer(() -> {
            StringBuilder buffer = new StringBuilder();
            List<Map<String, Object>> thinking = new ArrayList<>();
            AtomicBoolean persisted = new AtomicBoolean(false);
            AtomicBoolean settled = new AtomicBoolean(false);
            AtomicBoolean usagePersisted = new AtomicBoolean(false);
            java.util.concurrent.atomic.AtomicReference<com.fasterxml.jackson.databind.JsonNode> usage =
                    new java.util.concurrent.atomic.AtomicReference<>();
            java.util.concurrent.atomic.AtomicReference<String> outcome = new java.util.concurrent.atomic.AtomicReference<>("FAILED");
            // 首个错误事件的码与说明。留到最终落库，否则 run 只留一个 FAILED，
            // 事后完全查不出为什么失败 —— 线上 89 条 FAILED 的 error_message 都是空的。
            java.util.concurrent.atomic.AtomicReference<String> failureCode =
                    new java.util.concurrent.atomic.AtomicReference<>();
            java.util.concurrent.atomic.AtomicReference<String> failureMessage =
                    new java.util.concurrent.atomic.AtomicReference<>();

            return events
                    .handle((AgentEvent event, SynchronousSink<AgentEvent> sink) -> {
                        accumulate(event, buffer, thinking);
                        if ("import.review.requested".equals(event.event())) outcome.set("WAITING_REVIEW");
                        if (AgentEvent.RUN_COMPLETED.equals(event.event())) {
                            // cancelled 是「等待审批超时」这类主动暂停，不是失败。
                            outcome.set(switch (event.status()) {
                                case "succeeded" -> "SUCCEEDED";
                                case "cancelled" -> "CANCELLED";
                                default -> "FAILED";
                            });
                            if (event.frame().path("usage").isObject()) {
                                usage.set(event.frame().path("usage").deepCopy());
                            }
                        }
                        if (AgentEvent.ERROR.equals(event.event())) {
                            outcome.set("FAILED");
                            // compareAndSet 保留第一条：后面的往往是第一条的连带产物。
                            failureCode.compareAndSet(null, event.errorCode());
                            failureMessage.compareAndSet(null, event.frame().path("message").asText());
                        }
                        if (AgentEvent.MODEL_ROUTE_FALLBACK.equals(event.event())
                                && !recordModelFallback(appId, runId, event)) {
                            return;
                        }
                        sink.next(event);
                    })
                    .doOnComplete(() -> {
                        persistOnce(appId, userId, roundId, buffer, thinking, persisted);
                        persistUsageOnce(runId, usage.get(), usagePersisted);
                        if (finishRun(runId, outcome.get(), failureCode.get(), failureMessage.get(), settled)) {
                            publishFinished(appId, userId, runId, outcome.get());
                        }
                    })
                    .doOnError(error -> {
                        persistOnce(appId, userId, roundId, buffer, thinking, persisted);
                        persistUsageOnce(runId, usage.get(), usagePersisted);
                        if (finishRun(runId, "FAILED", null, error.getMessage(), settled)) {
                            publishFinished(appId, userId, runId, "FAILED");
                        }
                    })
                    // 取消既不走 onComplete 也不走 onError，只能靠 doFinally 的信号类型区分。
                    // 少这一条的话，前端关页面留下的 run 会永远停在 RUNNING。
                    .doFinally(signal -> {
                        if (signal == SignalType.CANCEL) {
                            thinking.forEach(item -> { if ("running".equals(item.get("status"))) item.put("status", "cancelled"); });
                            persistOnce(appId, userId, roundId, buffer, thinking, persisted);
                            persistUsageOnce(runId, usage.get(), usagePersisted);
                            if (finishRun(runId, "CANCELLED", null, "客户端取消", settled)) {
                                publishFinished(appId, userId, runId, "CANCELLED");
                            }
                        }
                    });
        });
    }

    /** 落审计并用数据库 CAS 决定这次事件是否有资格提示用户。 */
    private boolean recordModelFallback(Long appId, String runId, AgentEvent event) {
        try {
            var route = new java.util.LinkedHashMap<String, Object>();
            route.put("from", event.frame().path("fromRoute").asText("proxy"));
            route.put("to", event.frame().path("toRoute").asText("direct"));
            route.put("reason", event.frame().path("reason").asText("connect_error"));
            route.put("at", event.ts());
            String json = objectMapper.writeValueAsString(Map.of("modelRoute", route));
            if (agentRunMapper.mergeUsageJson(Long.valueOf(runId), json) != 1) {
                log.warn("模型路由审计未写入：runId={}", runId);
                return false;
            }
            return appMapper.forceModelDirectIfNeeded(appId) == 1;
        } catch (Exception e) {
            log.error("记录模型自动降级失败：appId={}, runId={}", appId, runId, e);
            return false;
        }
    }

    /** run.completed 的 usage 在展示过滤前落库；所有终止路径最多执行一次。 */
    private void persistUsageOnce(String runId, com.fasterxml.jackson.databind.JsonNode usage,
                                  AtomicBoolean persisted) {
        if (usage == null || !persisted.compareAndSet(false, true)) {
            return;
        }
        try {
            agentRunMapper.mergeUsageJson(Long.valueOf(runId), objectMapper.writeValueAsString(usage));
        } catch (Exception e) {
            log.error("写入 agent_run usage 失败：runId={}", runId, e);
        }
    }

    /** 收口台账终态。正常、异常、取消三条路都可能触发，只写一次。 */
    /**
     * 收尾终态。
     *
     * @return 本次调用是否真的完成了结算（CAS 抢到）。抢不到说明别的分支已经处理过，
     *         此时不该再发完成事件，否则一轮会触发两次下游。
     */
    private boolean finishRun(String runId, String status, String errorCode, String errorMessage,
                              AtomicBoolean settled) {
        if (!settled.compareAndSet(false, true)) {
            return false;
        }
        try {
            // runId 就是主键，按主键更新即可。默认忽略 null 字段，
            // 所以这里只写终态相关的列，不会把 started_at 之类冲掉。
            AgentRun update = AgentRun.builder()
                    .id(Long.valueOf(runId))
                    .status(status)
                    .errorCode(errorCode)
                    .errorMessage(truncate(errorMessage))
                    .finishedAt(LocalDateTime.now())
                    .build();
            agentRunMapper.update(update);
        } catch (Exception e) {
            log.error("更新 agent_run 终态失败：runId={}, status={}", runId, status, e);
        }
        return true;
    }

    /** 广播本轮结束。订阅方（如知识模块）据此决定要不要跟进。 */
    private void publishFinished(Long appId, Long userId, String runId, String status) {
        try {
            eventPublisher.publishEvent(new AgentRunFinishedEvent(appId, userId, runId, status));
        } catch (Exception e) {
            // 订阅方出错不该影响生成结果本身
            log.warn("发布 run 结束事件失败：runId={}, status={}", runId, status, e);
        }
    }

    /** error_message 列是 VARCHAR(512)，超长会让整条 UPDATE 失败、丢掉状态。 */
    private static String truncate(String message) {
        if (message == null) {
            return null;
        }
        return message.length() <= 512 ? message : message.substring(0, 512);
    }

    private void accumulate(AgentEvent event, StringBuilder buffer, List<Map<String, Object>> thinking) {
        if (event.event().startsWith("thinking.")) {
            String id = event.frame().path("thinkingId").asText("");
            if (id.isBlank()) return;
            Map<String, Object> item = thinking.stream()
                    .filter(x -> id.equals(x.get("thinkingId"))).findFirst().orElse(null);
            if (item == null) {
                item = new java.util.LinkedHashMap<>();
                item.put("thinkingId", id);
                item.put("details", new ArrayList<String>());
                thinking.add(item);
            }
            String summary = event.frame().path("summary").asText("");
            if (!summary.isBlank()) item.put("summary", summary);
            @SuppressWarnings("unchecked")
            List<String> details = (List<String>) item.get("details");
            String detail = event.frame().path("details").asText("");
            if (!detail.isBlank() && !details.contains(detail)) details.add(detail);
            if (!"completed".equals(item.get("status")))
                item.put("status", event.frame().path("status").asText("running"));
            return;
        }
        if (event.isTextDelta()) {
            buffer.append(event.textContent());
        } else if (AgentEvent.TEXT_MESSAGE.equals(event.event())) {
            // 终稿是权威文本，整体替换而不是追加 —— 增量可能因为网络或过滤有缺口
            buffer.setLength(0);
            buffer.append(event.frame().path("content").asText(""));
        } else if (AgentEvent.RUN_COMPLETED.equals(event.event()) && buffer.length() == 0) {
            // 只有终态摘要、没有正文事件的路径（例如异常终止）也要落一句，避免占位文案。
            String summary = event.frame().path("summary").asText("");
            if (!summary.isBlank()) buffer.append(summary);
        }
    }

    private void persistOnce(Long appId, Long userId, Long roundId, StringBuilder buffer, List<Map<String, Object>> thinking, AtomicBoolean persisted) {
        // 正常完成与异常可能都触发，只落一次
        if (!persisted.compareAndSet(false, true)) {
            return;
        }
        String text = buffer.toString();
        if (text.isBlank() && thinking.isEmpty()) {
            log.warn("本轮没有正文可落库：appId={}, roundId={}", appId, roundId);
            return;
        }
        thinking.forEach(item -> { if ("running".equals(item.get("status"))) item.put("status", "failed"); });
        try {
            chatHistoryService.saveAgentMessage(appId, userId,
                    text.isBlank() ? "本轮已执行，未生成文字总结。" : text, objectMapper.writeValueAsString(thinking));
        } catch (Exception e) {
            // 落 AI 行失败不该影响已经推给前端的流
            log.error("落 AI 消息失败：appId={}, roundId={}", appId, roundId, e);
        }
    }
}
