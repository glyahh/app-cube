package com.dbts.glyahhaigeneratecode.controller;

import com.dbts.glyahhaigeneratecode.common.BaseResponse;
import com.dbts.glyahhaigeneratecode.common.ResultUtils;
import com.dbts.glyahhaigeneratecode.exception.ErrorCode;
import com.dbts.glyahhaigeneratecode.exception.ThrowUtils;
import com.dbts.glyahhaigeneratecode.knowledge.KnowledgeQueryService;
import com.dbts.glyahhaigeneratecode.knowledge.KnowledgeQaService;
import com.dbts.glyahhaigeneratecode.knowledge.KnowledgeTaskService;
import com.dbts.glyahhaigeneratecode.model.Entity.App;
import com.dbts.glyahhaigeneratecode.model.Entity.KnowledgeDoc;
import com.dbts.glyahhaigeneratecode.model.Entity.KnowledgeQaMessage;
import com.dbts.glyahhaigeneratecode.model.Entity.KnowledgeTask;
import com.dbts.glyahhaigeneratecode.model.Entity.User;
import com.dbts.glyahhaigeneratecode.model.VO.KnowledgeGraphVO;
import com.dbts.glyahhaigeneratecode.model.VO.KnowledgeStatusVO;
import com.dbts.glyahhaigeneratecode.service.AppService;
import com.dbts.glyahhaigeneratecode.service.UserService;
import jakarta.servlet.http.HttpServletRequest;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.web.bind.annotation.*;

import java.nio.file.Path;
import java.util.List;
import java.util.Map;

/**
 * 项目知识 控制层。
 *
 * <p>两块能力：异步生成项目知识（文档 + 图谱），以及只读的知识问答。
 * 两者都不写主生成会话，也不占项目的生成锁。
 */
@RestController
@RequestMapping("/app")
@RequiredArgsConstructor
@Slf4j
public class KnowledgeController {

    private final KnowledgeTaskService taskService;
    private final KnowledgeQueryService queryService;
    private final KnowledgeQaService qaService;
    private final AppService appService;
    private final UserService userService;

    /**
     * 【用户】提交知识生成
     *
     * <p>仅对已启用「知识图谱」看板功能的应用开放 —— 没启用的项目不该被后台任务占用。
     */
    @PostMapping("/{appId}/knowledge/tasks")
    public BaseResponse<KnowledgeTask> submitTask(@PathVariable Long appId, HttpServletRequest request) {
        User loginUser = userService.getUserInSession(request);
        Path workspace = appService.resolveWorkspaceDir(loginUser, appId);
        requireKnowledgeEnabled(appId);
        return ResultUtils.success(taskService.submit(appId, loginUser.getId(), workspace));
    }

    /**
     * 【用户】查询知识状态（版本对齐情况 + 最近任务进度）
     */
    @GetMapping("/{appId}/knowledge/status")
    public BaseResponse<KnowledgeStatusVO> status(@PathVariable Long appId, HttpServletRequest request) {
        User loginUser = userService.getUserInSession(request);
        Path workspace = appService.resolveWorkspaceDir(loginUser, appId);
        return ResultUtils.success(queryService.status(appId, workspace));
    }

    /** 【用户】查询单个知识任务 */
    @GetMapping("/{appId}/knowledge/tasks/{taskId}")
    public BaseResponse<KnowledgeTask> task(@PathVariable Long appId,
                                            @PathVariable Long taskId,
                                            HttpServletRequest request) {
        User loginUser = userService.getUserInSession(request);
        appService.resolveWorkspaceDir(loginUser, appId);
        KnowledgeTask task = taskService.byId(taskId);
        ThrowUtils.throwIf(task == null || !appId.equals(task.getAppId()),
                ErrorCode.NOT_FOUND_ERROR, "任务不存在");
        return ResultUtils.success(task);
    }

    /**
     * 【用户】取消知识任务
     *
     * <p>取消只影响这一个任务，已生成的知识保留。
     */
    @PostMapping("/{appId}/knowledge/tasks/{taskId}/cancel")
    public BaseResponse<Boolean> cancelTask(@PathVariable Long appId,
                                            @PathVariable Long taskId,
                                            HttpServletRequest request) {
        User loginUser = userService.getUserInSession(request);
        appService.resolveWorkspaceDir(loginUser, appId);
        KnowledgeTask task = taskService.byId(taskId);
        ThrowUtils.throwIf(task == null || !appId.equals(task.getAppId()),
                ErrorCode.NOT_FOUND_ERROR, "任务不存在");
        return ResultUtils.success(taskService.cancel(taskId));
    }

    /**
     * 【用户】重试知识任务
     *
     * <p>失败、被取消、被重启中断的任务都可以重试；重试用原快照，输入与首次一致。
     */
    @PostMapping("/{appId}/knowledge/tasks/{taskId}/retry")
    public BaseResponse<KnowledgeTask> retryTask(@PathVariable Long appId,
                                                 @PathVariable Long taskId,
                                                 HttpServletRequest request) {
        User loginUser = userService.getUserInSession(request);
        Path workspace = appService.resolveWorkspaceDir(loginUser, appId);

        KnowledgeTask task = taskService.byId(taskId);
        ThrowUtils.throwIf(task == null || !appId.equals(task.getAppId()),
                ErrorCode.NOT_FOUND_ERROR, "任务不存在");
        return ResultUtils.success(taskService.retry(taskId, workspace));
    }

    /**
     * 【用户】读取知识图谱
     *
     * <p>每个节点与关系都带 evidence 标记，前端据此区分源码证据与模型推断。
     */
    @GetMapping("/{appId}/knowledge/graph")
    public BaseResponse<KnowledgeGraphVO> graph(@PathVariable Long appId, HttpServletRequest request) {
        User loginUser = userService.getUserInSession(request);
        Path workspace = appService.resolveWorkspaceDir(loginUser, appId);
        return ResultUtils.success(queryService.graph(appId, workspace));
    }

    /** 【用户】读取知识文档（当前有效版本） */
    @GetMapping("/{appId}/knowledge/docs")
    public BaseResponse<List<KnowledgeDoc>> docs(@PathVariable Long appId, HttpServletRequest request) {
        User loginUser = userService.getUserInSession(request);
        appService.resolveWorkspaceDir(loginUser, appId);
        return ResultUtils.success(queryService.docs(appId));
    }

    /**
     * 【用户】知识问答（只读）
     *
     * <p>服务端固定只读能力：不写文件、不执行命令、不启动程序。
     * 需要改代码时由用户回到生成对话发起。
     */
    @PostMapping("/{appId}/knowledge/qa")
    public BaseResponse<KnowledgeQaMessage> ask(@PathVariable Long appId,
                                                @RequestBody Map<String, String> body,
                                                HttpServletRequest request) {
        User loginUser = userService.getUserInSession(request);
        Path workspace = appService.resolveWorkspaceDir(loginUser, appId);
        String question = body == null ? null : body.get("question");
        ThrowUtils.throwIf(question == null || question.isBlank(), ErrorCode.PARAMS_ERROR, "问题不能为空");
        return ResultUtils.success(qaService.ask(appId, loginUser.getId(), question, workspace));
    }

    /** 【用户】读取问答历史（新的在前） */
    @GetMapping("/{appId}/knowledge/qa/history")
    public BaseResponse<List<KnowledgeQaMessage>> qaHistory(@PathVariable Long appId,
                                                            @RequestParam(required = false) Integer limit,
                                                            HttpServletRequest request) {
        User loginUser = userService.getUserInSession(request);
        appService.resolveWorkspaceDir(loginUser, appId);
        return ResultUtils.success(qaService.history(appId, limit == null ? 100 : limit));
    }

    /**
     * 【用户】清空问答历史
     *
     * <p>只删历史记录，不动已生成的知识。
     */
    @PostMapping("/{appId}/knowledge/qa/clear")
    public BaseResponse<Boolean> clearQa(@PathVariable Long appId, HttpServletRequest request) {
        User loginUser = userService.getUserInSession(request);
        appService.resolveWorkspaceDir(loginUser, appId);
        qaService.clearHistory(appId);
        return ResultUtils.success(true);
    }

    /**
     * 知识功能只对勾选了「知识图谱」的项目开放。
     *
     * <p>没勾选的项目不该被后台任务占用 —— 那是用户的显式选择。
     */
    private void requireKnowledgeEnabled(Long appId) {
        App app = appService.getById(appId);
        ThrowUtils.throwIf(app == null, ErrorCode.NOT_FOUND_ERROR, "应用不存在");
        String features = app.getDashboardFeatures();
        boolean enabled = false;
        if (cn.hutool.core.util.StrUtil.isNotBlank(features)) {
            try {
                enabled = cn.hutool.json.JSONUtil.toList(features, String.class).contains("knowledge");
            } catch (Exception e) {
                enabled = false;
            }
        }
        ThrowUtils.throwIf(!enabled, ErrorCode.OPERATION_ERROR,
                "该项目未启用知识图谱，请先在「看板设置」里开启");
    }
}
