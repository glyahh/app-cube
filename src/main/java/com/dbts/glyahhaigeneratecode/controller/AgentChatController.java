package com.dbts.glyahhaigeneratecode.controller;

import cn.hutool.core.util.StrUtil;
import com.dbts.glyahhaigeneratecode.agent.AgentEvent;
import com.dbts.glyahhaigeneratecode.agent.AgentFrames;
import com.dbts.glyahhaigeneratecode.agent.AgentProperties;
import com.dbts.glyahhaigeneratecode.agent.AgentRunPersistence;
import com.dbts.glyahhaigeneratecode.agent.AgentRunRequest;
import com.dbts.glyahhaigeneratecode.agent.AgentRunRequestFactory;
import com.dbts.glyahhaigeneratecode.agent.AgentRunService;
import com.dbts.glyahhaigeneratecode.exception.ErrorCode;
import com.dbts.glyahhaigeneratecode.exception.MyException;
import com.dbts.glyahhaigeneratecode.exception.ThrowUtils;
import com.dbts.glyahhaigeneratecode.model.Entity.User;
import com.dbts.glyahhaigeneratecode.rateLimiter.annotation.RateLimit;
import com.dbts.glyahhaigeneratecode.rateLimiter.enums.RateLimitType;
import com.dbts.glyahhaigeneratecode.service.UserService;
import jakarta.servlet.http.HttpServletRequest;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.MediaType;
import org.springframework.http.codec.ServerSentEvent;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;
import reactor.core.publisher.Sinks;

import java.time.Duration;

/**
 * Agent 生成入口 —— **SSE 降级路径**。
 *
 * <p>默认走 WebSocket（{@code /api/ws/agent}），这条 HTTP 路径是 WebSocket 不可用时的备选。
 * 两条路共用 {@link AgentRunService} 的执行逻辑与 {@link AgentRunRequestFactory} 的组装与校验，
 * 差异只在传输适配。
 *
 * <p>为什么保留 SSE：WebSocket 要经过 {@code Upgrade} 握手，中间任一跳不支持就废；
 * SSE 是普通 HTTP，Nginx、CDN、网关全原生支持，浏览器 {@code EventSource} 还自带重连。
 */
@RestController
@RequestMapping("/chat/generate")
@RequiredArgsConstructor
@Slf4j
public class AgentChatController {

    private final AgentRunService agentRunService;
    private final AgentRunRequestFactory requestFactory;
    private final AgentRunPersistence persistence;
    private final AgentProperties agentProperties;
    private final UserService userService;

    /**
     * 流式生成。前端用 {@code EventSource} 消费，Cookie 会自动带上。
     *
     * <p>上下文路径是 {@code /api}，所以完整 URL 是
     * {@code GET /api/chat/generate/stream?appId=&message=}。
     */
    @GetMapping(value = "/stream", produces = MediaType.TEXT_EVENT_STREAM_VALUE)
    @RateLimit(limitType = RateLimitType.USER, rate = 5, rateInterval = 60, message = "你先别急")
    public Flux<ServerSentEvent<String>> stream(@RequestParam Long appId,
                                                @RequestParam String message,
                                                @RequestParam(required = false) Long loopId,
                                                HttpServletRequest request) {
        // 同步校验。这里抛出的异常由 GlobalExceptionHandler 转成 business-error 事件 ——
        // 必须让前端拿到 SSE 而不是 JSON，否则 EventSource 只会触发 onerror、看不到原因。
        ThrowUtils.throwIf(StrUtil.isBlank(message), ErrorCode.PARAMS_ERROR, "用户消息不能为空");
        User user = userService.getUserInSession(request);

        // 先落 USER 行拿到 roundId，它既进协议、也当幂等键。
        // 放在同步阶段是有意的：与现有链路一致，且失败不阻断生成（返回 null）。
        Long roundId = persistence.beginRound(appId, user.getId(), message, loopId);

        // 归属校验在工厂里，两条传输路径共用 —— 分散写就容易出现某条路漏检
        AgentRunRequest runRequest = requestFactory.build(appId, user.getId(), message, roundId);

        // 请求在 App 锁内刷新路由后才序列化，排队请求不会携带旧的永久直连状态。
        return agentRunService.streamForDisplay(appId,
                        () -> requestFactory.refreshModelRoute(runRequest))
                // 终止帧是传输层收尾，不转给前端 —— 由末尾补的 done 事件承担这个职责
                .filter(event -> !event.isEnd())
                .map(this::toSse)
                .transform(sseFlux -> withHeartbeat(sseFlux, agentProperties.getHeartbeatInterval()))
                .onErrorResume(this::degradeToSse)
                .concatWith(Mono.just(sse("done", "")));
    }

    /**
     * 心跳。
     *
     * <p>不是为了保活 Python —— 那是子进程，死了 stdout 立刻 EOF，不需要心跳去发现。
     * 心跳是为了中间代理：Nginx 默认会缓冲，空闲连接也可能被掐。
     */
    private Flux<ServerSentEvent<String>> withHeartbeat(Flux<ServerSentEvent<String>> source, Duration interval) {
        Sinks.Empty<Void> finished = Sinks.empty();
        Flux<ServerSentEvent<String>> beats = Flux
                .interval(interval)
                .map(tick -> sse(AgentEvent.HEARTBEAT, "{}"))
                .takeUntilOther(finished.asMono());
        return Flux.merge(source.doFinally(signal -> finished.tryEmitEmpty()), beats);
    }

    /** 把 Agent 事件转成前端 SSE 事件。data 是整帧 JSON，信息不丢。 */
    private ServerSentEvent<String> toSse(AgentEvent event) {
        return sse(event.event(), event.frame().toString());
    }

    private static ServerSentEvent<String> sse(String event, String data) {
        return ServerSentEvent.<String>builder().event(event).data(data).build();
    }

    /**
     * 失败降级。
     *
     * <p>一律在 SSE 通道内收尾，绝不返回 HTTP 500 —— 否则前端只会看到 onerror，
     * 完全不知道发生了什么。
     */
    private Flux<ServerSentEvent<String>> degradeToSse(Throwable e) {
        log.warn("Agent SSE 流失败", e);
        // MyException 是我们自己抛的业务错（池满、未授权等），消息可以给用户看；
        // 其他异常一律换成通用文案，避免把内部细节透出去。
        String message = e instanceof MyException ? e.getMessage() : "生成服务暂时不可用，请稍后重试";
        return Flux.just(sse("business-error", AgentFrames.businessErrorPayload(message)));
    }
}
