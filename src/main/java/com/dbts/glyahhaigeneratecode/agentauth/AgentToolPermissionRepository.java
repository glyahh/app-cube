package com.dbts.glyahhaigeneratecode.agentauth;

import com.dbts.glyahhaigeneratecode.exception.ErrorCode;
import com.dbts.glyahhaigeneratecode.exception.MyException;
import lombok.RequiredArgsConstructor;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

import java.sql.Timestamp;
import java.time.Instant;
import java.util.List;
import java.util.UUID;

/**
 * 普通应用的许可票据读写。
 *
 * <p>所有状态推进都用 CAS；对象字段（动作、工具、风险、范围）一经写入不可改，
 * 动作变化必须重新申请，不能沿用旧票。
 */
@Repository
@RequiredArgsConstructor
public class AgentToolPermissionRepository {
    private final JdbcTemplate jdbc;

    public record Row(UUID id, UUID sessionId, String tool, String actionHash, String actionJson,
                      String risk, String scopeHash, String displayJson, String status,
                      long revision, String decisionSource, UUID consumptionProof, Instant expiresAt) { }

    public Row require(UUID id, long ownerId) {
        var rows = jdbc.query("""
                SELECT p.* FROM biz.agent_tool_permission p
                JOIN biz.agent_edit_session s ON s.session_id = p.session_id
                WHERE p.permission_id=? AND s.owner_id=?
                """, (row, index) -> new Row(row.getObject("permission_id", UUID.class),
                row.getObject("session_id", UUID.class), row.getString("tool"),
                row.getString("action_hash"), row.getString("action_json"),
                row.getString("risk"), row.getString("scope_hash"), row.getString("display_json"),
                row.getString("status"), row.getLong("revision"), row.getString("decision_source"),
                row.getObject("consumption_proof", UUID.class),
                row.getTimestamp("expires_at").toInstant()), id, ownerId);
        if (rows.size() != 1) throw new MyException(ErrorCode.NOT_FOUND_ERROR, "工具审批不存在。");
        return rows.getFirst();
    }

    /** 票据 ID 由执行侧生成并随事件上报，这里按该 ID 落库以便两侧对齐。 */
    public UUID insert(UUID permissionId, UUID sessionId, String tool, String actionHash,
                       String actionJson, String risk, String scopeHash, String displayJson,
                       Instant expiresAt) {
        jdbc.update("""
                INSERT INTO biz.agent_tool_permission(permission_id,session_id,tool,action_hash,
                    action_json,risk,scope_hash,display_json,expires_at)
                VALUES(?,?,?,?,CAST(? AS jsonb),?,?,CAST(? AS jsonb),?)
                """, permissionId, sessionId, tool, actionHash, actionJson, risk, scopeHash, displayJson,
                Timestamp.from(expiresAt));
        return permissionId;
    }

    /** 只有 REQUESTED 且未过期才能给出决定；source 必须与冻结的审批模式一致。 */
    public void decide(UUID id, String status, String source, UUID requestId, long revision) {
        if (jdbc.update("""
                UPDATE biz.agent_tool_permission SET status=?,decision_source=?,decision_request_id=?,
                    revision=revision+1
                WHERE permission_id=? AND revision=? AND status='REQUESTED' AND expires_at>now()
                """, status, source, requestId, id, revision) != 1) {
            throw conflict();
        }
    }

    public UUID consume(UUID id, String actionHash, UUID consumeRequestId) {
        UUID proof = UUID.randomUUID();
        if (jdbc.update("""
                UPDATE biz.agent_tool_permission SET status='CONSUMED',consumption_proof=?,
                    consume_request_id=?,revision=revision+1
                WHERE permission_id=? AND status='APPROVED' AND action_hash=? AND expires_at>now()
                """, proof, consumeRequestId, id, actionHash) != 1) {
            throw conflict();
        }
        return proof;
    }

    public void result(UUID id, String state, UUID proof) {
        if (jdbc.update("""
                UPDATE biz.agent_tool_permission SET status=?,revision=revision+1
                WHERE permission_id=? AND status='CONSUMED' AND consumption_proof=?
                """, state, id, proof) != 1) {
            throw conflict();
        }
    }

    /** 该应用未关闭且未过期会话下的待决票据；没有则返回 null。前端靠它显示批准卡片。 */
    public UUID pendingByApp(long ownerId, long appId) {
        var rows = jdbc.queryForList("""
                SELECT p.permission_id FROM biz.agent_tool_permission p
                JOIN biz.agent_edit_session s ON s.session_id=p.session_id
                WHERE s.owner_id=? AND s.app_id=? AND s.closed_at IS NULL AND s.expires_at>now()
                  AND p.status='REQUESTED' AND p.expires_at>now()
                LIMIT 1
                """, UUID.class, ownerId, appId);
        return rows.isEmpty() ? null : rows.getFirst();
    }

    /** 该会话当前待决票据；没有则返回 null。 */
    public UUID pending(UUID sessionId) {
        var rows = jdbc.queryForList("""
                SELECT permission_id FROM biz.agent_tool_permission
                WHERE session_id=? AND status='REQUESTED' AND expires_at>now()
                """, UUID.class, sessionId);
        return rows.isEmpty() ? null : rows.getFirst();
    }

    /** 会话同类授权匹配：同工具、同风险、同范围且未过期。 */
    public boolean granted(UUID sessionId, String tool, String risk, String scopeHash) {
        return Boolean.TRUE.equals(jdbc.queryForObject("""
                SELECT EXISTS(SELECT 1 FROM biz.agent_session_grant
                    WHERE session_id=? AND tool=? AND risk=? AND scope_hash=? AND expires_at>now())
                """, Boolean.class, sessionId, tool, risk, scopeHash));
    }

    public void grant(UUID permissionId, UUID sessionId, String tool, String risk, String scopeHash,
                      Instant expiresAt) {
        jdbc.update("""
                INSERT INTO biz.agent_session_grant(grant_id,session_id,tool,risk,scope_hash,
                    permission_id,expires_at)
                VALUES(?,?,?,?,?,?,?)
                """, UUID.randomUUID(), sessionId, tool, risk, scopeHash, permissionId,
                Timestamp.from(expiresAt));
    }

    public List<String> statuses(UUID sessionId) {
        return jdbc.queryForList("SELECT status FROM biz.agent_tool_permission WHERE session_id=? ORDER BY created_at",
                String.class, sessionId);
    }

    private static MyException conflict() {
        return new MyException(ErrorCode.CONFLICT_ERROR, "工具审批对象或资格已变化，请刷新后重试。");
    }
}