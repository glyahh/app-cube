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

/** 展示偏好只读写当前登录用户，不接受客户端指定用户。 */
@RestController
@RequestMapping("/user/preferences")
@RequiredArgsConstructor
public class UserPreferenceController {
    private final UserService userService;

    public record Preference(Boolean expandExecutionProcess) { }

    @GetMapping
    public BaseResponse<Preference> get(HttpServletRequest request) {
        User user = userService.getById(userService.getUserInSession(request).getId());
        ThrowUtils.throwIf(user == null, ErrorCode.NOT_LOGIN_ERROR);
        return ResultUtils.success(new Preference(Boolean.TRUE.equals(user.getExpandExecutionProcess())));
    }

    @PutMapping
    public BaseResponse<Preference> update(@RequestBody Preference preference, HttpServletRequest request) {
        User loginUser = userService.getUserInSession(request);
        ThrowUtils.throwIf(preference == null || preference.expandExecutionProcess() == null, ErrorCode.PARAMS_ERROR);
        User update = new User();
        update.setId(loginUser.getId());
        update.setExpandExecutionProcess(preference.expandExecutionProcess());
        ThrowUtils.throwIf(!userService.updateById(update), ErrorCode.OPERATION_ERROR);
        return ResultUtils.success(preference);
    }
}
