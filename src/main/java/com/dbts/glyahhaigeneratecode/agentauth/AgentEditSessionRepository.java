package com.dbts.glyahhaigeneratecode.agentauth;

import com.dbts.glyahhaigeneratecode.exception.ErrorCode;
import com.dbts.glyahhaigeneratecode.exception.MyException;
import com.dbts.glyahhaigeneratecode.exception.ThrowUtils;
import lombok.RequiredArgsConstructor;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

import java.sql.Timestamp;
import java.time.Instant;
import java.util.UUID;

/**
 * 普通应用的编辑会话。
 *
 * <p>同一应用同时只允许一个未关闭会话，由数据库唯一索引兜底；
 * 冻结的两轴取值一经写入不可改。
 */
@Repository
@RequiredArgsConstructor
public class AgentEditSessionRepository {
    private final JdbcTemplate jdbc;

    public record Session(UUID id, long ownerId, long appId, long runId, String sourceHash,
                          String sandboxMode, String approvalMode, long policyRevision,
                          UUID sessionEpoch, UUID serverGeneration, String state,
                          Instant expiresAt, boolean closed) { }

    public Session require(UUID id, long ownerId) {
        var rows = jdbc.query("""
                SELECT * FROM biz.agent_edit_session WHERE session_id=? AND owner_id=?
                """, (row, index) -> new Session(row.getObject("session_id", UUID.class),
                row.getLong("owner_id"), row.getLong("app_id"), row.getLong("run_id"),
                row.getString("source_hash"), row.getString("sandbox_mode"),
                row.getString("approval_mode"), row.getLong("policy_revision"),
                row.getObject("session_epoch", UUID.class),
                row.getObject("server_generation", UUID.class), row.getString("state"),
                row.getTimestamp("expires_at").toInstant(),
                row.getTimestamp("closed_at") != null), id, ownerId);
        if (rows.size() != 1) throw new MyException(ErrorCode.NOT_FOUND_ERROR, "编辑会话不存在。");
        return rows.getFirst();
    }

    /** 会话必须未关闭、未过期且处于 EDITING 才允许继续产生副作用。 */
    public void qualified(Session session) {
        ThrowUtils.throwIf(session.closed() || !"EDITING".equals(session.state())
                        || !session.expiresAt().isAfter(Instant.now()),
                ErrorCode.CONFLICT_ERROR, "编辑会话已结束或已过期。");
    }

    public Session open(long ownerId, long appId, long runId, String sourceHash,
                        String sandboxMode, String approvalMode, long policyRevision,
                        UUID sessionEpoch, UUID serverGeneration, Instant expiresAt) {
        // 1. 先回收该应用已过期的未关闭会话：进程被杀时没有机会走 doFinally，
        //    残留会因唯一索引永久挡住后续请求。
        jdbc.update("""
                UPDATE biz.agent_edit_session SET state='FAILED',closed_at=now()
                WHERE app_id=? AND closed_at IS NULL AND expires_at<=now()
                """, appId);
        UUID id = UUID.randomUUID();
        // 2. 再开新会话；仍未关闭且未过期时由唯一索引拒绝，不静默复用旧会话。
        jdbc.update("""
                INSERT INTO biz.agent_edit_session(session_id,owner_id,app_id,run_id,source_hash,
                    sandbox_mode,approval_mode,policy_revision,session_epoch,server_generation,
                    state,expires_at)
                VALUES(?,?,?,?,?,?,?,?,?,?,'EDITING',?)
                """, id, ownerId, appId, runId, sourceHash, sandboxMode, approvalMode, policyRevision,
                sessionEpoch, serverGeneration, Timestamp.from(expiresAt));
        return require(id, ownerId);
    }

    /** 归档某应用的全部未关闭会话；应用被删除时调用，避免唯一索引被永久占用。 */
    public int archiveByApp(long appId) {
        jdbc.update("""
                UPDATE biz.agent_tool_permission SET status='CANCELLED',revision=revision+1
                WHERE status IN ('REQUESTED','APPROVED') AND session_id IN
                  (SELECT session_id FROM biz.agent_edit_session WHERE app_id=? AND closed_at IS NULL)
                """, appId);
        return jdbc.update("""
                UPDATE biz.agent_edit_session SET state='CANCELLED',closed_at=now()
                WHERE app_id=? AND closed_at IS NULL
                """, appId);
    }

    /** 关闭会话；已关闭的不再变动，避免覆盖既有终态。 */
    public void close(UUID id, long ownerId, String state) {
        ThrowUtils.throwIf(!java.util.Set.of("SUCCEEDED", "CANCELLED", "FAILED", "WAITING_REVIEW").contains(state),
                ErrorCode.PARAMS_ERROR, "会话终态无效。");
        jdbc.update("""
                UPDATE biz.agent_edit_session SET state=?,closed_at=now()
                WHERE session_id=? AND owner_id=? AND closed_at IS NULL
                """, state, id, ownerId);
        // 只取消从未批准的票据。APPROVED 的可能正被执行侧消费，
        // 这里改成 CANCELLED 会让它的 consume 撞上状态变更、把整轮判成失败
        // —— 令牌过期由票据自己的 expires_at 兜底，不需要这里强杀。
        jdbc.update("""
                UPDATE biz.agent_tool_permission SET status='CANCELLED',revision=revision+1
                WHERE session_id=? AND status='REQUESTED'
                """, id);
    }
}