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
 * 对话历史 实体类。
 *
 * @author <a href="https://github.com/glyahh">glyahh</a>
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
@Table("chat_history")
public class ChatHistory implements Serializable {

    @Serial
    private static final long serialVersionUID = 1L;

    /**
     * id
     */
    @Id(keyType = KeyType.Generator, value = KeyGenerators.snowFlakeId)
    private Long id;

    /**
     * 消息
     */
    private String message;

    /**
     * user/ai
     */
    @Column("message_type")
    private String messageType;

    /**
     * 应用id
     */
    @Column("app_id")
    private Long appId;

    /**
     * 创建用户id
     */
    @Column("user_id")
    private Long userId;

    /**
     * 审查动作：ALLOW / REJECT / SKIP
     */
    @Column("audit_action")
    private String auditAction;

    /**
     * 命中的审查规则编码，未命中时为 NONE
     */
    @Column("audit_hit_rule")
    private String auditHitRule;

    /**
     * Loop ID（本轮注入的 Loop 快照，nullable）
     */
    @Column("loop_id")
    private Long loopId;

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
