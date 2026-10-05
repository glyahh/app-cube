package com.dbts.glyahhaigeneratecode.projectimport;

import com.dbts.glyahhaigeneratecode.common.ResultUtils;
import com.dbts.glyahhaigeneratecode.exception.ErrorCode;
import com.dbts.glyahhaigeneratecode.exception.MyException;
import com.dbts.glyahhaigeneratecode.service.UserService;
import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.ServletInputStream;
import jakarta.servlet.ReadListener;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletRequestWrapper;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;

/** 导入入口在读取请求体前核验会话、来源和 CSRF，不继承全局宽松 CORS 授权。 */
@Component
public class ImportRequestGuard extends OncePerRequestFilter {
    public static final String CSRF_SESSION_KEY = "PROJECT_IMPORT_CSRF";
    public static final String CSRF_HEADER = "X-Project-Import-CSRF";
    private final UserService users;
    private final ImportProperties properties;
    private final ObjectMapper json;
    public ImportRequestGuard(UserService users, ImportProperties properties, ObjectMapper json) {
        this.users = users;
        this.properties = properties;
        this.json = json;
    }

    @Override protected boolean shouldNotFilter(HttpServletRequest request) {
        String path = request.getRequestURI().substring(request.getContextPath().length());
        return !path.startsWith("/project/import/") || request.getMethod().equals("OPTIONS");
    }

    @Override protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain chain)
            throws IOException, ServletException {
        response.setHeader("Cache-Control", "no-store");
        try { users.getUserInSession(request); }
        catch (MyException e) { reject(response, 401, e.getCode(), "请先登录。"); return; }
        String origin = request.getHeader("Origin");
        if ((origin != null && !properties.getAllowedOrigins().contains(origin))
                || (origin == null && "cross-site".equals(request.getHeader("Sec-Fetch-Site")))) {
            reject(response, 403, ErrorCode.FORBIDDEN_ERROR.getCode(), "请求来源不被允许。");
            return;
        }
        if (!request.getMethod().equals("GET") && !request.getMethod().equals("HEAD")) {
            var session = request.getSession(false);
            Object expected = session == null ? null : session.getAttribute(CSRF_SESSION_KEY);
            String supplied = request.getHeader(CSRF_HEADER);
            if (!(expected instanceof String token) || supplied == null
                    || !MessageDigest.isEqual(token.getBytes(StandardCharsets.UTF_8), supplied.getBytes(StandardCharsets.UTF_8))) {
                reject(response, 403, ErrorCode.FORBIDDEN_ERROR.getCode(), "请求已失效，请刷新后重试。");
                return;
            }
        }
        String path = request.getRequestURI().substring(request.getContextPath().length());
        if (!(request.getMethod().equals("GET") || request.getMethod().equals("HEAD"))
                && !(request.getMethod().equals("PUT") && path.matches("/project/import/tasks/[^/]+/files/[0-9]+"))) {
            if (request.getContentLengthLong() > properties.getMaxMetadataBytes()) {
                reject(response, 413, ErrorCode.PARAMS_ERROR.getCode(), "导入清单超过大小限制。");
                return;
            }
            chain.doFilter(new HttpServletRequestWrapper(request) {
                @Override public ServletInputStream getInputStream() throws IOException {
                    ServletInputStream source = super.getInputStream();
                    return new ServletInputStream() {
                        private long received;
                        @Override public int read() throws IOException {
                            int value = source.read();
                            if (value >= 0) check(1);
                            return value;
                        }
                        @Override public int read(byte[] buffer, int offset, int length) throws IOException {
                            int count = source.read(buffer, offset, length);
                            if (count > 0) check(count);
                            return count;
                        }
                        private void check(int count) throws IOException {
                            received += count;
                            if (received > properties.getMaxMetadataBytes()) throw new IOException("导入清单超过大小限制。");
                        }
                        @Override public boolean isFinished() { return source.isFinished(); }
                        @Override public boolean isReady() { return source.isReady(); }
                        @Override public void setReadListener(ReadListener listener) { source.setReadListener(listener); }
                        @Override public void close() throws IOException { source.close(); }
                    };
                }
            }, response);
            return;
        }
        chain.doFilter(request, response);
    }

    private void reject(HttpServletResponse response, int status, int code, String message) throws IOException {
        response.setStatus(status);
        response.setContentType("application/json;charset=UTF-8");
        json.writeValue(response.getOutputStream(), ResultUtils.error(code, message));
    }
}
