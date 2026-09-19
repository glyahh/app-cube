package com.dbts.glyahhaigeneratecode.runtime;

import com.dbts.glyahhaigeneratecode.mapper.AppRuntimeIncidentMapper;
import com.dbts.glyahhaigeneratecode.model.Entity.AppRuntime;
import com.dbts.glyahhaigeneratecode.model.Entity.AppRuntimeIncident;
import com.mybatisflex.core.query.QueryWrapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;

/**
 * 运行异常事件的读写与状态流转。
 *
 * <p>三种状态变化的语义要分清：
 * <ul>
 *   <li>用户关掉弹窗 → {@code DISMISSED}：只是不再打扰，**记录不删**（Plan 明确要求）。</li>
 *   <li>用户点「让 AI 修复」→ {@code REPAIRING}：修复期间这条不再反复提示。</li>
 *   <li>修复那一轮跑完 → {@code REPAIRED}；但**不自动重启应用**，
 *       要等用户自己再点一次运行（Plan 明确）。</li>
 * </ul>
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class RuntimeIncidentService {

    private final AppRuntimeIncidentMapper incidentMapper;
    private final RuntimeIncidentDetector detector;

    /** 记录一次异常退出。 */
    public List<AppRuntimeIncident> detectOnExit(AppRuntime runtime, Integer exitCode, long durationSeconds,
                                                 String logs, String command, String sourceVersion) {
        AppRuntimeIncident incident = detector.recordAbnormalExit(
                runtime.getAppId(), runtime.getUserId(), runtime.getRunNo(),
                exitCode, durationSeconds, logs, command, sourceVersion);
        return incident == null ? List.of() : List.of(incident);
    }

    /** 扫描日志里的明确错误。 */
    public List<AppRuntimeIncident> scanLogs(AppRuntime runtime, String logs, String command,
                                             String sourceVersion, boolean startupPhase) {
        return detector.scanLogsForErrors(runtime.getAppId(), runtime.getUserId(), runtime.getRunNo(),
                logs, command, sourceVersion, startupPhase);
    }

    /** 某次运行的全部事件，新的在前。 */
    public List<AppRuntimeIncident> listByRun(Long appId, Integer runNo) {
        QueryWrapper query = QueryWrapper.create().eq("app_id", appId);
        if (runNo != null) {
            query.eq("run_no", runNo);
        }
        query.orderBy("id", false).limit(50);
        return incidentMapper.selectListByQuery(query);
    }

    /** 待用户处理的事件（弹窗据此展示）。 */
    public List<AppRuntimeIncident> listOpen(Long appId) {
        return incidentMapper.selectListByQuery(QueryWrapper.create()
                .eq("app_id", appId)
                .eq("status", RuntimeIncidentDetector.STATUS_OPEN)
                .orderBy("id", false)
                .limit(20));
    }

    public AppRuntimeIncident byId(Long incidentId) {
        return incidentMapper.selectOneById(incidentId);
    }

    /** 用户关掉弹窗：只改状态，记录保留。 */
    public boolean dismiss(Long incidentId) {
        return updateStatus(incidentId, RuntimeIncidentDetector.STATUS_DISMISSED);
    }

    /** 发起修复前调用；返回 null 表示这条事件不存在。 */
    public AppRuntimeIncident markRepairing(Long incidentId) {
        AppRuntimeIncident incident = byId(incidentId);
        if (incident == null) {
            return null;
        }
        AppRuntimeIncident update = new AppRuntimeIncident();
        update.setId(incidentId);
        update.setStatus(RuntimeIncidentDetector.STATUS_REPAIRING);
        update.setNotifiedAt(LocalDateTime.now());
        update.setUpdateTime(LocalDateTime.now());
        incidentMapper.update(update);
        incident.setStatus(RuntimeIncidentDetector.STATUS_REPAIRING);
        return incident;
    }

    /** 修复轮结束后收尾。 */
    public void markRepaired(Long incidentId) {
        updateStatus(incidentId, RuntimeIncidentDetector.STATUS_REPAIRED);
    }

    public void removeAppIncidents(Long appId) {
        List<AppRuntimeIncident> list = incidentMapper.selectListByQuery(
                QueryWrapper.create().eq("app_id", appId));
        for (AppRuntimeIncident incident : list) {
            incidentMapper.deleteById(incident.getId());
        }
    }

    private boolean updateStatus(Long incidentId, String status) {
        AppRuntimeIncident update = new AppRuntimeIncident();
        update.setId(incidentId);
        update.setStatus(status);
        update.setUpdateTime(LocalDateTime.now());
        return incidentMapper.update(update) > 0;
    }

    /**
     * 生成交给模型做修复的提示词。
     *
     * <p>内容是**已经脱敏过**的诊断包（{@code incident.detail} 在写入前就过滤了凭据），
     * 这里只做组织。同时明确要求「改完不要自动恢复常驻服务」——
     * 与 Plan 的「完成复测后等待用户再次点击运行」保持一致。
     */
    public String buildRepairPrompt(AppRuntimeIncident incident, String appName) {
        StringBuilder sb = new StringBuilder();
        sb.append("我的应用「").append(appName == null ? "未命名" : appName)
                .append("」在运行时出现了问题，请帮我定位并修复。\n\n");
        sb.append("问题：").append(incident.getSummary()).append('\n');
        sb.append("类型：").append(RuntimeIncidentDetector.KIND_STARTUP.equals(incident.getKind())
                ? "启动失败" : "运行期异常").append('\n');
        sb.append("第 ").append(incident.getRunNo()).append(" 次运行\n\n");
        sb.append("下面是平台采集到的脱敏诊断信息：\n\n");
        sb.append("```\n").append(incident.getDetail() == null ? "（无）" : incident.getDetail()).append("\n```\n\n");
        sb.append("请先分析根因，再修改代码。修好后请在本轮结束时说明改了什么；")
                .append("不需要自动重启服务，我会自己再点一次运行来验证。");
        return sb.toString();
    }

    /** 合并多份诊断，供「一次修复全部待处理问题」的场景使用。 */
    public String mergeDetails(List<AppRuntimeIncident> incidents) {
        List<String> parts = new ArrayList<>();
        for (AppRuntimeIncident incident : incidents) {
            parts.add("【" + incident.getSummary() + "】\n"
                    + (incident.getDetail() == null ? "" : incident.getDetail()));
        }
        return String.join("\n\n---\n\n", parts);
    }
}
