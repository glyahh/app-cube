package com.dbts.glyahhaigeneratecode.model.Entity;

import com.mybatisflex.annotation.Column;
import com.mybatisflex.annotation.Id;
import com.mybatisflex.annotation.KeyType;
import com.mybatisflex.annotation.Table;
import com.mybatisflex.core.keygen.KeyGenerators;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.io.Serial;
import java.io.Serializable;
import java.time.LocalDateTime;

/**
 * 用户应用 / 权限申请记录 实体类。
 *
 * 对应表：user_app_apply
 *
 * 用于记录用户申请将自己的应用设置为精选应用，或申请成为管理员的操作。
 *
 * @author <a href="https://github.com/glyahh">glyahh</a>
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
@Table("user_app_apply")
public class UserAppApply implements Serializable {

    @Serial
    private static final long serialVersionUID = 1L;

    /**
     * id（使用雪花算法生成）
     */
    @Id(keyType = KeyType.Generator, value = KeyGenerators.snowFlakeId)
    private Long id;

    /**
     * 申请用户 id
     */
    @Column("user_id")
    private Long userId;

    /**
     * 申请关联的应用 id，申请管理员时可为空
     */
    @Column("app_id")
    private Long appId;

    /**
     * 应用展示优先级，模拟申请将应用设置为精选时的目标优先级（越大越靠前）
     */
    @Column("app_propriety")
    private Integer appPropriety;

    /**
     * 操作类型：1-申请将自己的应用设置为精选应用；2-申请成为管理员
     */
    @Column("operate")
    private Integer operate;

    /**
     * 申请理由
     */
    @Column("apply_reason")
    private String applyReason;

    /**
     * 处理状态：0-待处理；1-通过；2-拒绝
     */
    @Column("status")
    private Integer status;

    /**
     * 审核管理员用户 id
     */
    @Column("review_user_id")
    private Long reviewUserId;

    /**
     * 审核备注
     */
    @Column("review_remark")
    private String reviewRemark;

    /**
     * 审核时间
     */
    @Column("review_time")
    private LocalDateTime reviewTime;

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
}

