package com.dbts.glyahhaigeneratecode.projectimport;

import com.dbts.glyahhaigeneratecode.exception.ErrorCode;
import com.dbts.glyahhaigeneratecode.exception.ThrowUtils;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

import java.util.UUID;

/** 候选审阅只负责归属、版本和决定入口；发布仍由执行器在应用锁内完成。 */
@Service
@RequiredArgsConstructor
public class ImportAgentCandidateReviewService {
    public record DecisionDTO(UUID requestId, String reviewHash, String decision, long revision) { }

    private final ImportAgentEditLedger ledger;
    private final ImportAgentCandidateReviewRepository reviews;
    private final ImportAgentPermissionServiceImpl permissions;
    private final ObjectMapper json;
    private final ImportDiskVolume volumes;
    private final ImportAgentModelClient model;
    private final ImportAgentSettingsRepository settings;
    private final ImportAgentPublication publication;
    private final ImportWorkspaceCoordinator coordinator;
    private final ImportAgentEditPreparation preparation;
    private final org.springframework.jdbc.core.JdbcTemplate jdbc;

    public ImportAgentCandidateReviewRepository.Review current(long ownerId, UUID editId) {
        return reviews.require(editId, ownerId);
    }

    public JsonNode active(long ownerId, long appId) {
        permissions.requireOwner(ownerId, appId);
        var rows = jdbc.queryForList("SELECT edit_id,state FROM biz.project_import_agent_edit WHERE owner_id=? AND app_id=? AND closed_at IS NULL", ownerId, appId);
        if (rows.isEmpty()) {
            var latest = jdbc.queryForList("SELECT state FROM biz.project_import_agent_edit WHERE owner_id=? AND app_id=? ORDER BY created_at DESC LIMIT 1", String.class, ownerId, appId);
            return json.createObjectNode().put("state", "IDLE").put("outcome", latest.isEmpty() ? "NONE" : latest.getFirst());
        }
        var value = rows.getFirst();
        UUID editId = (UUID) value.get("edit_id");
        var out = json.createObjectNode().put("editId", editId.toString()).put("state", value.get("state").toString());
        if ("WAITING_REVIEW".equals(value.get("state"))) out.set("review", json.valueToTree(reviews.require(editId, ownerId)));
        var pending = jdbc.queryForList("SELECT permission_id FROM biz.project_import_agent_permission WHERE edit_id=? AND status='REQUESTED' AND expires_at>now()", UUID.class, editId);
        if (!pending.isEmpty()) out.put("permissionId", pending.getFirst().toString());
        return out;
    }

    @org.springframework.scheduling.annotation.Scheduled(fixedDelay = 10_000)
    public void expireWaiting() {
        var rows = jdbc.queryForList("""
                SELECT e.edit_id,e.owner_id,e.app_id FROM biz.project_import_agent_edit e
                WHERE e.state='WAITING_REVIEW' AND e.executor_pid=? AND e.executor_start=? AND
                    (e.expires_at<=now() OR NOT EXISTS(SELECT 1 FROM biz.project_import_agent_settings s
                        WHERE s.owner_id=e.owner_id AND s.app_id=e.app_id AND s.session_epoch=e.session_epoch
                            AND s.server_generation=e.server_generation AND s.session_expires_at>now())) LIMIT 100
                """, ProcessHandle.current().pid(), java.sql.Timestamp.from(ProcessHandle.current().info().startInstant().orElseThrow()));
        for (var value : rows) {
            coordinator.withLock(((Number) value.get("app_id")).longValue(), () -> {
                var row = ledger.require((UUID) value.get("edit_id"), ((Number) value.get("owner_id")).longValue());
                if ("WAITING_REVIEW".equals(row.state()) && (!row.identity().expiresAt().isAfter(java.time.Instant.now()) || !ledger.policyCurrent(row))) discard(row, "CANCELLED");
                return null;
            });
        }
    }

    public ImportAgentCandidateReviewRepository.Review decide(long ownerId, UUID editId, DecisionDTO request,
                                                               String loginSessionId) {
        ThrowUtils.throwIf(request == null || request.requestId() == null || request.reviewHash() == null
                        || !request.reviewHash().matches("[0-9a-f]{64}")
                        || request.decision() == null || !java.util.Set.of("APPROVE", "REJECT").contains(request.decision())
                        || request.revision() < 0, ErrorCode.PARAMS_ERROR, "候选决定无效。");
        var row = ledger.require(editId, ownerId);
        permissions.requireSession(ownerId, row.identity().appId(), loginSessionId);
        var decided = reviews.decide(row, request, ledger);
        if ("APPROVED".equals(decided.status())) {
            var latest = ledger.require(editId, ownerId);
            if ("WAITING_REVIEW".equals(latest.state())) {
                // 发布服务自行持应用锁，不能在另一连接的会话锁内再次获取。
                publication.publish(editId, ownerId);
                finishRun(latest, "SUCCEEDED");
            }
            return decided;
        }
        return coordinator.withLock(row.identity().appId(), () -> {
            var latest = ledger.require(editId, ownerId);
            if ("WAITING_REVIEW".equals(latest.state())) {
                discard(latest, "CANCELLED");
            }
            return decided;
        });
    }

    public ImportAgentCandidateReviewRepository.Review prepare(java.util.UUID editId, long ownerId) throws java.io.IOException {
        var row = ledger.require(editId, ownerId);
        var proof = ledger.candidateProof(row);
        var projected = volumes.reviewCandidate(row, proof);
        var body = projected.path("body");
        ThrowUtils.throwIf(!"REVIEW_READY".equals(projected.path("state").asText()) || !body.isObject()
                        || !proof.path("candidateHash").asText().equals(body.path("candidateHash").asText())
                        || !proof.path("candidateManifestHash").asText().equals(body.path("manifestHash").asText())
                        || !proof.path("authorizedStateHash").asText().equals(body.path("authorizedHash").asText())
                        || !row.identity().sourceHash().equals(body.path("sourceHash").asText()),
                ErrorCode.CONFLICT_ERROR, "候选审阅内容已变化。");
        var policy = settings.current(ownerId, row.identity().appId());
        var ai = "MANUAL".equals(policy.mode()) ? json.createObjectNode()
                : model.review("CANDIDATE", ImportAgentCanonicalJson.hash(body), body);
        boolean high = "HIGH".equals(ai.path("risk").asText());
        for (var change : body.path("changes")) {
            String path = change.path("path").asText().toLowerCase(java.util.Locale.ROOT);
            String name = path.substring(path.lastIndexOf('/') + 1);
            high |= change.path("after").isNull() || path.startsWith(".") || path.startsWith("scripts/")
                    || path.contains("/migration/") || name.matches("(readme|config|application|package|requirements|compose).*|pom.xml|dockerfile|uv.lock|pyproject.toml")
                    || name.matches(".*\\.(sql|lock|sh|ps1|cmd|bat|ya?ml)")
                    || change.path("after").isArray() && (change.path("after").get(2).asInt() & 0111) != 0;
        }
        boolean waiting = "MANUAL".equals(policy.mode()) || "GUARDED_AUTO".equals(policy.mode()) && high;
        return reviews.prepare(ledger.require(editId, ownerId), body, high ? "HIGH" : "ORDINARY", ai,
                waiting, "AUTO".equals(policy.mode()) ? "AUTO" : "AI", ledger);
    }

    private void discard(ImportAgentEditLedger.Row row, String outcome) {
        var cancelled = ledger.cancel(row);
        preparation.finishNotReady(new ImportAgentEditPreparation.Prepared(null, cancelled), true);
        ThrowUtils.throwIf(!java.util.List.of("CANCELLED", "FAILED", "STALE").contains(
                ledger.require(row.identity().id(), row.identity().ownerId()).state()), ErrorCode.OPERATION_ERROR, "停止或资源清理尚未确认。");
        finishRun(row, outcome);
    }

    private void finishRun(ImportAgentEditLedger.Row row, String status) {
        jdbc.update("UPDATE biz.agent_run SET status=?,finished_at=now(),update_time=now() WHERE id=? AND status='WAITING_REVIEW'",
                status, Long.parseLong(row.identity().runId()));
    }
}
