package com.dbts.glyahhaigeneratecode.model.VO;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.io.Serializable;
import java.time.LocalDateTime;

/**
 * 知识状态视图对象。
 *
 * <p>回答一个具体问题：「现在展示的知识，是不是当前这份代码的？」
 * 三个版本要分清楚：代码当前版本、知识产出所依据的版本、以及任务进行到哪了。
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class KnowledgeStatusVO implements Serializable {

    private static final long serialVersionUID = 1L;

    private Long appId;

    /** 工作区当前内容版本。 */
    private String currentVersion;

    /** 已有知识对应的快照版本；还没有知识时为 null。 */
    private String knowledgeVersion;

    /**
     * 知识是否落后于代码。
     *
     * <p>为真时前端显示「待更新」—— 不能把旧版本的知识当成当前代码的说明。
     */
    private Boolean outdated;

    /** 是否已有可展示的知识（文档或图谱）。 */
    private Boolean hasKnowledge;

    private Integer docCount;

    private Integer nodeCount;

    private Integer edgeCount;

    /** 最近一次任务的状态；从未生成过为 null。 */
    private String taskStatus;

    private Long taskId;

    private Integer progress;

    private String phase;

    private String errorMessage;

    /** 任务中断是否可重试（失败/取消/中断可重试，成功与进行中不可）。 */
    private Boolean retryable;

    private LocalDateTime taskUpdatedAt;
}
