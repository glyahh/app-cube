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
 * Agent 执行台账 实体类。
 *
 * <p>与 {@code chat_history} 的分工：那张表记录**对话内容**，这张记录**执行过程** ——
 * 谁在什么时候跑了哪一轮、跑成什么样、卡在哪。用它回答「会话里那条 AI 回复是哪次 run 产生的」
 * 以及「有没有 run 起了头但没收尾」。
 *
 * <p>只有 Java 写这张表。Python 不碰 {@code biz} schema：{@code isDelete} 逻辑删除、
 * 雪花 ID 生成都是 MyBatis-Flex 在 ORM 层做的，裸 SQL 会绕过它们 ——
 * 这条是用 database role 从物理上限制的，不靠约定。
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
@Table("agent_run")
public class AgentRun implements Serializable {

    @Serial
    private static final long serialVersionUID = 1L;

    /**
     * 雪花 ID，**由代码显式设置**，值就是协议里的 {@code runId}。
     *
     * <p>不能用 {@code KeyType.Generator}：那会让 ORM 另生成一个 id，
     * 于是 {@code agent_run.id} 与 Python 侧事件里的 {@code runId} 对不上，
     * 而「用 runId 反查执行记录」正是这张表存在的意义。
     */
    @Id(keyType = KeyType.None)
    private Long id;

    @Column("app_id")
    private Long appId;

    @Column("user_id")
    private Long userId;

    /** chat_history 里那条 USER 行的 id。既是本轮锚点，也是幂等键。 */
    @Column("round_id")
    private Long roundId;

    /** 形如 {@code app:431379639885025280}，由 appId 纯函数派生。 */
    @Column("thread_id")
    private String threadId;

    /** 跨进程日志串联用，Java 与 Python 共用。 */
    @Column("trace_id")
    private String traceId;

    @Column("code_gen_type")
    private String codeGenType;

    /** 产物目录名，如 {@code vue_project_431379639885025280}。 */
    @Column("workspace_name")
    private String workspaceName;

    /**
     * PENDING / RUNNING / SUCCEEDED / FAILED / CANCELLED / UNREACHABLE / UNKNOWN。
     * 数据库侧有 CHECK 约束兜着，写错值会被直接拒绝。
     */
    @Column("status")
    private String status;

    @Column("phase")
    private String phase;

    @Column("error_code")
    private String errorCode;

    @Column("error_message")
    private String errorMessage;

    /** 对账用：Python 事件的最后序号与时间，用来识别起了头就没动静的 run。 */
    @Column("last_event_seq")
    private Integer lastEventSeq;

    @Column("last_event_at")
    private LocalDateTime lastEventAt;

    @Column("started_at")
    private LocalDateTime startedAt;

    @Column("finished_at")
    private LocalDateTime finishedAt;

    @Column("deadline_at")
    private LocalDateTime deadlineAt;

    @Column("reconcile_tries")
    private Integer reconcileTries;

    @Column("usage_json")
    private String usageJson;

    @Column("manifest_json")
    private String manifestJson;

    @Column("create_time")
    private LocalDateTime createTime;

    @Column("update_time")
    private LocalDateTime updateTime;

    @Column(value = "is_delete", isLogicDelete = true)
    private Integer isDelete;
}
