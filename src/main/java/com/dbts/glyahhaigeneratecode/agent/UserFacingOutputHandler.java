package com.dbts.glyahhaigeneratecode.agent;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.JsonNodeFactory;
import com.fasterxml.jackson.databind.node.ObjectNode;
import reactor.core.publisher.Flux;

import java.text.Normalizer;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.regex.Pattern;

/** 用户展示出口；不修改执行上下文、作品或原始事件。 */
public final class UserFacingOutputHandler {
    public static final String OMITTED = "部分内部执行信息已省略";
    static final int MAX_TEXT = 65536;
    private static final Pattern INTERNAL = Pattern.compile(
            "(?iu)\\b(sandbox(?:ed|ing|es)?|workspaces?|environment|docker|container|checkpointer|langgraph|langchain|psycopg|traceback)\\b"
            + "|沙箱|容器|工作区|运行环境|执行环境|内部架构|glyahh-sbx-|/mnt/[a-z]/|/var/run/|/home/|/tmp/"
            + "|[a-z]:[\\\\/]|\\b[0-9a-f]{64}\\b|localhost:\\d+|127\\.0\\.0\\.1|\\bat [\\w.$]+\\([^)]*\\.java:\\d+\\)"
            + "|(?:我是|我使用|我运行|I am|I'm).{0,35}(?:Claude|Anthropic|OpenAI|GPT|DeepSeek|Gemini)");
    private static final Set<String> TOOLS = Set.of("write_file", "edit_file", "delete", "delete_file",
            "read_file", "ls", "glob", "grep", "execute", "git_snapshot", "record_finding");

    private UserFacingOutputHandler() { }

    public static String sanitize(String text) {
        if (text == null) return null;
        if (text.length() > MAX_TEXT) return OMITTED;
        StringBuilder out = new StringBuilder();
        for (String line : text.replace("\r\n", "\n").split("\n", -1)) {
            String normalized = Normalizer.normalize(line, Normalizer.Form.NFKC)
                    .replaceAll("[\\p{Cf}]", "");
            out.append(INTERNAL.matcher(normalized).find() ? OMITTED : line).append('\n');
        }
        return out.length() == 0 ? "" : out.substring(0, out.length() - 1);
    }

    public static String history(String type, String text) {
        if ("error".equalsIgnoreCase(type)) return errorMessage(null);
        return "ai".equalsIgnoreCase(type) || "assistant".equalsIgnoreCase(type) ? sanitize(text) : text;
    }

    public static String errorMessage(String code) {
        return switch (code == null ? "" : code) {
            case "IMPORT_EDIT_NOT_READY" -> "导入项目编辑尚未就绪，本次未执行";
            case "MODEL_QUOTA_EXCEEDED" -> "生成服务额度不足，请稍后重试";
            case "RUN_DEADLINE_EXCEEDED", "SANDBOX_TIMEOUT" -> "处理超时，请稍后重试";
            case "SANDBOX_BUSY" -> "当前任务正在处理中，请稍后重试";
            default -> "本次处理未完成，请稍后重试";
        };
    }

    /** 每次订阅持有独立缓冲；只有审核过的完整消息可以离开出口。 */
    public static Flux<AgentEvent> apply(Flux<AgentEvent> source) {
        return Flux.defer(() -> {
            StringBuilder pending = new StringBuilder();
            boolean[] overflow = {false};
            int[] seq = {0};
            String[] lastProcessEvent = {null};
            return source.concatMapIterable(event -> {
                List<AgentEvent> result = new ArrayList<>();
                if (event.isTextDelta()) {
                    if (pending.length() + event.textContent().length() > MAX_TEXT) {
                        overflow[0] = true;
                        pending.setLength(0);
                    } else if (!overflow[0]) pending.append(event.textContent());
                    return result;
                }
                if (AgentEvent.TEXT_MESSAGE.equals(event.event())) {
                    String text = sanitize(event.textContent());
                    pending.setLength(0);
                    overflow[0] = false;
                    addText(result, event, text, seq);
                    return result;
                }
                // 失败或取消不发布未经确认的候选回答。
                if (event.isTerminal() || event.isEnd()) {
                    pending.setLength(0);
                    overflow[0] = false;
                }
                ObjectNode out = envelope(event, seq[0]++);
                JsonNode in = event.frame();
                switch (event.event()) {
                    case AgentEvent.TOOL_STARTED, AgentEvent.TOOL_FINISHED -> {
                        String tool = in.path("toolName").asText();
                        out.put("toolName", TOOLS.contains(tool) ? tool : "operation");
                        out.put("category", "process");
                        out.put("label", toolLabel(tool));
                        copy(in, out, "callId", "durationMs");
                        if (AgentEvent.TOOL_FINISHED.equals(event.event()))
                            out.put("status", "succeeded".equals(in.path("status").asText()) ? "succeeded" : "failed");
                        // 仅 execute 安全转发预览，其余工具不暴露参数和输出
                        if ("execute".equals(tool)) {
                            safePreview(in, out, "inputPreview");
                            if (AgentEvent.TOOL_FINISHED.equals(event.event()))
                                safePreview(in, out, "outputPreview");
                        }
                    }
                    case AgentEvent.STATUS, AgentEvent.STEP, AgentEvent.TODO_DELTA -> {
                        String dedupKey = event.event() + ":" + in.path("status").asText("") + ":" + in.path("message").asText("");
                        if (dedupKey.equals(lastProcessEvent[0])) return result;
                        lastProcessEvent[0] = dedupKey;
                        out.put("category", "process");
                        out.put("message", "正在处理");
                    }
                    case AgentEvent.THINKING_STARTED, AgentEvent.THINKING_DELTA, AgentEvent.THINKING_COMPLETED -> {
                        // 思考阶段单独透传，避免被执行过程的通用事件吞并。
                        out.put("category", "thinking");
                        String id = in.path("thinkingId").asText();
                        if (!id.matches("[A-Za-z0-9_:.\\-]{1,200}")) return result;
                        out.put("thinkingId", id);
                        for (String field : List.of("summary", "details")) {
                            if (in.path(field).isTextual()) out.put(field, sanitize(in.path(field).asText()));
                        }
                        copy(in, out, "status");
                    }
                    case AgentEvent.ERROR -> {
                        out.put("code", "GENERATION_FAILED");
                        out.put("message", errorMessage(event.errorCode()));
                        out.put("retryable", in.path("retryable").asBoolean(false));
                        String failureKey=in.path("failureKey").asText();
                        if(failureKey.matches("IMPORT_[A-Z_]{1,70}"))out.put("failureKey",failureKey);
                    }
                    case AgentEvent.RUN_COMPLETED -> {
                        out.put("status", "succeeded".equals(event.status()) ? "succeeded" : "failed");
                        copy(in, out, "durationMs");
                    }
                    case AgentEvent.MODEL_ROUTE_FALLBACK -> {
                        out.put("fromRoute", "proxy");
                        out.put("toRoute", "direct");
                        out.put("reason", "connect_error");
                    }
                    case "import.edit.model.audit" -> {
                        // 审计帧只包含编辑身份、数量和请求摘要；通过白名单转发，不能把内部请求正文带出。
                        String editId = in.path("editId").asText();
                        int count = in.path("count").asInt(-1);
                        int blocked = in.path("blocked").asInt(-1);
                        JsonNode digests = in.path("digests");
                        // ImportAgentEditExecution 已在发布前完成完整格式校验；这里仅复制白名单字段，
                        // 避免展示层的重复校验把已确认的审计事件静默吞掉。
                        if (!editId.matches("[0-9a-fA-F-]{36}") || count < 1 || blocked != 0
                                || !digests.isArray() || digests.size() != count) return result;
                        out.put("editId", editId).put("count", count).put("blocked", blocked);
                        out.set("digests", digests.deepCopy());
                    }
                    case AgentEvent.RUN_STARTED, AgentEvent.HEARTBEAT, AgentEvent.END -> { }
                    case AgentEvent.ARTIFACT_CHANGED, AgentEvent.ARTIFACT_MANIFEST -> {
                        // 作品通过已有的授权文件接口读取，不转发执行层路径或原始清单。
                    }
                    default -> { return result; }
                }
                result.add(wrap(out));
                return result;
            });
        });
    }

    private static void addText(List<AgentEvent> result, AgentEvent event, String text, int[] seq) {
        for (String kind : List.of(AgentEvent.TEXT_DELTA, AgentEvent.TEXT_MESSAGE)) {
            ObjectNode out = envelope(event, seq[0]++);
            out.put("event", kind).put("category", "answer").put("content", text).put("mode", "append");
            copy(event.frame(), out, "messageId");
            result.add(wrap(out));
        }
    }

    private static ObjectNode envelope(AgentEvent event, int seq) {
        return JsonNodeFactory.instance.objectNode().put("v", 1).put("runId", event.runId())
                .put("seq", seq).put("ts", event.ts()).put("event", event.event());
    }

    private static void copy(JsonNode in, ObjectNode out, String... fields) {
        for (String field : fields) {
            if (!in.has(field)) continue;
            if ("durationMs".equals(field)) out.put(field, Math.max(0L, in.path(field).asLong()));
            else if (in.path(field).asText().matches("[A-Za-z0-9_-]{1,128}")) out.put(field, in.path(field).asText());
        }
    }

    private static AgentEvent wrap(ObjectNode node) {
        return new AgentEvent(node.path("runId").asText(), node.path("seq").asInt(),
                node.path("ts").asLong(), node.path("event").asText(), node);
    }

    /** 经 sanitize 清洗后转发预览字段；清洗后为空则不写入。 */
    private static void safePreview(JsonNode in, ObjectNode out, String field) {
        String raw = in.path(field).asText("");
        if (raw.isEmpty()) return;
        String safe = sanitize(raw);
        if (safe != null && !safe.isBlank()) out.put(field, safe);
    }

    private static String toolLabel(String tool) {
        return switch (tool) {
            case "write_file", "edit_file" -> "正在生成页面";
            case "read_file", "ls", "glob", "grep" -> "正在检查文件";
            case "execute", "git_snapshot" -> "正在检查结果";
            default -> "正在处理";
        };
    }
}
