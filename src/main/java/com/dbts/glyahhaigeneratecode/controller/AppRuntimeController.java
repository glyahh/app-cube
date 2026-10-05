package com.dbts.glyahhaigeneratecode.controller;

import com.dbts.glyahhaigeneratecode.common.BaseResponse;
import com.dbts.glyahhaigeneratecode.common.ResultUtils;
import com.dbts.glyahhaigeneratecode.exception.ErrorCode;
import com.dbts.glyahhaigeneratecode.exception.ThrowUtils;
import com.dbts.glyahhaigeneratecode.model.Entity.App;
import com.dbts.glyahhaigeneratecode.model.Entity.AppRuntimeIncident;
import com.dbts.glyahhaigeneratecode.model.Entity.User;
import com.dbts.glyahhaigeneratecode.model.VO.RuntimeStatusVO;
import com.dbts.glyahhaigeneratecode.runtime.RuntimeIncidentDetector;
import com.dbts.glyahhaigeneratecode.runtime.RuntimeIncidentService;
import com.dbts.glyahhaigeneratecode.runtime.RuntimeLogService;
import com.dbts.glyahhaigeneratecode.runtime.RuntimeMetricsStore;
import com.dbts.glyahhaigeneratecode.runtime.RuntimeOrchestrator;
import com.dbts.glyahhaigeneratecode.runtime.RuntimeTrafficStats;
import com.dbts.glyahhaigeneratecode.service.AppService;
import com.dbts.glyahhaigeneratecode.service.UserService;
import com.fasterxml.jackson.databind.node.LongNode;
import jakarta.servlet.http.HttpServletRequest;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.web.bind.annotation.*;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 应用运行时 控制层。
 *
 * <p>四个动作：启动、停止、心跳、查状态；另有日志与指标两个只读接口。
 * 所有接口都按「本人或管理员」校验归属，且不接收客户端传来的路径 ——
 * 工作区目录一律由 appId 推导。
 */
@RestController
@RequestMapping("/app")
@RequiredArgsConstructor
@Slf4j
public class AppRuntimeController {

    private final RuntimeOrchestrator orchestrator;
    private final RuntimeLogService logService;
    private final RuntimeMetricsStore metricsStore;
    private final RuntimeTrafficStats trafficStats;
    private final RuntimeIncidentService incidentService;
    private final AppService appService;
    private final UserService userService;
    private final com.dbts.glyahhaigeneratecode.projectimport.ImportWorkspaceGate importGate;

    /**
     * 【用户】启动应用运行
     *
     * @param appId 应用 id
     * @return 启动后的运行状态；已在运行则直接返回当前状态（不重复创建进程）
     */
    @PostMapping("/{appId}/runtime/start")
    public BaseResponse<RuntimeStatusVO> start(@PathVariable Long appId, HttpServletRequest request) {
        User loginUser = userService.getUserInSession(request);
        Path workspace = appService.resolveWorkspaceDir(loginUser, appId);
        importGate.requireLegacyExecutionAllowed(appId);
        return ResultUtils.success(orchestrator.start(appId, loginUser.getId(), workspace));
    }

    /**
     * 【用户】停止应用运行
     *
     * @return 是否确实停掉了一个运行中的容器
     */
    @PostMapping("/{appId}/runtime/stop")
    public BaseResponse<Boolean> stop(@PathVariable Long appId, HttpServletRequest request) {
        User loginUser = userService.getUserInSession(request);
        Path workspace = appService.resolveWorkspaceDir(loginUser, appId);
        return ResultUtils.success(orchestrator.stop(appId, workspace));
    }

    /**
     * 【用户】运行心跳
     *
     * <p>页面在查看该应用时定期调用。服务端据此判断「还有人在看」——
     * 最后一个页面离开后按配置保留一段时间再自动停止。
     */
    @PostMapping("/{appId}/runtime/heartbeat")
    public BaseResponse<Boolean> heartbeat(@PathVariable Long appId, HttpServletRequest request) {
        User loginUser = userService.getUserInSession(request);
        // 归属校验走同一入口，避免心跳成为绕过鉴权的探针
        appService.resolveWorkspaceDir(loginUser, appId);
        orchestrator.heartbeat(appId);
        return ResultUtils.success(true);
    }

    /**
     * 【用户】查询运行状态
     *
     * <p>状态以容器实际状态为准：后端重启或容器被外部停掉后，这里不会返回虚假的「运行中」。
     */
    @GetMapping("/{appId}/runtime/status")
    public BaseResponse<RuntimeStatusVO> status(@PathVariable Long appId, HttpServletRequest request) {
        User loginUser = userService.getUserInSession(request);
        Path workspace = appService.resolveWorkspaceDir(loginUser, appId);
        return ResultUtils.success(orchestrator.status(appId, workspace));
    }

    /**
     * 【用户】读取运行日志
     *
     * @param runNo    启动编号；省略表示最近一次运行
     * @param maxBytes 最多返回的字节数
     * @return 日志内容与截断标记
     */
    @GetMapping("/{appId}/runtime/logs")
    public BaseResponse<Map<String, Object>> logs(@PathVariable Long appId,
                                                  @RequestParam(required = false) Integer runNo,
                                                  @RequestParam(required = false) Integer maxBytes,
                                                  HttpServletRequest request) {
        User loginUser = userService.getUserInSession(request);
        Path workspace = appService.resolveWorkspaceDir(loginUser, appId);

        Integer targetRun = runNo;
        if (targetRun == null) {
            RuntimeStatusVO status = orchestrator.status(appId, workspace);
            targetRun = status.getRunNo();
        }
        if (targetRun == null) {
            return ResultUtils.success(Map.of(
                    "runNo", 0,
                    "content", "",
                    "truncated", false,
                    "totalBytes", 0L));
        }

        RuntimeLogService.LogSlice slice = logService.read(appId, targetRun, maxBytes == null ? 0 : maxBytes);
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("runNo", targetRun);
        body.put("content", slice.content());
        body.put("truncated", slice.truncated());
        body.put("totalBytes", slice.totalBytes());
        return ResultUtils.success(body);
    }

    /**
     * 【用户】列出运行异常事件
     *
     * @param runNo 启动编号；省略表示最近一次运行
     * @param onlyOpen 只返回待处理的（弹窗用）
     */
    @GetMapping("/{appId}/runtime/incidents")
    public BaseResponse<List<AppRuntimeIncident>> incidents(
            @PathVariable Long appId,
            @RequestParam(required = false) Integer runNo,
            @RequestParam(required = false, defaultValue = "false") Boolean onlyOpen,
            HttpServletRequest request) {
        User loginUser = userService.getUserInSession(request);
        appService.resolveWorkspaceDir(loginUser, appId);
        return ResultUtils.success(Boolean.TRUE.equals(onlyOpen)
                ? incidentService.listOpen(appId)
                : incidentService.listByRun(appId, runNo));
    }

    /**
     * 【用户】关闭某条异常提示
     *
     * <p>只是不再打扰，记录仍然保留 —— 日志里还能查到。
     */
    @PostMapping("/{appId}/runtime/incidents/{incidentId}/dismiss")
    public BaseResponse<Boolean> dismissIncident(@PathVariable Long appId,
                                                 @PathVariable Long incidentId,
                                                 HttpServletRequest request) {
        User loginUser = userService.getUserInSession(request);
        appService.resolveWorkspaceDir(loginUser, appId);
        AppRuntimeIncident incident = incidentService.byId(incidentId);
        ThrowUtils.throwIf(incident == null || !appId.equals(incident.getAppId()),
                ErrorCode.NOT_FOUND_ERROR, "异常记录不存在");
        return ResultUtils.success(incidentService.dismiss(incidentId));
    }

    /**
     * 【用户】同意修复：生成脱敏诊断提示词
     *
     * <p>返回的提示词由前端通过现有生成事件链发送 —— 走的是用户发消息那条路，
     * 不另开执行通道。这样「修复」在原对话里可见，也不会绕过项目的写锁串行。
     *
     * <p>同时把事件标记为 REPAIRING，重复点击不会重复进入修复流程。
     */
    @PostMapping("/{appId}/runtime/incidents/{incidentId}/repair")
    public BaseResponse<Map<String, Object>> prepareRepair(@PathVariable Long appId,
                                                           @PathVariable Long incidentId,
                                                           HttpServletRequest request) {
        User loginUser = userService.getUserInSession(request);
        appService.resolveWorkspaceDir(loginUser, appId);

        AppRuntimeIncident incident = incidentService.byId(incidentId);
        ThrowUtils.throwIf(incident == null || !appId.equals(incident.getAppId()),
                ErrorCode.NOT_FOUND_ERROR, "异常记录不存在");

        // 已经在修复中的不重复受理：重复提交不该启动第二个任务
        ThrowUtils.throwIf(RuntimeIncidentDetector.STATUS_REPAIRING.equals(incident.getStatus()),
                ErrorCode.OPERATION_ERROR, "该问题已在修复中");

        App app = appService.getById(appId);
        String prompt = incidentService.buildRepairPrompt(incident, app == null ? null : app.getAppName());
        incidentService.markRepairing(incidentId);

        Map<String, Object> body = new LinkedHashMap<>();
        body.put("incidentId", incidentId);
        body.put("prompt", prompt);
        body.put("kind", incident.getKind());
        return ResultUtils.success(body);
    }

    /**
     * 【用户】读取运行指标（CPU / 内存时间序列）
     *
     * <p>只返回窗口内的采样点。采集失败的点其字段为 null ——
     * 前端据此显示「暂无数据」，不会把缺失当成 0。
     */
    @GetMapping("/{appId}/runtime/metrics")
    public BaseResponse<Map<String, Object>> metrics(@PathVariable Long appId, HttpServletRequest request) {
        User loginUser = userService.getUserInSession(request);
        appService.resolveWorkspaceDir(loginUser, appId);

        List<Map<String, Object>> points = new ArrayList<>();
        for (RuntimeMetricsStore.Sample sample : metricsStore.window(appId)) {
            Map<String, Object> point = new LinkedHashMap<>();
            point.put("at", sample.at().toString());
            point.put("cpuPercent", sample.cpuPercent());
            // 包成 LongNode 是为了绕开 JsonConfig 里那条全局 Long→String 规则：
            // 那条规则本意是防雪花 id 在前端丢精度，但它会把这个字节数也变成字符串，
            // 前端 Number.isFinite("13788774") 为 false，卡片显示「暂无数据」而曲线还在画。
            point.put("memoryBytes",
                    sample.memoryBytes() == null ? null : LongNode.valueOf(sample.memoryBytes()));
            point.put("memoryLimitBytes",
                    sample.memoryLimitBytes() == null ? null : LongNode.valueOf(sample.memoryLimitBytes()));
            points.add(point);
        }

        Map<String, Object> body = new LinkedHashMap<>();
        body.put("points", points);
        body.put("windowMinutes", 30);

        // QPS 与错误率来自网关计数。没有流量时给 null —— 前端显示「暂无数据」而不是 0。
        RuntimeTrafficStats.Snapshot traffic = trafficStats.snapshot(appId, 60);
        body.put("qps", traffic == null ? null : traffic.qps());
        body.put("errorRatePercent", traffic == null ? null : traffic.errorRatePercent());
        body.put("sampleRequests", traffic == null ? 0L : traffic.sampleRequests());
        return ResultUtils.success(body);
    }
}
