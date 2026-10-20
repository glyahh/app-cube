package com.dbts.glyahhaigeneratecode.agentauth;

import lombok.RequiredArgsConstructor;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * 普通应用的变更账本。
 *
 * <p>与导入的卷内封存不同：这里记录的是「已落盘」的改动，
 * 用于整批回滚与候选审阅；单文件正文超限时只留摘要并标 truncated。
 */
@Repository
@RequiredArgsConstructor
public class AgentChangeLedgerRepository {
    /** 单文件正文上限；超出即不存正文，避免账本无限膨胀。 */
    public static final int MAX_BLOB_CHARS = 256 * 1024;

    private final JdbcTemplate jdbc;

    public record Change(String path, String change, String beforeHash, String afterHash,
                         String beforeBlob, String afterBlob, boolean truncated) { }

    /** 同一路径重复改动时按最终状态覆盖，保留最初的 before 与最新 after。 */
    public void record(UUID sessionId, Change change) {
        boolean truncated = change.truncated()
                || (change.beforeBlob() != null && change.beforeBlob().length() > MAX_BLOB_CHARS)
                || (change.afterBlob() != null && change.afterBlob().length() > MAX_BLOB_CHARS);
        String before = truncated ? null : change.beforeBlob();
        String after = truncated ? null : change.afterBlob();
        jdbc.update("""
                INSERT INTO biz.agent_change_ledger(session_id,path,change,before_hash,after_hash,
                    before_blob,after_blob,truncated)
                VALUES(?,?,?,?,?,?,?,?)
                ON CONFLICT (session_id,path) DO UPDATE SET
                    change=EXCLUDED.change,
                    after_hash=EXCLUDED.after_hash,
                    after_blob=EXCLUDED.after_blob,
                    truncated=EXCLUDED.truncated
                """, sessionId, change.path(), change.change(), change.beforeHash(), change.afterHash(),
                before, after, truncated);
    }

    public List<Change> list(UUID sessionId) {
        return jdbc.query("""
                SELECT path,change,before_hash,after_hash,before_blob,after_blob,truncated
                FROM biz.agent_change_ledger WHERE session_id=? ORDER BY path
                """, (row, index) -> new Change(row.getString("path"), row.getString("change"),
                row.getString("before_hash"), row.getString("after_hash"),
                row.getString("before_blob"), row.getString("after_blob"),
                row.getBoolean("truncated")), sessionId);
    }

    /** 回滚所需的最小集合：只剩 before 内容与变更类型。 */
    public List<Map<String, Object>> rollbackPlan(UUID sessionId) {
        return jdbc.queryForList("""
                SELECT path,change,before_blob,truncated FROM biz.agent_change_ledger
                WHERE session_id=? ORDER BY path
                """, sessionId);
    }

    public void clear(UUID sessionId) {
        jdbc.update("DELETE FROM biz.agent_change_ledger WHERE session_id=?", sessionId);
    }
}