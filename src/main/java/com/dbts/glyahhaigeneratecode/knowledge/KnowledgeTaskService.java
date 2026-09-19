package com.dbts.glyahhaigeneratecode.knowledge;

import cn.hutool.json.JSONArray;
import cn.hutool.json.JSONObject;
import cn.hutool.json.JSONUtil;
import com.dbts.glyahhaigeneratecode.agent.AgentEvent;
import com.dbts.glyahhaigeneratecode.agent.AgentProperties;
import com.dbts.glyahhaigeneratecode.agent.AgentRunRequest;
import com.dbts.glyahhaigeneratecode.agent.AgentRunService;
import com.dbts.glyahhaigeneratecode.config.WorkspaceProperties;
import com.dbts.glyahhaigeneratecode.exception.ErrorCode;
import com.dbts.glyahhaigeneratecode.exception.MyException;
import com.dbts.glyahhaigeneratecode.mapper.KnowledgeDocMapper;
import com.dbts.glyahhaigeneratecode.mapper.KnowledgeEdgeMapper;
import com.dbts.glyahhaigeneratecode.mapper.KnowledgeNodeMapper;
import com.dbts.glyahhaigeneratecode.mapper.KnowledgeQaMessageMapper;
import com.dbts.glyahhaigeneratecode.mapper.KnowledgeSnapshotMapper;
import com.dbts.glyahhaigeneratecode.mapper.KnowledgeTaskMapper;
import com.dbts.glyahhaigeneratecode.mapper.AppMapper;
import com.dbts.glyahhaigeneratecode.model.Entity.KnowledgeDoc;
import com.dbts.glyahhaigeneratecode.model.Entity.KnowledgeEdge;
import com.dbts.glyahhaigeneratecode.model.Entity.KnowledgeNode;
import com.dbts.glyahhaigeneratecode.model.Entity.KnowledgeSnapshot;
import com.dbts.glyahhaigeneratecode.model.Entity.KnowledgeTask;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.mybatisflex.core.query.QueryWrapper;
import jakarta.annotation.PreDestroy;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.stereotype.Service;
import reactor.core.Disposable;
import reactor.core.scheduler.Schedulers;

import java.nio.file.Path;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Semaphore;
import java.util.concurrent.TimeUnit;

/**
 * 知识生成任务：在独立通道里异步把源码分析成文档与图谱。
 *
 * <p>几条不能省的边界：
 * <ul>
 *   <li><b>不占项目生成锁</b>：走 {@code streamUncoordinated}。知识任务与用户的生成请求
 *       互不排队 —— 用户可以一边改代码一边等知识生成。</li>
 *   <li><b>不进主生成会话</b>：threadId 用 {@code app:{appId}:knowledge}，
 *       不写 chat_history，用户看不到也不受影响。</li>
 *   <li><b>低并发</b>：全局只放一个知识任务在跑。它和生成共用 Python 进程池，
 *       放开并发会把用户的生成请求挤到没进程可用。</li>
 *   <li><b>失败不影响项目</b>：任务失败只标自己的状态。</li>
 * </ul>
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class KnowledgeTaskService {

    private final KnowledgeSnapshotService snapshotService;
    private final KnowledgeTaskMapper taskMapper;
    private final KnowledgeDocMapper docMapper;
    private final KnowledgeNodeMapper nodeMapper;
    private final KnowledgeEdgeMapper edgeMapper;
    private final KnowledgeSnapshotMapper snapshotMapper;
    private final KnowledgeQaMessageMapper qaMapper;
    private final AgentRunService agentRunService;
    private final AgentProperties agentProperties;
    private final WorkspaceProperties workspaceProperties;
    private final ObjectMapper objectMapper;
    private final AppMapper appMapper;

    public static final String STATUS_PENDING = "PENDING";
    public static final String STATUS_RUNNING = "RUNNING";
    public static final String STATUS_SUCCEEDED = "SUCCEEDED";
    public static final String STATUS_FAILED = "FAILED";
    public static final String STATUS_CANCELLED = "CANCELLED";
    /** 后端重启导致的中断：与失败区分开，前端据此提示「可重试」。 */
    public static final String STATUS_INTERRUPTED = "INTERRUPTED";

    /** 全局并发上限。知识任务与生成共用进程池，必须让路。 */
    private static final int MAX_CONCURRENT = 1;

    /** 等空闲槽位的上限，超时按失败处理，避免任务无限排队。 */
    private static final long SLOT_WAIT_SECONDS = 300;

    /** 单次知识生成的墙钟上限：超时判失败并断开事件流，不让任务无限挂着占住执行槽。 */
    private static final long TASK_TIMEOUT_MINUTES = 10;

    private final Semaphore slots = new Semaphore(MAX_CONCURRENT, true);
    private final Map<Long, Disposable> runningTasks = new ConcurrentHashMap<>();

    /**
     * 提交一次知识生成。
     *
     * <p>幂等：同一应用同一快照版本已有活跃任务时直接返回它，重复点击不会起第二个。
     */
    public KnowledgeTask submit(Long appId, Long userId, Path workspace) {
        KnowledgeSnapshot snapshot = snapshotService.snapshot(appId, workspace);
        if (snapshot == null) {
            throw new MyException(ErrorCode.OPERATION_ERROR,
                    "工作区还没有可分析的源码，先生成代码再试试");
        }

        KnowledgeTask active = findActive(appId, snapshot.getVersion());
        if (active != null) {
            return active;
        }

        KnowledgeTask task = KnowledgeTask.builder()
                .appId(appId)
                .userId(userId)
                .snapshotId(snapshot.getId())
                .snapshotVersion(snapshot.getVersion())
                .status(STATUS_PENDING)
                .progress(0)
                .phase("排队中")
                .createTime(LocalDateTime.now())
                .updateTime(LocalDateTime.now())
                .isDelete(0)
                .build();
        taskMapper.insertSelective(task);

        // 异步执行，不阻塞提交方
        Schedulers.boundedElastic().schedule(() -> runTask(task, snapshot, workspace));
        return task;
    }

    private void runTask(KnowledgeTask task, KnowledgeSnapshot snapshot, Path workspace) {
        boolean acquired = false;
        try {
            acquired = slots.tryAcquire(SLOT_WAIT_SECONDS, TimeUnit.SECONDS);
            if (!acquired) {
                finish(task.getId(), STATUS_FAILED, "等待执行槽位超时，请稍后重试");
                return;
            }
            markRunning(task.getId());

            Path snapshotWorkspace = snapshotService.snapshotWorkspace(snapshot, workspace);
            String requestJson = buildRequest(task, snapshot, snapshotWorkspace);
            StringBuilder output = new StringBuilder();
            // 用 CountDownLatch 而不是 blockLast：需要 subscribe 返回的 Disposable 才能取消
            CountDownLatch done = new CountDownLatch(1);

            Disposable disposable = agentRunService.streamUncoordinated(requestJson)
                    .subscribe(
                            event -> collect(task, event, output),
                            error -> {
                                log.warn("知识任务 {} 执行失败: {}", task.getId(), error.getMessage());
                                finish(task.getId(), STATUS_FAILED, abbreviate(error.getMessage(), 400));
                                done.countDown();
                            },
                            () -> {
                                // 取消后也会走到 complete，此时状态已被 cancel 改过，不覆盖
                                KnowledgeTask current = taskMapper.selectOneById(task.getId());
                                if (current != null && STATUS_RUNNING.equals(current.getStatus())) {
                                    persistKnowledge(task, snapshot, output.toString());
                                }
                                done.countDown();
                            });
            runningTasks.put(task.getId(), disposable);
            // 等待必须有超时。没有超时的话，只要事件流不结束，这个线程就永远卡在 await 上：
            // 任务停在 RUNNING 40% 不动、执行槽位一直被占，后面的任务全排队（实测卡了十几分钟）。
            if (!done.await(TASK_TIMEOUT_MINUTES, TimeUnit.MINUTES)) {
                finish(task.getId(), STATUS_FAILED,
                        "任务超时（超过 " + TASK_TIMEOUT_MINUTES + " 分钟没有返回结果）");
                disposable.dispose();
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            finish(task.getId(), STATUS_FAILED, "任务被中断");
        } catch (RuntimeException e) {
            log.warn("知识任务 {} 异常: {}", task.getId(), e.getMessage());
            finish(task.getId(), STATUS_FAILED, abbreviate(e.getMessage(), 400));
        } finally {
            runningTasks.remove(task.getId());
            if (acquired) {
                slots.release();
            }
        }
    }

    private void collect(KnowledgeTask task, AgentEvent event, StringBuilder output) {
        if (event.isTextDelta()) {
            output.append(event.textContent() == null ? "" : event.textContent());
            return;
        }
        if (AgentEvent.TEXT_MESSAGE.equals(event.event())) {
            // 终稿：整轮的完整正文。真实流式路径**只发这一个**，不发 delta
            // （delta 只有无密钥的占位路径才用）。所以必须覆盖而不是追加，
            // 否则会把终稿与已累积的增量拼成重复内容。
            output.setLength(0);
            output.append(event.textContent() == null ? "" : event.textContent());
            return;
        }
        if (AgentEvent.RUN_STARTED.equals(event.event())) {
            updateProgress(task.getId(), 10, "分析源码");
        } else if (AgentEvent.TOOL_STARTED.equals(event.event())) {
            updateProgress(task.getId(), 40, "整理结构与关系");
        } else if (event.isFailure()) {
            finish(task.getId(), STATUS_FAILED, abbreviate(event.errorMessage(), 400));
        }
    }

    /**
     * 把模型输出落成文档与图谱。
     *
     * <p>期望输出里有一个 JSON 块（docs / nodes / edges）。解析不出来时**不丢结果**：
     * 整段文本存成一篇文档 —— 有内容总比任务显示成功但什么都没有强。
     */
    private void persistKnowledge(KnowledgeTask task, KnowledgeSnapshot snapshot, String raw) {
        String text = raw == null ? "" : raw.trim();
        if (text.isEmpty()) {
            finish(task.getId(), STATUS_FAILED, "模型没有产出内容");
            return;
        }

        // 落新知识前先清掉这个应用上一轮的：文档表没有唯一键，不清就会一轮一轮往上叠
        // （实测 4 篇变 8 篇），而节点表有唯一键会去重、数量不变 ——
        // 结果是图谱说 22 个节点、文档列表说 8 篇，两边讲的不是同一件事。
        clearKnowledge(task.getAppId());

        JSONObject parsed = extractJson(text);
        int docCount = 0;
        int nodeCount = 0;
        int edgeCount = 0;

        if (parsed != null) {
            docCount = persistDocs(task, snapshot, parsed);
            nodeCount = persistNodes(task, snapshot, parsed);
            edgeCount = persistEdges(task, snapshot, parsed);
        }

        if (docCount == 0) {
            // 解析失败或没有 docs：把原文存成一篇文档，保证任务产出不为空
            saveDoc(task, snapshot, "项目知识（原始分析）", text);
            docCount = 1;
        }

        updateProgress(task.getId(), 100, "完成");
        finish(task.getId(), STATUS_SUCCEEDED, null);
        log.info("知识任务 {} 完成：文档 {}、节点 {}、关系 {}", task.getId(), docCount, nodeCount, edgeCount);
    }

    /**
     * 清掉该应用已有的文档 / 节点 / 关系。
     *
     * <p>知识是「这个项目当前长什么样」的一份快照，不是历史累积 ——
     * 重建时旧的那份就该整份作废，否则文档越堆越多、图谱却始终是去重后的那一份。
     *
     * @return 被清掉的文档条数
     */
    private int clearKnowledge(Long appId) {
        int docs = docMapper.deleteByQuery(QueryWrapper.create().eq("app_id", appId));
        nodeMapper.deleteByQuery(QueryWrapper.create().eq("app_id", appId));
        edgeMapper.deleteByQuery(QueryWrapper.create().eq("app_id", appId));
        return docs;
    }

    /** 从回复里抠出 JSON 对象：优先代码块，其次第一个大括号块。 */
    private static JSONObject extractJson(String text) {
        int fence = text.indexOf("```json");
        if (fence >= 0) {
            int start = text.indexOf('{', fence);
            int end = text.lastIndexOf('}');
            if (start >= 0 && end > start) {
                JSONObject obj = tryParse(text.substring(start, end + 1));
                if (obj != null) {
                    return obj;
                }
            }
        }
        int start = text.indexOf('{');
        int end = text.lastIndexOf('}');
        if (start >= 0 && end > start) {
            return tryParse(text.substring(start, end + 1));
        }
        return null;
    }

    private static JSONObject tryParse(String candidate) {
        try {
            return JSONUtil.parseObj(candidate);
        } catch (RuntimeException e) {
            return null;
        }
    }

    private int persistDocs(KnowledgeTask task, KnowledgeSnapshot snapshot, JSONObject parsed) {
        JSONArray docs = parsed.getJSONArray("docs");
        if (docs == null) {
            return 0;
        }
        int saved = 0;
        for (Object item : docs) {
            if (!(item instanceof JSONObject doc)) {
                continue;
            }
            String title = doc.getStr("title", "未命名章节");
            String content = doc.getStr("content", "");
            if (content.isBlank()) {
                continue;
            }
            saveDoc(task, snapshot, title, content);
            saved++;
        }
        return saved;
    }

    private void saveDoc(KnowledgeTask task, KnowledgeSnapshot snapshot, String title, String content) {
        docMapper.insertSelective(KnowledgeDoc.builder()
                .appId(task.getAppId())
                .snapshotId(snapshot.getId())
                .snapshotVersion(snapshot.getVersion())
                .title(abbreviate(title, 200))
                .content(content)
                .createTime(LocalDateTime.now())
                .updateTime(LocalDateTime.now())
                .isDelete(0)
                .build());
    }

    private int persistNodes(KnowledgeTask task, KnowledgeSnapshot snapshot, JSONObject parsed) {
        JSONArray nodes = parsed.getJSONArray("nodes");
        if (nodes == null) {
            return 0;
        }
        int saved = 0;
        for (Object item : nodes) {
            if (!(item instanceof JSONObject node)) {
                continue;
            }
            String key = node.getStr("key", "");
            if (key.isBlank()) {
                continue;
            }
            nodeMapper.insertSelective(KnowledgeNode.builder()
                    .appId(task.getAppId())
                    .snapshotId(snapshot.getId())
                    .snapshotVersion(snapshot.getVersion())
                    .nodeKey(abbreviate(key, 190))
                    .name(abbreviate(node.getStr("name", key), 250))
                    .kind(abbreviate(node.getStr("kind", "module"), 40))
                    .summary(abbreviate(node.getStr("summary", ""), 1000))
                    .evidence(normalizeEvidence(node.getStr("evidence")))
                    .sourceRef(abbreviate(node.getStr("sourceRef", ""), 500))
                    .createTime(LocalDateTime.now())
                    .updateTime(LocalDateTime.now())
                    .isDelete(0)
                    .build());
            saved++;
        }
        return saved;
    }

    private int persistEdges(KnowledgeTask task, KnowledgeSnapshot snapshot, JSONObject parsed) {
        JSONArray edges = parsed.getJSONArray("edges");
        if (edges == null) {
            return 0;
        }
        int saved = 0;
        for (Object item : edges) {
            if (!(item instanceof JSONObject edge)) {
                continue;
            }
            String from = edge.getStr("from", "");
            String to = edge.getStr("to", "");
            if (from.isBlank() || to.isBlank()) {
                continue;
            }
            edgeMapper.insertSelective(KnowledgeEdge.builder()
                    .appId(task.getAppId())
                    .snapshotId(snapshot.getId())
                    .snapshotVersion(snapshot.getVersion())
                    .fromKey(abbreviate(from, 190))
                    .toKey(abbreviate(to, 190))
                    .relation(abbreviate(edge.getStr("relation", "related"), 40))
                    .evidence(normalizeEvidence(edge.getStr("evidence")))
                    .sourceRef(abbreviate(edge.getStr("sourceRef", ""), 500))
                    .createTime(LocalDateTime.now())
                    .updateTime(LocalDateTime.now())
                    .isDelete(0)
                    .build());
            saved++;
        }
        return saved;
    }

    /** 证据类型只认两种；给了别的或没给，一律按「推断」处理 —— 不能把推断冒充成源码证据。 */
    private static String normalizeEvidence(String raw) {
        return KnowledgeNode.EVIDENCE_CODE.equalsIgnoreCase(raw)
                ? KnowledgeNode.EVIDENCE_CODE
                : KnowledgeNode.EVIDENCE_INFERENCE;
    }

    /** 取消任务：中断订阅即可，Python 侧进程会被丢弃。 */
    public boolean cancel(Long taskId) {
        KnowledgeTask task = taskMapper.selectOneById(taskId);
        if (task == null) {
            return false;
        }
        if (!STATUS_RUNNING.equals(task.getStatus()) && !STATUS_PENDING.equals(task.getStatus())) {
            return false;
        }
        // 先改状态再断流：doOnComplete 会检查状态，避免被当成正常完成
        finish(taskId, STATUS_CANCELLED, "用户取消");
        Disposable disposable = runningTasks.remove(taskId);
        if (disposable != null) {
            disposable.dispose();
        }
        return true;
    }

    /**
     * 重试：在同一个快照上重新起一个任务。
     *
     * <p>只有失败/取消/中断的任务能重试；成功的不重试（要更新就点「更新知识」）。
     */
    public KnowledgeTask retry(Long taskId, Path workspace) {
        KnowledgeTask task = taskMapper.selectOneById(taskId);
        if (task == null) {
            throw new MyException(ErrorCode.NOT_FOUND_ERROR, "任务不存在");
        }
        if (STATUS_SUCCEEDED.equals(task.getStatus()) || STATUS_RUNNING.equals(task.getStatus())
                || STATUS_PENDING.equals(task.getStatus())) {
            throw new MyException(ErrorCode.OPERATION_ERROR, "该任务当前状态不支持重试");
        }
        // 复用原快照，保证重试的输入与首次一致
        KnowledgeSnapshot snapshot = snapshotService.byId(task.getSnapshotId());
        if (snapshot == null) {
            return submit(task.getAppId(), task.getUserId(), workspace);
        }

        KnowledgeTask retried = KnowledgeTask.builder()
                .appId(task.getAppId())
                .userId(task.getUserId())
                .snapshotId(snapshot.getId())
                .snapshotVersion(snapshot.getVersion())
                .status(STATUS_PENDING)
                .progress(0)
                .phase("排队中")
                .createTime(LocalDateTime.now())
                .updateTime(LocalDateTime.now())
                .isDelete(0)
                .build();
        taskMapper.insertSelective(retried);
        Schedulers.boundedElastic().schedule(() -> runTask(retried, snapshot, workspace));
        return retried;
    }

    /**
     * 启动对账：把停在「生成中」的任务标成中断。
     *
     * <p>进程没了但记录还写着 RUNNING，用户会一直等一个永远不会完成的任务。
     * 标成 INTERRUPTED 而不是 FAILED：这两者对用户的含义不同 —— 中断是可重试的，
     * 前端据此给出重试入口。
     */
    @EventListener(ApplicationReadyEvent.class)
    public int reconcileOnStartup() {
        List<KnowledgeTask> stale = taskMapper.selectListByQuery(
                QueryWrapper.create().in("status", List.of(STATUS_PENDING, STATUS_RUNNING)));
        int fixed = 0;
        for (KnowledgeTask task : stale) {
            finish(task.getId(), STATUS_INTERRUPTED, "平台重启，任务已中断，可重试");
            fixed++;
        }
        if (fixed > 0) {
            log.info("知识任务对账：{} 条中断的任务已标记", fixed);
        }
        return fixed;
    }

    /** 查询任务状态。 */
    public KnowledgeTask byId(Long taskId) {
        return taskMapper.selectOneById(taskId);
    }

    /** 某应用最近的任务。 */
    public KnowledgeTask latest(Long appId) {
        List<KnowledgeTask> list = taskMapper.selectListByQuery(
                QueryWrapper.create().eq("app_id", appId).orderBy("id", false).limit(1));
        return list.isEmpty() ? null : list.get(0);
    }

    /**
     * 最近一次**成功**的任务。
     *
     * <p>取「知识对应哪一版」必须用这个而不是 {@link #latest} ——
     * 一次失败的任务不该把已经生成好的知识标成过期。
     */
    public List<KnowledgeTask> latestSuccessful(Long appId) {
        return taskMapper.selectListByQuery(
                QueryWrapper.create()
                        .eq("app_id", appId)
                        .eq("status", STATUS_SUCCEEDED)
                        .orderBy("id", false)
                        .limit(1));
    }

    public KnowledgeTask findActive(Long appId, String snapshotVersion) {
        List<KnowledgeTask> list = taskMapper.selectListByQuery(
                QueryWrapper.create()
                        .eq("app_id", appId)
                        .eq("snapshot_version", snapshotVersion)
                        .in("status", List.of(STATUS_PENDING, STATUS_RUNNING))
                        .limit(1));
        return list.isEmpty() ? null : list.get(0);
    }

    public void removeAppTasks(Long appId) {
        for (KnowledgeTask task : taskMapper.selectListByQuery(
                QueryWrapper.create().eq("app_id", appId))) {
            taskMapper.deleteById(task.getId());
        }
    }

    /**
     * 应用被删除时清干净知识痕迹：先取消在跑的任务，再删数据。
     *
     * <p>只删记录不取消是不够的 —— 后台任务会继续跑完然后写回一堆属于已删除应用的数据。
     * 取消要放在删除之前，否则任务找不到自己的记录会报错。
     */
    public void purgeApp(Long appId) {
        try {
            for (KnowledgeTask task : taskMapper.selectListByQuery(
                    QueryWrapper.create().eq("app_id", appId))) {
                cancel(task.getId());
            }
        } catch (Exception e) {
            log.warn("取消应用 {} 的知识任务失败: {}", appId, e.getMessage());
        }
        removeAppTasks(appId);
        deleteByApp(docMapper, appId);
        deleteByApp(nodeMapper, appId);
        deleteByApp(edgeMapper, appId);
        deleteByApp(qaMapper, appId);
        deleteByApp(snapshotMapper, appId);
    }

    private static <T> void deleteByApp(com.mybatisflex.core.BaseMapper<T> mapper, Long appId) {
        try {
            mapper.deleteByQuery(QueryWrapper.create().eq("app_id", appId));
        } catch (Exception e) {
            log.warn("清理应用 {} 的知识数据失败: {}", appId, e.getMessage());
        }
    }

    private void markRunning(Long taskId) {
        updateTask(taskId, STATUS_RUNNING, 5, "启动分析", LocalDateTime.now(), null);
    }

    private void updateProgress(Long taskId, int progress, String phase) {
        KnowledgeTask update = new KnowledgeTask();
        update.setId(taskId);
        update.setProgress(progress);
        update.setPhase(phase);
        update.setUpdateTime(LocalDateTime.now());
        taskMapper.update(update);
    }

    private void finish(Long taskId, String status, String errorMessage) {
        updateTask(taskId, status, status.equals(STATUS_SUCCEEDED) ? 100 : null, null, null, errorMessage);
    }

    private void updateTask(Long taskId, String status, Integer progress, String phase,
                            LocalDateTime startedAt, String errorMessage) {
        KnowledgeTask update = new KnowledgeTask();
        update.setId(taskId);
        update.setStatus(status);
        update.setProgress(progress);
        update.setPhase(phase);
        update.setStartedAt(startedAt);
        update.setErrorMessage(abbreviate(errorMessage, 500));
        update.setUpdateTime(LocalDateTime.now());
        if (!STATUS_RUNNING.equals(status) && !STATUS_PENDING.equals(status)) {
            update.setFinishedAt(LocalDateTime.now());
        }
        taskMapper.update(update);
    }

    private String buildRequest(KnowledgeTask task, KnowledgeSnapshot snapshot, Path workspace) {
        String runId = UUID.randomUUID().toString();
        String prompt = """
                请分析当前工作区的源码，产出一份项目知识说明与结构图谱。

                要求：
                1. 先浏览目录与关键文件，再下结论；不要臆测没读过的内容。
                2. 区分事实与推断：能从源码里直接读出来的标 CODE_EVIDENCE 并给出 sourceRef（如 src/main.js:42）；
                   属于你的推断标 AI_INFERENCE。
                3. 最后输出一个 JSON 代码块，格式如下（不要输出别的 JSON）：
                ```json
                {
                  "docs": [{"title": "章节标题", "content": "markdown 正文"}],
                  "nodes": [{"key": "唯一标识(文件路径或模块名)", "name": "展示名", "kind": "file|module|function|class|endpoint", "summary": "一句话说明", "evidence": "CODE_EVIDENCE|AI_INFERENCE", "sourceRef": "src/main.js:12"}],
                  "edges": [{"from": "源节点key", "to": "目标节点key", "relation": "imports|calls|contains|exposes", "evidence": "CODE_EVIDENCE|AI_INFERENCE", "sourceRef": "src/main.js:3"}]
                }
                ```
                4. 图谱以帮助理解项目为目标，不设节点或关系数量下限，禁止为充实画面凑数。
                   优先保留关键文件、职责独立的模块、对外接口与核心数据实体；同一职责不要重复拆成多个节点。
                   仅在函数承担核心流程、跨模块边界或关键业务规则时单列；普通辅助函数、变量、常量、
                   UI 元素和第三方库的常规调用归入所属节点的说明，不逐个枚举。
                   每个节点都要能说明独立职责，每条边都要有实际的依赖、调用、包含或暴露关系；
                   不得因为同目录、名称相似或为了连通图形而添加关系。不确定的内容写入文档，不添加装饰节点。
                   小项目可以只有几个节点；没有足够证据时 nodes 或 edges 可以为空。
                5. 只读分析：不要修改任何文件，不要执行构建或启动命令。
                """;

        var app = appMapper.selectOneById(task.getAppId());
        AgentRunRequest request = AgentRunRequest.builder()
                .runId(runId)
                // 与生成会话分开：知识任务不写 chat_history，用户看不到
                .threadId("app:" + task.getAppId() + ":knowledge")
                .appId(String.valueOf(task.getAppId()))
                .userId(String.valueOf(task.getUserId()))
                .roundId(null)
                .traceId(UUID.randomUUID().toString())
                .modelRoute(app != null && Boolean.TRUE.equals(app.getForceModelDirect())
                        ? "direct" : "configured")
                .allowProxyFallback(false)
                .workspaceRoot(workspace.getParent() == null
                        ? workspace.toString() : workspace.getParent().toString())
                .workspaceName(workspace.getFileName() == null
                        ? workspace.toString() : workspace.getFileName().toString())
                .codeGenType("knowledge")
                .mode("edit")
                .message(prompt)
                .initPrompt("")
                // 只读分析：Python 侧按 READ_ONLY 处理
                .taskKind("analysis")
                .deadlineMs(agentProperties.getRunDeadline().toMillis())
                .maxModelCalls(40)
                .build();

        KnowledgeTask update = new KnowledgeTask();
        update.setId(task.getId());
        update.setRunId(runId);
        update.setUpdateTime(LocalDateTime.now());
        taskMapper.update(update);

        try {
            return request.toFrameJson(objectMapper);
        } catch (RuntimeException e) {
            throw new MyException(ErrorCode.SYSTEM_ERROR, "构建知识任务请求失败");
        }
    }

    private static String abbreviate(String text, int max) {
        if (text == null) {
            return null;
        }
        return text.length() <= max ? text : text.substring(0, max);
    }

    @PreDestroy
    public void shutdown() {
        runningTasks.values().forEach(Disposable::dispose);
        runningTasks.clear();
    }
}
