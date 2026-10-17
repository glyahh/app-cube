package com.dbts.glyahhaigeneratecode.projectimport;

import com.dbts.glyahhaigeneratecode.exception.ErrorCode;
import com.dbts.glyahhaigeneratecode.exception.MyException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.support.TransactionTemplate;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Instant;
import java.util.List;
import java.util.Objects;
import java.util.UUID;

/** 不可变安全审阅与整批决定；等待移交和自动决定均在同一事务内登记。 */
@Repository
public class ImportAgentCandidateReviewRepository {
    public record Review(UUID editId, String hash, String candidateHash, String manifestHash, String authorizedHash,
                         String sourceHash, JsonNode body, String risk, JsonNode aiReview, String status,
                         long revision, String decisionSource, UUID requestId, Instant expiresAt) { }

    private final JdbcTemplate jdbc;
    private final TransactionTemplate transaction;
    private final ObjectMapper json = new ObjectMapper();

    public ImportAgentCandidateReviewRepository(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
        transaction = new TransactionTemplate(new DataSourceTransactionManager(Objects.requireNonNull(jdbc.getDataSource())));
    }

    public Review find(UUID editId, long ownerId) {
        var rows = jdbc.query("""
                SELECT r.* FROM biz.project_import_agent_review r JOIN biz.project_import_agent_edit e USING(edit_id)
                WHERE r.edit_id=? AND e.owner_id=?
                """, (row, index) -> read(row), editId, ownerId);
        return rows.isEmpty() ? null : rows.getFirst();
    }

    public Review require(UUID editId, long ownerId) {
        Review result = find(editId, ownerId);
        if (result == null) throw new MyException(ErrorCode.NOT_FOUND_ERROR, "候选审阅尚未生成。");
        return result;
    }

    public Review prepare(ImportAgentEditLedger.Row row, JsonNode body, String risk, JsonNode aiReview,
                          boolean waiting, String automaticSource, ImportAgentEditLedger ledger) {
        return transaction.execute(status -> {
            // 1. 锁住父任务和当前设置，避免审查结束与取消、策略换代交错。
            lock(row);
            var latest = ledger.require(row.identity().id(), row.identity().ownerId());
            if (latest.revision() != row.revision() || !"SEALING".equals(latest.state())
                    || !ledger.qualifiedSealing(latest) || !ledger.reviewStopped(latest)) throw conflict();
            String hash = ImportAgentCanonicalJson.hash(body);
            jdbc.update("""
                    INSERT INTO biz.project_import_agent_review(edit_id,review_hash,candidate_hash,manifest_hash,
                        authorized_hash,source_hash,review_json,risk,ai_review_json,expires_at)
                    VALUES(?,?,?,?,?,?,CAST(? AS jsonb),?,CAST(? AS jsonb),?)
                    """, row.identity().id(), hash, body.path("candidateHash").asText(), body.path("manifestHash").asText(),
                    body.path("authorizedHash").asText(), row.identity().sourceHash(), body.toString(), risk, aiReview.toString(),
                    java.sql.Timestamp.from(row.identity().expiresAt()));
            // 2. 等待保留原截止时间与卷额度；自动决定不擅自启动发布或清理。
            if (waiting) {
                if (jdbc.update("""
                        UPDATE biz.project_import_agent_edit SET state='WAITING_REVIEW',permission_phase='WAITING_REVIEW',
                            revision=revision+1,update_time=now()
                        WHERE edit_id=? AND owner_id=? AND revision=? AND state='SEALING' AND NOT cancel_requested
                            AND closed_at IS NULL AND expires_at>now()
                        """, row.identity().id(), row.identity().ownerId(), row.revision()) != 1) throw conflict();
            } else {
                if (!List.of("AI", "AUTO").contains(automaticSource)) throw conflict();
                updateDecision(row.identity().id(), 0, hash, "APPROVED", automaticSource, null);
            }
            return require(row.identity().id(), row.identity().ownerId());
        });
    }

    public Review decide(ImportAgentEditLedger.Row row, ImportAgentCandidateReviewService.DecisionDTO request,
                         ImportAgentEditLedger ledger) {
        return transaction.execute(status -> {
            lock(row);
            // 1. 同一请求只读回原决定；旧版本、不同对象或相反决定不能复用该身份。
            var replay = jdbc.query("SELECT * FROM biz.project_import_agent_review WHERE decision_request_id=?",
                    (result, index) -> read(result), request.requestId());
            String next = "APPROVE".equals(request.decision()) ? "APPROVED" : "REJECTED";
            if (!replay.isEmpty()) {
                var previous = replay.getFirst();
                if (!previous.editId().equals(row.identity().id()) || !previous.hash().equals(request.reviewHash())
                        || !previous.status().equals(next) || previous.revision() != request.revision() + 1
                        || !"USER".equals(previous.decisionSource())) throw conflict();
                return previous;
            }
            var current = require(row.identity().id(), row.identity().ownerId());
            var latest = ledger.require(row.identity().id(), row.identity().ownerId());
            if (!"WAITING_REVIEW".equals(latest.state()) || !ledger.qualifiedSealing(latest)
                    || !ledger.reviewStopped(latest) || !"WAITING".equals(current.status())
                    || current.revision() != request.revision() || !current.hash().equals(request.reviewHash())
                    || !current.expiresAt().isAfter(Instant.now())) throw conflict();
            // 2. 只登记决定；任务管理器随后持应用锁调用发布或安全清理。
            updateDecision(current.editId(), current.revision(), current.hash(), next, "USER", request.requestId());
            return require(current.editId(), row.identity().ownerId());
        });
    }

    private void updateDecision(UUID editId, long revision, String hash, String status, String source, UUID requestId) {
        if (jdbc.update("""
                UPDATE biz.project_import_agent_review SET status=?,decision_source=?,decision_request_id=?,revision=revision+1
                WHERE edit_id=? AND revision=? AND review_hash=? AND status='WAITING' AND expires_at>now()
                """, status, source, requestId, editId, revision, hash) != 1) throw conflict();
    }

    private void lock(ImportAgentEditLedger.Row row) {
        var e = row.identity();
        if (jdbc.queryForList("SELECT edit_id FROM biz.project_import_agent_edit WHERE edit_id=? AND owner_id=? FOR UPDATE",
                UUID.class, e.id(), e.ownerId()).size() != 1) throw conflict();
        jdbc.queryForList("SELECT policy_revision FROM biz.project_import_agent_settings WHERE owner_id=? AND app_id=? FOR SHARE",
                Long.class, e.ownerId(), e.appId());
    }

    private Review read(ResultSet row) throws SQLException {
        try {
            return new Review(row.getObject("edit_id", UUID.class), row.getString("review_hash"), row.getString("candidate_hash"),
                    row.getString("manifest_hash"), row.getString("authorized_hash"), row.getString("source_hash"),
                    json.readTree(row.getString("review_json")), row.getString("risk"), json.readTree(row.getString("ai_review_json")),
                    row.getString("status"), row.getLong("revision"), row.getString("decision_source"),
                    row.getObject("decision_request_id", UUID.class), row.getTimestamp("expires_at").toInstant());
        } catch (java.io.IOException error) {
            throw conflict();
        }
    }

    private static MyException conflict() {
        return new MyException(ErrorCode.CONFLICT_ERROR, "候选审阅、决定或权限资格已变化，请刷新后重试。");
    }
}
