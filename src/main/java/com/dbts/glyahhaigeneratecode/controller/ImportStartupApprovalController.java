package com.dbts.glyahhaigeneratecode.controller;

import com.dbts.glyahhaigeneratecode.common.BaseResponse;
import com.dbts.glyahhaigeneratecode.common.ResultUtils;
import com.dbts.glyahhaigeneratecode.exception.ErrorCode;
import com.dbts.glyahhaigeneratecode.exception.MyException;
import com.dbts.glyahhaigeneratecode.projectimport.ImportStartupApprovalService;
import com.dbts.glyahhaigeneratecode.service.UserService;
import jakarta.servlet.http.HttpServletRequest;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.*;
import java.util.List;
import java.util.UUID;

/** 识别补充的用户批准/拒绝与找回；不发布候选，不是安装或运行授权。写请求沿用导入来源/CSRF 门禁。 */
@RestController
@RequestMapping("/project/import/apps")
@RequiredArgsConstructor
public class ImportStartupApprovalController {
    private final UserService users;
    private final ImportStartupApprovalService approvals;
    public record DecisionRequest(String ownerId,UUID requestId,UUID scanId,List<String> directories,Boolean approved){ }
    public record RevisionRequest(String ownerId,Long revision){ }

    @PostMapping("/{appId}/startup/approvals")
    public BaseResponse<ImportStartupApprovalService.View> decide(@PathVariable long appId,@RequestBody DecisionRequest input,HttpServletRequest request){
        var user=users.getUserInSession(request);
        if(input==null||!Long.toString(user.getId()).equals(input.ownerId()))throw new MyException(ErrorCode.CONFLICT_ERROR,"登录状态已变化。");
        if(input.requestId()==null||input.scanId()==null||input.approved()==null||input.directories()==null||input.directories().isEmpty())
            throw new MyException(ErrorCode.PARAMS_ERROR,"缺少批准身份、识别记录、目录或决定。");
        return ResultUtils.success(approvals.decide(user,appId,input.requestId(),input.scanId(),input.directories(),input.approved()));
    }
    @GetMapping("/{appId}/startup/approvals/{approvalId}")
    public BaseResponse<ImportStartupApprovalService.View> get(@PathVariable long appId,@PathVariable UUID approvalId,HttpServletRequest request){
        return ResultUtils.success(approvals.get(users.getUserInSession(request),appId,approvalId));
    }
    @GetMapping("/{appId}/startup/approval-requests/{requestId}")
    public BaseResponse<ImportStartupApprovalService.View> byRequest(@PathVariable long appId,@PathVariable UUID requestId,HttpServletRequest request){
        return ResultUtils.success(approvals.byRequest(users.getUserInSession(request),appId,requestId));
    }
    @PostMapping("/{appId}/startup/approvals/{approvalId}/cancel")
    public BaseResponse<ImportStartupApprovalService.View> cancel(@PathVariable long appId,@PathVariable UUID approvalId,@RequestBody RevisionRequest input,HttpServletRequest request){
        var user=users.getUserInSession(request);requireRevision(user.getId(),input);
        return ResultUtils.success(approvals.cancel(user,appId,approvalId,input.revision()));
    }
    @PostMapping("/{appId}/startup/approvals/{approvalId}/reconcile")
    public BaseResponse<ImportStartupApprovalService.View> reconcile(@PathVariable long appId,@PathVariable UUID approvalId,@RequestBody RevisionRequest input,HttpServletRequest request){
        var user=users.getUserInSession(request);requireRevision(user.getId(),input);
        return ResultUtils.success(approvals.reconcile(user,appId,approvalId,input.revision()));
    }
    private static void requireRevision(Long owner,RevisionRequest input){
        if(input==null||!Long.toString(owner).equals(input.ownerId()))throw new MyException(ErrorCode.CONFLICT_ERROR,"登录状态已变化。");
        if(input.revision()==null||input.revision()<0)throw new MyException(ErrorCode.PARAMS_ERROR,"缺少有效批准版本。");
    }
}
