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
 * 知识文档 实体类。
 *
 * <p>与图谱共用同一批节点与源码引用：文档里提到的模块名就是图谱里的 {@code node_key}，
 * 所以「从文档跳到源码」和「从图谱节点跳到源码」指向同一处。
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
@Table("knowledge_doc")
public class KnowledgeDoc implements Serializable {

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

    @Column("title")
    private String title;

    @Column("content")
    private String content;

    @Column("create_time")
    private LocalDateTime createTime;

    @Column("update_time")
    private LocalDateTime updateTime;

    @Column(value = "is_delete", isLogicDelete = true)
    private Integer isDelete;
}
