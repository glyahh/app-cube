package com.dbts.glyahhaigeneratecode.agent;

import cn.hutool.core.util.IdUtil;
import com.dbts.glyahhaigeneratecode.exception.ErrorCode;
import com.dbts.glyahhaigeneratecode.exception.ThrowUtils;
import com.dbts.glyahhaigeneratecode.model.Entity.App;
import com.dbts.glyahhaigeneratecode.model.enums.CodeGenTypeEnum;
import com.dbts.glyahhaigeneratecode.service.AppService;
import com.dbts.glyahhaigeneratecode.service.ChatHistoryService;
import com.dbts.glyahhaigeneratecode.service.UserService;
import com.dbts.glyahhaigeneratecode.service.support.WorkspacePathResolver;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.nio.file.Path;
import java.util.Objects;
import java.util.UUID;

/**
 * 组装一次 run 的请求。
 *
 * <p>两条传输路径（WebSocket 与 SSE）共用这里。把**归属校验**也放在这里是有意的：
 * 校验逻辑一旦分散，就很难保证新增的路径都做了检查 —— 参考项目的
 * {@code publish_gitee_pr_comment} 就是绕过只读检查直接调了发布接口。
 */
@Component
@RequiredArgsConstructor
@Slf4j
public class AgentRunRequestFactory {

    private final AppService appService;
    private final UserService userService;
    private final WorkspacePathResolver pathResolver;
    private final AgentProperties agentProperties;
    private final com.dbts.glyahhaigeneratecode.projectimport.ImportWorkspaceGate importGate;
    private final com.dbts.glyahhaigeneratecode.projectimport.ImportAgentSettingsRepository importSettings;
    private final com.dbts.glyahhaigeneratecode.agentauth.AgentModelPreferenceService modelPreferences;
    private final com.dbts.glyahhaigeneratecode.agentauth.AgentEditSettingsService editSettings;
    private final com.dbts.glyahhaigeneratecode.agentauth.AgentEditSessionService sessionService;
    private final ChatHistoryService chatHistoryService;

    /**
     * 加载应用、校验归属、组装请求。
     *
     * @param appId   应用 ID
     * @param userId  当前登录用户 ID
     * @param message 用户消息
     * @param roundId 本轮在 {@code chat_history} 里的主键，由 {@link AgentRunPersistence} 落 USER 行时产生
     */
    public AgentRunRequest build(Long appId, Long userId, String message, Long roundId) {
        ThrowUtils.throwIf(appId == null || appId <= 0, ErrorCode.PARAMS_ERROR, "应用ID无效");

        App app = appService.getById(appId);
        ThrowUtils.throwIf(app == null, ErrorCode.NOT_FOUND_ERROR, "应用不存在");
        ThrowUtils.throwIf(!Objects.equals(app.getUserId(), userId),
                ErrorCode.NO_AUTH_ERROR, "只能操作自己的应用");
        var publication = importGate.requireAgentEditAllowed(appId);

        CodeGenTypeEnum type = CodeGenTypeEnum.getEnumByValue(app.getCodeGenType());
        ThrowUtils.throwIf(type == null, ErrorCode.PARAMS_ERROR, "应用的生成类型不合法");

        Path workspace = pathResolver.outputDir(appId, type);
        var user = userService.getById(userId);
        var policy = publication == null ? null : importSettings.current(userId, appId);
        // 普通应用读取应用级模型偏好；导入应用继续用冻结策略里的模型字段。
        var preference = publication == null ? modelPreferences.freeze(userId, appId) : null;
        // 普通应用的编辑会话不在这里开：build() 在 App 锁外执行，并发请求会同时开会话，
        // 第二个必然撞唯一索引。改到 refreshModelRoute（锁内）开启，见下方注释。
        String runId = IdUtil.getSnowflakeNextIdStr();

        return AgentRunRequest.builder()
                .runId(runId)
                .threadId(threadIdOf(appId))
                .appId(String.valueOf(appId))
                .userId(String.valueOf(userId))
                // null 表示 USER 消息落库失败；不要编码成空字符串，否则 tap() 会在
                // 构建流时执行 Long.valueOf("")，让本可继续的 Agent 请求直接失败。
                .roundId(roundId == null ? null : String.valueOf(roundId))
                .traceId(UUID.randomUUID().toString())
                .modelRoute(Boolean.TRUE.equals(app.getForceModelDirect()) ? "direct" : "configured")
                .allowProxyFallback(user != null && Boolean.TRUE.equals(user.getAutoModelProxyFallback()))
                // 工作区路径由 Java 下发，Python 不做任何自己的拼接
                .workspaceRoot(workspace.getParent().toString())
                .workspaceName(workspace.getFileName().toString())
                .codeGenType(type.getDirPrefix())
                // 按对话轮数判断首轮：本轮 USER 行已由调用方先落库，
                // 所以纯问答的第一轮即使没产出文件，第二轮也必须是 edit。
                .mode(chatHistoryService.countRoundsByAppId(appId, user) > 1 ? "edit" : "create")
                .importTaskId(publication == null ? null : publication.taskId().toString())
                .importPolicyRevision(policy == null ? null : Long.toString(policy.revision()))
                .importSessionEpoch(policy == null ? null : policy.sessionEpoch().toString())
                .modelProfileId(preference == null ? null : preference.profileId())
                .thinkingEffort(preference == null ? null : preference.effort())
                .message(message)
                .initPrompt(app.getInitPrompt())
                .deadlineMs(agentProperties.getRunDeadline().toMillis())
                .deployKey(app.getDeployKey())
                .build();
    }

    /** 拿到应用锁后重读路由状态，避免排队请求携带旧值。 */
    public AgentRunRequest refreshModelRoute(AgentRunRequest request) {
        Long appId = Long.valueOf(request.getAppId());
        Long userId = Long.valueOf(request.getUserId());
        App app = appService.getById(appId);
        ThrowUtils.throwIf(app == null, ErrorCode.NOT_FOUND_ERROR, "应用不存在");
        ThrowUtils.throwIf(!Objects.equals(app.getUserId(), userId),
                ErrorCode.NO_AUTH_ERROR, "只能操作自己的应用");
        var user = userService.getById(userId);
        var publication = importGate.requireAgentEditAllowed(appId);
        var preference = publication == null ? modelPreferences.freeze(userId, appId) : null;
        // 1. 编辑会话只在这里开：本方法在 App 锁内执行，并发请求会排队，
        //    不会像在 build() 里那样同时开会话撞上唯一索引。
        var session = publication == null
                ? sessionService.open(userId, appId, Long.parseLong(request.getRunId()),
                    editSettings.sandboxMode(userId, appId), editSettings.mode(userId, appId))
                : null;
        if (session != null) {
            log.info("开启编辑会话 app={} run={} session={} mode={} sandbox={}",
                    appId, request.getRunId(), session.id(), session.approvalMode(), session.sandboxMode());
        }
        // 2. 路由与应用级偏好一并刷新，保证排队的请求不携带旧状态。
        return request.toBuilder()
                .importTaskId(publication == null ? null : publication.taskId().toString())
                .modelRoute(Boolean.TRUE.equals(app.getForceModelDirect()) ? "direct" : "configured")
                .allowProxyFallback(user != null && Boolean.TRUE.equals(user.getAutoModelProxyFallback()))
                .modelProfileId(preference == null ? null : preference.profileId())
                .thinkingEffort(preference == null ? null : preference.effort())
                .permissionSessionId(session == null ? null : session.id().toString())
                .permissionSandboxMode(session == null ? null : session.sandboxMode())
                .permissionApprovalMode(session == null ? null : session.approvalMode())
                .build();
    }

    /**
     * 会话标识。纯函数派生，不需要新表也不需要对账。
     *
     * <p>现有系统就是「一 app 一对话」：{@code chat_history} 按 appId 分组、
     * 记忆状态主键是 appId、生成锁也按 appId。将来要做「一 app 多对话」，
     * 扩成 {@code app:{appId}:conv:{id}} 即可。
     */
    public static String threadIdOf(Long appId) {
        return "app:" + appId;
    }
}
