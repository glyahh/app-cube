package com.dbts.glyahhaigeneratecode.agentauth;

import com.dbts.glyahhaigeneratecode.exception.ErrorCode;
import com.dbts.glyahhaigeneratecode.exception.MyException;
import lombok.RequiredArgsConstructor;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.UUID;

/**
 * 普通应用的会话授权设置。
 *
 * <p>只存授权模式与会话身份；模型与思考力度在 {@code biz.agent_model_preference}，
 * 两件事的作用域和生命周期不同，不放在一张表。
 */
@Repository
@RequiredArgsConstructor
public class AgentEditSettingsRepository {
    private final JdbcTemplate jdbc;

    public record Settings(long ownerId, long appId, String threadId, String mode, String sandboxMode,
                           long revision, UUID sessionEpoch, String sessionBinding,
                           UUID serverGeneration, Instant sessionExpiresAt) { }

    /** 未登记过时返回 null，供请求工厂按表默认值冻结，不隐式写入。 */
    public Settings find(long ownerId, long appId) {
        var rows = jdbc.query("""
                SELECT * FROM biz.agent_edit_settings WHERE owner_id=? AND app_id=? AND thread_id=?
                """, (row, index) -> read(row), ownerId, appId, "app:" + appId);
        return rows.isEmpty() ? null : rows.getFirst();
    }

    public Settings current(long ownerId, long appId) {
        var settings = find(ownerId, appId);
        if (settings == null) throw new MyException(ErrorCode.CONFLICT_ERROR, "授权设置尚未就绪，请刷新后重试。");
        return settings;
    }

    /** 首次登记默认 MANUAL；刷新不续期，登录变化或到期时换代。 */
    public Settings restore(long ownerId, long appId, String binding, UUID generation) {
        jdbc.update("""
                INSERT INTO biz.agent_edit_settings(owner_id,app_id,thread_id,session_epoch,
                    session_binding,server_generation,session_expires_at)
                VALUES(?,?,?,?,?,?,now()+interval '8 hours') ON CONFLICT DO NOTHING
                """, ownerId, appId, "app:" + appId, UUID.randomUUID(), binding, generation);
        var settings = current(ownerId, appId);
        if (!settings.sessionBinding().equals(binding) || !settings.serverGeneration().equals(generation)
                || !settings.sessionExpiresAt().isAfter(Instant.now())) {
            if (jdbc.update("""
                    UPDATE biz.agent_edit_settings SET session_epoch=?,session_binding=?,
                        server_generation=?,session_expires_at=now()+interval '8 hours',policy_revision=policy_revision+1
                    WHERE owner_id=? AND app_id=? AND thread_id=? AND policy_revision=?
                    """, UUID.randomUUID(), binding, generation, ownerId, appId, settings.threadId(),
                    settings.revision()) != 1) {
                throw conflict();
            }
            settings = current(ownerId, appId);
        }
        return settings;
    }

    public Settings save(Settings observed, String mode, String sandboxMode) {
        if (jdbc.update("""
                UPDATE biz.agent_edit_settings SET mode=?,sandbox_mode=?,policy_revision=policy_revision+1
                WHERE owner_id=? AND app_id=? AND thread_id=? AND policy_revision=? AND session_epoch=?
                    AND server_generation=? AND session_expires_at>now()
                """, mode, sandboxMode, observed.ownerId(), observed.appId(), observed.threadId(), observed.revision(),
                observed.sessionEpoch(), observed.serverGeneration()) != 1) {
            throw conflict();
        }
        return current(observed.ownerId(), observed.appId());
    }

    private static Settings read(ResultSet row) throws SQLException {
        Timestamp expiry = row.getTimestamp("session_expires_at");
        return new Settings(row.getLong("owner_id"), row.getLong("app_id"), row.getString("thread_id"),
                row.getString("mode"), row.getString("sandbox_mode"), row.getLong("policy_revision"),
                row.getObject("session_epoch", UUID.class), row.getString("session_binding"),
                row.getObject("server_generation", UUID.class), expiry.toInstant());
    }

    private static MyException conflict() {
        return new MyException(ErrorCode.CONFLICT_ERROR, "授权设置已变化，请刷新后重试。");
    }
}