package com.dbts.glyahhaigeneratecode.controller;

import com.dbts.glyahhaigeneratecode.model.Entity.AppRuntime;
import com.dbts.glyahhaigeneratecode.model.Entity.User;
import com.dbts.glyahhaigeneratecode.runtime.RuntimeOrchestrator;
import com.dbts.glyahhaigeneratecode.runtime.RuntimeTrafficStats;
import com.dbts.glyahhaigeneratecode.service.AppService;
import com.dbts.glyahhaigeneratecode.service.UserService;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.URI;
import java.net.URISyntaxException;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.file.Path;
import java.time.Duration;
import java.util.List;
import java.util.Locale;
import java.util.Set;

/**
 * 项目访问网关：把 {@code /app/{appId}/runtime/gateway/**} 转发到运行容器的端口。
 *
 * <p>三条硬约束，缺一条都会变成漏洞：
 * <ol>
 *   <li><b>目标不接受客户端输入</b>：目标端口来自该应用当前运行记录的容器映射，
 *       URL 里多出来的路径段只是被转发的资源路径，拼不出别的主机。</li>
 *   <li><b>Cookie 彻底剥离</b>：平台 Cookie 永远不随请求转发给项目容器。</li>
 *   <li><b>严格限制代理请求体大小</b>：防止大报文直接撑爆 JVM 堆内存导致 OOM。</li>
 * </ol>
 *
 * <p>QPS 与错误率在这里统计 —— 这是「经过平台项目访问入口的请求」的唯一口径。
 * 健康检查与平台自身的轮询不计入，否则空闲应用也会显示流量。
 */
@RestController
@RequestMapping("/app")
@RequiredArgsConstructor
@Slf4j
public class RuntimeGatewayController {

    private final RuntimeOrchestrator orchestrator;
    private final RuntimeTrafficStats trafficStats;
    private final AppService appService;
    private final UserService userService;

    /** 单次转发允许的最大请求体：10MB，防止恶意大报文消耗堆内存造成 OOM。 */
    private static final int MAX_REQUEST_BODY_BYTES = 10 * 1024 * 1024;

    /** 逐跳首部与被显式替换的首部，不参与转发。 */
    private static final Set<String> BLOCKED_REQUEST_HEADERS = Set.of(
            "host", "connection", "keep-alive", "proxy-authenticate", "proxy-authorization",
            "te", "trailer", "transfer-encoding", "upgrade", "content-length",
            // 平台会话不外泄给生成的应用
            "cookie");

    private static final Set<String> BLOCKED_RESPONSE_HEADERS = Set.of(
            "connection", "keep-alive", "proxy-authenticate", "proxy-authorization",
            "te", "trailer", "transfer-encoding", "upgrade", "content-length");

    /** 健康检查路径：不算用户流量。 */
    private static final List<String> HEALTH_PATHS = List.of("/health", "/healthz", "/ping", "/favicon.ico");

    private final HttpClient httpClient = HttpClient.newBuilder()
            .connectTimeout(Duration.ofSeconds(5))
            .followRedirects(HttpClient.Redirect.NEVER)
            .build();

    /**
     * 转发入口。用 {@code /**} 兜住全部方法与子路径，避免为每种资源各写一个映射。
     */
    @RequestMapping("/{appId}/runtime/gateway/**")
    public void proxy(HttpServletRequest request, HttpServletResponse response) throws IOException {
        Long appId = parseAppId(request.getRequestURI());
        if (appId == null) {
            writeError(response, HttpServletResponse.SC_BAD_REQUEST, "无法解析应用 id");
            return;
        }

        User loginUser = userService.getUserInSession(request);
        // 归属校验：不是本人也不是管理员就直接拒绝
        appService.resolveWorkspaceDir(loginUser, appId);

        AppRuntime runtime = orchestrator.activeRun(appId);
        if (runtime == null || runtime.getGatewayPort() == null) {
            writeError(response, HttpServletResponse.SC_SERVICE_UNAVAILABLE, "应用未在运行");
            return;
        }

        String subPath = extractSubPath(request.getRequestURI(), appId);
        String query = request.getQueryString();

        URI targetUri;
        try {
            targetUri = new URI("http", null, "127.0.0.1", runtime.getGatewayPort(),
                    subPath, (query == null || query.isBlank() ? null : query), null);
        } catch (URISyntaxException | IllegalArgumentException e) {
            writeError(response, HttpServletResponse.SC_BAD_REQUEST, "请求路径或参数格式不合法");
            return;
        }

        boolean healthCheck = HEALTH_PATHS.stream().anyMatch(subPath::startsWith);

        // 限制请求体长度，防止超大请求耗尽 JVM 堆内存
        long contentLength = request.getContentLengthLong();
        if (contentLength > MAX_REQUEST_BODY_BYTES) {
            writeError(response, HttpServletResponse.SC_REQUEST_ENTITY_TOO_LARGE, "请求体超过 10MB 限制");
            return;
        }

        try {
            HttpRequest.Builder builder = HttpRequest.newBuilder(targetUri)
                    .timeout(Duration.ofSeconds(30));

            byte[] body;
            try (InputStream in = request.getInputStream()) {
                body = in.readNBytes(MAX_REQUEST_BODY_BYTES + 1);
            }
            if (body.length > MAX_REQUEST_BODY_BYTES) {
                writeError(response, HttpServletResponse.SC_REQUEST_ENTITY_TOO_LARGE, "请求体超过 10MB 限制");
                return;
            }

            builder.method(request.getMethod(),
                    body.length == 0
                            ? HttpRequest.BodyPublishers.noBody()
                            : HttpRequest.BodyPublishers.ofByteArray(body));

            var headers = request.getHeaderNames();
            while (headers.hasMoreElements()) {
                String name = headers.nextElement();
                if (BLOCKED_REQUEST_HEADERS.contains(name.toLowerCase(Locale.ROOT))) {
                    continue;
                }
                var values = request.getHeaders(name);
                while (values.hasMoreElements()) {
                    builder.header(name, values.nextElement());
                }
            }

            HttpResponse<InputStream> upstream = httpClient.send(
                    builder.build(), HttpResponse.BodyHandlers.ofInputStream());

            trafficStats.record(appId, upstream.statusCode(), healthCheck);

            response.setStatus(upstream.statusCode());
            upstream.headers().map().forEach((name, values) -> {
                if (BLOCKED_RESPONSE_HEADERS.contains(name.toLowerCase(Locale.ROOT))) {
                    return;
                }
                for (String value : values) {
                    response.addHeader(name, value);
                }
            });

            try (InputStream in = upstream.body(); OutputStream out = response.getOutputStream()) {
                in.transferTo(out);
            }
            response.flushBuffer();
        } catch (IOException e) {
            trafficStats.record(appId, 502, healthCheck);
            log.warn("网关转发失败 appId={} target={}: {}", appId, targetUri, e.getMessage());
            if (!response.isCommitted()) {
                writeError(response, HttpServletResponse.SC_BAD_GATEWAY, "应用暂时无法访问");
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            trafficStats.record(appId, 502, healthCheck);
            if (!response.isCommitted()) {
                writeError(response, HttpServletResponse.SC_BAD_GATEWAY, "应用请求被中断");
            }
        }
    }

    /** 从 {@code /api/app/123/runtime/gateway/xxx} 里取出 appId。 */
    private static Long parseAppId(String uri) {
        String[] parts = uri.split("/");
        for (int i = 0; i < parts.length - 1; i++) {
            if ("app".equals(parts[i])) {
                try {
                    return Long.parseLong(parts[i + 1]);
                } catch (NumberFormatException e) {
                    return null;
                }
            }
        }
        return null;
    }

    /** 取出 {@code gateway/} 之后的资源路径；没有则返回 {@code /}。 */
    private static String extractSubPath(String uri, Long appId) {
        String marker = "/app/" + appId + "/runtime/gateway";
        int index = uri.indexOf(marker);
        if (index < 0) {
            return "/";
        }
        String sub = uri.substring(index + marker.length());
        return sub.isEmpty() ? "/" : sub;
    }

    private static void writeError(HttpServletResponse response, int status, String message) throws IOException {
        response.setStatus(status);
        response.setContentType("application/json; charset=UTF-8");
        response.getWriter().write("{\"code\":" + status + ",\"message\":\"" + message + "\"}");
    }
}
