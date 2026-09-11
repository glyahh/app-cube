package com.dbts.glyahhaigeneratecode.agent;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

import java.io.IOException;

/**
 * Python 侧发来的一帧事件。
 *
 * <p>帧格式是 NDJSON，每行一个 JSON 对象，统一带信封 {@code {v, runId, seq, ts, event}}。
 * 契约见 docs/AGENT_CONTRACT.md。
 *
 * @param runId 所属 run
 * @param seq   per-run 单调递增序号，用于去重与乱序检测
 * @param ts    毫秒时间戳
 * @param event 事件名
 * @param frame 整帧原始 JSON，payload 字段直接从它取
 */
public record AgentEvent(String runId, int seq, long ts, String event, JsonNode frame) {

    /** 终止帧。读到它就说明这一轮结束，进程可以归还池子。 */
    public static final String END = "__end__";

    public static final String RUN_STARTED = "run.started";
    public static final String STATUS = "status";
    public static final String HEARTBEAT = "heartbeat";
    public static final String TEXT_DELTA = "text.delta";
    public static final String TEXT_MESSAGE = "text.message";
    public static final String TODO_DELTA = "todo.delta";
    public static final String STEP = "step";
    public static final String TOOL_STARTED = "tool.started";
    public static final String TOOL_FINISHED = "tool.finished";
    public static final String ARTIFACT_CHANGED = "artifact.changed";
    public static final String ARTIFACT_MANIFEST = "artifact.manifest";
    public static final String ERROR = "error";
    public static final String RUN_COMPLETED = "run.completed";

    /**
     * 解析一行 NDJSON。
     *
     * @throws IOException 不是合法 JSON，或缺少必要的信封字段
     */
    public static AgentEvent parse(ObjectMapper mapper, String line) throws IOException {
        JsonNode node = mapper.readTree(line);
        if (node == null || !node.isObject()) {
            throw new IOException("帧不是一个 JSON 对象");
        }
        JsonNode eventNode = node.get("event");
        if (eventNode == null || eventNode.asText().isEmpty()) {
            throw new IOException("帧缺少 event 字段");
        }
        return new AgentEvent(
                text(node, "runId"),
                node.path("seq").asInt(-1),
                node.path("ts").asLong(0L),
                eventNode.asText(),
                node);
    }

    private static String text(JsonNode node, String field) {
        JsonNode value = node.get(field);
        return value == null ? "" : value.asText();
    }

    public boolean isEnd() {
        return END.equals(event);
    }

    public boolean isHeartbeat() {
        return HEARTBEAT.equals(event);
    }

    public boolean isError() {
        return ERROR.equals(event);
    }

    public boolean isTextDelta() {
        return TEXT_DELTA.equals(event);
    }

    /** 文本增量内容。非文本事件返回空串。 */
    public String textContent() {
        return frame.path("content").asText("");
    }

    /**
     * 终止性事件：这一轮不会再产生业务输出。
     *
     * <p>注意终止帧不算「终态」—— 它是传输层的收尾，不代表 run 成功或失败。
     */
    public boolean isTerminal() {
        return RUN_COMPLETED.equals(event) || ERROR.equals(event);
    }

    public boolean isFailure() {
        return ERROR.equals(event);
    }

    public String errorCode() {
        return frame.path("code").asText("");
    }

    public String errorMessage() {
        return frame.path("message").asText("");
    }

    public String status() {
        return frame.path("status").asText("");
    }
}
