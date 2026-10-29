package com.dbts.glyahhaigeneratecode.runtime;

import java.util.Map;

/**
 * 一次合入前后的文件级快照。
 *
 * <p>状态机为 {@code PENDING → COMMITTED | FAILED}。{@code beforeHashes} 在创建时写入，
 * {@code afterHashes} 只在合入成功后写入；合入失败时保持为空，恢复不再比对合入后内容。
 */
public record SnapshotRecord(
        String id,
        Status status,
        String createdAt,
        String workspace,
        String reason,
        Map<String, String> beforeHashes,
        Map<String, String> afterHashes,
        Map<String, String> recoveryHashes) {

    public enum Status { PENDING, COMMITTED, FAILED }
}
