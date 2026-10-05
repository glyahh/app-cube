package com.dbts.glyahhaigeneratecode.controller;

import com.dbts.glyahhaigeneratecode.common.BaseResponse;
import com.dbts.glyahhaigeneratecode.common.ResultUtils;
import com.dbts.glyahhaigeneratecode.exception.ErrorCode;
import com.dbts.glyahhaigeneratecode.exception.MyException;
import com.dbts.glyahhaigeneratecode.projectimport.ImportRequestGuard;
import com.dbts.glyahhaigeneratecode.projectimport.ImportTask;
import com.dbts.glyahhaigeneratecode.projectimport.ImportTaskService;
import com.dbts.glyahhaigeneratecode.projectimport.ImportPreferenceService;
import com.dbts.glyahhaigeneratecode.service.UserService;
import com.dbts.glyahhaigeneratecode.service.support.ProjectImportManifest.*;
import jakarta.servlet.http.HttpServletRequest;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.*;
import org.springframework.http.converter.HttpMessageNotReadableException;

import java.io.IOException;
import java.util.List;
import java.util.UUID;

/** 文件夹任务、私有内容接收和资格受控的独立应用提交。 */
@RestController
@RequestMapping("/project/import")
@RequiredArgsConstructor
public class ProjectImportController {
    private final ImportTaskService tasks;
    private final UserService users;
    private final ImportPreferenceService preferences;
    public record CreateRequest(String ownerId, String appName, List<FileEntry> files, Options options) { }
    public record PreferenceRequest(String ownerId, Boolean skipDependencies, Boolean skipBuildOutputs, Boolean includeSensitive) { }
    public record ConfirmRequest(long revision, String contentHash) { }

    @PostMapping("/tasks/{taskId}/commit")
    public BaseResponse<ImportTaskService.CommitResult> commit(@PathVariable UUID taskId, @RequestBody ConfirmRequest input, HttpServletRequest request) {
        return ResultUtils.success(tasks.commit(users.getUserInSession(request).getId(), taskId, input.revision(), input.contentHash()));
    }

    @PostMapping("/tasks/{taskId}/recover")
    public BaseResponse<ImportTaskService.CommitResult> recover(@PathVariable UUID taskId, @RequestBody ConfirmRequest input, HttpServletRequest request) {
        return ResultUtils.success(tasks.recover(users.getUserInSession(request).getId(), taskId, input.revision(), input.contentHash()));
    }

    @GetMapping("/tasks/{taskId}/result")
    public BaseResponse<ImportTaskService.CommitResult> result(@PathVariable UUID taskId, HttpServletRequest request) {
        return ResultUtils.success(tasks.result(users.getUserInSession(request).getId(), taskId));
    }

    @GetMapping("/preferences")
    public BaseResponse<Options> preferences(HttpServletRequest request) {
        return ResultUtils.success(preferences.get(users.getUserInSession(request).getId()));
    }

    @PutMapping("/preferences")
    public BaseResponse<Options> savePreferences(@RequestBody PreferenceRequest input, HttpServletRequest request) {
        long ownerId = users.getUserInSession(request).getId();
        if (input == null || !Long.toString(ownerId).equals(input.ownerId())) {
            throw new MyException(ErrorCode.CONFLICT_ERROR, "登录状态已变化，请刷新后重试。");
        }
        if (input.skipDependencies() == null || input.skipBuildOutputs() == null || input.includeSensitive() == null) {
            throw new MyException(ErrorCode.PARAMS_ERROR, "导入选项不完整。");
        }
        return ResultUtils.success(preferences.save(ownerId, new Options(input.skipDependencies(), input.skipBuildOutputs(), input.includeSensitive())));
    }

    @ExceptionHandler(HttpMessageNotReadableException.class)
    public BaseResponse<?> invalidBody() {
        return ResultUtils.error(ErrorCode.PARAMS_ERROR.getCode(), "导入清单无效或超过大小限制。");
    }

    @GetMapping("/csrf")
    public BaseResponse<String> csrf(HttpServletRequest request) {
        users.getUserInSession(request);
        var session = request.getSession();
        synchronized (session) {
            if (!(session.getAttribute(ImportRequestGuard.CSRF_SESSION_KEY) instanceof String)) {
                session.setAttribute(ImportRequestGuard.CSRF_SESSION_KEY, UUID.randomUUID().toString());
            }
            return ResultUtils.success((String) session.getAttribute(ImportRequestGuard.CSRF_SESSION_KEY));
        }
    }

    @PostMapping("/tasks")
    public BaseResponse<ImportTask.View> create(@RequestBody CreateRequest input, HttpServletRequest request) {
        long ownerId = users.getUserInSession(request).getId();
        if (input == null || !Long.toString(ownerId).equals(input.ownerId())) {
            throw new MyException(ErrorCode.CONFLICT_ERROR, "登录状态已变化，请刷新后重试。");
        }
        return ResultUtils.success(tasks.create(ownerId, input.appName(), input.files(), input.options()));
    }

    @GetMapping("/tasks/{taskId}")
    public BaseResponse<ImportTask.View> get(@PathVariable UUID taskId, HttpServletRequest request) {
        return ResultUtils.success(tasks.get(users.getUserInSession(request).getId(), taskId));
    }

    @PutMapping(value = "/tasks/{taskId}/files/{index}", consumes = "application/octet-stream")
    public BaseResponse<ImportTask.View> upload(@PathVariable UUID taskId, @PathVariable int index,
                                               @RequestParam long revision, HttpServletRequest request) throws IOException {
        long ownerId = users.getUserInSession(request).getId();
        return ResultUtils.success(tasks.upload(ownerId, taskId, revision, index, request.getInputStream()));
    }

    @PostMapping("/tasks/{taskId}/cancel")
    public BaseResponse<ImportTask.View> cancel(@PathVariable UUID taskId, @RequestParam long revision, HttpServletRequest request) {
        return ResultUtils.success(tasks.cancel(users.getUserInSession(request).getId(), taskId, revision));
    }
}
