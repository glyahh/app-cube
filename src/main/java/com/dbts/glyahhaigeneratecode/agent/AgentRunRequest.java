package com.dbts.glyahhaigeneratecode.agent;

import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.Builder;
import lombok.Singular;
import lombok.Value;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 发给 Python 的一次 run 请求。
 *
 * <p>字段名与 docs/AGENT_CONTRACT.md 里的 schema 一一对应，构造出来的 JSON 直接
 * 写成一行喂给子进程的 stdin。
 *
 * <p>**所有 ID 都是字符串。** 雪花 ID 约 19 位，超过 {@code 2^53}，
 * 经过 JS 的 Number 或任何 float64 都会丢精度 —— 这条在跨语言边界上尤其重要。
 *
 * <p>工作区路径由这里下发，Python 侧不做任何自己的路径拼接。
 */
@Value
@Builder(toBuilder = true)
public class AgentRunRequest {

    String runId;
    String threadId;
    String appId;
    String userId;
    String roundId;
    String traceId;

    /** configured 使用 Worker 全局配置；direct 强制本轮直连。 */
    String modelRoute;
    /** 只允许主生成链路按账号偏好触发自动降级。 */
    Boolean allowProxyFallback;

    /** 产物根目录，绝对路径。 */
    String workspaceRoot;
    /** 产物目录名，如 {@code vue_project_431379639885025280}。 */
    String workspaceName;
    /** 生成类型，如 {@code html} / {@code vue_project}。 */
    String codeGenType;
    /** {@code create} 表示首轮建工程，{@code edit} 表示改已有产物。 */
    String mode;
    /** 导入来源身份；非空时禁止执行侧挂载发布工作区。 */
    String importTaskId;
    /** 平台冻结的编辑身份；不包含秘密映射或原配置。 */
    String importEditId;
    String importSourceHash;
    String importConfigRevision;
    String importContextEpoch;
    String importVolumePath;
    String importViewPath;
    String importViewHash;
    String importViewStateHash;
    String importContainerId;
    String importWorkerLeaseId;
    Long importDiskBytes;

    /** 导入权限由Java冻结，执行侧不能自行选择或替换。 */
    String importPermissionMode;
    String importPolicyRevision;
    String importSessionEpoch;
    String importServerGeneration;
    String importModelProfileId;
    String importThinkingEffort;

    String message;
    String initPrompt;

    /**
     * 任务类型，对应 Python 侧的 TaskKind：{@code coding}（默认，可写）/
     * {@code analysis} / {@code qa}（只读）。
     *
     * <p>知识生成走 analysis、知识问答走 qa —— 两者都不该改代码。
     * 只读的强制在 Python 执行侧做，这里只负责把意图传过去。
     */
    String taskKind;

    /** 冷启动时灌入的历史尾部。有 checkpoint 时 Python 会忽略它。 */
    @Singular("historyItem")
    List<Map<String, String>> historyTail;

    Long deadlineMs;
    Integer maxModelCalls;

    /** 部署 key。Python 不感知部署，仅作为透传元信息。 */
    String deployKey;

    /** 序列化成一行 JSON。 */
    public String toFrameJson(ObjectMapper mapper) {
        Map<String, Object> frame = new LinkedHashMap<>();
        frame.put("v", 1);
        frame.put("runId", runId);
        frame.put("threadId", threadId);
        frame.put("appId", appId);
        frame.put("userId", userId);
        frame.put("roundId", roundId);
        frame.put("traceId", traceId);
        frame.put("modelRoute", "direct".equals(modelRoute) ? "direct" : "configured");
        frame.put("allowProxyFallback", Boolean.TRUE.equals(allowProxyFallback));
        if (importTaskId != null) {
            frame.put("importPermissionMode", importPermissionMode);
            frame.put("importPolicyRevision", importPolicyRevision);
            frame.put("importSessionEpoch", importSessionEpoch);
            frame.put("importServerGeneration", importServerGeneration);
            frame.put("importModelProfileId", importModelProfileId);
            frame.put("importThinkingEffort", importThinkingEffort);
        }

        Map<String, Object> workspace = new LinkedHashMap<>();
        workspace.put("root", workspaceRoot);
        workspace.put("name", workspaceName);
        workspace.put("codeGenType", codeGenType == null ? "html" : codeGenType);
        workspace.put("mode", mode == null ? "create" : mode);
        if (importTaskId != null) workspace.put("importTaskId", importTaskId);
        if (importEditId != null) {
            workspace.put("importEditId", importEditId);
            workspace.put("sourceHash", importSourceHash);
            workspace.put("configRevision", importConfigRevision);
            workspace.put("contextEpoch", importContextEpoch);
            workspace.put("importVolumePath", importVolumePath);
            workspace.put("importViewPath", importViewPath);
            workspace.put("viewHash", importViewHash);
            workspace.put("viewStateHash", importViewStateHash);
            if(importContainerId!=null)workspace.put("containerId",importContainerId);
            if(importWorkerLeaseId!=null)workspace.put("workerLeaseId",importWorkerLeaseId);
        }
        frame.put("workspace", workspace);

        Map<String, Object> input = new LinkedHashMap<>();
        input.put("message", message);
        input.put("initPrompt", initPrompt == null ? "" : initPrompt);
        input.put("historyTail", historyTail == null ? List.of() : historyTail);
        input.put("historyTailTruncated", false);
        // Python 侧 _classify 读这个字段；不带时按 coding（可写）处理
        if (taskKind != null && !taskKind.isBlank()) {
            input.put("taskKind", taskKind);
        }
        frame.put("input", input);

        Map<String, Object> limits = new LinkedHashMap<>();
        limits.put("deadlineMs", deadlineMs == null ? 1_800_000L : deadlineMs);
        limits.put("maxModelCalls", maxModelCalls == null ? 60 : maxModelCalls);
        if(importDiskBytes!=null)limits.put("importDiskBytes",importDiskBytes);
        frame.put("limits", limits);

        Map<String, Object> artifacts = new LinkedHashMap<>();
        artifacts.put("deployKey", deployKey == null ? "" : deployKey);
        frame.put("artifacts", artifacts);

        // Phase 0/1 恒为 false：Java 已经通过事件流实时拿到全部信息，
        // 再加一条回调通道是多余的入站鉴权面加对账问题。
        frame.put("callback", Map.of("enabled", false));

        try {
            return mapper.writeValueAsString(frame);
        } catch (Exception e) {
            throw new IllegalStateException("序列化 Agent 请求失败", e);
        }
    }
}
