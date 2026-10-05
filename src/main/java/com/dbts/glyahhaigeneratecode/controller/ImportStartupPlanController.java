package com.dbts.glyahhaigeneratecode.controller;

import com.dbts.glyahhaigeneratecode.common.BaseResponse;
import com.dbts.glyahhaigeneratecode.common.ResultUtils;
import com.dbts.glyahhaigeneratecode.exception.ErrorCode;
import com.dbts.glyahhaigeneratecode.exception.MyException;
import com.dbts.glyahhaigeneratecode.projectimport.ImportStartupPlanService;
import com.dbts.glyahhaigeneratecode.service.UserService;
import jakarta.servlet.http.HttpServletRequest;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.*;
import java.util.List;
import java.util.UUID;

/** 冻结运行计划的生成、找回与确认；确认不是安装或运行，执行入口仍关闭。写请求沿用导入来源/CSRF 门禁。 */
@RestController
@RequestMapping("/project/import/apps")
@RequiredArgsConstructor
public class ImportStartupPlanController {
    private final UserService users;
    private final ImportStartupPlanService plans;
    public record PlanRequest(String ownerId,UUID scanId,List<String> serviceIds){ }
    public record ConfirmRequest(String ownerId,String planRevision,Boolean aggregateAcknowledged){ }

    @PostMapping("/{appId}/startup/plans")
    public BaseResponse<ImportStartupPlanService.View> create(@PathVariable long appId,@RequestBody PlanRequest input,HttpServletRequest request){
        var user=users.getUserInSession(request);
        if(input==null||!Long.toString(user.getId()).equals(input.ownerId()))throw new MyException(ErrorCode.CONFLICT_ERROR,"登录状态已变化。");
        if(input.scanId()==null||input.serviceIds()==null||input.serviceIds().isEmpty())throw new MyException(ErrorCode.PARAMS_ERROR,"缺少识别记录或所选服务。");
        return ResultUtils.success(plans.create(user,appId,input.scanId(),input.serviceIds()));
    }
    @GetMapping("/{appId}/startup/plans/current")
    public BaseResponse<ImportStartupPlanService.View> current(@PathVariable long appId,HttpServletRequest request){
        return ResultUtils.success(plans.current(users.getUserInSession(request),appId));
    }
    @GetMapping("/{appId}/startup/plans/{planId}")
    public BaseResponse<ImportStartupPlanService.View> get(@PathVariable long appId,@PathVariable UUID planId,HttpServletRequest request){
        return ResultUtils.success(plans.get(users.getUserInSession(request),appId,planId));
    }
    @PostMapping("/{appId}/startup/plans/{planId}/confirm")
    public BaseResponse<ImportStartupPlanService.View> confirm(@PathVariable long appId,@PathVariable UUID planId,@RequestBody ConfirmRequest input,HttpServletRequest request){
        var user=users.getUserInSession(request);
        if(input==null||!Long.toString(user.getId()).equals(input.ownerId()))throw new MyException(ErrorCode.CONFLICT_ERROR,"登录状态已变化。");
        if(input.planRevision()==null||input.aggregateAcknowledged()==null)throw new MyException(ErrorCode.PARAMS_ERROR,"缺少计划版本或聚合脚本确认。");
        return ResultUtils.success(plans.confirm(user,appId,planId,input.planRevision(),input.aggregateAcknowledged()));
    }
}
