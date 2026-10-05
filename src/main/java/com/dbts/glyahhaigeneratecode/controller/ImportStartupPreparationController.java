package com.dbts.glyahhaigeneratecode.controller;

import com.dbts.glyahhaigeneratecode.common.BaseResponse;
import com.dbts.glyahhaigeneratecode.common.ResultUtils;
import com.dbts.glyahhaigeneratecode.exception.ErrorCode;
import com.dbts.glyahhaigeneratecode.exception.MyException;
import com.dbts.glyahhaigeneratecode.projectimport.ImportStartupPreparationService;
import com.dbts.glyahhaigeneratecode.service.UserService;
import jakarta.servlet.http.HttpServletRequest;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.*;
import java.util.UUID;

/** 安装确认与运行。写操作沿用导入 CSRF。拒绝或准入失败不会启动服务。 */
@RestController
@RequestMapping("/project/import/apps")
@RequiredArgsConstructor
public class ImportStartupPreparationController {
    private final UserService users;
    private final ImportStartupPreparationService preparations;
    public record DecisionRequest(String ownerId,UUID planId,String planRevision,Boolean approved){ }
    public record RunRequest(String ownerId,UUID planId,String planRevision){ }

    @PostMapping("/{appId}/startup/preparations/install")
    public BaseResponse<ImportStartupPreparationService.View> install(@PathVariable long appId,@RequestBody DecisionRequest input,HttpServletRequest request){
        var user=users.getUserInSession(request);
        require(user.getId(),input==null?null:input.ownerId(),input==null?null:input.planId(),input==null?null:input.planRevision());
        if(input.approved()==null)throw new MyException(ErrorCode.PARAMS_ERROR,"缺少安装决定。");
        return ResultUtils.success(preparations.install(user,appId,input.planId(),input.planRevision(),input.approved()));
    }
    @PostMapping("/{appId}/startup/preparations/run")
    public BaseResponse<ImportStartupPreparationService.View> run(@PathVariable long appId,@RequestBody RunRequest input,HttpServletRequest request){
        var user=users.getUserInSession(request);
        require(user.getId(),input==null?null:input.ownerId(),input==null?null:input.planId(),input==null?null:input.planRevision());
        return ResultUtils.success(preparations.run(user,appId,input.planId(),input.planRevision()));
    }
    @PostMapping("/{appId}/startup/preparations/stop")
    public BaseResponse<ImportStartupPreparationService.View> stop(@PathVariable long appId,@RequestBody RunRequest input,HttpServletRequest request){
        var user=users.getUserInSession(request);
        require(user.getId(),input==null?null:input.ownerId(),input==null?null:input.planId(),input==null?null:input.planRevision());
        return ResultUtils.success(preparations.stop(user,appId,input.planId(),input.planRevision()));
    }
    @PostMapping("/{appId}/startup/preparations/retry")
    public BaseResponse<ImportStartupPreparationService.View> retry(@PathVariable long appId,@RequestBody RunRequest input,HttpServletRequest request){
        var user=users.getUserInSession(request);
        require(user.getId(),input==null?null:input.ownerId(),input==null?null:input.planId(),input==null?null:input.planRevision());
        return ResultUtils.success(preparations.retry(user,appId,input.planId(),input.planRevision()));
    }
    @GetMapping("/{appId}/startup/preparations/current")
    public BaseResponse<ImportStartupPreparationService.View> current(@PathVariable long appId,@RequestParam UUID planId,HttpServletRequest request){
        return ResultUtils.success(preparations.current(users.getUserInSession(request),appId,planId));
    }
    private static void require(Long owner,String ownerId,UUID planId,String planRevision){
        if(ownerId==null||!Long.toString(owner).equals(ownerId))throw new MyException(ErrorCode.CONFLICT_ERROR,"登录状态已变化。");
        if(planId==null||planRevision==null)throw new MyException(ErrorCode.PARAMS_ERROR,"缺少计划身份或版本。");
    }
}
