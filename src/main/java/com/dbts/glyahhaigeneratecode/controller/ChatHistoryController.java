package com.dbts.glyahhaigeneratecode.controller;

import com.dbts.glyahhaigeneratecode.annotation.MyRole;
import com.dbts.glyahhaigeneratecode.common.BaseResponse;
import com.dbts.glyahhaigeneratecode.common.ResultUtils;
import com.dbts.glyahhaigeneratecode.constant.UserConstant;
import com.dbts.glyahhaigeneratecode.exception.ErrorCode;
import com.dbts.glyahhaigeneratecode.exception.ThrowUtils;
import com.dbts.glyahhaigeneratecode.model.DTO.ChatHistoryQueryRequest;
import com.dbts.glyahhaigeneratecode.model.Entity.App;
import com.dbts.glyahhaigeneratecode.model.Entity.ChatHistory;
import com.dbts.glyahhaigeneratecode.model.Entity.User;
import com.dbts.glyahhaigeneratecode.model.VO.AppChatHistoryPageVO;
import com.dbts.glyahhaigeneratecode.model.VO.ChatHistoryVO;
import com.dbts.glyahhaigeneratecode.model.VO.UserChatHistoryItemVO;
import com.dbts.glyahhaigeneratecode.service.AppService;
import com.dbts.glyahhaigeneratecode.service.ChatHistoryService;
import com.dbts.glyahhaigeneratecode.service.UserService;
import com.mybatisflex.core.paginate.Page;
import com.mybatisflex.core.query.QueryWrapper;
import jakarta.servlet.http.HttpServletRequest;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.web.bind.annotation.*;

import java.time.LocalDateTime;
import java.util.List;

/**
 * 对话历史 控制层。
 *
 * @author <a href="https://github.com/glyahh">glyahh</a>
 */
@RestController
@RequestMapping("/chatHistory")
@RequiredArgsConstructor
@Slf4j
public class ChatHistoryController {

    private final ChatHistoryService chatHistoryService;
    private final UserService userService;
    private final AppService appService;

    /**
     * 【用户】保存对话历史
     *
     * @param chatHistory 对话历史实体（需包含 appId、message、messageType）
     * @param request     HTTP 请求
     * @return 是否保存成功
     */
    @PostMapping("/save")
    public BaseResponse<Boolean> saveChatHistory(@RequestBody ChatHistory chatHistory, HttpServletRequest request) {
        ThrowUtils.throwIf(chatHistory == null, ErrorCode.PARAMS_ERROR, "对话记录不能为空");

        User loginUser = userService.getUserInSession(request);

        // 权限校验：仅应用创建者或管理员可保存
        Long appId = chatHistory.getAppId();
        ThrowUtils.throwIf(appId == null || appId <= 0, ErrorCode.PARAMS_ERROR, "应用 id 异常");
        App app = appService.getById(appId);
        ThrowUtils.throwIf(app == null, ErrorCode.NOT_FOUND_ERROR, "应用不存在");
        ThrowUtils.throwIf(
                !loginUser.getId().equals(app.getUserId())
                        && !UserConstant.ADMIN_ROLE.equals(loginUser.getUserRole()),
                ErrorCode.NO_AUTH_ERROR, "仅应用创建者和管理员可保存对话历史"
        );

        boolean saved = chatHistoryService.addChatMessage(
                appId,
                chatHistory.getMessage(),
                chatHistory.getMessageType(),
                loginUser.getId()
        );
        return ResultUtils.success(saved);
    }

    /**
     * 【用户】查询某个应用的对话历史（基于时间游标，向前加载）
     * 进入应用页面时调用，首次不传 lastCreateTime 加载最新消息；
     * 向上滚动加载更多时，传入当前最早一条消息的 createTime。
     * 仅应用创建者和管理员可见（权限在 Service 层校验）。
     *
     * @param appId          应用 id
     * @param lastCreateTime 游标时间（可选，加载早于该时间的消息）
     * @param size           每次加载条数（默认 10）
     * @param request        HTTP 请求
     * @return 分页结果
     */
    /**
     * 【用户】导出某应用全部对话历史（用于生成 Markdown 到本地）
     * 仅应用创建者和管理员可调用。
     *
     * @param appId   应用 id
     * @param request HTTP 请求
     * @return 按创建时间升序的对话历史 VO 列表
     */
    @GetMapping("/export/{appId}")
    public BaseResponse<List<ChatHistoryVO>> exportChatHistory(
            @PathVariable Long appId,
            HttpServletRequest request) {
        User loginUser = userService.getUserInSession(request);
        List<ChatHistoryVO> list = chatHistoryService.listAllByAppIdForExport(appId, loginUser);
        return ResultUtils.success(list);
    }

    /**
     * 【用户】获取某应用的对话轮数（用户一问 + AI 一答为一轮）
     * 仅应用创建者和管理员可调用。
     *
     * @param appId   应用 id
     * @param request HTTP 请求
     * @return 对话轮数
     */
    @GetMapping("/roundCount/{appId}")
    public BaseResponse<Integer> getRoundCount(@PathVariable Long appId, HttpServletRequest request) {
        User loginUser = userService.getUserInSession(request);
        int rounds = chatHistoryService.countRoundsByAppId(appId, loginUser);
        return ResultUtils.success(rounds);
    }

    @GetMapping("/app/{appId}")
    public BaseResponse<AppChatHistoryPageVO> listChatHistory(
            @PathVariable Long appId,
            @RequestParam(required = false)
            @DateTimeFormat(pattern = "yyyy-MM-dd HH:mm:ss") LocalDateTime lastCreateTime,
            @RequestParam(defaultValue = "10") Integer size,
            HttpServletRequest request) {

        User loginUser = userService.getUserInSession(request);
        AppChatHistoryPageVO page = chatHistoryService.listAppChatHistoryByPage(appId, size, lastCreateTime, loginUser);
        return ResultUtils.success(page);
    }

    /**
     * 【管理员】分页查询所有对话历史（支持按应用 id、用户 id 筛选，按时间降序排序）
     *
     * @return 分页结果
     */
    @PostMapping("/admin")
    @MyRole(role = UserConstant.ADMIN_ROLE)
    public BaseResponse<Page<ChatHistory>> listChatHistoryByPageAdmin(
            @RequestBody ChatHistoryQueryRequest chatHistoryQueryRequest,
            HttpServletRequest request
            ) {
        ThrowUtils.throwIf(chatHistoryQueryRequest == null, ErrorCode.PARAMS_ERROR, "参数不能为空");
        int pageNum = chatHistoryQueryRequest.getPageNum();
        int pageSize = chatHistoryQueryRequest.getPageSize();
        ThrowUtils.throwIf(pageSize <= 0, ErrorCode.PARAMS_ERROR, "pageSize 必须大于 0");

        QueryWrapper queryWrapper = chatHistoryService.buildQueryWrapper(chatHistoryQueryRequest);

        Page<ChatHistory> voPage = chatHistoryService.page(Page.of(pageNum, pageSize), queryWrapper);
        voPage.getRecords().forEach(item -> item.setMessage(
                com.dbts.glyahhaigeneratecode.agent.UserFacingOutputHandler.history(item.getMessageType(), item.getMessage())));

        return ResultUtils.success(voPage);
    }

    /**
     * 【用户】分页查询当前登录用户的对话历史（仅返回自己的记录）
     */
    @PostMapping("/myChatHistory")
    public BaseResponse<Page<UserChatHistoryItemVO>> listMyChatHistoryByPage(
            @RequestBody ChatHistoryQueryRequest chatHistoryQueryRequest,
            HttpServletRequest request) {
        ThrowUtils.throwIf(chatHistoryQueryRequest == null, ErrorCode.PARAMS_ERROR, "参数不能为空");
        int pageSize = chatHistoryQueryRequest.getPageSize();
        ThrowUtils.throwIf(pageSize <= 0, ErrorCode.PARAMS_ERROR, "pageSize 必须大于 0");

        User loginUser = userService.getUserInSession(request);
        Page<UserChatHistoryItemVO> page = chatHistoryService.listMyChatHistoryByPage(chatHistoryQueryRequest, loginUser);
        return ResultUtils.success(page);
    }

    /**
     * 【用户】删除某应用的全部对话历史（仅应用创建者或管理员可操作）
     */
    @PostMapping("/deleteByAppId/{appId}")
    public BaseResponse<Boolean> deleteChatHistoryByAppId(
            @PathVariable Long appId,
            HttpServletRequest request) {
        ThrowUtils.throwIf(appId == null || appId <= 0, ErrorCode.PARAMS_ERROR, "appId 不能为空");
        User loginUser = userService.getUserInSession(request);
        App app = appService.getById(appId);
        ThrowUtils.throwIf(app == null, ErrorCode.NOT_FOUND_ERROR, "应用不存在");
        ThrowUtils.throwIf(
                !loginUser.getId().equals(app.getUserId())
                        && !UserConstant.ADMIN_ROLE.equals(loginUser.getUserRole()),
                ErrorCode.NO_AUTH_ERROR, "仅应用创建者和管理员可删除对话历史"
        );
        boolean result = chatHistoryService.removeByAppId(appId);
        return ResultUtils.success(result);
    }
}
