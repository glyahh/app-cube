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
 * 知识问答消息 实体类。
 *
 * <p>与生成对话分开存：这是「只读地了解项目」，不是「让 AI 改项目」。
 * 每条回答记下它读取的 {@code snapshotVersion}，资料过期时把 {@code stale} 置真 ——
 * 用户能看到答案是基于哪一版给的，而不是以为它一定最新。
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
@Table("knowledge_qa_message")
public class KnowledgeQaMessage implements Serializable {

    @Serial
    private static final long serialVersionUID = 1L;

    @Id(keyType = KeyType.Generator, value = com.mybatisflex.core.keygen.KeyGenerators.snowFlakeId)
    private Long id;

    @Column("app_id")
    private Long appId;

    @Column("user_id")
    private Long userId;

    /** user / assistant */
    @Column("role")
    private String role;

    @Column("content")
    private String content;

    /** 回答依据的快照版本。 */
    @Column("snapshot_version")
    private String snapshotVersion;

    /** 资料是否已过期（回答所依据的版本已经不是当前版本）。 */
    @Column("stale")
    private Boolean stale;

    /** 引用的源码/文档，JSON 数组。 */
    @Column("citations_json")
    private String citationsJson;

    @Column("create_time")
    private LocalDateTime createTime;

    @Column(value = "is_delete", isLogicDelete = true)
    private Integer isDelete;
}
