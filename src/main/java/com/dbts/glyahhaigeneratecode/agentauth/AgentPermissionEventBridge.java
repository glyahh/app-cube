package com.dbts.glyahhaigeneratecode.agentauth;

import com.dbts.glyahhaigeneratecode.agent.AgentEvent;
import com.dbts.glyahhaigeneratecode.agent.AgentRunRequest;
import com.dbts.glyahhaigeneratecode.exception.ErrorCode;
import com.dbts.glyahhaigeneratecode.exception.MyException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import reactor.core.publisher.Flux;

import java.util.UUID;

/**
 * 把 Python 的许可事件接到 Java 票据登记。
 *
 * <p>必须是同步处理：Python 侧在等待票据状态，事件处理慢了会拖长整个工具调用。
 * 事件本身仍是内部协议，只把安全投影转给前端。
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class AgentPermissionEventBridge {
    private static final String REQUESTED = "agent.permission.requested";
    private static final String CONSUME = "agent.permission.consume";
    private static final String RESULT = "agent.permission.result";
    private static final String HEARTBEAT = "agent.permission.heartbeat";
    private static final String CHANGE = "agent.change.recorded";

    private final AgentToolPermissionService permissions;
    private final AgentChangeLedgerService changes;
    private final ObjectMapper json;

    /**
     * 让 Python 生成的 sessionId 与 Java 侧会话对齐。
     *
     * <p>会话由 Java 在请求前创建，runId 是唯一关联点；这里按 runId 反查，
     * 不接受执行侧自报的 sessionId 来决定归属。
     */
    public Flux<AgentEvent> attach(Flux<AgentEvent> events, AgentRunRequest request) {
        UUID sessionId = request.getPermissionSessionId() == null ? null
                : UUID.fromString(request.getPermissionSessionId());
        if (sessionId == null) return events;
        long ownerId = Long.parseLong(request.getUserId());
        return events.handle((event, sink) -> {
            // 许可事件先登记票据，再把安全投影放行给前端；原始动作不透明转发。
            AgentEvent forwarded = handle(event, ownerId, sessionId);
            if (forwarded != null) sink.next(forwarded);
        });
    }

    /**
     * 处理一条许可事件。
     *
     * @return 需要转给前端的安全投影；不需要转发时返回 null
     */
    private AgentEvent handle(AgentEvent event, long ownerId, UUID sessionId) {
        String name = event.event();
        if (!REQUESTED.equals(name) && !CONSUME.equals(name) && !RESULT.equals(name)
                && !HEARTBEAT.equals(name) && !CHANGE.equals(name)) {
            return event;
        }
        try {
            // 执行侧自报的 sessionId 必须与 Java 冻结的一致，否则拒绝继续。
            String reported = event.frame().path("sessionId").asText();
            if (!sessionId.toString().equals(reported)) {
                throw new MyException(ErrorCode.CONFLICT_ERROR, "许可会话身份不一致。");
            }
            if (REQUESTED.equals(name)) {
                // 登记票据后把安全投影转给前端，让用户看到待批准卡片。
                var permission = permissions.requested(ownerId, sessionId, event.frame());
                log.info("登记工具许可 session={} permission={} tool={} status={}", sessionId,
                        permission.path("id").asText(), permission.path("action").asText(),
                        permission.path("status").asText());
                return project(event, permission);
            }
            if (CONSUME.equals(name)) {
                permissions.consume(ownerId, sessionId, event.frame());
            } else if (RESULT.equals(name)) {
                permissions.result(ownerId, sessionId, event.frame());
            } else if (CHANGE.equals(name)) {
                changes.record(ownerId, sessionId, event.frame());
            } else {
                permissions.heartbeat(ownerId, sessionId);
            }
            // 其余事件是内部协议，不转前端。
            return null;
        } catch (Exception error) {
            // 处理失败必须让整轮停下：Python 侧继续等待会一直卡到超时。
            // 原始原因必须带进消息里：只报「处理失败」时，现场只剩数据库能查，
            // 而票据状态往往已经被后续清理改掉。
            log.warn("许可事件处理失败 session={} event={} frame={}", sessionId, name,
                    event.frame(), error);
            String detail = error.getMessage() == null ? error.getClass().getSimpleName()
                    : error.getClass().getSimpleName() + ": " + error.getMessage();
            throw new MyException(ErrorCode.OPERATION_ERROR, "许可事件处理失败（" + name + "）：" + detail);
        }
    }

    /** 只转发白名单字段，动作正文与风险细节留在服务端。 */
    private AgentEvent project(AgentEvent origin, JsonNode permission) {
        var frame = json.createObjectNode()
                .put("permissionId", permission.path("id").asText())
                .put("tool", permission.path("action").asText())
                .put("highRisk", permission.path("highRisk").asBoolean())
                .put("status", permission.path("status").asText())
                .put("revision", permission.path("revision").asText());
        frame.set("display", permission.path("display"));
        // 复用原帧的 runId/seq/ts：前端按 seq 单调推进，另造序号会破坏顺序。
        return new AgentEvent(origin.runId(), origin.seq(), origin.ts(), REQUESTED, frame);
    }
}