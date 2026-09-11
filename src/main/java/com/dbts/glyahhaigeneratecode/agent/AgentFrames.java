package com.dbts.glyahhaigeneratecode.agent;

/**
 * 传输层要用的帧构造。
 *
 * <p>两条传输（WebSocket / SSE）都要给前端发同样形状的收尾帧与错误帧，
 * 放在一处避免各写一份后慢慢漂移。
 */
public final class AgentFrames {

    private AgentFrames() {
    }

    /**
     * 业务错误负载。
     *
     * <p>形状与现有 {@code GlobalExceptionHandler} 写出的 business-error 保持一致
     * （{@code {"error":true,"message":"..."}}），前端两条流式路径可以用同一套解析。
     */
    public static String businessErrorPayload(String message) {
        String safe = message == null ? "" : message.replace("\\", "\\\\").replace("\"", "\\\"");
        return "{\"error\":true,\"message\":\"" + safe + "\"}";
    }

    /** WebSocket 上的错误帧：外层带 event 名，内层与 SSE 的 data 同形状。 */
    public static String webSocketBusinessError(String message) {
        return "{\"event\":\"business-error\",\"data\":" + businessErrorPayload(message) + "}";
    }

    /** WebSocket 上的结束帧。 */
    public static String webSocketDone() {
        return "{\"event\":\"done\"}";
    }
}
