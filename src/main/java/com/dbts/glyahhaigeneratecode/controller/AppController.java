package com.dbts.glyahhaigeneratecode.controller;

import cn.hutool.core.util.StrUtil;
import com.dbts.glyahhaigeneratecode.annotation.MyRole;
import com.dbts.glyahhaigeneratecode.common.BaseResponse;
import com.dbts.glyahhaigeneratecode.common.DeleteRequest;
import com.dbts.glyahhaigeneratecode.common.ResultUtils;
import com.dbts.glyahhaigeneratecode.constant.UserConstant;
import com.dbts.glyahhaigeneratecode.exception.ErrorCode;
import com.dbts.glyahhaigeneratecode.exception.ThrowUtils;
import com.dbts.glyahhaigeneratecode.model.DTO.*;
import com.dbts.glyahhaigeneratecode.model.Entity.User;
import com.dbts.glyahhaigeneratecode.model.VO.ApplyHistoryVO;
import com.dbts.glyahhaigeneratecode.model.VO.ApplyVO;
import com.dbts.glyahhaigeneratecode.model.VO.AppVO;
import com.dbts.glyahhaigeneratecode.model.VO.AppWorkspaceVO;
import com.dbts.glyahhaigeneratecode.model.VO.ProjectFileContentVO;
import com.dbts.glyahhaigeneratecode.model.VO.ProjectFileVO;
import com.dbts.glyahhaigeneratecode.model.VO.ProjectTreeEntryVO;
import com.dbts.glyahhaigeneratecode.service.AppService;
import com.dbts.glyahhaigeneratecode.service.UserAppApplyService;
import com.dbts.glyahhaigeneratecode.service.UserService;
import com.mybatisflex.core.paginate.Page;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.cache.annotation.Cacheable;
import org.springframework.web.bind.annotation.*;

import java.util.List;

/**
 * 应用 控制层。
 *
 * @author <a href="https://github.com/glyahh">glyahh</a>
 */
@RestController
@RequestMapping("/app")
@RequiredArgsConstructor
@Slf4j
public class AppController {

    private final AppService appService;
    private final UserService userService;
    private final UserAppApplyService userAppApplyService;

    /**
     * 【用户】创建应用（须填写 initPrompt）
     *
     * @param appAddRequest 用户创建app的基础数据
     * @param request       请求
     * @return 应用 id
     */
    @PostMapping("/add")
    public BaseResponse<Long> addMyApp(@RequestBody AppAddRequest appAddRequest, HttpServletRequest request) {
        ThrowUtils.throwIf(appAddRequest == null, ErrorCode.PARAMS_ERROR, "创建应用请求参数为空");
        ThrowUtils.throwIf(StrUtil.isBlank(appAddRequest.getInitPrompt()), ErrorCode.PARAMS_ERROR, "initPrompt 必填");
        User loginUser = userService.getUserInSession(request);

        return ResultUtils.success(appService.createApp(loginUser, appAddRequest));
    }

    /**
     * 【用户】根据 id 修改自己的应用（目前只支持修改应用名称）
     */
    @PostMapping("/update")
    public BaseResponse<Boolean> updateMyApp(@RequestBody AppUpdateRequest appUpdateRequest, HttpServletRequest request) {
        User loginUser = userService.getUserInSession(request);
        return ResultUtils.success(appService.updateMyApp(loginUser, appUpdateRequest));
    }

    /**
     * 【用户】根据 id 删除自己的应用
     */
    @PostMapping("/delete")
    public BaseResponse<Boolean> deleteMyApp(@RequestBody DeleteRequest deleteRequest, HttpServletRequest request) {
        User loginUser = userService.getUserInSession(request);
        return ResultUtils.success(appService.deleteMyApp(loginUser, deleteRequest.getId()));
    }

    /**
     * 【用户】根据 id 查看自己的应用详情
     */
    @GetMapping("/get/vo")
    public BaseResponse<AppVO> getMyAppVOById(@RequestParam String id, HttpServletRequest request) {
        User loginUser = userService.getUserInSession(request);
        return ResultUtils.success(appService.getMyAppVOById(loginUser, id));
    }

    /**
     * 【用户】分页查询自己的应用列表（支持根据名称查询，每页最多 {@link com.dbts.glyahhaigeneratecode.constant.AppConstant#MAX_APP_LIST_PAGE_SIZE} 条）
     */
    @PostMapping("/my/list/page/vo")
    public BaseResponse<Page<AppVO>> listMyAppVOByPage(@RequestBody AppQueryRequest appQueryRequest,
                                                       HttpServletRequest request) {
        User loginUser = userService.getUserInSession(request);
        return ResultUtils.success(appService.listMyAppVOByPage(loginUser, appQueryRequest));
    }

    /**
     * 分页获取精选应用列表
     *
     * @param appQueryRequest 查询请求
     * @return 精选应用列表
     */
    @PostMapping("/good/list/page/vo")
    @Cacheable(
            value = "good_app_page",
            key = "T(com.dbts.glyahhaigeneratecode.utils.CacheKeyUtils).generateKey(#appQueryRequest)",
            condition = "#appQueryRequest.pageNum <= 10"
    )
    public BaseResponse<Page<AppVO>> listGoodAppVOByPage(@RequestBody AppQueryRequest appQueryRequest) {
        return ResultUtils.success(appService.listGoodAppVOByPage(appQueryRequest));
    }


    /**
     * 【管理员】根据 id 删除任意应用
     */
    @PostMapping("/admin/delete")
    @MyRole(role = UserConstant.ADMIN_ROLE)
    public BaseResponse<Boolean> deleteAppByAdmin(@RequestBody DeleteRequest deleteRequest) {
        return ResultUtils.success(appService.deleteAppByAdmin(deleteRequest.getId()));
    }

    /**
     * 【管理员】根据 id 更新任意应用（支持更新应用名称、应用封面、优先级）
     */
    @PostMapping("/admin/update")
    @MyRole(role = UserConstant.ADMIN_ROLE)
    public BaseResponse<Boolean> updateAppByAdmin(@RequestBody AppAdminUpdateRequest appAdminUpdateRequest) {
        return ResultUtils.success(appService.updateAppByAdmin(appAdminUpdateRequest));
    }

    /**
     * 【管理员】分页查询应用列表（支持根据除时间外的任何字段查询，每页数量不限）
     */
    @PostMapping("/admin/list/page/vo")
    @MyRole(role = UserConstant.ADMIN_ROLE)
    public BaseResponse<Page<AppVO>> listAppVOByPageAdmin(@RequestBody AppQueryRequest appQueryRequest) {
        return ResultUtils.success(appService.listAppVOByPageAdmin(appQueryRequest));
    }

    /**
     * 【管理员】根据 id 查看应用详情
     */
    @GetMapping("/admin/get/vo")
    @MyRole(role = UserConstant.ADMIN_ROLE)
    public BaseResponse<AppVO> getAppVOByIdAdmin(long id) {
        return ResultUtils.success(appService.getAppVOByIdAdmin(id));
    }

    /**
     * 应用部署
     *
     * @param appDeployRequest 部署请求
     * @param request          请求
     * @return 部署 URL
     */
    @PostMapping("/deploy")
    public BaseResponse<String> deployApp (@RequestBody AppDeployRequest appDeployRequest, HttpServletRequest request) {
        ThrowUtils.throwIf(appDeployRequest == null, ErrorCode.PARAMS_ERROR);
        Long appId = appDeployRequest.getAppId();
        ThrowUtils.throwIf(appId == null || appId <= 0, ErrorCode.PARAMS_ERROR, "应用 ID 不能为空");
        // 获取当前登录用户
        User loginUser = userService.getUserInSession(request);
        // 调用服务部署应用，仅返回 deployKey（避免服务层硬编码 host 导致 URL 错误）。
        String deployKey = appService.deployApp(appId, loginUser);
        String deployUrl = buildDeployUrlFromRequest(request, deployKey);
        return ResultUtils.success(deployUrl);
    }

    /**
     * 根据当前请求动态拼接部署访问地址（包含协议/域名/端口/context-path）。
     */
    private String buildDeployUrlFromRequest(HttpServletRequest request, String deployKey) {
        // debug 修复：之前返回 http://localhost/{key}/ 会丢失端口和 /api，导致最终 404。
        String scheme = request.getScheme();
        String serverName = request.getServerName();
        int serverPort = request.getServerPort();
        String contextPath = request.getContextPath();

        StringBuilder base = new StringBuilder();
        base.append(scheme).append("://").append(serverName);
        if (!(("http".equalsIgnoreCase(scheme) && serverPort == 80)
                || ("https".equalsIgnoreCase(scheme) && serverPort == 443))) {
            base.append(":").append(serverPort);
        }
        if (StrUtil.isNotBlank(contextPath)) {
            if (!contextPath.startsWith("/")) {
                base.append("/");
            }
            base.append(StrUtil.removeSuffix(contextPath, "/"));
        }
        return base + "/deploy/" + deployKey + "/";
    }

    /**
     * 取消应用部署（删除部署目录）
     *
     * @param appDeployRequest 取消部署请求，仅使用 appId
     * @param request          请求
     * @return 删除成功返回 true，未部署或无目录返回 false
     */
    @PostMapping("/undeploy")
    public BaseResponse<Boolean> undeployApp(@RequestBody AppDeployRequest appDeployRequest, HttpServletRequest request) {
        ThrowUtils.throwIf(appDeployRequest == null, ErrorCode.PARAMS_ERROR);
        Long appId = appDeployRequest.getAppId();
        ThrowUtils.throwIf(appId == null || appId <= 0, ErrorCode.PARAMS_ERROR, "应用 ID 不能为空");
        // 获取当前登录用户
        User loginUser = userService.getUserInSession(request);
        // 调用服务取消部署应用
        boolean result = appService.undeployApp(appId, loginUser);
        return ResultUtils.success(result);
    }

    /**
     * 【用户】下载应用对应的生成项目（打包为 zip）
     *
     * @param appId    应用 id
     * @param request  请求
     * @param response 响应（写入 zip）
     */
    @GetMapping("/download/{appId}")
    public void downloadProject(@PathVariable Long appId,
                                HttpServletRequest request,
                                HttpServletResponse response) {
        User loginUser = userService.getUserInSession(request);
        appService.downloadProject(loginUser, appId, response);
    }


    /**
     * 【用户】获取应用项目文件列表（回显用）
     *
     * @param appId   应用 id
     * @param request 请求
     * @return 项目文件列表（路径、语言、内容、更新时间）
     */
    @GetMapping("/static/project-files/{appId}")
    public BaseResponse<List<ProjectFileVO>> getProjectFiles(@PathVariable Long appId,
                                                              HttpServletRequest request) {
        User loginUser = userService.getUserInSession(request);
        return ResultUtils.success(appService.getProjectFiles(loginUser, appId));
    }

    /**
     * 【用户】获取应用工作区描述
     *
     * <p>前端据此决定右侧能否预览、静态入口取哪个文件，不再按创建时选的类型猜测。
     *
     * @param appId   应用 id
     * @param request 请求
     * @return 工作区描述（实际目录名、是否已有产物、静态入口、可用能力及不支持原因）
     */
    @GetMapping("/{appId}/workspace")
    public BaseResponse<AppWorkspaceVO> getAppWorkspace(@PathVariable Long appId,
                                                        HttpServletRequest request) {
        User loginUser = userService.getUserInSession(request);
        return ResultUtils.success(appService.getWorkspace(loginUser, appId));
    }

    /**
     * 【用户】修改应用的看板功能开关（监控 / 日志 / 知识图谱）
     *
     * @param appId           应用 id
     * @param featuresRequest 要启用的功能标识；空列表表示全部关闭
     * @param request         请求
     * @return 更新后的工作区描述
     */
    @PutMapping("/{appId}/dashboard-features")
    public BaseResponse<AppWorkspaceVO> updateDashboardFeatures(
            @PathVariable Long appId,
            @RequestBody DashboardFeaturesRequest featuresRequest,
            HttpServletRequest request) {
        User loginUser = userService.getUserInSession(request);
        return ResultUtils.success(
                appService.updateDashboardFeatures(loginUser, appId, featuresRequest));
    }

    /**
     * 【用户】列出工作区某个目录的直接子项（文件树按需展开）
     *
     * @param appId  应用 id
     * @param path   相对工作区根的目录路径；省略表示根目录
     * @param request 请求
     * @return 该目录下的直接子项，目录在前
     */
    @GetMapping("/{appId}/files/tree")
    public BaseResponse<List<ProjectTreeEntryVO>> listProjectTree(@PathVariable Long appId,
                                                                  @RequestParam(required = false) String path,
                                                                  HttpServletRequest request) {
        User loginUser = userService.getUserInSession(request);
        return ResultUtils.success(appService.listProjectTree(loginUser, appId, path));
    }

    /**
     * 【用户】读取工作区内的单个文件
     *
     * @param appId  应用 id
     * @param path   相对工作区根的文件路径
     * @param request 请求
     * @return 文件内容与版本标识；二进制文件不返回内容
     */
    @GetMapping("/{appId}/files/content")
    public BaseResponse<ProjectFileContentVO> getProjectFileContent(@PathVariable Long appId,
                                                                     @RequestParam String path,
                                                                     HttpServletRequest request) {
        User loginUser = userService.getUserInSession(request);
        return ResultUtils.success(appService.getProjectFileContent(loginUser, appId, path));
    }

    /**
     * 【用户】保存工作区内的单个文本文件（带内容版本校验）
     *
     * @param appId        应用 id
     * @param saveRequest  路径、新内容与读取时的版本
     * @param request      请求
     * @return 保存后的文件内容与新的版本标识
     */
    @PostMapping("/{appId}/files/content")
    public BaseResponse<ProjectFileContentVO> saveProjectFile(@PathVariable Long appId,
                                                              @RequestBody ProjectFileSaveRequest saveRequest,
                                                              HttpServletRequest request) {
        User loginUser = userService.getUserInSession(request);
        return ResultUtils.success(appService.saveProjectFile(loginUser, appId, saveRequest));
    }

    /**
     * 【用户】下载工作区内的单个文件（原样输出字节）
     *
     * @param appId   应用 id
     * @param path    相对工作区根的文件路径
     * @param request 请求
     * @param response 响应（写入文件字节）
     */
    @GetMapping("/{appId}/files/download")
    public void downloadProjectFile(@PathVariable Long appId,
                                    @RequestParam String path,
                                    HttpServletRequest request,
                                    HttpServletResponse response) {
        User loginUser = userService.getUserInSession(request);
        appService.downloadProjectFile(loginUser, appId, path, response);
    }

    /**
     * 【用户】提交应用 / 权限申请
     *
     * @param applyRequest 申请参数（appId、appPropriety、operate、applyReason）
     * @param request      请求
     * @return 申请是否创建成功；若用户已是管理员，则返回 false，并通过 message 提示
     */
    @PostMapping("/apply")
    public BaseResponse<Boolean> applyForAppOrAdmin(@RequestBody UserAppApplyRequest applyRequest,
                                                    HttpServletRequest request) {
        ThrowUtils.throwIf(applyRequest == null, ErrorCode.PARAMS_ERROR, "申请参数不能为空");

        User loginUser = userService.getUserInSession(request);

        // 如果用户已经是管理员，直接返回 false 和提示信息
        if (UserConstant.ADMIN_ROLE.equals(loginUser.getUserRole())) {
            return new BaseResponse<>(20000, false, "您已经是管理员了");
        }

        boolean result = userAppApplyService.createUserAppApply(
                applyRequest.getAppId(),
                applyRequest.getAppPropriety(),
                applyRequest.getOperate(),
                applyRequest.getApplyReason(),
                loginUser
        );
        return ResultUtils.success(result);
    }

    /**
     * 【管理员】回显待处理的用户申请列表（status=0）
     *
     * @param request 请求
     * @return ApplyVO 列表：用户id、用户头像、operate、appId、Reason
     */
    @PostMapping("/apply/list/pending")
    @MyRole(role = UserConstant.ADMIN_ROLE)
    public BaseResponse<List<ApplyVO>> listPendingApply(HttpServletRequest request) {
        User loginUser = userService.getUserInSession(request);
        List<ApplyVO> result = userAppApplyService.listPendingApplyVO(loginUser);
        return ResultUtils.success(result);
    }

    /**
     * 【用户】回显自己的申请历史记录
     *
     * @param request 请求
     * @return ApplyHistoryVO 列表：operate、appId、appName、applyReason、status、reviewRemark、createTime、reviewTime
     */
    @PostMapping("/apply/list/my/history")
    public BaseResponse<List<ApplyHistoryVO>> listMyApplyHistory(HttpServletRequest request) {
        User loginUser = userService.getUserInSession(request);
        ThrowUtils.throwIf(loginUser == null, ErrorCode.NOT_LOGIN_ERROR);
        ThrowUtils.throwIf(UserConstant.ADMIN_ROLE.equals(loginUser.getUserRole()), ErrorCode.NO_AUTH_ERROR, "仅普通用户可查看申请历史");
        List<ApplyHistoryVO> result = userAppApplyService.listMyApplyHistoryVO(loginUser);
        return ResultUtils.success(result);
    }

    /**
     * 【管理员】同意某条用户申请
     *
     * @param handleRequest 申请处理请求（applyId，来源于待处理列表 ApplyVO.applyId）
     * @param request       请求
     * @return 是否处理成功
     */
    @PostMapping("/apply/agree")
    @MyRole(role = UserConstant.ADMIN_ROLE)
    public BaseResponse<Boolean> agreeApply(@RequestBody UserAppApplyHandleRequest handleRequest,
                                            HttpServletRequest request) {
        ThrowUtils.throwIf(handleRequest == null || handleRequest.getApplyId() == null || handleRequest.getApplyId() <= 0,
                ErrorCode.PARAMS_ERROR, "applyId 异常");
        User loginUser = userService.getUserInSession(request);
        boolean result = userAppApplyService.agreeApply(handleRequest.getApplyId(), loginUser);
        return ResultUtils.success(result);
    }

    @PostMapping("/apply/reject")
    @MyRole(role = UserConstant.ADMIN_ROLE)
    public BaseResponse<Boolean> rejectApply(@RequestBody UserAppApplyHandleRequest handleRequest,
                                             HttpServletRequest request) {
        ThrowUtils.throwIf(handleRequest == null
                        || handleRequest.getApplyId() == null
                        || handleRequest.getApplyId() <= 0,
                ErrorCode.PARAMS_ERROR, "applyId 异常");
        ThrowUtils.throwIf(StrUtil.isBlank(handleRequest.getReviewRemark()),
                ErrorCode.PARAMS_ERROR, "审核备注不能为空");
        User loginUser = userService.getUserInSession(request);
        boolean result = userAppApplyService.rejectApply(handleRequest.getApplyId(),
                handleRequest.getReviewRemark(), handleRequest.getApplyReason(), loginUser);
        return ResultUtils.success(result);
    }
}
