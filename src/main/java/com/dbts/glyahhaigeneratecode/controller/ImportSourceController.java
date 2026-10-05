package com.dbts.glyahhaigeneratecode.controller;

import com.dbts.glyahhaigeneratecode.common.BaseResponse;
import com.dbts.glyahhaigeneratecode.common.ResultUtils;
import com.dbts.glyahhaigeneratecode.exception.ErrorCode;
import com.dbts.glyahhaigeneratecode.exception.MyException;
import com.dbts.glyahhaigeneratecode.projectimport.ImportSourceService;
import com.dbts.glyahhaigeneratecode.projectimport.ImportSourceTask;
import com.dbts.glyahhaigeneratecode.service.UserService;
import com.dbts.glyahhaigeneratecode.service.support.ProjectImportManifest.Options;
import jakarta.servlet.http.HttpServletRequest;
import lombok.RequiredArgsConstructor;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.web.bind.annotation.*;
import java.util.UUID;

/** 来源准备不等于导入成功；完整清单和提交仍由同 UUID 主任务负责。 */
@RestController
@RequestMapping("/project/import/git/tasks")
@RequiredArgsConstructor
public class ImportSourceController {
    private final ImportSourceService sources;
    private final UserService users;
    public record Create(String ownerId,String appName,String provider,String repositoryUrl,UUID credentialId,Long credentialVersion,Options options,UUID requestId){ }
    public record Select(String ownerId,Long revision,String branch){ }
    public record Rebuild(String ownerId,Long revision,String appName,Options options){ }
    @PostMapping public BaseResponse<ImportSourceTask.View> create(@RequestBody Create input,HttpServletRequest request) {
        long owner=users.getUserInSession(request).getId();
        if(!Long.toString(owner).equals(input.ownerId()))throw new MyException(ErrorCode.CONFLICT_ERROR,"登录状态已变化，请刷新后重试。");
        return ResultUtils.success(input.requestId()==null
                ?sources.prepare(owner,input.appName(),input.provider(),input.repositoryUrl(),input.options(),input.credentialId(),input.credentialVersion())
                :sources.prepare(owner,input.appName(),input.provider(),input.repositoryUrl(),input.options(),input.credentialId(),input.credentialVersion(),input.requestId()));
    }
    @GetMapping("/{id}") public BaseResponse<ImportSourceTask.View> get(@PathVariable UUID id,HttpServletRequest request) {
        return ResultUtils.success(sources.get(users.getUserInSession(request).getId(),id));
    }
    @GetMapping("/request/{requestId}") public BaseResponse<ImportSourceTask.View> getByRequest(@PathVariable UUID requestId,HttpServletRequest request) {
        return ResultUtils.success(sources.getByRequest(users.getUserInSession(request).getId(),requestId));
    }
    @PostMapping("/{id}/branch") public BaseResponse<ImportSourceTask.View> select(@PathVariable UUID id,@RequestBody Select input,HttpServletRequest request) {
        long owner=users.getUserInSession(request).getId();
        if(!Long.toString(owner).equals(input.ownerId()))throw new MyException(ErrorCode.CONFLICT_ERROR,"登录状态已变化，请刷新后重试。");
        if(input.revision()==null || input.revision()<0)throw new MyException(ErrorCode.PARAMS_ERROR,"来源版本不完整。");
        return ResultUtils.success(sources.selectBranch(owner,id,input.revision(),input.branch()));
    }
    @PostMapping("/{id}/cancel") public BaseResponse<ImportSourceTask.View> cancel(@PathVariable UUID id,@RequestParam long revision,HttpServletRequest request) {
        return ResultUtils.success(sources.cancel(users.getUserInSession(request).getId(),id,revision));
    }
    @PostMapping("/{id}/range") public BaseResponse<ImportSourceTask.View> rebuild(@PathVariable UUID id,@RequestBody Rebuild input,HttpServletRequest request) {
        long owner=users.getUserInSession(request).getId();
        if(!Long.toString(owner).equals(input.ownerId()))throw new MyException(ErrorCode.CONFLICT_ERROR,"登录状态已变化，请刷新后重试。");
        if(input.revision()==null || input.revision()<0)throw new MyException(ErrorCode.PARAMS_ERROR,"来源版本不完整。");
        return ResultUtils.success(sources.rebuild(owner,id,input.revision(),input.appName(),input.options()));
    }
    @ExceptionHandler(HttpMessageNotReadableException.class) public BaseResponse<?> invalidBody() {
        return ResultUtils.error(ErrorCode.PARAMS_ERROR.getCode(),"来源参数无效或超过大小限制。");
    }
}
