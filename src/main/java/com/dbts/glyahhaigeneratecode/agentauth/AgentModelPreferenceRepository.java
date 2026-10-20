package com.dbts.glyahhaigeneratecode.agentauth;

import com.dbts.glyahhaigeneratecode.exception.ErrorCode;
import com.dbts.glyahhaigeneratecode.exception.MyException;
import lombok.RequiredArgsConstructor;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

/**
 * 普通应用的模型偏好读写。
 *
 * <p>只允许 CAS 更新；首次保存用插入，重复插入视为冲突而不是覆盖。
 */
@Repository
@RequiredArgsConstructor
public class AgentModelPreferenceRepository {
    private final JdbcTemplate jdbc;

    public record Preference(long ownerId, long appId, String profileId, String effort, long revision) { }

    /** 未设置过时返回 null，由调用方决定默认展示，不静默补一行。 */
    public Preference find(long ownerId, long appId) {
        var rows = jdbc.query("""
                SELECT owner_id,app_id,profile_id,effort,revision FROM biz.agent_model_preference
                WHERE owner_id=? AND app_id=?
                """, (row, index) -> new Preference(row.getLong("owner_id"), row.getLong("app_id"),
                row.getString("profile_id"), row.getString("effort"), row.getLong("revision")), ownerId, appId);
        return rows.isEmpty() ? null : rows.getFirst();
    }

    /**
     * 保存偏好。
     *
     * @param expectedRevision 为 null 表示首次登记；非 null 时做 revision CAS
     */
    public Preference save(long ownerId, long appId, Long expectedRevision, String profileId, String effort) {
        if (expectedRevision == null) {
            try {
                jdbc.update("""
                        INSERT INTO biz.agent_model_preference(owner_id,app_id,profile_id,effort)
                        VALUES(?,?,?,?)
                        """, ownerId, appId, profileId, effort);
            } catch (DuplicateKeyException error) {
                throw conflict();
            }
        } else if (jdbc.update("""
                UPDATE biz.agent_model_preference SET profile_id=?,effort=?,revision=revision+1,updated_at=now()
                WHERE owner_id=? AND app_id=? AND revision=?
                """, profileId, effort, ownerId, appId, expectedRevision) != 1) {
            throw conflict();
        }
        return find(ownerId, appId);
    }

    private static MyException conflict() {
        return new MyException(ErrorCode.CONFLICT_ERROR, "模型设置已变化，请刷新后重试。");
    }
}