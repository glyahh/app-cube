package com.dbts.glyahhaigeneratecode.config;

import com.dbts.glyahhaigeneratecode.agent.AgentHandshakeInterceptor;
import com.dbts.glyahhaigeneratecode.agent.AgentWebSocketHandler;
import org.junit.jupiter.api.Test;
import org.springframework.web.socket.config.annotation.WebSocketHandlerRegistration;
import org.springframework.web.socket.config.annotation.WebSocketHandlerRegistry;
import org.springframework.web.socket.server.HandshakeInterceptor;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.*;

class AgentWebSocketConfigTest {

    private final AgentWebSocketHandler handler = mock(AgentWebSocketHandler.class);
    private final AgentHandshakeInterceptor interceptor = mock(AgentHandshakeInterceptor.class);
    private final WebSocketHandlerRegistry registry = mock(WebSocketHandlerRegistry.class);
    private final WebSocketHandlerRegistration registration = mock(WebSocketHandlerRegistration.class);

    @Test
    void registersConfiguredOrigins() {
        when(registry.addHandler(handler, "/ws/agent")).thenReturn(registration);
        when(registration.addInterceptors(any(HandshakeInterceptor[].class))).thenReturn(registration);
        when(registration.setAllowedOriginPatterns(any(String[].class))).thenReturn(registration);

        SecurityProperties properties = new SecurityProperties();
        properties.setAllowedOriginPatterns(List.of("http://127.0.0.1:5173"));

        new AgentWebSocketConfig(handler, interceptor, properties).registerWebSocketHandlers(registry);

        verify(registration).setAllowedOriginPatterns("http://127.0.0.1:5173");
    }

    @Test
    void refusesEmptyOrigins() {
        SecurityProperties properties = new SecurityProperties();
        AgentWebSocketConfig config = new AgentWebSocketConfig(handler, interceptor, properties);

        assertThrows(IllegalStateException.class, () -> config.registerWebSocketHandlers(registry));
    }
}
