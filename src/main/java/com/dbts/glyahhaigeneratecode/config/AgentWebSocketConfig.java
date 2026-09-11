package com.dbts.glyahhaigeneratecode.config;

import com.dbts.glyahhaigeneratecode.agent.AgentHandshakeInterceptor;
import com.dbts.glyahhaigeneratecode.agent.AgentWebSocketHandler;
import lombok.RequiredArgsConstructor;
import org.springframework.context.annotation.Configuration;
import org.springframework.web.socket.config.annotation.EnableWebSocket;
import org.springframework.web.socket.config.annotation.WebSocketConfigurer;
import org.springframework.web.socket.config.annotation.WebSocketHandlerRegistry;

/**
 * Agent WebSocket 端点注册。
 *
 * <p>用原生 {@code WebSocketHandler}，不上 STOMP —— STOMP 带订阅与代理一整套协议，
 * 而这里的消息模型是「一个 run 一条流」，用不上。
 *
 * <p>跨域策略与 {@link CorsConfig} 保持一致。注意**握手拦截器必须挂上**：
 * 它是 WebSocket 路径唯一的鉴权点（{@code @MyRole} 切不到 handler）。
 */
@Configuration
@EnableWebSocket
@RequiredArgsConstructor
public class AgentWebSocketConfig implements WebSocketConfigurer {

    /** 上下文路径是 /api，所以完整地址是 ws://host:8124/api/ws/agent。 */
    private static final String ENDPOINT = "/ws/agent";

    private final AgentWebSocketHandler agentWebSocketHandler;
    private final AgentHandshakeInterceptor agentHandshakeInterceptor;

    @Override
    public void registerWebSocketHandlers(WebSocketHandlerRegistry registry) {
        registry.addHandler(agentWebSocketHandler, ENDPOINT)
                .addInterceptors(agentHandshakeInterceptor)
                .setAllowedOriginPatterns("*");
    }
}
