package com.dbts.glyahhaigeneratecode.projectimport;

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

/** 持久策略与权限会话；调用者必须先持有应用锁并核对归属。 */
@Repository
@RequiredArgsConstructor
public class ImportAgentSettingsRepository {
    private final JdbcTemplate jdbc;

    public record Settings(long ownerId, long appId, String threadId, String mode, String profileId,
                           String effort, long revision, UUID sessionEpoch, String sessionBinding,
                           UUID serverGeneration, Instant sessionExpiresAt) { }

    public Settings current(long ownerId, long appId) {
        var rows = jdbc.query("""
                SELECT * FROM biz.project_import_agent_settings
                WHERE owner_id=? AND app_id=? AND thread_id=?
                """, (row, index) -> read(row), ownerId, appId, "app:" + appId);
        if (rows.size() != 1) throw new MyException(ErrorCode.CONFLICT_ERROR, "权限设置尚未就绪，请刷新后重试。");
        return rows.getFirst();
    }

    public Settings restore(long ownerId, long appId, String binding, UUID generation) {
        // 1. 默认策略只在首次登记时生成，不用页面默认值代替数据库设置。
        jdbc.update("""
                INSERT INTO biz.project_import_agent_settings(owner_id,app_id,thread_id,session_epoch,
                    session_binding,server_generation,session_expires_at)
                VALUES(?,?,?,?,?,?,now()+interval '8 hours') ON CONFLICT DO NOTHING
                """, ownerId, appId, "app:" + appId, UUID.randomUUID(), binding, generation);
        var settings = current(ownerId, appId);
        // 2. 刷新不续期；登录变化、重启或到期时换代，旧授权不再匹配。
        if (!settings.sessionBinding().equals(binding) || !settings.serverGeneration().equals(generation)
                || !settings.sessionExpiresAt().isAfter(Instant.now())) {
            if (jdbc.update("""
                    UPDATE biz.project_import_agent_settings SET session_epoch=?,session_binding=?,
                        server_generation=?,session_expires_at=now()+interval '8 hours',policy_revision=policy_revision+1
                    WHERE owner_id=? AND app_id=? AND thread_id=? AND policy_revision=?
                    """, UUID.randomUUID(), binding, generation, ownerId, appId, settings.threadId(), settings.revision()) != 1) {
                throw conflict();
            }
            settings = current(ownerId, appId);
        }
        return settings;
    }

    public Settings save(Settings observed, String mode, String profileId, String effort) {
        if (jdbc.update("""
                UPDATE biz.project_import_agent_settings SET mode=?,profile_id=?,effort=?,policy_revision=policy_revision+1
                WHERE owner_id=? AND app_id=? AND thread_id=? AND policy_revision=? AND session_epoch=?
                    AND server_generation=? AND session_expires_at>now()
                    AND NOT EXISTS (SELECT 1 FROM biz.project_import_agent_edit WHERE app_id=? AND closed_at IS NULL)
                """, mode, profileId, effort, observed.ownerId(), observed.appId(), observed.threadId(),
                observed.revision(), observed.sessionEpoch(), observed.serverGeneration(), observed.appId()) != 1) {
            throw conflict();
        }
        return current(observed.ownerId(), observed.appId());
    }

    private static Settings read(ResultSet row) throws SQLException {
        Timestamp expiry = row.getTimestamp("session_expires_at");
        return new Settings(row.getLong("owner_id"), row.getLong("app_id"), row.getString("thread_id"),
                row.getString("mode"), row.getString("profile_id"), row.getString("effort"), row.getLong("policy_revision"),
                row.getObject("session_epoch", UUID.class), row.getString("session_binding"),
                row.getObject("server_generation", UUID.class), expiry.toInstant());
    }

    private static MyException conflict() {
        return new MyException(ErrorCode.CONFLICT_ERROR, "权限设置已变化或编辑尚未结束，请刷新后重试。");
    }
}
