package com.dbts.glyahhaigeneratecode.agent;

import com.dbts.glyahhaigeneratecode.exception.MyException;
import com.dbts.glyahhaigeneratecode.rateLimiter.service.RateLimiterService;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;
import org.springframework.web.socket.CloseStatus;
import org.springframework.web.socket.TextMessage;
import org.springframework.web.socket.WebSocketSession;
import org.springframework.web.socket.handler.ConcurrentWebSocketSessionDecorator;
import org.springframework.web.socket.handler.TextWebSocketHandler;
import reactor.core.Disposable;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Sinks;

import java.time.Duration;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Agent 的 WebSocket 入口 —— **默认传输**。
 *
 * <p>选它做默认是因为新 Harness 有「流进行中往服务端发数据」的需求：取消、以及将来的
 * 计划审批。纯单向的 token 流 SSE 就够，但一旦要双向，SSE 就得靠额外的 REST 端点绕。
 *
 * <p>与 SSE 路径共用 {@link AgentRunService} 和 {@link AgentRunRequestFactory}，
 * 差异只在传输适配。
 *
 * <h3>两条必须注意的事</h3>
 * <ul>
 *   <li><b>鉴权在握手里做</b>（{@link AgentHandshakeInterceptor}）——
 *       {@code @MyRole} 切不到 handler。</li>
 *   <li><b>限流要显式调</b>（{@link RateLimiterService}）——
 *       {@code @RateLimit} 同样切不到 handler，不调就是绕过限流的口子。</li>
 * </ul>
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class AgentWebSocketHandler extends TextWebSocketHandler {

    /** 与 SSE 路径保持一致的限流参数。 */
    private static final int RATE = 5;
    private static final int RATE_INTERVAL_SECONDS = 60;
    private static final String RATE_MESSAGE = "你先别急";

    private final AgentRunService agentRunService;
    private final AgentRunRequestFactory requestFactory;
    private final AgentRunPersistence persistence;
    private final AgentProperties agentProperties;
    private final RateLimiterService rateLimiterService;
    private final ObjectMapper objectMapper;

    /** 包装过的 session —— 原始 WebSocketSession 并发发送不安全。 */
    private final Map<String, WebSocketSession> sessions = new ConcurrentHashMap<>();
    /** 每个连接当前在跑的 run。取消与断连都要能把它停掉。 */
    private final Map<String, Disposable> running = new ConcurrentHashMap<>();

    @Override
    public void afterConnectionEstablished(WebSocketSession raw) {
        // 发送缓冲区 512KB，发送超时 5 秒
        sessions.put(raw.getId(), new ConcurrentWebSocketSessionDecorator(raw, 5_000, 512 * 1024));
        log.debug("Agent WebSocket 连接建立：{}", raw.getId());
    }

    @Override
    protected void handleTextMessage(WebSocketSession raw, TextMessage message) {
        // 与 SSE 保持一致：不返回 HTTP 错误码，一切失败都在通道内收尾
        WebSocketSession session = sessions.getOrDefault(raw.getId(), raw);
        try {
            JsonNode payload = objectMapper.readTree(message.getPayload());
            String action = payload.path("action").asText("");
            switch (action) {
                case "start" -> handleStart(raw, session, payload);
                case "cancel" -> handleCancel(raw);
                case "ping" -> send(session, "{\"event\":\"pong\"}");
                default -> send(session, businessError("未知指令：" + action));
            }
        } catch (Exception e) {
            log.warn("处理 WebSocket 消息失败：{}", message.getPayload(), e);
            send(session, businessError("请求格式不正确"));
        }
    }

    private void handleStart(WebSocketSession raw, WebSocketSession session, JsonNode payload) {
        Long userId = (Long) raw.getAttributes().get(AgentHandshakeInterceptor.ATTR_USER_ID);
        if (userId == null) {
            // 握手里已经校验过，走到这里说明 attributes 丢了 —— 按未授权处理
            send(session, businessError("未登录"));
            return;
        }

        Long appId = payload.path("appId").asLong(0L);
        String message = payload.path("message").asText("");
        if (message.isBlank()) {
            send(session, businessError("用户消息不能为空"));
            return;
        }

        // 一个连接同时只跑一个 run —— 覆盖前先停掉旧的，避免两个流往同一个 socket 写
        handleCancel(raw);

        AgentRunRequest runRequest;
        Long roundId = null;
        try {
            // 限流 + 归属校验。注意顺序：先限流，避免越权探测把配额也烧掉。
            rateLimiterService.tryAcquire(rateLimiterService.userKey(userId),
                    RATE, RATE_INTERVAL_SECONDS, RATE_MESSAGE);
            // 落 USER 行拿 roundId（失败返回 null，不阻断生成）
            roundId = persistence.beginRound(appId, userId, message, null);
            runRequest = requestFactory.build(appId, userId, message, roundId);
        } catch (MyException e) {
            send(session, businessError(e.getMessage()));
            sendDone(session);
            return;
        }

        String frameJson = runRequest.toFrameJson(objectMapper);
        String runId = runRequest.getRunId();

        // 累积正文并在终态落一次 AI 行。挂在 filter 之前，才看得到 text.message。
        Flux<AgentEvent> events = persistence
                .tap(agentRunService.stream(frameJson), appId, userId, roundId)
                .filter(event -> !event.isEnd());

        Disposable subscription = withHeartbeat(events, runId)
                .subscribe(
                        frame -> send(session, frame),
                        error -> {
                            log.warn("Agent WebSocket 流失败：runId={}", runId, error);
                            send(session, businessError(userFacingMessage(error)));
                            sendDone(session);
                            running.remove(raw.getId());
                        },
                        () -> {
                            sendDone(session);
                            running.remove(raw.getId());
                        });

        running.put(raw.getId(), subscription);
    }

    private void handleCancel(WebSocketSession raw) {
        Disposable previous = running.remove(raw.getId());
        if (previous != null && !previous.isDisposed()) {
            // dispose 会把取消传到 AgentRunService，那里会杀掉 Python 进程
            previous.dispose();
            log.debug("已取消 WebSocket 上的 Agent run：{}", raw.getId());
        }
    }

    @Override
    public void afterConnectionClosed(WebSocketSession raw, CloseStatus status) {
        handleCancel(raw);
        sessions.remove(raw.getId());
        log.debug("Agent WebSocket 连接关闭：{} status={}", raw.getId(), status);
    }

    /**
     * 心跳。
     *
     * <p>WS 侧同样需要：中间代理和负载均衡器会把长时间没数据的连接掐掉。
     * 心跳直接拼成 JSON 字符串参与 merge，不必先解析成 AgentEvent 再序列化回去。
     */
    private Flux<String> withHeartbeat(Flux<AgentEvent> source, String runId) {
        Sinks.Empty<Void> finished = Sinks.empty();
        Flux<String> frames = source.map(event -> event.frame().toString())
                .doFinally(signal -> finished.tryEmitEmpty());
        Flux<String> beats = Flux.interval(agentProperties.getHeartbeatInterval())
                .map(tick -> heartbeatFrame(runId))
                .takeUntilOther(finished.asMono());
        return Flux.merge(frames, beats);
    }

    private String heartbeatFrame(String runId) {
        return "{\"v\":1,\"runId\":\"" + runId + "\",\"seq\":-1,\"ts\":" + System.currentTimeMillis()
                + ",\"event\":\"" + AgentEvent.HEARTBEAT + "\"}";
    }

    private void sendDone(WebSocketSession session) {
        send(session, AgentFrames.webSocketDone());
    }

    private String businessError(String message) {
        return AgentFrames.webSocketBusinessError(message);
    }

    private String userFacingMessage(Throwable e) {
        return e instanceof MyException ? e.getMessage() : "生成服务暂时不可用，请稍后重试";
    }

    private void send(WebSocketSession session, String text) {
        if (session == null || !session.isOpen()) {
            return;
        }
        try {
            session.sendMessage(new TextMessage(text));
        } catch (Exception e) {
            // 连接已断是常态（用户关页面），不是错误
            log.debug("WebSocket 发送失败，连接可能已关闭：{}", e.getMessage());
        }
    }
}
