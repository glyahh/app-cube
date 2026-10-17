package com.dbts.glyahhaigeneratecode.projectimport;

import com.dbts.glyahhaigeneratecode.exception.ErrorCode;
import com.dbts.glyahhaigeneratecode.exception.MyException;
import com.dbts.glyahhaigeneratecode.exception.ThrowUtils;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionTemplate;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;

import java.time.Instant;
import java.util.List;
import java.util.Objects;
import java.util.UUID;

/** 工具许可只由Java决定；平台独立核对实际动作，消费后不重复副作用。 */
@Service
@RequiredArgsConstructor
public class ImportAgentToolPermissionService {
    public record DecisionDTO(UUID requestId, String actionHash, String decision, long revision) { }
    private final JdbcTemplate jdbc;
    private final ImportAgentEditLedger ledger;
    private final ImportAgentSettingsRepository settings;
    private final ImportAgentPermissionServiceImpl permissions;
    private final ImportAgentModelClient model;
    private final ImportDiskVolume volumes;
    private final ObjectMapper json;

    public JsonNode requested(UUID editId, long ownerId, JsonNode frame) throws java.io.IOException {
        var row = ledger.require(editId, ownerId);
        UUID permissionId = uuid(frame.path("permissionId").asText());
        JsonNode action = frame.path("action");
        String hash = ImportAgentCanonicalJson.hash(action);
        ThrowUtils.throwIf(!editId.toString().equals(frame.path("editId").asText())
                        || !hash.equals(frame.path("actionHash").asText()) || !ledger.qualified(row)
                        || !"EDITING".equals(row.state()), ErrorCode.CONFLICT_ERROR, "工具动作或资格已变化。");
        // 1. 平台从真实视图重算，风险审查不能替代硬校验。
        long bytes = json.readTree(row.resources()).get(0).path("diskBytes").asLong();
        var inspection = volumes.inspectAction(row.identity(), bytes, action);
        ThrowUtils.throwIf(!"ACTION_VERIFIED".equals(inspection.path("state").asText())
                        || !hash.equals(inspection.path("actionHash").asText()), ErrorCode.CONFLICT_ERROR, "工具动作独立核对失败。");
        var policy = settings.current(ownerId, row.identity().appId());
        JsonNode review = "GUARDED_AUTO".equals(policy.mode()) ? model.review("ACTION", hash, action) : json.createObjectNode();
        String risk = "HIGH".equals(inspection.path("riskFloor").asText()) || "HIGH".equals(review.path("risk").asText())
                ? "HIGH" : "ORDINARY";
        String scope = ImportAgentCanonicalJson.hash(json.createObjectNode().put("scope", inspection.path("scopeKey").asText()).put("risk", risk));
        var display = inspection.path("display").deepCopy();
        if (review.has("reason")) ((com.fasterxml.jackson.databind.node.ObjectNode) display).put("reason", review.path("reason").asText());
        // 2. 审查返回后重新锁父任务，当前设置与数据库守卫同时校验。
        transaction().execute(status -> {
            lock(editId, ownerId);
            var latest = ledger.require(editId, ownerId);
            if (!ledger.qualified(latest) || !"EDITING".equals(latest.state())) throw conflict();
            jdbc.update("""
                    INSERT INTO biz.project_import_agent_permission(permission_id,edit_id,action_hash,action_json,
                        tool,risk,scope_hash,review_json,display_json,expires_at)
                    VALUES(?,?,?,CAST(? AS jsonb),?,?,?,CAST(? AS jsonb),CAST(? AS jsonb),least(?,now()+interval '5 minutes'))
                    """, permissionId, editId, hash, action.toString(), action.path("tool").asText(), risk, scope,
                    review.toString(), display.toString(), java.sql.Timestamp.from(latest.identity().expiresAt()));
            String source = "AUTO".equals(policy.mode()) ? "AUTO" : null;
            if (source == null && Boolean.TRUE.equals(jdbc.queryForObject("""
                    SELECT EXISTS(SELECT 1 FROM biz.project_import_agent_grant WHERE owner_id=? AND app_id=?
                        AND thread_id=? AND session_epoch=? AND server_generation=? AND expires_at>now()
                        AND tool=? AND risk=? AND scope_hash=?)
                    """, Boolean.class, ownerId, policy.appId(), policy.threadId(), policy.sessionEpoch(),
                    policy.serverGeneration(), action.path("tool").asText(), risk, scope))) source = "SESSION";
            if (source == null && "GUARDED_AUTO".equals(policy.mode()) && "ORDINARY".equals(risk)) source = "AI";
            if (source != null) approve(permissionId, hash, 0, source, null, false);
            return null;
        });
        return current(ownerId, permissionId);
    }

    public JsonNode current(long ownerId, UUID id) {
        var values = jdbc.queryForList("""
                SELECT p.permission_id,p.edit_id,p.action_hash,p.tool,p.risk,p.display_json::text,p.action_json::text,p.status,p.revision,p.expires_at
                FROM biz.project_import_agent_permission p JOIN biz.project_import_agent_edit e USING(edit_id)
                WHERE p.permission_id=? AND e.owner_id=?
                """, id, ownerId);
        if (values.size() != 1) throw new MyException(ErrorCode.NOT_FOUND_ERROR, "工具审批不存在。");
        var value = values.getFirst();
        var output = json.createObjectNode().put("id", id.toString()).put("editId", value.get("edit_id").toString())
                .put("actionHash", value.get("action_hash").toString()).put("action", value.get("tool").toString())
                .put("highRisk", "HIGH".equals(value.get("risk"))).put("status", value.get("status").toString())
                .put("revision", value.get("revision").toString());
        output.put("detailText", value.get("action_json").toString());
        try { output.set("display", json.readTree(value.get("display_json").toString())); }
        catch (java.io.IOException error) { throw conflict(); }
        return output;
    }

    public void requireApplication(long ownerId, long appId, UUID id) {
        UUID editId = uuid(current(ownerId, id).path("editId").asText());
        ThrowUtils.throwIf(ledger.require(editId, ownerId).identity().appId() != appId,
                ErrorCode.NOT_FOUND_ERROR, "工具审批不存在。");
    }

    public JsonNode decide(long ownerId, UUID id, DecisionDTO request, String loginSessionId) {
        ThrowUtils.throwIf(request == null || request.requestId() == null || request.actionHash() == null
                        || !request.actionHash().matches("[0-9a-f]{64}") || request.decision() == null
                        || !List.of("APPROVE_ONCE", "APPROVE_SIMILAR", "REJECT").contains(request.decision())
                        || request.revision() < 0, ErrorCode.PARAMS_ERROR, "工具决定无效。");
        UUID editId = uuid(current(ownerId, id).path("editId").asText());
        var row = ledger.require(editId, ownerId);
        var policy = permissions.requireSession(ownerId, row.identity().appId(), loginSessionId);
        transaction().execute(status -> {
            lock(editId, ownerId);
            var replay = jdbc.queryForList("SELECT permission_id,action_hash,status,approve_similar,revision FROM biz.project_import_agent_permission WHERE decision_request_id=?", request.requestId());
            boolean reject = "REJECT".equals(request.decision()), similar = "APPROVE_SIMILAR".equals(request.decision());
            if (!replay.isEmpty()) {
                var prior = replay.getFirst();
                if (!id.equals(prior.get("permission_id")) || !request.actionHash().equals(prior.get("action_hash"))
                        || reject != "REJECTED".equals(prior.get("status")) || similar != Boolean.TRUE.equals(prior.get("approve_similar"))) throw conflict();
                return null;
            }
            if (!ledger.qualified(ledger.require(editId, ownerId))) throw conflict();
            if (reject) {
                if (jdbc.update("UPDATE biz.project_import_agent_permission SET status='REJECTED',decision_source='USER',decision_request_id=?,revision=revision+1 WHERE permission_id=? AND revision=? AND action_hash=? AND status='REQUESTED' AND expires_at>now()",
                        request.requestId(), id, request.revision(), request.actionHash()) != 1) throw conflict();
            } else {
                approve(id, request.actionHash(), request.revision(), "USER", request.requestId(), similar);
                if (similar) jdbc.update("""
                        INSERT INTO biz.project_import_agent_grant(grant_id,owner_id,app_id,thread_id,session_epoch,
                            server_generation,tool,risk,scope_hash,permission_id,expires_at)
                        SELECT ?,?,?,?,?,?,tool,risk,scope_hash,permission_id,? FROM biz.project_import_agent_permission WHERE permission_id=?
                        """, UUID.randomUUID(), ownerId, policy.appId(), policy.threadId(), policy.sessionEpoch(),
                        policy.serverGeneration(), java.sql.Timestamp.from(policy.sessionExpiresAt()), id);
            }
            return null;
        });
        return current(ownerId, id);
    }

    public void consume(UUID editId, long ownerId, JsonNode frame) throws java.io.IOException {
        var row = ledger.require(editId, ownerId);
        UUID id = uuid(frame.path("permissionId").asText());
        UUID consume = uuid(frame.path("consumeRequestId").asText());
        var actions = jdbc.queryForList("SELECT action_json::text FROM biz.project_import_agent_permission WHERE permission_id=? AND edit_id=? AND status='APPROVED' AND action_hash=?",
                String.class, id, editId, frame.path("actionHash").asText());
        if (actions.size() != 1 || !ledger.qualified(row)) throw conflict();
        var inspection = volumes.inspectAction(row.identity(), json.readTree(row.resources()).get(0).path("diskBytes").asLong(), json.readTree(actions.getFirst()));
        if (!frame.path("actionHash").asText().equals(inspection.path("actionHash").asText())) throw conflict();
        if (jdbc.update("UPDATE biz.project_import_agent_permission SET status='CONSUMED',consumption_proof=?,consume_request_id=?,revision=revision+1 WHERE permission_id=? AND edit_id=? AND status='APPROVED' AND action_hash=? AND expires_at>now()",
                UUID.randomUUID(), consume, id, editId, frame.path("actionHash").asText()) != 1) throw conflict();
    }

    public void result(UUID editId, long ownerId, JsonNode frame) {
        ledger.require(editId, ownerId);
        String state = frame.path("state").asText();
        if (!List.of("RESULT_CONFIRMED", "RESULT_UNCONFIRMED").contains(state)) throw conflict();
        if (jdbc.update("UPDATE biz.project_import_agent_permission SET status=?,revision=revision+1 WHERE permission_id=? AND edit_id=? AND action_hash=? AND consumption_proof=? AND status='CONSUMED'",
                state, uuid(frame.path("permissionId").asText()), editId, frame.path("actionHash").asText(), uuid(frame.path("consumeProof").asText())) != 1) throw conflict();
    }

    private void approve(UUID id, String hash, long revision, String source, UUID request, boolean similar) {
        if (jdbc.update("UPDATE biz.project_import_agent_permission SET status='APPROVED',decision_source=?,decision_request_id=?,approve_similar=?,revision=revision+1 WHERE permission_id=? AND action_hash=? AND revision=? AND status='REQUESTED' AND expires_at>now()",
                source, request, similar, id, hash, revision) != 1) throw conflict();
    }
    private void lock(UUID editId, long owner) {
        if (jdbc.queryForList("SELECT edit_id FROM biz.project_import_agent_edit WHERE edit_id=? AND owner_id=? FOR UPDATE", UUID.class, editId, owner).size() != 1) throw conflict();
    }
    private TransactionTemplate transaction() { return new TransactionTemplate(new DataSourceTransactionManager(Objects.requireNonNull(jdbc.getDataSource()))); }
    private static UUID uuid(String value) {
        try { UUID id = UUID.fromString(value); if (!id.toString().equals(value)) throw conflict(); return id; }
        catch (IllegalArgumentException error) { throw conflict(); }
    }
    private static MyException conflict() { return new MyException(ErrorCode.CONFLICT_ERROR, "工具审批对象或资格已变化，请刷新后重试。"); }
}
