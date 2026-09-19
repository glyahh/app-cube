package com.dbts.glyahhaigeneratecode.model.Entity;

import com.mybatisflex.annotation.Column;
import com.mybatisflex.annotation.Id;
import com.mybatisflex.annotation.KeyType;
import com.mybatisflex.annotation.Table;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.io.Serial;
import java.io.Serializable;
import java.time.LocalDateTime;

/**
 * 知识生成任务 实体类。
 *
 * <p>与主生成会话完全分离：不占项目的生成锁，失败也不影响项目状态。
 * 后端重启时，凡是停在 {@code RUNNING} 的任务都要被标成 {@code INTERRUPTED} ——
 * 进程没了但记录还写着「生成中」，用户会一直等一个永远不会完成的任务。
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
@Table("knowledge_task")
public class KnowledgeTask implements Serializable {

    @Serial
    private static final long serialVersionUID = 1L;

    @Id(keyType = KeyType.Generator, value = com.mybatisflex.core.keygen.KeyGenerators.snowFlakeId)
    private Long id;

    @Column("app_id")
    private Long appId;

    @Column("user_id")
    private Long userId;

    @Column("snapshot_id")
    private Long snapshotId;

    @Column("snapshot_version")
    private String snapshotVersion;

    /** PENDING / RUNNING / SUCCEEDED / FAILED / CANCELLED / INTERRUPTED */
    @Column("status")
    private String status;

    @Column("progress")
    private Integer progress;

    @Column("phase")
    private String phase;

    @Column("error_message")
    private String errorMessage;

    /** 关联的 agent run id，便于与生成链路对照排障。 */
    @Column("run_id")
    private String runId;

    @Column("started_at")
    private LocalDateTime startedAt;

    @Column("finished_at")
    private LocalDateTime finishedAt;

    @Column("create_time")
    private LocalDateTime createTime;

    @Column("update_time")
    private LocalDateTime updateTime;

    @Column(value = "is_delete", isLogicDelete = true)
    private Integer isDelete;
}
