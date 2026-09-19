package com.dbts.glyahhaigeneratecode.model.VO;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.io.Serializable;
import java.util.List;

/**
 * 项目知识图谱视图对象。
 *
 * <p>每个节点与关系都带 {@code evidence}：源码里读出来的证据，还是模型推断。
 * 前端必须把两者区分展示 —— 让推断看起来像事实，是这类图谱最容易犯的错。
 *
 * <p>同时带上 {@code snapshotVersion}：客户端据此知道自己在看哪一版，
 * 与服务端最新版本不一致时提示「待更新」。
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class KnowledgeGraphVO implements Serializable {

    private static final long serialVersionUID = 1L;

    private Long appId;

    /** 这份图谱对应的快照版本。 */
    private String snapshotVersion;

    /** 工作区当前版本，用于判断图谱是否过期。 */
    private String currentVersion;

    /** 图谱是否落后于当前代码。 */
    private Boolean outdated;

    private List<GraphNode> nodes;

    private List<GraphEdge> edges;

    /** 图谱节点。 */
    @Data
    @Builder
    @NoArgsConstructor
    @AllArgsConstructor
    public static class GraphNode implements Serializable {

        private static final long serialVersionUID = 1L;

        /** 稳定标识，关系两端引用它。 */
        private String key;

        private String name;

        /** file / module / function / class / endpoint / table */
        private String kind;

        private String summary;

        /** CODE_EVIDENCE 或 AI_INFERENCE */
        private String evidence;

        /** 源码引用，如 src/main.js:42 */
        private String sourceRef;
    }

    /** 图谱关系。 */
    @Data
    @Builder
    @NoArgsConstructor
    @AllArgsConstructor
    public static class GraphEdge implements Serializable {

        private static final long serialVersionUID = 1L;

        private String from;

        private String to;

        /** imports / calls / contains / exposes */
        private String relation;

        private String evidence;

        private String sourceRef;
    }
}
