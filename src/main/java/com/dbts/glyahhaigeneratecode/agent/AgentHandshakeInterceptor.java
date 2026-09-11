package com.dbts.glyahhaigeneratecode.agent;

import com.dbts.glyahhaigeneratecode.exception.MyException;
import com.dbts.glyahhaigeneratecode.model.Entity.User;
import com.dbts.glyahhaigeneratecode.service.UserService;
import jakarta.servlet.http.HttpServletRequest;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.server.ServerHttpRequest;
import org.springframework.http.server.ServerHttpResponse;
import org.springframework.http.server.ServletServerHttpRequest;
import org.springframework.stereotype.Component;
import org.springframework.web.socket.WebSocketHandler;
import org.springframework.web.socket.server.HandshakeInterceptor;

import java.util.Map;

/**
 * WebSocket 握手拦截器 —— **鉴权必须在这里做**。
 *
 * <p>现有的 {@code @MyRole} 与 {@code @RateLimit} 都是 AOP 切在 controller 方法上的，
 * 而 WebSocket handler 不是 controller，切面根本不会生效。不在这里校验的话，
 * WS 路径就是一个绕过鉴权的口子。
 *
 * <p>握手本身是一个 HTTP 请求，所以 {@code HttpSession} 拿得到，
 * Spring Session 在 Redis 的会话照常工作。校验通过后把 userId 放进 attributes，
 * handler 里从 {@code session.getAttributes()} 取。
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class AgentHandshakeInterceptor implements HandshakeInterceptor {

    /** 握手时写入会话属性的用户 ID。 */
    public static final String ATTR_USER_ID = "agentUserId";

    private final UserService userService;

    @Override
    public boolean beforeHandshake(ServerHttpRequest request,
                                   ServerHttpResponse response,
                                   WebSocketHandler wsHandler,
                                   Map<String, Object> attributes) {
        if (!(request instanceof ServletServerHttpRequest servletRequest)) {
            log.warn("非 Servlet 握手请求，拒绝");
            return false;
        }
        HttpServletRequest httpRequest = servletRequest.getServletRequest();
        try {
            User user = userService.getUserInSession(httpRequest);
            if (user == null || user.getId() == null) {
                return false;
            }
            attributes.put(ATTR_USER_ID, user.getId());
            return true;
        } catch (MyException e) {
            // 未登录 —— 拒绝握手，连接不会建立
            log.debug("WebSocket 握手被拒：{}", e.getMessage());
            return false;
        } catch (Exception e) {
            log.warn("WebSocket 握手校验异常，拒绝", e);
            return false;
        }
    }

    @Override
    public void afterHandshake(ServerHttpRequest request,
                               ServerHttpResponse response,
                               WebSocketHandler wsHandler,
                               Exception exception) {
        // 无需处理
    }
}
