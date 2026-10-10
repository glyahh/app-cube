package com.dbts.glyahhaigeneratecode.controller;

import com.dbts.glyahhaigeneratecode.common.BaseResponse;
import com.dbts.glyahhaigeneratecode.common.ResultUtils;
import com.dbts.glyahhaigeneratecode.exception.ErrorCode;
import com.dbts.glyahhaigeneratecode.exception.MyException;
import com.dbts.glyahhaigeneratecode.projectimport.ImportExecutionService;
import com.dbts.glyahhaigeneratecode.projectimport.ImportExecutionLedger;
import com.dbts.glyahhaigeneratecode.projectimport.ImportExecutionRunner;
import com.dbts.glyahhaigeneratecode.service.UserService;
import jakarta.servlet.http.HttpServletRequest;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.*;
import java.util.UUID;

/** 导入执行的用户查询、停止、日志和应用级对账入口；所有身份来自当前登录用户与冻结计划。 */
@RestController
@RequestMapping("/project/import/apps")
@RequiredArgsConstructor
public class ImportStartupExecutionController {
    private final UserService users;
    private final ImportExecutionService executions;

    public record ExecutionRequest(String ownerId,UUID planId){ }
    public record LogView(UUID executionId,String state,String content,boolean truncated){ }
    /** 雪花 ID 以字符串传输，避免前端 JavaScript 精度丢失。 */
    public record ExecutionView(UUID id,String ownerId,String appId,UUID planId,String planRevision,String contentHash,String directory,
                                String slot,String network,String state,String reason,Integer exitCode,long outputBytes,String cleanup,
                                long revision,java.time.Instant createdAt,java.time.Instant updatedAt,java.time.Instant finishedAt,String containerId){
        static ExecutionView of(ImportExecutionLedger.Detail detail){
            return new ExecutionView(detail.id(),Long.toString(detail.ownerId()),Long.toString(detail.appId()),detail.planId(),detail.planRevision(),
                detail.contentHash(),detail.directory(),detail.slot(),detail.network(),detail.state(),detail.reason(),detail.exitCode(),detail.outputBytes(),
                detail.cleanup(),detail.revision(),detail.createdAt(),detail.updatedAt(),detail.finishedAt(),detail.containerId());
        }
    }

    @GetMapping("/{appId}/startup/executions/{executionId}")
    public BaseResponse<ExecutionView> detail(@PathVariable long appId,@PathVariable UUID executionId,
                                                               @RequestParam UUID planId,HttpServletRequest request){
        var user=users.getUserInSession(request);
        var detail=executions.detail(user,appId,planId,executionId);
        if(detail==null)throw new MyException(ErrorCode.NOT_FOUND_ERROR,"执行记录不存在。");
        return ResultUtils.success(ExecutionView.of(detail));
    }

    @GetMapping("/{appId}/startup/executions/{executionId}/log")
    public BaseResponse<LogView> log(@PathVariable long appId,@PathVariable UUID executionId,@RequestParam UUID planId,
                                     @RequestParam(defaultValue="262144") int maxBytes,HttpServletRequest request){
        var user=users.getUserInSession(request);
        var detail=executions.detail(user,appId,planId,executionId);
        if(detail==null)throw new MyException(ErrorCode.NOT_FOUND_ERROR,"执行记录不存在。");
        if(maxBytes<1||maxBytes>1<<20)throw new MyException(ErrorCode.PARAMS_ERROR,"日志长度无效。");
        var content=executions.log(user,appId,planId,executionId,maxBytes);
        if(content==null)throw new MyException(ErrorCode.NOT_FOUND_ERROR,"执行记录不存在。");
        return ResultUtils.success(new LogView(executionId,detail.state(),content,content.endsWith("[日志已截断]")));
    }

    @PostMapping("/{appId}/startup/executions/{executionId}/stop")
    public BaseResponse<ExecutionView> stop(@PathVariable long appId,@PathVariable UUID executionId,
                                                            @RequestBody ExecutionRequest input,HttpServletRequest request){
        var user=users.getUserInSession(request);
        require(user.getId(),input);
        var before=executions.detail(user,appId,input.planId(),executionId);
        if(before==null)throw new MyException(ErrorCode.NOT_FOUND_ERROR,"执行记录不存在。");
        executions.stop(executionId);
        var after=executions.detail(user,appId,input.planId(),executionId);
        if(after==null)throw new MyException(ErrorCode.NOT_FOUND_ERROR,"执行记录不存在。");
        return ResultUtils.success(ExecutionView.of(after));
    }

    @PostMapping("/{appId}/startup/preparations/reconcile")
    public BaseResponse<ImportExecutionRunner.Reconciled> reconcile(@PathVariable long appId,
                                                                     @RequestBody ExecutionRequest input,HttpServletRequest request){
        var user=users.getUserInSession(request);
        require(user.getId(),input);
        return ResultUtils.success(executions.reconcile(user,appId,input.planId()));
    }

    private static void require(long owner,ExecutionRequest input){
        if(input==null||input.ownerId()==null||!Long.toString(owner).equals(input.ownerId()))
            throw new MyException(ErrorCode.CONFLICT_ERROR,"登录状态已变化，请刷新后重试。");
        if(input.planId()==null)throw new MyException(ErrorCode.PARAMS_ERROR,"缺少计划身份。");
    }
}
