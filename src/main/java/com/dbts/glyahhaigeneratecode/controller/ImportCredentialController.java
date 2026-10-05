package com.dbts.glyahhaigeneratecode.controller;

import com.dbts.glyahhaigeneratecode.common.BaseResponse;
import com.dbts.glyahhaigeneratecode.common.ResultUtils;
import com.dbts.glyahhaigeneratecode.exception.ErrorCode;
import com.dbts.glyahhaigeneratecode.exception.MyException;
import com.dbts.glyahhaigeneratecode.projectimport.ImportCredentialService;
import com.dbts.glyahhaigeneratecode.projectimport.ImportTask;
import com.dbts.glyahhaigeneratecode.projectimport.ImportTaskService;
import com.dbts.glyahhaigeneratecode.projectimport.ImportSourceService;
import com.dbts.glyahhaigeneratecode.service.UserService;
import com.fasterxml.jackson.annotation.JsonProperty;
import jakarta.servlet.http.HttpServletRequest;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.*;
import org.springframework.http.converter.HttpMessageNotReadableException;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

@RestController
@RequestMapping("/project/import/credentials")
@RequiredArgsConstructor
public class ImportCredentialController {
    private final ImportCredentialService credentials;
    private final ImportTaskService tasks;
    private final ImportSourceService sources;
    private final UserService users;
    public record Create(String ownerId,String provider,String name,@JsonProperty(access=JsonProperty.Access.WRITE_ONLY) String token) {
        @Override public String toString() { return "CreateCredential[secret omitted]"; }
    }
    public record Edit(String ownerId,Long revision,String name,@JsonProperty(access=JsonProperty.Access.WRITE_ONLY) String token) {
        @Override public String toString() { return "EditCredential[secret omitted]"; }
    }
    public record Stop(UUID taskId,String status,String cleanup,String error) { }
    public record Changed(ImportCredentialService.View credential,List<Stop> stops) { }

    @GetMapping public BaseResponse<List<ImportCredentialService.View>> list(HttpServletRequest request) {
        return ResultUtils.success(credentials.list(users.getUserInSession(request).getId()));
    }
    @PostMapping public BaseResponse<ImportCredentialService.View> create(@RequestBody Create input,HttpServletRequest request) {
        long owner=owner(request,input.ownerId());
        return ResultUtils.success(credentials.create(owner,input.provider(),input.name(),input.token()));
    }
    @PutMapping("/{id}/name") public BaseResponse<ImportCredentialService.View> rename(@PathVariable UUID id,@RequestBody Edit input,HttpServletRequest request) {
        return ResultUtils.success(credentials.rename(owner(request,input.ownerId()),id,revision(input),input.name()));
    }
    @PutMapping("/{id}/token") public BaseResponse<Changed> replace(@PathVariable UUID id,@RequestBody Edit input,HttpServletRequest request) {
        long owner=owner(request,input.ownerId());
        return ResultUtils.success(stopped(owner,credentials.replace(owner,id,revision(input),input.token())));
    }
    @DeleteMapping("/{id}") public BaseResponse<Changed> delete(@PathVariable UUID id,@RequestParam String ownerId,@RequestParam long revision,HttpServletRequest request) {
        long owner=owner(request,ownerId);
        return ResultUtils.success(stopped(owner,credentials.delete(owner,id,revision)));
    }

    private Changed stopped(long owner,ImportCredentialService.Mutation mutation) {
        List<Stop> result=new ArrayList<>();
        for(UUID taskId:mutation.revokedTasks()) {
            try {
                var source=sources.cancelIfPresent(owner,taskId);
                if(source!=null) {
                    result.add(new Stop(taskId,source.status().name(),source.cleanup(),source.error()));
                    continue;
                }
                var current=tasks.get(owner,taskId);
                if(current.status()==ImportTask.Status.SUCCEEDED || current.status()==ImportTask.Status.CANCELLED) {
                    result.add(new Stop(taskId,current.status().name(),current.cleanup(),current.error()));
                } else {
                    var cancelled=tasks.cancel(owner,taskId,current.revision());
                    result.add(new Stop(taskId,cancelled.status().name(),cancelled.cleanup(),cancelled.error()));
                }
            } catch (RuntimeException error) {
                // 资格已撤销，但停止未核实；不把请求接受伪装成取消完成。
                result.add(new Stop(taskId,"STOP_FAILED","UNVERIFIED","RESOURCE_STOP_UNCONFIRMED"));
            }
        }
        return new Changed(mutation.credential(),List.copyOf(result));
    }

    private long owner(HttpServletRequest request,String expected) {
        long actual=users.getUserInSession(request).getId();
        if(!Long.toString(actual).equals(expected)) throw new MyException(ErrorCode.CONFLICT_ERROR,"登录状态已变化，请刷新后重试。");
        return actual;
    }
    private static long revision(Edit input) {
        if(input.revision()==null || input.revision()<0) throw new MyException(ErrorCode.PARAMS_ERROR,"请提供当前凭据版本。");
        return input.revision();
    }
    @ExceptionHandler(HttpMessageNotReadableException.class) public BaseResponse<?> invalidBody() {
        return ResultUtils.error(ErrorCode.PARAMS_ERROR.getCode(),"凭据输入无效或超过大小限制。");
    }
}
