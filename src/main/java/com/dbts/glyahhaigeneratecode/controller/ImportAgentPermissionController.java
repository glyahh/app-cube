package com.dbts.glyahhaigeneratecode.controller;

import com.dbts.glyahhaigeneratecode.common.BaseResponse;
import com.dbts.glyahhaigeneratecode.common.ResultUtils;
import com.dbts.glyahhaigeneratecode.projectimport.ImportAgentPermissionService;
import com.dbts.glyahhaigeneratecode.projectimport.ImportAgentPermissionService.*;
import com.dbts.glyahhaigeneratecode.service.UserService;
import jakarta.servlet.http.HttpServletRequest;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.web.bind.annotation.*;

import java.util.List;

/** 写请求复用导入CSRF过滤器；查询同样验证当前用户和应用归属。 */
@RestController
@RequestMapping("/project/import/{appId}")
@RequiredArgsConstructor
@Slf4j
public class ImportAgentPermissionController {
    private final ImportAgentPermissionService permissions;
    private final UserService users;
    private final com.dbts.glyahhaigeneratecode.projectimport.ImportAgentToolPermissionService tools;
    private final com.dbts.glyahhaigeneratecode.projectimport.ImportAgentCandidateReviewService reviews;

    @GetMapping("/agent-current")
    public BaseResponse<com.fasterxml.jackson.databind.JsonNode> current(@PathVariable long appId, HttpServletRequest request) {
        return ResultUtils.success(reviews.active(users.getUserInSession(request).getId(), appId));
    }

    @GetMapping("/agent-permissions/{permissionId}")
    public BaseResponse<com.fasterxml.jackson.databind.JsonNode> permission(@PathVariable long appId,
            @PathVariable java.util.UUID permissionId, HttpServletRequest request) {
        long owner = users.getUserInSession(request).getId();
        tools.requireApplication(owner, appId, permissionId);
        return ResultUtils.success(tools.current(owner, permissionId));
    }

    @PostMapping("/agent-permissions/{permissionId}/decision")
    public BaseResponse<com.fasterxml.jackson.databind.JsonNode> decide(@PathVariable long appId,
            @PathVariable java.util.UUID permissionId,
            @RequestBody com.dbts.glyahhaigeneratecode.projectimport.ImportAgentToolPermissionService.DecisionDTO input,
            HttpServletRequest request) {
        long owner = users.getUserInSession(request).getId();
        tools.requireApplication(owner, appId, permissionId);
        return ResultUtils.success(tools.decide(owner, permissionId, input, request.getSession(false).getId()));
    }

    @GetMapping("/agent-settings")
    public BaseResponse<SettingsVO> settings(@PathVariable long appId, HttpServletRequest request) {
        return ResultUtils.success(permissions.settings(users.getUserInSession(request).getId(), appId,
                request.getSession(false).getId()));
    }

    @PutMapping("/agent-settings")
    public BaseResponse<SettingsVO> saveSettings(@PathVariable long appId, @RequestBody SettingsDTO input,
                                                HttpServletRequest request) {
        return ResultUtils.success(permissions.saveSettings(users.getUserInSession(request).getId(), appId,
                request.getSession(false).getId(), input));
    }

    @GetMapping("/agent-models")
    public BaseResponse<List<ModelVO>> models(@PathVariable long appId, HttpServletRequest request) {
        return ResultUtils.success(permissions.models(users.getUserInSession(request).getId(), appId));
    }
}
