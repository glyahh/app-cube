package com.dbts.glyahhaigeneratecode.agentauth;

import com.dbts.glyahhaigeneratecode.exception.ErrorCode;
import com.dbts.glyahhaigeneratecode.exception.ThrowUtils;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

/**
 * 普通应用的候选审阅。
 *
 * <p>候选来自本轮的变更账本；拒绝后按账本回滚，批准则保留。
 * 决定来源必须与冻结的审批模式一致，自动模式不能冒充用户确认。
 */
@Service
@RequiredArgsConstructor
public class AgentCandidateReviewService {
    public record DecisionDTO(UUID requestId, String reviewHash, String decision, long revision) { }

    private final JdbcTemplate jdbc;
    private final AgentChangeLedgerRepository ledger;
    private final AgentEditSessionRepository sessions;
    private final AgentChangeRollbackService rollback;
    private final AgentModelsService models;
    private final ObjectMapper json;

    /**
     * 会话结束时自动冻结候选；没有变更就什么都不做。
     *
     * <p>与 {@link #prepare} 的区别：这里不把「没改文件」当错误——
     * 一轮纯问答本来就可能不产生变更，不该因此让整轮失败。
     */
    public JsonNode prepareIfChanged(long ownerId, UUID sessionId) {
        if (ledger.list(sessionId).isEmpty()) return null;
        var session = sessions.require(sessionId, ownerId);
        return prepare(ownerId, sessionId, session.expiresAt());
    }

    /** 该应用最近一条审阅；前端用它决定是否显示整批确认卡片。 */
    public JsonNode latest(long ownerId, long appId) {
        // 先校验应用归属与可见性：只按 owner_id 过滤会让越权访问返回「无审阅」，
        // 既泄露了存在性，又与 pending / agent-model 的 40400 不一致。
        models.requireVisible(ownerId, appId);
        var rows = jdbc.queryForList("""
                SELECT r.review_id FROM biz.agent_candidate_review r
                JOIN biz.agent_edit_session s ON s.session_id=r.session_id
                WHERE s.owner_id=? AND s.app_id=?
                ORDER BY r.created_at DESC LIMIT 1
                """, UUID.class, ownerId, appId);
        return rows.isEmpty() ? null : current(ownerId, rows.getFirst());
    }

    /** 一轮结束：把本轮变更冻成不可变候选，并登记等待决定。 */
    public JsonNode prepare(long ownerId, UUID sessionId, Instant expiresAt) {
        // 审阅是事后操作：会话可能已经关闭，账本非空才是唯一判据。
        // 这里不查 qualified——那会让 MANUAL 模式在会话结束后永久失去整批确认入口。
        var session = sessions.require(sessionId, ownerId);
        // 同一会话只该有一份候选：重复调用（前端重试、轮询）直接返回已有的，
        // 否则会撞 session_id 唯一约束变成 500。
        var existing = jdbc.queryForList(
                "SELECT review_id FROM biz.agent_candidate_review WHERE session_id=?",
                UUID.class, sessionId);
        if (!existing.isEmpty()) return current(ownerId, existing.getFirst());
        var changes = ledger.list(sessionId);
        ThrowUtils.throwIf(changes.isEmpty(), ErrorCode.CONFLICT_ERROR, "本轮没有产生变更。");

        var array = json.createArrayNode();
        boolean high = false;
        for (var change : changes) {
            var item = json.createObjectNode()
                    .put("path", change.path())
                    .put("change", change.change())
                    .put("beforeHash", change.beforeHash())
                    .put("afterHash", change.afterHash())
                    .put("truncated", change.truncated());
            if (change.beforeBlob() != null) item.put("beforeText", change.beforeBlob());
            if (change.afterBlob() != null) item.put("afterText", change.afterBlob());
            array.add(item);
            high |= change.truncated() || !"MODIFIED".equals(change.change())
                    || AgentToolPermissionService.highRisk(
                            json.createObjectNode().put("tool", "write").put("path", change.path()));
        }
        String risk = high ? "HIGH" : "ORDINARY";
        String candidateHash = AgentCanonicalJson.hash(array);
        UUID reviewId = UUID.randomUUID();
        var body = json.createObjectNode().put("candidateHash", candidateHash).set("changes", array);
        String reviewHash = AgentCanonicalJson.hash(body);

        jdbc.update("""
                INSERT INTO biz.agent_candidate_review(review_id,session_id,review_hash,candidate_hash,
                    changes_json,risk,expires_at)
                VALUES(?,?,?,?,CAST(? AS jsonb),?,?)
                """, reviewId, sessionId, reviewHash, candidateHash, array.toString(), risk,
                java.sql.Timestamp.from(expiresAt));

        // 非手动模式且无高风险时可自动合入；有高风险仍等用户。
        String source = null;
        if ("AUTO".equals(session.approvalMode())) source = "AUTO";
        else if ("GUARDED_AUTO".equals(session.approvalMode()) && "ORDINARY".equals(risk)) source = "AI";
        if (source != null) decide(reviewId, "APPROVED", source, null, 0);
        return current(ownerId, reviewId);
    }

    public JsonNode current(long ownerId, UUID reviewId) {
        var rows = jdbc.queryForList("""
                SELECT r.* FROM biz.agent_candidate_review r
                JOIN biz.agent_edit_session s ON s.session_id=r.session_id
                WHERE r.review_id=? AND s.owner_id=?
                """, reviewId, ownerId);
        ThrowUtils.throwIf(rows.isEmpty(), ErrorCode.NOT_FOUND_ERROR, "候选审阅不存在。");
        var row = rows.getFirst();
        var output = json.createObjectNode()
                .put("reviewId", reviewId.toString())
                .put("sessionId", row.get("session_id").toString())
                .put("reviewHash", row.get("review_hash").toString())
                .put("risk", row.get("risk").toString())
                .put("status", row.get("status").toString())
                .put("revision", row.get("revision").toString());
        try {
            // 与前端契约一致：body 是对象，changes 在数组里；
            // 直接回数组会让前端拿不到 .changes，卡片永远空白。
            var body = json.createObjectNode()
                    .put("candidateHash", row.get("candidate_hash").toString());
            body.set("changes", json.readTree(row.get("changes_json").toString()));
            output.set("body", body);
        } catch (java.io.IOException error) {
            throw new IllegalStateException(error);
        }
        return output;
    }

    public JsonNode decide(long ownerId, UUID reviewId, DecisionDTO input) {
        ThrowUtils.throwIf(input == null || input.requestId() == null || input.reviewHash() == null
                        || !input.reviewHash().matches("[0-9a-f]{64}") || input.decision() == null
                        || !List.of("APPROVE", "REJECT").contains(input.decision())
                        || input.revision() < 0, ErrorCode.PARAMS_ERROR, "候选决定无效。");
        var current = current(ownerId, reviewId);
        ThrowUtils.throwIf(!current.path("reviewHash").asText().equals(input.reviewHash()),
                ErrorCode.CONFLICT_ERROR, "候选内容已变化，请刷新后重试。");
        boolean reject = "REJECT".equals(input.decision());
        // 必须先用 CAS 抢占再回滚：两个请求同时到达时，抢输的那个一旦先删掉文件，
        // 就会出现「状态是已批准、文件却没了」——状态与磁盘不一致。
        decide(reviewId, reject ? "REJECTED" : "APPROVED", "USER", input.requestId(), input.revision());
        if (reject) {
            var session = sessions.require(UUID.fromString(current.path("sessionId").asText()), ownerId);
            try {
                rollback.rollback(ownerId, session.id(), session.appId());
            } catch (RuntimeException error) {
                // 回滚失败要把状态退回等待，否则用户既没回滚成功也失去了重新决定的入口。
                reopen(reviewId);
                throw error;
            }
        }
        return current(ownerId, reviewId);
    }

    /** 回滚失败时把审阅退回等待态；只对刚被本次请求决定的那条生效。 */
    private void reopen(UUID reviewId) {
        jdbc.update("""
                UPDATE biz.agent_candidate_review SET status='WAITING',decision_source=NULL,
                    decision_request_id=NULL,revision=revision+1
                WHERE review_id=? AND status='REJECTED'
                """, reviewId);
    }

    private void decide(UUID reviewId, String status, String source, UUID requestId, long revision) {
        ThrowUtils.throwIf(jdbc.update("""
                UPDATE biz.agent_candidate_review SET status=?,decision_source=?,decision_request_id=?,
                    revision=revision+1
                WHERE review_id=? AND revision=? AND status='WAITING' AND expires_at>now()
                """, status, source, requestId, reviewId, revision) != 1,
                ErrorCode.CONFLICT_ERROR, "候选已决定或已过期。");
    }
}