package com.dbts.glyahhaigeneratecode.agent;

import cn.hutool.core.util.IdUtil;
import com.dbts.glyahhaigeneratecode.exception.ErrorCode;
import com.dbts.glyahhaigeneratecode.exception.ThrowUtils;
import com.dbts.glyahhaigeneratecode.model.Entity.App;
import com.dbts.glyahhaigeneratecode.model.enums.CodeGenTypeEnum;
import com.dbts.glyahhaigeneratecode.service.AppService;
import com.dbts.glyahhaigeneratecode.service.support.WorkspacePathResolver;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

import java.nio.file.Files;
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
public class AgentRunRequestFactory {

    private final AppService appService;
    private final WorkspacePathResolver pathResolver;
    private final AgentProperties agentProperties;

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

        CodeGenTypeEnum type = CodeGenTypeEnum.getEnumByValue(app.getCodeGenType());
        ThrowUtils.throwIf(type == null, ErrorCode.PARAMS_ERROR, "应用的生成类型不合法");

        Path workspace = pathResolver.outputDir(appId, type);

        return AgentRunRequest.builder()
                .runId(IdUtil.getSnowflakeNextIdStr())
                .threadId(threadIdOf(appId))
                .appId(String.valueOf(appId))
                .userId(String.valueOf(userId))
                .roundId(roundId == null ? "" : String.valueOf(roundId))
                .traceId(UUID.randomUUID().toString())
                // 工作区路径由 Java 下发，Python 不做任何自己的拼接
                .workspaceRoot(workspace.getParent().toString())
                .workspaceName(workspace.getFileName().toString())
                .codeGenType(type.getDirPrefix())
                .mode(isWorkspacePopulated(workspace) ? "edit" : "create")
                .message(message)
                .initPrompt(app.getInitPrompt())
                .deadlineMs(agentProperties.getRunDeadline().toMillis())
                .deployKey(app.getDeployKey())
                .build();
    }

    /**
     * 产物目录里有没有东西 —— 用来区分「建工程」还是「改产物」。
     *
     * <p>Phase 0 的过渡判断：产物已存在就按修改轮处理，否则按首轮。
     * Phase 1 接上 {@code chat_history} 后会换成按轮数判断（对齐现有的 isFirstRound），
     * 因为「目录非空」和「有对话历史」是两件事。
     */
    private static boolean isWorkspacePopulated(Path workspace) {
        try (var entries = Files.list(workspace)) {
            return entries.findAny().isPresent();
        } catch (Exception e) {
            // 目录不存在或读不了 —— 按首轮处理，让 Python 去建
            return false;
        }
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
