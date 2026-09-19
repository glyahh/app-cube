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
 * 运行异常台账 实体类。
 *
 * <p>{@code fingerprint} 是同次运行内的去重键：错误行归一化后取摘要，
 * 所以同一处错误无论出现多少次都只记一条 —— 这是「同次运行的重复错误不反复弹框」的落点。
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
@Table("app_runtime_incident")
public class AppRuntimeIncident implements Serializable {

    @Serial
    private static final long serialVersionUID = 1L;

    @Id(keyType = KeyType.Generator, value = com.mybatisflex.core.keygen.KeyGenerators.snowFlakeId)
    private Long id;

    @Column("app_id")
    private Long appId;

    @Column("user_id")
    private Long userId;

    @Column("run_no")
    private Integer runNo;

    /** 归一化错误行的摘要，同次运行内去重。 */
    @Column("fingerprint")
    private String fingerprint;

    /** STARTUP_FAILURE / RUNTIME_FAILURE */
    @Column("kind")
    private String kind;

    /** OPEN / DISMISSED / REPAIRING / REPAIRED —— 关闭弹窗只是 DISMISSED，记录仍在。 */
    @Column("status")
    private String status;

    @Column("summary")
    private String summary;

    /** 脱敏后的诊断内容。 */
    @Column("detail")
    private String detail;

    @Column("exit_code")
    private Integer exitCode;

    @Column("detected_at")
    private LocalDateTime detectedAt;

    @Column("notified_at")
    private LocalDateTime notifiedAt;

    @Column("create_time")
    private LocalDateTime createTime;

    @Column("update_time")
    private LocalDateTime updateTime;

    @Column(value = "is_delete", isLogicDelete = true)
    private Integer isDelete;
}
