package com.dbts.glyahhaigeneratecode.controller;

import com.dbts.glyahhaigeneratecode.agentauth.AgentCandidateReviewService;
import com.dbts.glyahhaigeneratecode.agentauth.AgentEditSessionService;
import com.dbts.glyahhaigeneratecode.agentauth.AgentToolPermissionService;
import com.dbts.glyahhaigeneratecode.agentauth.AgentToolPermissionService.DecisionDTO;
import com.dbts.glyahhaigeneratecode.common.BaseResponse;
import com.dbts.glyahhaigeneratecode.common.ResultUtils;
import com.dbts.glyahhaigeneratecode.service.UserService;
import com.fasterxml.jackson.databind.JsonNode;
import jakarta.servlet.http.HttpServletRequest;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.web.bind.annotation.*;

import java.util.UUID;

/**
 * 普通应用的工具许可入口。
 *
 * <p>只暴露安全投影；动作详情、风险理由与决定都来自服务端登记，
 * 不接受客户端提交动作内容或风险结论。
 */
@RestController
@RequestMapping("/app/{appId}/agent-permissions")
@RequiredArgsConstructor
@Slf4j
public class AgentPermissionController {
    private final AgentToolPermissionService permissions;
    private final AgentCandidateReviewService reviews;
    private final AgentEditSessionService sessions;
    private final UserService users;

    /** 当前应用是否有待批准的工具动作；前端轮询它来显示卡片。 */
    @GetMapping("/pending")
    public BaseResponse<JsonNode> pending(@PathVariable long appId, HttpServletRequest request) {
        return ResultUtils.success(permissions.pending(users.getUserInSession(request).getId(), appId));
    }

    @GetMapping("/{permissionId}")
    public BaseResponse<JsonNode> current(@PathVariable String appId, @PathVariable UUID permissionId,
                                          HttpServletRequest request) {
        return ResultUtils.success(permissions.current(users.getUserInSession(request).getId(), permissionId));
    }

    @PostMapping("/{permissionId}/decision")
    public BaseResponse<JsonNode> decide(@PathVariable String appId, @PathVariable UUID permissionId,
                                         @RequestBody DecisionDTO input, HttpServletRequest request) {
        return ResultUtils.success(permissions.decide(users.getUserInSession(request).getId(), permissionId, input));
    }

    /** 关闭会话：拒绝后停止本轮，旧票据随即失效。 */
    @PostMapping("/session/{sessionId}/close")
    public BaseResponse<Void> close(@PathVariable String appId, @PathVariable UUID sessionId,
                                    @RequestParam(defaultValue = "CANCELLED") String state,
                                    HttpServletRequest request) {
        sessions.close(sessionId, users.getUserInSession(request).getId(), state);
        return ResultUtils.success(null);
    }

    /**
     * 本轮结束：把变更账本冻成不可变候选。
     *
     * <p>候选不在这里决定是否合入，只登记等待；决定由 review/decision 完成。
     */
    @PostMapping("/session/{sessionId}/review")
    public BaseResponse<JsonNode> prepareReview(@PathVariable String appId, @PathVariable UUID sessionId,
                                                HttpServletRequest request) {
        long ownerId = users.getUserInSession(request).getId();
        var session = sessions.require(sessionId, ownerId);
        return ResultUtils.success(reviews.prepare(ownerId, sessionId, session.expiresAt()));
    }

    /** 该应用最近一条候选审阅；前端轮询它显示整批确认卡片。 */
    @GetMapping("/reviews/latest")
    public BaseResponse<JsonNode> latestReview(@PathVariable long appId, HttpServletRequest request) {
        return ResultUtils.success(reviews.latest(users.getUserInSession(request).getId(), appId));
    }

    @GetMapping("/reviews/{reviewId}")
    public BaseResponse<JsonNode> review(@PathVariable String appId, @PathVariable UUID reviewId,
                                         HttpServletRequest request) {
        return ResultUtils.success(reviews.current(users.getUserInSession(request).getId(), reviewId));
    }

    /** 拒绝会按账本回滚到本轮开始前；批准保留改动。 */
    @PostMapping("/reviews/{reviewId}/decision")
    public BaseResponse<JsonNode> decideReview(@PathVariable String appId, @PathVariable UUID reviewId,
                                               @RequestBody AgentCandidateReviewService.DecisionDTO input,
                                               HttpServletRequest request) {
        return ResultUtils.success(reviews.decide(users.getUserInSession(request).getId(), reviewId, input));
    }
}