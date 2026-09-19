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
 * 应用运行台账 实体类。
 *
 * <p>与 {@link AgentRun} 的分工：那张记录**生成**（Python 侧跑的一轮对话），
 * 这张记录**运行**（运行时沙箱里常驻的应用进程）。一次生成可以对应多次运行。
 *
 * <p>{@code containerName} 是重启后的对账锚点：内存里的运行状态在进程重启后就没了，
 * 但容器还在 Docker 里跑着 —— 靠名字反查真实状态，避免「后端重启后页面显示运行中，
 * 实际容器早死了」或者反过来的情况。
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
@Table("app_runtime")
public class AppRuntime implements Serializable {

    @Serial
    private static final long serialVersionUID = 1L;

    @Id(keyType = KeyType.Generator, value = com.mybatisflex.core.keygen.KeyGenerators.snowFlakeId)
    private Long id;

    @Column("app_id")
    private Long appId;

    @Column("user_id")
    private Long userId;

    /** 同一应用内从 1 递增的启动编号；日志目录与前端展示都用它区分「第几次运行」。 */
    @Column("run_no")
    private Integer runNo;

    /** STARTING / RUNNING / STOPPED / FAILED / UNKNOWN，数据库侧有 CHECK 约束兜底。 */
    @Column("status")
    private String status;

    @Column("container_name")
    private String containerName;

    @Column("container_id")
    private String containerId;

    /** 宿主侧映射端口；没有 HTTP 入口的项目为 null。 */
    @Column("gateway_port")
    private Integer gatewayPort;

    /** 受管进程定义快照（JSON），停止与重启时按它复现。 */
    @Column("spec_json")
    private String specJson;

    /** 启动时所用源码的版本标识；与工作区当前版本不同即「运行版本落后」。 */
    @Column("source_version")
    private String sourceVersion;

    @Column("exit_code")
    private Integer exitCode;

    @Column("error_message")
    private String errorMessage;

    @Column("started_at")
    private LocalDateTime startedAt;

    @Column("stopped_at")
    private LocalDateTime stoppedAt;

    @Column("last_heartbeat_at")
    private LocalDateTime lastHeartbeatAt;

    @Column("create_time")
    private LocalDateTime createTime;

    @Column("update_time")
    private LocalDateTime updateTime;

    @Column(value = "is_delete", isLogicDelete = true)
    private Integer isDelete;
}
