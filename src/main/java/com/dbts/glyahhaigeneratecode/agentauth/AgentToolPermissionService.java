package com.dbts.glyahhaigeneratecode.agentauth;

import com.dbts.glyahhaigeneratecode.exception.ErrorCode;
import com.dbts.glyahhaigeneratecode.exception.MyException;
import com.dbts.glyahhaigeneratecode.exception.ThrowUtils;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;

import java.time.Instant;
import java.util.List;
import java.util.Set;
import java.util.UUID;

/**
 * 普通应用的工具许可。
 *
 * <p>两轴顺序不可颠倒：沙箱拒绝直接 DENY，不进入审批环节，
 * 因此不存在「危险操作经批准后放行」。
 */
@Service
@RequiredArgsConstructor
@Slf4j
public class AgentToolPermissionService {
    public record DecisionDTO(UUID requestId, String actionHash, String decision, long revision) { }

    private static final Set<String> TOOLS =
            Set.of("write", "edit", "delete", "upload_files", "execute");
    private static final Set<String> DECISIONS = Set.of("APPROVE_ONCE", "APPROVE_SIMILAR", "REJECT");

    private final AgentToolPermissionRepository permissions;
    private final AgentEditSessionRepository sessions;
    private final com.dbts.glyahhaigeneratecode.projectimport.ImportAgentModelClient reviewer;
    private final AgentModelsService models;
    private final ObjectMapper json;
    private final JdbcTemplate jdbc;

    /**
     * Python 报告一个待执行动作。
     *
     * <p>动作哈希由执行侧从真实工作区重算，这里复核格式但不采信其风险结论。
     */
    public JsonNode requested(long ownerId, UUID sessionId, JsonNode frame) throws java.io.IOException {
        var session = sessions.require(sessionId, ownerId);
        sessions.qualified(session);

        UUID permissionId = uuid(frame.path("permissionId").asText());
        JsonNode action = frame.path("action");
        ThrowUtils.throwIf(!action.isObject(), ErrorCode.PARAMS_ERROR, "工具动作无效。");
        String tool = action.path("tool").asText();
        ThrowUtils.throwIf(!TOOLS.contains(tool), ErrorCode.PARAMS_ERROR, "工具类型无效。");
        String hash = AgentCanonicalJson.hash(action);
        ThrowUtils.throwIf(!hash.equals(frame.path("actionHash").asText()),
                ErrorCode.CONFLICT_ERROR, "工具动作已变化。");

        // 1. 沙箱硬边界：只读会话拒绝一切写操作与命令执行，不进入审批。
        ThrowUtils.throwIf(!AgentSandboxPolicy.allowsWrite(session.sandboxMode()),
                ErrorCode.NO_AUTH_ERROR, "当前会话为只读，不允许执行该操作。");

        String risk = highRisk(action) ? "HIGH" : "ORDINARY";
        // execute 的 argv/cwd 不能证明脚本或其他输入未变化，暂不支持同类授权。
        // 记录完整动作范围用于审计，但每次仍必须取得独立票据。
        boolean reusable = !"execute".equals(tool);
        String scope = reusable
                ? AgentCanonicalJson.hash(json.createObjectNode().put("scope", action.path("path").asText()))
                : hash;

        // 2. 审批策略必须在落库之前算完：独立审查可能十几秒，
        //    先 insert 会让前端轮询读到 REQUESTED，自动批准也会弹卡片。
        String source = null;
        if (reusable && permissions.granted(sessionId, tool, risk, scope)) {
            source = "SESSION";
        } else if ("AUTO".equals(session.approvalMode())) {
            source = "AUTO";
        } else if ("GUARDED_AUTO".equals(session.approvalMode())) {
            // 规则判 ORDINARY 只是前提；还必须由服务端独立审查确认，
            // 审查失败或判高风险一律回到用户，不降级放行。
            if ("ORDINARY".equals(risk) && approvedByReview(hash, action)) source = "AI";
        }

        UUID id = permissions.insert(permissionId, sessionId, tool, hash, action.toString(), risk, scope,
                action.toString(), session.expiresAt());
        if (source != null) autoDecide(ownerId, id, source);
        return current(ownerId, id);
    }

    /**
     * 自动决定票据；人工决定优先。
     *
     * <p>insert 到 decide 之间仍有极短窗口，用户可能已经点了批准。
     * 这种情况 decide 会因 revision 不匹配失败，但票据已经由 USER 决定，
     * 不应该把整轮判成失败。
     */
    private void autoDecide(long ownerId, UUID id, String source) {
        try {
            permissions.decide(id, "APPROVED", source, null, 0);
        } catch (MyException error) {
            var row = permissions.require(id, ownerId);
            if ("USER".equals(row.decisionSource())) return;
            throw error;
        }
    }

    /**
     * 独立风险审查：无工程工具的一次模型调用，只判风险等级并绑定对象哈希。
     *
     * <p>失败即视为不可放行，返回 false；不把异常吞成批准。
     */
    private boolean approvedByReview(String actionHash, JsonNode action) {
        try {
            var result = reviewer.review("ACTION", actionHash, action);
            return "ORDINARY".equals(result.path("risk").asText())
                    && actionHash.equals(result.path("objectHash").asText());
        } catch (Exception error) {
            log.warn("独立风险审查失败，本次不自动放行 action={}", actionHash, error);
            return false;
        }
    }

    /** 当前应用待决票据的安全投影；没有则返回 null。 */
    public JsonNode pending(Long ownerId, long appId) {
        models.requireVisible(ownerId, appId);
        UUID id = permissions.pendingByApp(ownerId, appId);
        return id == null ? null : current(ownerId, id);
    }

    public JsonNode current(long ownerId, UUID id) {
        var row = permissions.require(id, ownerId);
        var output = json.createObjectNode()
                .put("id", row.id().toString())
                .put("sessionId", row.sessionId().toString())
                .put("actionHash", row.actionHash())
                .put("action", row.tool())
                .put("highRisk", "HIGH".equals(row.risk()))
                .put("status", row.status())
                .put("revision", Long.toString(row.revision()));
        output.put("detailText", row.actionJson());
        try {
            output.set("display", json.readTree(row.displayJson()));
        } catch (java.io.IOException error) {
            throw conflict();
        }
        return output;
    }

    public JsonNode decide(long ownerId, UUID id, DecisionDTO input) {
        ThrowUtils.throwIf(input == null || input.requestId() == null || input.actionHash() == null
                        || !input.actionHash().matches("[0-9a-f]{64}") || input.decision() == null
                        || !DECISIONS.contains(input.decision()) || input.revision() < 0,
                ErrorCode.PARAMS_ERROR, "工具决定无效。");
        var row = permissions.require(id, ownerId);
        ThrowUtils.throwIf(!row.actionHash().equals(input.actionHash()),
                ErrorCode.CONFLICT_ERROR, "工具动作已变化，请刷新后重试。");
        // 会话过期或已关闭后不能再批准：执行侧早已超时不会回来消费，
        // 放行只会留下永远 APPROVED 的脏票据。
        var session = sessions.require(row.sessionId(), ownerId);
        sessions.qualified(session);
        boolean reject = "REJECT".equals(input.decision());
        boolean similar = "APPROVE_SIMILAR".equals(input.decision());
        ThrowUtils.throwIf(similar && "execute".equals(row.tool()), ErrorCode.PARAMS_ERROR,
                "执行命令不支持同类授权，请选择仅批准本次。");
        permissions.decide(id, reject ? "REJECTED" : "APPROVED", "USER", input.requestId(), input.revision());
        if (similar) {
            permissions.grant(id, row.sessionId(), row.tool(), row.risk(), row.scopeHash(),
                    session.expiresAt());
        }
        return current(ownerId, id);
    }

    /** Python 在执行前请求消费；返回的证明由执行侧核对后才允许副作用。 */
    public void consume(long ownerId, UUID sessionId, JsonNode frame) {
        var session = sessions.require(sessionId, ownerId);
        sessions.qualified(session);
        UUID id = uuid(frame.path("permissionId").asText());
        permissions.consume(id, frame.path("actionHash").asText(),
                uuid(frame.path("consumeRequestId").asText()));
    }

    public void result(long ownerId, UUID sessionId, JsonNode frame) {
        sessions.require(sessionId, ownerId);
        String state = frame.path("state").asText();
        ThrowUtils.throwIf(!List.of("RESULT_CONFIRMED", "RESULT_UNCONFIRMED").contains(state),
                ErrorCode.PARAMS_ERROR, "工具结果状态无效。");
        permissions.result(uuid(frame.path("permissionId").asText()), state,
                uuid(frame.path("consumeProof").asText()));
    }

    /** 心跳：确认会话仍然有效，失效即让执行侧断流。 */
    public void heartbeat(long ownerId, UUID sessionId) {
        sessions.qualified(sessions.require(sessionId, ownerId));
    }

    /** 高风险判定：执行、删除、配置、脚本与不可见内容的变更都归 HIGH。 */
    static boolean highRisk(JsonNode action) {
        String tool = action.path("tool").asText();
        if ("execute".equals(tool) || "delete".equals(tool) || "upload_files".equals(tool)) return true;
        // Windows 上执行侧会用反斜杠：不归一化时文件名会被当成目录部分，判定直接失效。
        String path = action.path("path").asText().replace('\\', '/').toLowerCase(java.util.Locale.ROOT);
        if (path.isEmpty()) return false;
        String name = path.substring(path.lastIndexOf('/') + 1);
        // 隐藏文件不只看根目录：`sub/.env` 与 `.env` 同样敏感。
        boolean hidden = java.util.Arrays.stream(path.split("/")).anyMatch(part -> part.startsWith("."));
        return hidden || path.startsWith("scripts/") || path.contains("/scripts/") || path.contains("/migration/")
                || name.matches("(readme|config|application|package|requirements|compose).*|pom\\.xml|dockerfile|uv\\.lock|pyproject\\.toml")
                || name.matches(".*\\.(sql|lock|sh|ps1|cmd|bat|ya?ml)");
    }

    private static UUID uuid(String value) {
        try {
            UUID id = UUID.fromString(value);
            if (!id.toString().equals(value)) throw conflict();
            return id;
        } catch (IllegalArgumentException error) {
            throw conflict();
        }
    }

    private static MyException conflict() {
        return new MyException(ErrorCode.CONFLICT_ERROR, "工具审批对象或资格已变化，请刷新后重试。");
    }
}