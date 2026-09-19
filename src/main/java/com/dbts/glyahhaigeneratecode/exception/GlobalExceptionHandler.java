package com.dbts.glyahhaigeneratecode.exception;

import cn.hutool.json.JSONUtil;
import com.dbts.glyahhaigeneratecode.common.BaseResponse;
import com.dbts.glyahhaigeneratecode.common.ResultUtils;
import io.swagger.v3.oas.annotations.Hidden;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import lombok.extern.slf4j.Slf4j;
import org.springframework.web.bind.MissingServletRequestParameterException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.context.request.RequestContextHolder;
import org.springframework.web.context.request.ServletRequestAttributes;
import org.springframework.web.method.annotation.MethodArgumentTypeMismatchException;

import java.io.IOException;
import java.util.Map;

/**
 * 全局异常处理器
 */

// 在 Swagger/OpenAPI 文档里隐藏这个类，不当作业务接口展示
@Hidden
// 告诉 Spring 这是全局异常处理类，拦截所有控制器异常
@RestControllerAdvice
@Slf4j
public class GlobalExceptionHandler {

    /**
     * 会以 SSE 形式响应的路径。
     *
     * <p>同步阶段（鉴权、参数、限流）抛出的异常要走这里转成 SSE 事件，
     * 否则前端拿到的是 JSON，只会触发 EventSource 的 onerror，看不到原因。
     * **新增流式端点时必须登记到这里。**
     */
    private static final String[] SSE_PATHS = {
            "/chat/generate/stream",
    };

    /**
     * 路径变量 / 查询参数类型不对，或必填参数缺失。
     *
     * <p>例如 {@code /app/abc/workspace} 里的 appId 不是数字、{@code /files/content}
     * 没带 path。这些是调用方传错了参数，不是服务端故障 —— 不单独接住的话
     * 会落到 RuntimeException 分支变成「系统错误」，用户看到 500
     * 却完全不知道是自己地址写错了。
     */
    @ExceptionHandler({MethodArgumentTypeMismatchException.class,
            MissingServletRequestParameterException.class})
    public BaseResponse<?> badRequestHandler(Exception e) {
        log.warn("请求参数不合法: {}", e.getMessage());
        String name = e instanceof MethodArgumentTypeMismatchException mismatch
                ? mismatch.getName()
                : ((MissingServletRequestParameterException) e).getParameterName();
        return ResultUtils.error(ErrorCode.PARAMS_ERROR.getCode(), "参数 " + name + " 格式不正确或缺失");
    }

    @ExceptionHandler(MyException.class)
    public BaseResponse<?> businessExceptionHandler(MyException e) {
        log.error("BusinessException", e);
        // 尝试处理 SSE 请求
        if (handleSseError(e.getCode(), e.getMessage())) {
            return null;
        }
        // 业务异常的消息是开发者手写给用户看的（「只能查看自己的应用」这类），
        // 原样返回。以前它要过一遍 UserFacingOutputHandler.sanitize，
        // 那条规则会把含「容器」「工作区」或十六进制 id 的消息整句替换成
        // 「部分内部执行信息已省略」—— 用户看不到自己哪里做错了。
        // 真正需要兜底的是未捕获异常，那里本来就不透传消息。
        return ResultUtils.error(e.getCode(), e.getMessage());
    }

    @ExceptionHandler(RuntimeException.class)
    public BaseResponse<?> runtimeExceptionHandler(RuntimeException e) {
        log.error("RuntimeException", e);
        // 尝试处理 SSE 请求
        if (handleSseError(ErrorCode.SYSTEM_ERROR.getCode(), "系统错误")) {
            return null;
        }
        return ResultUtils.error(ErrorCode.SYSTEM_ERROR, "系统错误");
    }

    /**
     * 判断这个请求要不要按 SSE 格式回错误。
     *
     * <p>两条依据：Accept 头（浏览器 EventSource 会带），或命中登记的流式路径。
     */
    private boolean isSseRequest(String accept, String uri) {
        if (accept != null && accept.contains("text/event-stream")) {
            return true;
        }
        if (uri == null) {
            return false;
        }
        for (String path : SSE_PATHS) {
            if (uri.contains(path)) {
                return true;
            }
        }
        return false;
    }

    /**
     * 处理SSE请求的错误响应
     *
     * @param errorCode 错误码
     * @param errorMessage 错误信息
     * @return true表示是SSE请求并已处理，false表示不是SSE请求
     */
    private boolean handleSseError(int errorCode, String errorMessage) {
        ServletRequestAttributes attributes = (ServletRequestAttributes) RequestContextHolder.getRequestAttributes();
        if (attributes == null) {
            return false;
        }
        HttpServletRequest request = attributes.getRequest();
        HttpServletResponse response = attributes.getResponse();
        // 判断是否是SSE请求（通过Accept头或URL路径）
        String accept = request.getHeader("Accept");
        String uri = request.getRequestURI();
        if (isSseRequest(accept, uri)) {
            if (response == null) {
                return false;
            }
            try {
                // 设置SSE响应头
                response.setContentType("text/event-stream");
                response.setCharacterEncoding("UTF-8");
                response.setHeader("Cache-Control", "no-cache");
                response.setHeader("Connection", "keep-alive");
                // 构造错误消息的SSE格式
                Map<String, Object> errorData = Map.of(
                        "error", true,
                        "code", errorCode,
                        "message", com.dbts.glyahhaigeneratecode.agent.UserFacingOutputHandler.sanitize(errorMessage)
                );
                String errorJson = JSONUtil.toJsonStr(errorData);
                // 发送业务错误事件（避免与标准error事件冲突）
                String sseData = "event: business-error\ndata: " + errorJson + "\n\n";
                response.getWriter().write(sseData);
                response.getWriter().flush();
                // 发送结束事件
                response.getWriter().write("event: done\ndata: {}\n\n");
                response.getWriter().flush();
                // 表示已处理SSE请求
                return true;
            } catch (IOException ioException) {
                log.error("Failed to write SSE error response", ioException);
                // 即使写入失败，也表示这是SSE请求
                return true;
            }
        }
        return false;
    }
}
