package com.dbts.glyahhaigeneratecode.knowledge;

import cn.hutool.json.JSONUtil;
import com.dbts.glyahhaigeneratecode.agent.AgentEvent;
import com.dbts.glyahhaigeneratecode.agent.AgentProperties;
import com.dbts.glyahhaigeneratecode.agent.AgentRunRequest;
import com.dbts.glyahhaigeneratecode.agent.AgentRunService;
import com.dbts.glyahhaigeneratecode.exception.ErrorCode;
import com.dbts.glyahhaigeneratecode.exception.MyException;
import com.dbts.glyahhaigeneratecode.mapper.KnowledgeDocMapper;
import com.dbts.glyahhaigeneratecode.mapper.AppMapper;
import com.dbts.glyahhaigeneratecode.mapper.KnowledgeQaMessageMapper;
import com.dbts.glyahhaigeneratecode.model.Entity.KnowledgeDoc;
import com.dbts.glyahhaigeneratecode.model.Entity.KnowledgeQaMessage;
import com.dbts.glyahhaigeneratecode.model.Entity.KnowledgeSnapshot;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.mybatisflex.core.query.QueryWrapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

/**
 * 知识问答：只读地问「这个项目是怎么实现的」。
 *
 * <p>与生成对话是两件事，所以物理上分开：
 * <ul>
 *   <li><b>能力固定只读</b>：服务端只给 read/检索 能力，不提供写文件、执行命令、启动程序。
 *       需要改代码时，由用户回到生成对话发起 —— 那条链才有写权限。</li>
 *   <li><b>不写 chat_history</b>：用独立的历史表，问答不会污染生成上下文。</li>
 *   <li><b>回答绑定读取版本</b>：每条回答记下它依据的快照版本；版本过期时标 stale，
 *       并用「资料可能已过期」明确提示，而不是拿旧资料当最新答案。</li>
 * </ul>
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class KnowledgeQaService {

    private final KnowledgeSnapshotService snapshotService;
    private final KnowledgeTaskService taskService;
    private final KnowledgeDocMapper docMapper;
    private final KnowledgeQaMessageMapper qaMapper;
    private final AgentRunService agentRunService;
    private final AgentProperties agentProperties;
    private final ObjectMapper objectMapper;
    private final AppMapper appMapper;

    /** 送进上下文的源码片段上限，避免把整个项目塞给模型。 */
    private static final int MAX_EVIDENCE_FILES = 6;

    private static final int MAX_EVIDENCE_CHARS = 6000;

    private static final long ANSWER_TIMEOUT_SECONDS = 240;

    /**
     * 提一个问题。
     *
     * @return 助手回答（已落库）；资料过期时 {@code stale} 为真
     */
    public KnowledgeQaMessage ask(Long appId, Long userId, String question, Path workspace) {
        if (question == null || question.isBlank()) {
            throw new MyException(ErrorCode.PARAMS_ERROR, "问题不能为空");
        }

        KnowledgeSnapshot current = snapshotService.snapshot(appId, workspace);
        if (current == null) {
            throw new MyException(ErrorCode.OPERATION_ERROR, "工作区还没有可分析的源码");
        }
        Path evidenceWorkspace = snapshotService.snapshotWorkspace(current, workspace);
        String currentVersion = current == null ? null : current.getVersion();

        // 落用户问题
        qaMapper.insertSelective(KnowledgeQaMessage.builder()
                .appId(appId)
                .userId(userId)
                .role("user")
                .content(question)
                .snapshotVersion(currentVersion)
                .stale(false)
                .createTime(LocalDateTime.now())
                .isDelete(0)
                .build());

        // 知识文档只取与当前版本一致的：拿旧版本的知识回答新代码的问题是错的
        List<KnowledgeDoc> docs = loadDocs(appId, currentVersion);
        String evidence = collectEvidence(evidenceWorkspace, question);
        String prompt = buildPrompt(question, docs, evidence, currentVersion);

        String answer = runReadOnlyRun(appId, userId, prompt, evidenceWorkspace);
        if (answer == null || answer.isBlank()) {
            throw new MyException(ErrorCode.OPERATION_ERROR, "没有得到回答，请稍后重试");
        }

        // 回答期间代码又改了：这条回答依据的是旧版本，要如实标出来
        KnowledgeSnapshot after = snapshotService.snapshot(appId, workspace);
        String afterVersion = after == null ? null : after.getVersion();
        boolean stale = currentVersion != null && afterVersion != null
                && !currentVersion.equals(afterVersion);

        KnowledgeQaMessage saved = KnowledgeQaMessage.builder()
                .appId(appId)
                .userId(userId)
                .role("assistant")
                .content(answer)
                .snapshotVersion(currentVersion)
                .stale(stale)
                .citationsJson(JSONUtil.toJsonStr(collectCitations(docs, evidenceWorkspace, question)))
                .createTime(LocalDateTime.now())
                .isDelete(0)
                .build();
        qaMapper.insertSelective(saved);
        return saved;
    }

    /** 知识问答历史，新的在前。 */
    public List<KnowledgeQaMessage> history(Long appId, int limit) {
        List<KnowledgeQaMessage> list = qaMapper.selectListByQuery(
                QueryWrapper.create()
                        .eq("app_id", appId)
                        .orderBy("id", false)
                        .limit(Math.max(1, Math.min(limit, 200))));
        List<KnowledgeQaMessage> ordered = new ArrayList<>(list);
        java.util.Collections.reverse(ordered);
        return ordered;
    }

    public void clearHistory(Long appId) {
        for (KnowledgeQaMessage message : qaMapper.selectListByQuery(
                QueryWrapper.create().eq("app_id", appId))) {
            qaMapper.deleteById(message.getId());
        }
    }

    /**
     * 跑一轮只读问答。
     *
     * <p>taskKind 传 {@code qa}：Python 侧按 READ_ONLY 处理，不给写工具。
     * 这里的保证是「服务端固定只读」，不由请求文本或模型决定。
     */
    private String runReadOnlyRun(Long appId, Long userId, String prompt, Path workspace) {
        var app = appMapper.selectOneById(appId);
        AgentRunRequest request = AgentRunRequest.builder()
                .runId(UUID.randomUUID().toString())
                // 与生成会话、知识生成任务都分开
                .threadId("app:" + appId + ":qa")
                .appId(String.valueOf(appId))
                .userId(String.valueOf(userId))
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
                .taskKind("qa")
                .deadlineMs(agentProperties.getRunDeadline().toMillis())
                .maxModelCalls(20)
                .build();

        StringBuilder output = new StringBuilder();
        CountDownLatch done = new CountDownLatch(1);
        try {
            agentRunService.streamUncoordinated(request.toFrameJson(objectMapper))
                    .subscribe(
                            event -> {
                                if (event.isTextDelta() && event.textContent() != null) {
                                    output.append(event.textContent());
                                } else if (AgentEvent.TEXT_MESSAGE.equals(event.event())) {
                                    // 终稿全文。真实流式路径只发这个 —— 以它为准覆盖累积内容
                                    output.setLength(0);
                                    output.append(event.textContent() == null ? "" : event.textContent());
                                }
                            },
                            error -> {
                                log.warn("知识问答执行失败 appId={}: {}", appId, error.getMessage());
                                done.countDown();
                            },
                            done::countDown);
            if (!done.await(ANSWER_TIMEOUT_SECONDS, TimeUnit.SECONDS)) {
                log.warn("知识问答超时 appId={}", appId);
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new MyException(ErrorCode.OPERATION_ERROR, "问答被中断");
        } catch (RuntimeException e) {
            throw new MyException(ErrorCode.OPERATION_ERROR, "问答执行失败：" + e.getMessage());
        }
        return output.toString();
    }

    private List<KnowledgeDoc> loadDocs(Long appId, String version) {
        if (version == null) {
            return List.of();
        }
        return docMapper.selectListByQuery(QueryWrapper.create()
                .eq("app_id", appId)
                .eq("snapshot_version", version)
                .limit(20));
    }

    /**
     * 按关键词挑几段最相关的源码作为证据。
     *
     * <p>刻意用最简单的做法：这里只是给模型一点抓手，真正的定位靠它自己读文件。
     * 引入向量检索是另一个量级的事，等有实际效果问题再说。
     */
    private String collectEvidence(Path workspace, String question) {
        if (workspace == null || !Files.isDirectory(workspace)) {
            return "";
        }
        List<String> keywords = extractKeywords(question);
        StringBuilder sb = new StringBuilder();
        int taken = 0;

        try (var stream = Files.walk(workspace, 4)) {
            for (Path file : stream.filter(Files::isRegularFile).toList()) {
                if (taken >= MAX_EVIDENCE_FILES || sb.length() >= MAX_EVIDENCE_CHARS) {
                    break;
                }
                String relative = workspace.relativize(file).toString().replace('\\', '/');
                if (!isLikelyRelevant(relative, keywords)) {
                    continue;
                }
                String content = snapshotService.readFileForEvidence(workspace, relative);
                if (content == null || content.isBlank()) {
                    continue;
                }
                sb.append("\n--- ").append(relative).append(" ---\n");
                sb.append(content.length() > 2000 ? content.substring(0, 2000) + "\n…（已截断）" : content);
                sb.append('\n');
                taken++;
            }
        } catch (IOException e) {
            log.debug("收集问答证据失败: {}", e.getMessage());
        }
        return sb.toString();
    }

    private static List<String> extractKeywords(String question) {
        List<String> keywords = new ArrayList<>();
        for (String token : question.toLowerCase(Locale.ROOT).split("[^a-z0-9_\\u4e00-\\u9fa5]+")) {
            if (token.length() >= 2) {
                keywords.add(token);
            }
        }
        return keywords;
    }

    /** 路径里命中任一关键词就认为可能相关；没有关键词时取前几个文件兜底。 */
    private static boolean isLikelyRelevant(String relativePath, List<String> keywords) {
        if (keywords.isEmpty()) {
            return true;
        }
        String lower = relativePath.toLowerCase(Locale.ROOT);
        for (String keyword : keywords) {
            if (lower.contains(keyword)) {
                return true;
            }
        }
        return false;
    }

    private List<Map<String, String>> collectCitations(List<KnowledgeDoc> docs, Path workspace, String question) {
        List<Map<String, String>> citations = new ArrayList<>();
        for (KnowledgeDoc doc : docs) {
            citations.add(Map.of("type", "doc", "title", doc.getTitle() == null ? "" : doc.getTitle()));
        }
        List<String> keywords = extractKeywords(question);
        if (workspace != null && Files.isDirectory(workspace)) {
            try (var stream = Files.walk(workspace, 4)) {
                stream.filter(Files::isRegularFile)
                        .map(file -> workspace.relativize(file).toString().replace('\\', '/'))
                        .filter(relative -> isLikelyRelevant(relative, keywords))
                        .limit(MAX_EVIDENCE_FILES)
                        .forEach(relative -> citations.add(Map.of("type", "source", "path", relative)));
            } catch (IOException ignored) {
                // 引用收集失败不影响回答本身
            }
        }
        return citations;
    }

    private String buildPrompt(String question, List<KnowledgeDoc> docs, String evidence, String version) {
        StringBuilder sb = new StringBuilder();
        sb.append("你是这个项目的代码讲解助手，只做只读分析。\n\n");
        sb.append("当前代码版本：").append(version == null ? "（未建立快照）" : version).append("\n\n");

        if (!docs.isEmpty()) {
            sb.append("已有的项目知识文档（可能不完整）：\n");
            for (KnowledgeDoc doc : docs) {
                sb.append("\n### ").append(doc.getTitle()).append('\n');
                String content = doc.getContent() == null ? "" : doc.getContent();
                sb.append(content.length() > 1500 ? content.substring(0, 1500) + "\n…（已截断）" : content);
                sb.append('\n');
            }
            sb.append('\n');
        }

        if (!evidence.isBlank()) {
            sb.append("相关源码片段：\n").append(evidence).append('\n');
        }

        sb.append("用户的问题：").append(question).append("\n\n");
        sb.append("""
                回答要求：
                1. 只依据你实际读到的源码与上面的资料回答；没读到就说明「这一点我没在代码里找到」，
                   不要凭经验猜。
                2. 给出结论时附上来源（文件路径，必要时带行号或函数名）。
                3. 如果问题需要修改代码，明确说明「这需要改代码，请在生成对话里提出」。
                4. 不要修改任何文件，不要执行构建或启动命令。
                """);
        return sb.toString();
    }
}
