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
 * 源码快照 实体类。
 *
 * <p>一次知识生成对应一个**不可变**的输入集合：版本由文件内容算出，
 * 同一应用同一版本只会有一条。之后代码再改，产生的是新快照，
 * 旧快照上的知识仍然绑定旧版本 —— 这是「完成时版本落后则显示待更新」的依据。
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
@Table("knowledge_snapshot")
public class KnowledgeSnapshot implements Serializable {

    @Serial
    private static final long serialVersionUID = 1L;

    @Id(keyType = KeyType.Generator, value = com.mybatisflex.core.keygen.KeyGenerators.snowFlakeId)
    private Long id;

    @Column("app_id")
    private Long appId;

    /** 按文件内容算出的摘要，不是宿主仓库的 Git SHA。 */
    @Column("version")
    private String version;

    @Column("file_count")
    private Integer fileCount;

    @Column("total_bytes")
    private Long totalBytes;

    /** 纳入快照的文件清单（路径 + 大小 + 摘要）。 */
    @Column("manifest_json")
    private String manifestJson;

    @Column("create_time")
    private LocalDateTime createTime;

    @Column("update_time")
    private LocalDateTime updateTime;

    @Column(value = "is_delete", isLogicDelete = true)
    private Integer isDelete;
}
