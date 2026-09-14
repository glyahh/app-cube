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
 * 用户 实体类。
 *
 * @author <a href="https://github.com/glyahh">glyahh</a>
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
@Table("user")
public class User implements Serializable {

    @Serial
    private static final long serialVersionUID = 1L;

    /**
     * id
     */
    @Id(keyType = KeyType.Generator, value = KeyGenerators.snowFlakeId)
    private Long id;

    /**
     * 账号
     */
    @Column("user_account")
    private String userAccount;

    /**
     * 密码
     */
    @Column("user_password")
    private String userPassword;

    /**
     * 用户昵称
     */
    @Column("user_name")
    private String userName;

    /**
     * 用户头像
     */
    @Column("user_avatar")
    private String userAvatar;

    /**
     * 用户简介
     */
    @Column("user_profile")
    private String userProfile;

    /**
     * 用户角色: user/admin
     */
    @Column("user_role")
    private String userRole;

    /** 新消息是否默认展开执行过程。 */
    @Column("expand_execution_process")
    private Boolean expandExecutionProcess;

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

}
