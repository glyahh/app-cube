package com.dbts.glyahhaigeneratecode.model.Entity;

import com.mybatisflex.annotation.Column;
import com.mybatisflex.annotation.Id;
import com.mybatisflex.annotation.KeyType;
import com.mybatisflex.annotation.Table;
import java.io.Serializable;
import java.time.LocalDateTime;

import java.io.Serial;

import com.mybatisflex.core.keygen.KeyGenerators;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * 应用 实体类。
 *
 * @author <a href="https://github.com/glyahh">glyahh</a>
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
@Table("app")
public class App implements Serializable {

    @Serial
    private static final long serialVersionUID = 1L;

    /**
     * id（使用雪花算法生成，需用 KeyType.Generator；KeyType.Auto 表示数据库自增）
     */
    @Id(keyType = KeyType.Generator, value = KeyGenerators.snowFlakeId)
    private Long id;

    /**
     * 应用名称
     */
    @Column("app_name")
    private String appName;

    /**
     * 应用封面
     */
    private String cover;

    /**
     * 应用初始化的 prompt
     */
    @Column("init_prompt")
    private String initPrompt;

    /**
     * 代码生成类型（枚举）
     */
    @Column("code_gen_type")
    private String codeGenType;

    /**
     * 部署标识
     */
    @Column("deploy_key")
    private String deployKey;

    /**
     * 部署时间
     */
    @Column("deployed_time")
    private LocalDateTime deployedTime;

    /**
     * 优先级
     */
    private Integer priority;

    /**
     * 创建用户id
     */
    @Column("user_id")
    private Long userId;

    /**
     * 编辑时间
     */
    @Column("edit_time")
    private LocalDateTime editTime;

    /**
     * 创建时间
     */
    @Column("create_time")
    private LocalDateTime createTime;

    /**
     * 更新时间
     */
    @Column("update_time")
    private LocalDateTime updateTime;

    /**
     * 是否删除
     */
    @Column(value = "is_delete", isLogicDelete = true)
    private Integer isDelete;

    /**
     * 是否 beta 应用：0-否，1-是（workflow beta）
     */
    @Column("is_beta")
    private Integer isBeta;

    /**
     * 看板功能开关，JSON 数组文本，如 {@code ["metrics","logs"]}。
     *
     * <p>null 或空数组表示全不选 —— 新建应用的默认状态。
     */
    @Column("dashboard_features")
    private String dashboardFeatures;

    /** 首次成功降级后，该应用后续模型调用固定直连。 */
    @Column("force_model_direct")
    private Boolean forceModelDirect;

}
