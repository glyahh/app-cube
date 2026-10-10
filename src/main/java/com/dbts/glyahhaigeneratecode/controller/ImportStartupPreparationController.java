package com.dbts.glyahhaigeneratecode.controller;

import com.dbts.glyahhaigeneratecode.common.BaseResponse;
import com.dbts.glyahhaigeneratecode.common.ResultUtils;
import com.dbts.glyahhaigeneratecode.exception.ErrorCode;
import com.dbts.glyahhaigeneratecode.exception.MyException;
import com.dbts.glyahhaigeneratecode.projectimport.ImportStartupInstallJob;
import com.dbts.glyahhaigeneratecode.projectimport.ImportStartupInstallJobService;
import com.dbts.glyahhaigeneratecode.projectimport.ImportStartupPreparationService;
import com.dbts.glyahhaigeneratecode.service.UserService;
import jakarta.servlet.http.HttpServletRequest;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.*;
import java.util.UUID;

/**
 * 安装受理、任务查询与运行。安装写操作沿用导入 CSRF，只受理并返回任务身份，不在请求里等待安装结果。
 * 拒绝或准入失败不会启动服务。
 */
@RestController
@RequestMapping("/project/import/apps")
@RequiredArgsConstructor
public class ImportStartupPreparationController {
    private final UserService users;
    private final ImportStartupPreparationService preparations;
    private final ImportStartupInstallJobService installs;
    public record DecisionRequest(String ownerId,UUID requestId,UUID planId,String planRevision,Boolean approved){ }
    public record RunRequest(String ownerId,UUID planId,String planRevision){ }
    public record CancelRequest(String ownerId,String revision){ }

    @PostMapping("/{appId}/startup/preparations/install")
    public BaseResponse<ImportStartupInstallJob.View> install(@PathVariable long appId,@RequestBody DecisionRequest input,HttpServletRequest request){
        var user=users.getUserInSession(request);
        require(user.getId(),input==null?null:input.ownerId(),input==null?null:input.planId(),input==null?null:input.planRevision());
        if(input.approved()==null)throw new MyException(ErrorCode.PARAMS_ERROR,"缺少安装决定。");
        if(input.requestId()==null)throw new MyException(ErrorCode.PARAMS_ERROR,"缺少安装请求身份。");
        return ResultUtils.success(installs.submit(user,appId,input.requestId(),input.planId(),input.planRevision(),input.approved()));
    }
    @GetMapping("/{appId}/startup/install-jobs/{jobId}")
    public BaseResponse<ImportStartupInstallJob.View> installJob(@PathVariable long appId,@PathVariable UUID jobId,HttpServletRequest request){
        return ResultUtils.success(installs.get(users.getUserInSession(request),appId,jobId));
    }
    @GetMapping("/{appId}/startup/install-jobs/current")
    public BaseResponse<ImportStartupInstallJob.View> installCurrent(@PathVariable long appId,@RequestParam UUID planId,HttpServletRequest request){
        return ResultUtils.success(installs.current(users.getUserInSession(request),appId,planId));
    }
    @GetMapping("/{appId}/startup/install-jobs/by-request/{requestId}")
    public BaseResponse<ImportStartupInstallJob.View> installByRequest(@PathVariable long appId,@PathVariable UUID requestId,HttpServletRequest request){
        return ResultUtils.success(installs.byRequest(users.getUserInSession(request),appId,requestId));
    }
    @PostMapping("/{appId}/startup/install-jobs/{jobId}/cancel")
    public BaseResponse<ImportStartupInstallJob.View> installCancel(@PathVariable long appId,@PathVariable UUID jobId,@RequestBody CancelRequest input,HttpServletRequest request){
        var user=users.getUserInSession(request);
        if(input==null||input.ownerId()==null||!Long.toString(user.getId()).equals(input.ownerId()))throw new MyException(ErrorCode.CONFLICT_ERROR,"登录状态已变化。");
        if(input.revision()==null||!input.revision().matches("^(0|[1-9]\\d*)$"))throw new MyException(ErrorCode.PARAMS_ERROR,"缺少安装任务版本。");
        return ResultUtils.success(installs.cancel(user,appId,jobId,Long.parseLong(input.revision())));
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
