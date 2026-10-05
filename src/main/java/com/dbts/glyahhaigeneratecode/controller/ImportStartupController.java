package com.dbts.glyahhaigeneratecode.controller;

import com.dbts.glyahhaigeneratecode.common.BaseResponse;
import com.dbts.glyahhaigeneratecode.common.ResultUtils;
import com.dbts.glyahhaigeneratecode.exception.ErrorCode;
import com.dbts.glyahhaigeneratecode.exception.MyException;
import com.dbts.glyahhaigeneratecode.projectimport.ImportStartupService;
import com.dbts.glyahhaigeneratecode.projectimport.ImportStartupConfiguration;
import com.dbts.glyahhaigeneratecode.projectimport.ImportStartupScan;
import com.dbts.glyahhaigeneratecode.projectimport.ImportStartupScanService;
import com.dbts.glyahhaigeneratecode.service.UserService;
import jakarta.servlet.http.HttpServletRequest;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.*;

/** 同步只读检查与已完成证据恢复；不是执行计划。写请求沿用导入来源/CSRF 门禁。 */
@RestController
@RequestMapping("/project/import/apps")
@RequiredArgsConstructor
public class ImportStartupController {
    private final UserService users;
    private final ImportStartupService startup;
    private final ImportStartupScanService scans;
    public record InspectionRequest(String ownerId,String scope){ }
    public record ConfigurationRequest(String ownerId,Long configRevision,Long contextEpoch,String scope,java.util.List<ImportStartupConfiguration.Manual> services){ }
    public record ScanRequest(String ownerId,java.util.UUID requestId,Long configRevision,Long contextEpoch,String scope){ }
    public record CancelRequest(String ownerId,Long revision){ }

    @PostMapping("/{appId}/startup/scans")
    public BaseResponse<ImportStartupScan.View> createScan(@PathVariable long appId,@RequestBody ScanRequest input,HttpServletRequest request){
        var user=users.getUserInSession(request);
        if(input==null||!Long.toString(user.getId()).equals(input.ownerId()))throw new MyException(ErrorCode.CONFLICT_ERROR,"登录状态已变化。");
        if(input.requestId()==null||input.configRevision()==null||input.contextEpoch()==null)throw new MyException(ErrorCode.PARAMS_ERROR,"缺少任务身份或版本。");
        return ResultUtils.success(scans.create(user,appId,input.requestId(),input.configRevision(),input.contextEpoch(),input.scope()));
    }
    @GetMapping("/{appId}/startup/scans/{scanId}")
    public BaseResponse<ImportStartupScan.View> getScan(@PathVariable long appId,@PathVariable java.util.UUID scanId,HttpServletRequest request){
        return ResultUtils.success(scans.get(users.getUserInSession(request),appId,scanId));
    }
    @GetMapping("/{appId}/startup/scan-requests/{requestId}")
    public BaseResponse<ImportStartupScan.View> getRequest(@PathVariable long appId,@PathVariable java.util.UUID requestId,HttpServletRequest request){
        return ResultUtils.success(scans.byRequest(users.getUserInSession(request),appId,requestId));
    }
    @PostMapping("/{appId}/startup/scans/{scanId}/cancel")
    public BaseResponse<ImportStartupScan.View> cancelScan(@PathVariable long appId,@PathVariable java.util.UUID scanId,@RequestBody CancelRequest input,HttpServletRequest request){
        var user=users.getUserInSession(request);
        if(input==null||!Long.toString(user.getId()).equals(input.ownerId()))throw new MyException(ErrorCode.CONFLICT_ERROR,"登录状态已变化。");
        if(input.revision()==null||input.revision()<0)throw new MyException(ErrorCode.PARAMS_ERROR,"缺少有效任务版本。");
        return ResultUtils.success(scans.cancel(user,appId,scanId,input.revision()));
    }
    @PostMapping("/{appId}/startup/scans/{scanId}/reconcile")
    public BaseResponse<ImportStartupScan.View> reconcileScan(@PathVariable long appId,@PathVariable java.util.UUID scanId,@RequestBody CancelRequest input,HttpServletRequest request){
        var user=users.getUserInSession(request);
        if(input==null||!Long.toString(user.getId()).equals(input.ownerId()))throw new MyException(ErrorCode.CONFLICT_ERROR,"登录状态已变化。");
        if(input.revision()==null||input.revision()<0)throw new MyException(ErrorCode.PARAMS_ERROR,"缺少有效任务版本。");
        return ResultUtils.success(scans.reconcile(user,appId,scanId,input.revision()));
    }

    @GetMapping("/{appId}/startup/inspections/{scanId}")
    public BaseResponse<ImportStartupService.InspectionRecord> inspection(@PathVariable long appId,@PathVariable java.util.UUID scanId,HttpServletRequest request){
        return ResultUtils.success(startup.inspection(users.getUserInSession(request),appId,scanId));
    }

    @GetMapping("/{appId}/startup/configuration")
    public BaseResponse<ImportStartupConfiguration.View> configuration(@PathVariable long appId,HttpServletRequest request){
        return ResultUtils.success(startup.configuration(users.getUserInSession(request),appId));
    }

    @PutMapping("/{appId}/startup/configuration")
    public BaseResponse<ImportStartupConfiguration.View> saveConfiguration(@PathVariable long appId,@RequestBody ConfigurationRequest input,HttpServletRequest request){
        var user=users.getUserInSession(request);
        if(input==null||!Long.toString(user.getId()).equals(input.ownerId()))throw new MyException(ErrorCode.CONFLICT_ERROR,"登录状态已变化，请刷新后重试。");
        if(input.configRevision()==null||input.contextEpoch()==null||input.configRevision()<0||input.contextEpoch()<0)throw new MyException(ErrorCode.PARAMS_ERROR,"缺少有效配置版本，请重新读取。");
        return ResultUtils.success(startup.saveConfiguration(user,appId,input.configRevision(),input.contextEpoch(),input.scope(),input.services()));
    }

    @PostMapping("/{appId}/startup/inspection")
    public BaseResponse<ImportStartupService.Inspection> inspect(@PathVariable long appId,@RequestBody InspectionRequest input,HttpServletRequest request){
        var user=users.getUserInSession(request);
        if(input==null||!Long.toString(user.getId()).equals(input.ownerId()))throw new MyException(ErrorCode.CONFLICT_ERROR,"登录状态已变化，请刷新后重试。");
        return ResultUtils.success(scans.inspect(user,appId,input.scope()));
    }
}
