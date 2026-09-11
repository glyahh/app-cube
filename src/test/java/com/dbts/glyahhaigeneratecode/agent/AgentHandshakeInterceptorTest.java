package com.dbts.glyahhaigeneratecode.agent;

import com.dbts.glyahhaigeneratecode.exception.ErrorCode;
import com.dbts.glyahhaigeneratecode.exception.MyException;
import com.dbts.glyahhaigeneratecode.model.Entity.User;
import com.dbts.glyahhaigeneratecode.service.UserService;
import jakarta.servlet.http.HttpServletRequest;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.http.server.ServerHttpRequest;
import org.springframework.http.server.ServletServerHttpRequest;

import java.util.HashMap;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * WebSocket 握手鉴权。
 *
 * <p>这是 WebSocket 路径**唯一的鉴权点** —— {@code @MyRole} 是 AOP 切在 controller
 * 方法上的，而 handler 不是 controller，切面根本不生效。所以这里的重点是「拒绝」用例：
 * 没登录、拿不到 session、校验过程本身出错，都必须**失败关闭**（拒绝握手），
 * 不能因为「异常了就放过去」。
 */
@DisplayName("WebSocket 握手鉴权")
class AgentHandshakeInterceptorTest {

    private UserService userService;
    private AgentHandshakeInterceptor interceptor;

    @BeforeEach
    void setUp() {
        userService = mock(UserService.class);
        interceptor = new AgentHandshakeInterceptor(userService);
    }

    private ServletServerHttpRequest servletRequest(HttpServletRequest inner) {
        ServletServerHttpRequest request = mock(ServletServerHttpRequest.class);
        when(request.getServletRequest()).thenReturn(inner);
        return request;
    }

    private static Map<String, Object> attributes() {
        return new HashMap<>();
    }

    @Test
    @DisplayName("已登录：放行，并把 userId 写进 attributes")
    void allowsLoggedInUser() {
        HttpServletRequest inner = mock(HttpServletRequest.class);
        User user = new User();
        user.setId(12345L);
        when(userService.getUserInSession(inner)).thenReturn(user);

        Map<String, Object> attributes = attributes();
        boolean allowed = interceptor.beforeHandshake(servletRequest(inner), null, null, attributes);

        assertTrue(allowed);
        assertEquals(12345L, attributes.get(AgentHandshakeInterceptor.ATTR_USER_ID));
    }

    @Test
    @DisplayName("未登录：拒绝握手，不写入任何身份信息")
    void rejectsAnonymousUser() {
        HttpServletRequest inner = mock(HttpServletRequest.class);
        when(userService.getUserInSession(inner))
                .thenThrow(new MyException(ErrorCode.NOT_LOGIN_ERROR, "未登录"));

        Map<String, Object> attributes = attributes();
        boolean allowed = interceptor.beforeHandshake(servletRequest(inner), null, null, attributes);

        assertFalse(allowed);
        assertNull(attributes.get(AgentHandshakeInterceptor.ATTR_USER_ID));
    }

    @Test
    @DisplayName("会话返回空用户：拒绝")
    void rejectsNullUser() {
        HttpServletRequest inner = mock(HttpServletRequest.class);
        when(userService.getUserInSession(inner)).thenReturn(null);

        assertFalse(interceptor.beforeHandshake(servletRequest(inner), null, null, attributes()));
    }

    @Test
    @DisplayName("用户存在但 id 为空：拒绝")
    void rejectsUserWithoutId() {
        HttpServletRequest inner = mock(HttpServletRequest.class);
        when(userService.getUserInSession(inner)).thenReturn(new User());

        assertFalse(interceptor.beforeHandshake(servletRequest(inner), null, null, attributes()));
    }

    @Test
    @DisplayName("校验过程抛意外异常：必须失败关闭，不能放行")
    void failsClosedOnUnexpectedError() {
        HttpServletRequest inner = mock(HttpServletRequest.class);
        when(userService.getUserInSession(any()))
                .thenThrow(new IllegalStateException("Redis 挂了"));

        Map<String, Object> attributes = attributes();
        boolean allowed = interceptor.beforeHandshake(servletRequest(inner), null, null, attributes);

        assertFalse(allowed, "校验出错时必须拒绝，不能因为异常就放过去");
        assertNull(attributes.get(AgentHandshakeInterceptor.ATTR_USER_ID));
    }

    @Test
    @DisplayName("非 Servlet 握手请求：拒绝")
    void rejectsNonServletRequest() {
        ServerHttpRequest plain = mock(ServerHttpRequest.class);

        Map<String, Object> attributes = attributes();
        boolean allowed = interceptor.beforeHandshake(plain, null, null, attributes);

        assertFalse(allowed);
        assertNull(attributes.get(AgentHandshakeInterceptor.ATTR_USER_ID));
    }
}
