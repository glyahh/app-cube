package com.dbts.glyahhaigeneratecode.controller;

import com.dbts.glyahhaigeneratecode.agentauth.AgentEditSettingsService;
import com.dbts.glyahhaigeneratecode.agentauth.AgentEditSettingsService.SettingsDTO;
import com.dbts.glyahhaigeneratecode.agentauth.AgentEditSettingsService.SettingsVO;
import com.dbts.glyahhaigeneratecode.agentauth.AgentModelPreferenceService;
import com.dbts.glyahhaigeneratecode.agentauth.AgentModelPreferenceService.ConfigVO;
import com.dbts.glyahhaigeneratecode.agentauth.AgentModelPreferenceService.PreferenceDTO;
import com.dbts.glyahhaigeneratecode.agentauth.AgentModelPreferenceService.PreferenceVO;
import com.dbts.glyahhaigeneratecode.common.BaseResponse;
import com.dbts.glyahhaigeneratecode.common.ResultUtils;
import com.dbts.glyahhaigeneratecode.service.UserService;
import jakarta.servlet.http.HttpServletRequest;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.web.bind.annotation.*;

/**
 * 普通应用的模型偏好与授权模式入口。
 *
 * <p>两个能力共用同一个应用级入口，但存储分开：模型偏好按应用存，
 * 授权模式按 owner+app+thread 的会话存。写请求沿用登录态与归属校验。
 */
@RestController
@RequestMapping("/app/{appId}")
@RequiredArgsConstructor
@Slf4j
public class AgentModelPreferenceController {
    private final AgentModelPreferenceService preferences;
    private final AgentEditSettingsService editSettings;
    private final UserService users;

    @GetMapping("/agent-model")
    public BaseResponse<ConfigVO> config(@PathVariable long appId, HttpServletRequest request) {
        return ResultUtils.success(preferences.config(users.getUserInSession(request).getId(), appId,
                request.getSession(false).getId()));
    }

    @PutMapping("/agent-model")
    public BaseResponse<PreferenceVO> save(@PathVariable long appId, @RequestBody PreferenceDTO input,
                                           HttpServletRequest request) {
        return ResultUtils.success(preferences.save(users.getUserInSession(request).getId(), appId, input));
    }

    /** 普通应用的授权模式；导入应用走 /project/import 下的设置接口。 */
    @PutMapping("/agent-settings")
    public BaseResponse<SettingsVO> saveSettings(@PathVariable long appId, @RequestBody SettingsDTO input,
                                                 HttpServletRequest request) {
        return ResultUtils.success(editSettings.save(users.getUserInSession(request).getId(), appId,
                request.getSession(false).getId(), input));
    }
}
