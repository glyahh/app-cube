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
 * 图谱关系 实体类。
 *
 * <p>两端存的是 {@link KnowledgeNode#getNodeKey()} 而不是节点主键：
 * 重新生成知识时节点主键会变，但模块名与文件路径这类标识是稳定的，
 * 用 key 对齐才能让新旧图谱做差分。
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
@Table("knowledge_edge")
public class KnowledgeEdge implements Serializable {

    @Serial
    private static final long serialVersionUID = 1L;

    @Id(keyType = KeyType.Generator, value = com.mybatisflex.core.keygen.KeyGenerators.snowFlakeId)
    private Long id;

    @Column("app_id")
    private Long appId;

    @Column("snapshot_id")
    private Long snapshotId;

    @Column("snapshot_version")
    private String snapshotVersion;

    @Column("from_key")
    private String fromKey;

    @Column("to_key")
    private String toKey;

    /** imports / calls / contains / exposes ... */
    @Column("relation")
    private String relation;

    @Column("evidence")
    private String evidence;

    @Column("source_ref")
    private String sourceRef;

    @Column("create_time")
    private LocalDateTime createTime;

    @Column("update_time")
    private LocalDateTime updateTime;

    @Column(value = "is_delete", isLogicDelete = true)
    private Integer isDelete;
}
