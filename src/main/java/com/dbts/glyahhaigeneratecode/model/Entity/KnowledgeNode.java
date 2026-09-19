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
 * 图谱节点 实体类。
 *
 * <p>{@code evidence} 是硬约束：{@code CODE_EVIDENCE} 表示这条信息是从源码里读出来的
 * （有 {@code sourceRef} 指向具体位置），{@code AI_INFERENCE} 是模型推断。
 * 两者在前端必须区分展示，不能让推断看起来像事实。
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
@Table("knowledge_node")
public class KnowledgeNode implements Serializable {

    @Serial
    private static final long serialVersionUID = 1L;

    public static final String EVIDENCE_CODE = "CODE_EVIDENCE";
    public static final String EVIDENCE_INFERENCE = "AI_INFERENCE";

    @Id(keyType = KeyType.Generator, value = com.mybatisflex.core.keygen.KeyGenerators.snowFlakeId)
    private Long id;

    @Column("app_id")
    private Long appId;

    @Column("snapshot_id")
    private Long snapshotId;

    @Column("snapshot_version")
    private String snapshotVersion;

    /** 稳定标识，关系两端靠它对齐。 */
    @Column("node_key")
    private String nodeKey;

    @Column("name")
    private String name;

    /** file / module / function / class / endpoint / table ... */
    @Column("kind")
    private String kind;

    @Column("summary")
    private String summary;

    @Column("evidence")
    private String evidence;

    /** 源码引用，如 {@code src/main.js:42}。 */
    @Column("source_ref")
    private String sourceRef;

    @Column("create_time")
    private LocalDateTime createTime;

    @Column("update_time")
    private LocalDateTime updateTime;

    @Column(value = "is_delete", isLogicDelete = true)
    private Integer isDelete;
}
