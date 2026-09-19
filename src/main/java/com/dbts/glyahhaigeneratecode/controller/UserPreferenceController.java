package com.dbts.glyahhaigeneratecode.controller;

import com.dbts.glyahhaigeneratecode.common.BaseResponse;
import com.dbts.glyahhaigeneratecode.common.ResultUtils;
import com.dbts.glyahhaigeneratecode.exception.ErrorCode;
import com.dbts.glyahhaigeneratecode.exception.ThrowUtils;
import com.dbts.glyahhaigeneratecode.model.Entity.User;
import com.dbts.glyahhaigeneratecode.service.UserService;
import jakarta.servlet.http.HttpServletRequest;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.*;

/** 展示与运行偏好只读写当前登录用户，不接受客户端指定用户。 */
@RestController
@RequestMapping("/user/preferences")
@RequiredArgsConstructor
public class UserPreferenceController {
    private final UserService userService;

    /**
     * 偏好集合。字段为 null 表示「本次不修改这一项」——
     * PUT 只更新非 null 的字段，这样老前端只传 expandExecutionProcess 也能正常工作。
     */
    public record Preference(
            Boolean expandExecutionProcess,
            Boolean alertOnStartupFailure,
            Boolean alertOnRuntimeFailure,
            Boolean autoModelProxyFallback,
            Boolean autoNameProject) {
        public Preference(Boolean expanded, Boolean startup, Boolean runtime, Boolean fallback) {
            this(expanded, startup, runtime, fallback, null);
        }
    }

    @GetMapping
    public BaseResponse<Preference> get(HttpServletRequest request) {
        User user = userService.getById(userService.getUserInSession(request).getId());
        ThrowUtils.throwIf(user == null, ErrorCode.NOT_LOGIN_ERROR);
        // 库里的 null 一律折叠成明确的默认值：启动异常默认开、运行异常默认关
        return ResultUtils.success(new Preference(
                Boolean.TRUE.equals(user.getExpandExecutionProcess()),
                user.getAlertOnStartupFailure() == null || user.getAlertOnStartupFailure(),
                Boolean.TRUE.equals(user.getAlertOnRuntimeFailure()),
                Boolean.TRUE.equals(user.getAutoModelProxyFallback()),
                !Boolean.FALSE.equals(user.getAutoNameProject())));
    }

    @PutMapping
    public BaseResponse<Preference> update(@RequestBody Preference preference, HttpServletRequest request) {
        User loginUser = userService.getUserInSession(request);
        ThrowUtils.throwIf(preference == null, ErrorCode.PARAMS_ERROR);
        ThrowUtils.throwIf(preference.expandExecutionProcess() == null
                        && preference.alertOnStartupFailure() == null
                        && preference.alertOnRuntimeFailure() == null
                        && preference.autoModelProxyFallback() == null
                        && preference.autoNameProject() == null,
                ErrorCode.PARAMS_ERROR, "没有要修改的偏好项");

        User update = new User();
        update.setId(loginUser.getId());
        // updateById 会跳过 null 字段，所以这里逐项设置不会互相覆盖
        if (preference.expandExecutionProcess() != null) {
            update.setExpandExecutionProcess(preference.expandExecutionProcess());
        }
        if (preference.alertOnStartupFailure() != null) {
            update.setAlertOnStartupFailure(preference.alertOnStartupFailure());
        }
        if (preference.alertOnRuntimeFailure() != null) {
            update.setAlertOnRuntimeFailure(preference.alertOnRuntimeFailure());
        }
        if (preference.autoModelProxyFallback() != null) {
            update.setAutoModelProxyFallback(preference.autoModelProxyFallback());
        }
        if (preference.autoNameProject() != null) {
            update.setAutoNameProject(preference.autoNameProject());
        }
        ThrowUtils.throwIf(!userService.updateById(update), ErrorCode.OPERATION_ERROR);
        return ResultUtils.success(preference);
    }
}
