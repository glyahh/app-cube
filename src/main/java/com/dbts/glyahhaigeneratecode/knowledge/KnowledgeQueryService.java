package com.dbts.glyahhaigeneratecode.knowledge;

import com.dbts.glyahhaigeneratecode.mapper.KnowledgeDocMapper;
import com.dbts.glyahhaigeneratecode.mapper.KnowledgeEdgeMapper;
import com.dbts.glyahhaigeneratecode.mapper.KnowledgeNodeMapper;
import com.dbts.glyahhaigeneratecode.model.Entity.KnowledgeDoc;
import com.dbts.glyahhaigeneratecode.model.Entity.KnowledgeEdge;
import com.dbts.glyahhaigeneratecode.model.Entity.KnowledgeNode;
import com.dbts.glyahhaigeneratecode.model.Entity.KnowledgeSnapshot;
import com.dbts.glyahhaigeneratecode.model.Entity.KnowledgeTask;
import com.dbts.glyahhaigeneratecode.model.VO.KnowledgeGraphVO;
import com.dbts.glyahhaigeneratecode.model.VO.KnowledgeStatusVO;
import com.mybatisflex.core.query.QueryWrapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.nio.file.Path;
import java.util.List;

/**
 * 知识查询：状态、图谱、文档。
 *
 * <p>核心是**版本对齐**：展示的知识属于哪一个快照，与工作区当前版本是否一致。
 * 不一致就如实标 {@code outdated} —— 覆盖成「当前版本」比不展示更糟。
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class KnowledgeQueryService {

    private final KnowledgeSnapshotService snapshotService;
    private final KnowledgeTaskService taskService;
    private final KnowledgeDocMapper docMapper;
    private final KnowledgeNodeMapper nodeMapper;
    private final KnowledgeEdgeMapper edgeMapper;

    /** 图谱节点上限，防止超大项目一次返回过多。 */
    private static final int MAX_GRAPH_NODES = 500;

    private static final int MAX_GRAPH_EDGES = 1000;

    /**
     * 组装知识状态。
     *
     * <p>「知识版本」取最近一次**成功**任务所绑定的快照版本，而不是最近一次任务 ——
     * 一次失败的任务不该把已经生成好的知识标成过期。
     */
    public KnowledgeStatusVO status(Long appId, Path workspace) {
        KnowledgeSnapshot current = snapshotService.snapshot(appId, workspace);
        String currentVersion = current == null ? null : current.getVersion();

        KnowledgeTask latest = taskService.latest(appId);
        String knowledgeVersion = latestSuccessfulVersion(appId);
        int docCount = knowledgeVersion == null ? 0 : countDocs(appId, knowledgeVersion);
        int nodeCount = knowledgeVersion == null ? 0 : countNodes(appId, knowledgeVersion);
        int edgeCount = knowledgeVersion == null ? 0 : countEdges(appId, knowledgeVersion);

        boolean hasKnowledge = docCount > 0 || nodeCount > 0;
        // 有知识、且它的版本与当前代码不一致 → 待更新
        boolean outdated = hasKnowledge && currentVersion != null && knowledgeVersion != null
                && !currentVersion.equals(knowledgeVersion);

        return KnowledgeStatusVO.builder()
                .appId(appId)
                .currentVersion(currentVersion)
                .knowledgeVersion(knowledgeVersion)
                .outdated(outdated)
                .hasKnowledge(hasKnowledge)
                .docCount(docCount)
                .nodeCount(nodeCount)
                .edgeCount(edgeCount)
                .taskId(latest == null ? null : latest.getId())
                .taskStatus(latest == null ? null : latest.getStatus())
                .progress(latest == null ? null : latest.getProgress())
                .phase(latest == null ? null : latest.getPhase())
                .errorMessage(latest == null ? null : latest.getErrorMessage())
                .retryable(isRetryable(latest))
                .taskUpdatedAt(latest == null ? null : latest.getUpdateTime())
                .build();
    }

    /** 组装图谱。没有知识时返回空节点与空关系，而不是报错。 */
    public KnowledgeGraphVO graph(Long appId, Path workspace) {
        KnowledgeSnapshot current = snapshotService.snapshot(appId, workspace);
        String currentVersion = current == null ? null : current.getVersion();
        String knowledgeVersion = latestSuccessfulVersion(appId);

        if (knowledgeVersion == null) {
            return KnowledgeGraphVO.builder()
                    .appId(appId)
                    .snapshotVersion(null)
                    .currentVersion(currentVersion)
                    .outdated(false)
                    .nodes(List.of())
                    .edges(List.of())
                    .build();
        }

        List<KnowledgeNode> nodes = nodeMapper.selectListByQuery(QueryWrapper.create()
                .eq("app_id", appId)
                .eq("snapshot_version", knowledgeVersion)
                .limit(MAX_GRAPH_NODES));
        List<KnowledgeEdge> edges = edgeMapper.selectListByQuery(QueryWrapper.create()
                .eq("app_id", appId)
                .eq("snapshot_version", knowledgeVersion)
                .limit(MAX_GRAPH_EDGES));

        // 只保留两端都存在的边：悬空关系会让前端画出连不上的节点
        var nodeKeys = nodes.stream().map(KnowledgeNode::getNodeKey).collect(java.util.stream.Collectors.toSet());
        List<KnowledgeGraphVO.GraphEdge> validEdges = edges.stream()
                .filter(edge -> nodeKeys.contains(edge.getFromKey()) && nodeKeys.contains(edge.getToKey()))
                .map(edge -> KnowledgeGraphVO.GraphEdge.builder()
                        .from(edge.getFromKey())
                        .to(edge.getToKey())
                        .relation(edge.getRelation())
                        .evidence(edge.getEvidence())
                        .sourceRef(edge.getSourceRef())
                        .build())
                .toList();

        return KnowledgeGraphVO.builder()
                .appId(appId)
                .snapshotVersion(knowledgeVersion)
                .currentVersion(currentVersion)
                .outdated(currentVersion != null && !currentVersion.equals(knowledgeVersion))
                .nodes(nodes.stream()
                        .map(node -> KnowledgeGraphVO.GraphNode.builder()
                                .key(node.getNodeKey())
                                .name(node.getName())
                                .kind(node.getKind())
                                .summary(node.getSummary())
                                .evidence(node.getEvidence())
                                .sourceRef(node.getSourceRef())
                                .build())
                        .toList())
                .edges(validEdges)
                .build();
    }

    /** 当前有效版本的知识文档。 */
    public List<KnowledgeDoc> docs(Long appId) {
        String knowledgeVersion = latestSuccessfulVersion(appId);
        if (knowledgeVersion == null) {
            return List.of();
        }
        return docMapper.selectListByQuery(QueryWrapper.create()
                .eq("app_id", appId)
                .eq("snapshot_version", knowledgeVersion)
                .limit(50));
    }

    /** 最近一次成功任务所绑定的快照版本；没有成功过则返回 null。 */
    private String latestSuccessfulVersion(Long appId) {
        List<KnowledgeTask> list = taskService.latestSuccessful(appId);
        return list.isEmpty() ? null : list.get(0).getSnapshotVersion();
    }

    private static boolean isRetryable(KnowledgeTask task) {
        if (task == null || task.getStatus() == null) {
            return false;
        }
        return KnowledgeTaskService.STATUS_FAILED.equals(task.getStatus())
                || KnowledgeTaskService.STATUS_CANCELLED.equals(task.getStatus())
                || KnowledgeTaskService.STATUS_INTERRUPTED.equals(task.getStatus());
    }

    private int countDocs(Long appId, String version) {
        return (int) docMapper.selectCountByQuery(QueryWrapper.create()
                .eq("app_id", appId).eq("snapshot_version", version));
    }

    private int countNodes(Long appId, String version) {
        return (int) nodeMapper.selectCountByQuery(QueryWrapper.create()
                .eq("app_id", appId).eq("snapshot_version", version));
    }

    private int countEdges(Long appId, String version) {
        return (int) edgeMapper.selectCountByQuery(QueryWrapper.create()
                .eq("app_id", appId).eq("snapshot_version", version));
    }
}
