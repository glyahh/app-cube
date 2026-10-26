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

    /** 同一路径保留最初 before 与最新 after；原文一旦缺失，后续小改动不得恢复可回滚标记。 */
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
                    truncated=agent_change_ledger.truncated OR EXCLUDED.truncated
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

    /** 原始摘要与正文一起保留：缺少正文但有摘要表示未知原文，不是新增文件。 */
    public List<Map<String, Object>> rollbackPlan(UUID sessionId) {
        return jdbc.queryForList("""
                SELECT path,change,before_hash,before_blob,truncated FROM biz.agent_change_ledger
                WHERE session_id=? ORDER BY path
                """, sessionId);
    }

    public void clear(UUID sessionId) {
        jdbc.update("DELETE FROM biz.agent_change_ledger WHERE session_id=?", sessionId);
    }
}