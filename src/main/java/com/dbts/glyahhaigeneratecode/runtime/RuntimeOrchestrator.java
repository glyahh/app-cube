package com.dbts.glyahhaigeneratecode.runtime;

import com.dbts.glyahhaigeneratecode.config.RuntimeProperties;
import com.dbts.glyahhaigeneratecode.config.WorkspaceProperties;
import com.dbts.glyahhaigeneratecode.exception.ErrorCode;
import com.dbts.glyahhaigeneratecode.exception.MyException;
import com.dbts.glyahhaigeneratecode.exception.ThrowUtils;
import com.dbts.glyahhaigeneratecode.mapper.AppRuntimeMapper;
import com.dbts.glyahhaigeneratecode.model.Entity.AppRuntime;
import com.dbts.glyahhaigeneratecode.model.VO.RuntimeStatusVO;
import com.mybatisflex.core.query.QueryWrapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Locale;

/**
 * 应用运行的编排层：把「启动 / 停止 / 状态 / 心跳」四件事串起来。
 *
 * <p>关键设计：**容器状态是唯一真相**。内存里只缓存 spec 快照，运行的死活一律回查
 * Docker —— 后端重启后内存就空了，而容器可能还在跑（也可能早被外部停掉）。
 * 不做这层对账，就会出现「页面显示运行中，实际容器已经不存在」这类假状态。
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class RuntimeOrchestrator {

    private final RuntimeManager runtimeManager;
    private final RuntimeSpecResolver specResolver;
    private final RuntimeLogService logService;
    private final RuntimeMetricsStore metricsStore;
    private final WorkspaceVersioner versioner;
    private final RuntimeProperties runtimeProperties;
    private final WorkspaceProperties workspaceProperties;
    private final AppRuntimeMapper appRuntimeMapper;
    private final RuntimeIncidentService incidentService;
    private final RuntimeTrafficStats trafficStats;

    /** 状态取值，与迁移里的 CHECK 约束一致。 */
    public static final String STATUS_STARTING = "STARTING";
    public static final String STATUS_RUNNING = "RUNNING";
    public static final String STATUS_STOPPED = "STOPPED";
    public static final String STATUS_FAILED = "FAILED";
    public static final String STATUS_UNKNOWN = "UNKNOWN";

    /**
     * 启动一次运行。
     *
     * @param appId        应用 id
     * @param userId       当前用户 id
     * @param workspaceDir 产物目录（已由调用方按 appId 解析好）
     */
    public RuntimeStatusVO start(Long appId, Long userId, Path workspaceDir) {
        // 1. 已有活着的运行就不重复拉起 —— 重复点击「运行」不该创建第二个进程
        AppRuntime existing = activeRun(appId);
        if (existing != null) {
            String status = runtimeManager.containerStatus(existing.getContainerName());
            if ("running".equals(status) || "created".equals(status)) {
                log.info("应用 {} 已有运行中的容器 {}，复用", appId, existing.getContainerName());
                return toVO(existing, workspaceDir);
            }
            // 记录说活着但容器已死：先把它按失败收尾，再走新启动
            closeRun(existing.getId(), STATUS_FAILED, "运行容器已不存在", null);
        }

        // 2. 推断运行规格并校验（不校验就执行等于把工作区里的字符串当命令跑）
        //    工作区不存在时先在这里挡下：resolve 对不存在的目录会直接返回 UNSUPPORTED 就结束，
        //    根本没走 validate，于是容器照样被拉起来，里面跑的却是一条空命令 ——
        //    退出码 127 最终被报成「应用启动失败」，用户按提示去翻日志，日志里什么线索都没有。
        ThrowUtils.throwIf(workspaceDir == null || !Files.isDirectory(workspaceDir),
                ErrorCode.OPERATION_ERROR, "应用还没有生成产物，先让助手生成一次再运行");
        RuntimeSpec spec = specResolver.resolve(workspaceDir);

        // 3. 分配启动编号并建日志目录
        int runNo = nextRunNo(appId);
        logService.prepareRunDir(appId, runNo);
        logService.pruneOldRuns(appId);

        // 4. 落一条 STARTING 记录
        AppRuntime record = AppRuntime.builder()
                .appId(appId)
                .userId(userId)
                .runNo(runNo)
                .status(STATUS_STARTING)
                .containerName(runtimeManager.containerName(appId))
                .specJson(toSpecJson(spec))
                .sourceVersion(versioner.version(workspaceDir))
                .startedAt(LocalDateTime.now())
                .lastHeartbeatAt(LocalDateTime.now())
                .createTime(LocalDateTime.now())
                .updateTime(LocalDateTime.now())
                .isDelete(0)
                .build();
        appRuntimeMapper.insertSelective(record);

        // 5. 起容器
        try {
            RuntimeManager.StartResult result = runtimeManager.start(
                    appId, workspaceDir, logService.runLogDir(appId, runNo), spec, spec.getPrimaryPort());

            record.setContainerId(result.containerId());
            record.setGatewayPort(result.hostPort());

            // 6. 等入口就绪；仍在构建时保持 STARTING，不能把容器存活当成服务可用。
            boolean ready = runtimeManager.awaitReady(
                    record.getContainerName(), result.hostPort(), runtimeProperties.getReadinessTimeout());

            String containerStatus = runtimeManager.containerStatus(record.getContainerName());
            if (!"running".equals(containerStatus) && !"created".equals(containerStatus)) {
                Integer exitCode = runtimeManager.containerExitCode(record.getContainerName());
                record.setExitCode(exitCode);
                record.setErrorMessage(ready ? null : "进程启动后立即退出");
                record.setStatus(STATUS_FAILED);
                record.setStoppedAt(LocalDateTime.now());
                updateRecord(record);
                recordIncidentIfAbnormal(record, exitCode);
                logService.trimIfOversized(appId, runNo);
                throw new MyException(ErrorCode.OPERATION_ERROR,
                        "应用启动失败（退出码 " + (exitCode == null ? "未知" : exitCode) + "），请查看运行日志");
            }

            record.setStatus(ready ? STATUS_RUNNING : STATUS_STARTING);
            record.setLastHeartbeatAt(LocalDateTime.now());
            updateRecord(record);
            log.info("应用 {} 第 {} 次运行已启动，容器={} 端口={}",
                    appId, runNo, record.getContainerName(), result.hostPort());
            return toVO(record, workspaceDir);
        } catch (MyException e) {
            if (STATUS_STARTING.equals(record.getStatus())) {
                record.setStatus(STATUS_FAILED);
                record.setErrorMessage(e.getMessage());
                record.setStoppedAt(LocalDateTime.now());
                updateRecord(record);
            }
            // 启动失败也要把日志裁掉，避免半截日志占着配额
            logService.trimIfOversized(appId, runNo);
            throw e;
        }
    }

    /**
     * 停止当前运行。
     *
     * @return 停掉了返回 true；本来就没在跑返回 false
     */
    public boolean stop(Long appId, Path workspaceDir) {
        AppRuntime runtime = activeRun(appId);
        if (runtime == null) {
            // 记录说没跑，但容器可能残留（例如上次崩溃）—— 以容器为准清理一次
            String name = runtimeManager.containerName(appId);
            String status = runtimeManager.containerStatus(name);
            if (!"missing".equals(status)) {
                runtimeManager.removeContainer(name);
                return true;
            }
            return false;
        }

        runtimeManager.removeContainer(runtime.getContainerName());
        // 停止后容器不再持有日志句柄，这时裁才安全
        logService.trimIfOversized(appId, runtime.getRunNo());
        metricsStore.clear(appId);
        trafficStats.clear(appId);
        closeRun(runtime.getId(), STATUS_STOPPED, null, "用户停止");
        log.info("应用 {} 运行已停止（第 {} 次）", appId, runtime.getRunNo());
        return true;
    }

    /**
     * 查询运行状态。
     *
     * <p>每次都拿容器的真实状态回写记录：这是「后端重启后不保留虚假运行中」的落点。
     */
    public RuntimeStatusVO status(Long appId, Path workspaceDir) {
        AppRuntime runtime = latestRun(appId);
        if (runtime == null) {
            return RuntimeStatusVO.notStarted(versioner.version(workspaceDir));
        }
        return toVO(runtime, workspaceDir);
    }

    /** 心跳：登记「还有页面在看这个应用」。 */
    public void heartbeat(Long appId) {
        AppRuntime runtime = activeRun(appId);
        if (runtime == null) {
            return;
        }
        AppRuntime update = new AppRuntime();
        update.setId(runtime.getId());
        update.setLastHeartbeatAt(LocalDateTime.now());
        update.setUpdateTime(LocalDateTime.now());
        appRuntimeMapper.update(update);
    }

    /**
     * 空闲回收：最后一个页面离开后保留一段时间再停。
     *
     * <p>按心跳判断而不是浏览器关闭事件 —— 刷新、多标签、断网都不该误停。
     * 空闲期间不写心跳，所以「超过心跳超时 + 超过保留时长」就是真的没人看了。
     */
    public int reapIdle(Instant now) {
        List<AppRuntime> actives = appRuntimeMapper.selectListByQuery(
                QueryWrapper.create().in("status", List.of(STATUS_RUNNING, STATUS_STARTING)));
        int stopped = 0;
        for (AppRuntime runtime : actives) {
            LocalDateTime beat = runtime.getLastHeartbeatAt() != null
                    ? runtime.getLastHeartbeatAt()
                    : runtime.getStartedAt();
            if (beat == null) {
                continue;
            }
            Duration idle = Duration.between(beat, LocalDateTime.now());
            // 先越过心跳超时才计入空闲；再超过保留时长才停
            if (idle.compareTo(runtimeProperties.getHeartbeatTimeout()) < 0) {
                continue;
            }
            if (idle.compareTo(runtimeProperties.getHeartbeatTimeout()
                    .plus(runtimeProperties.getIdleStopAfter())) < 0) {
                continue;
            }

            String containerStatus = runtimeManager.containerStatus(runtime.getContainerName());
            if (!"running".equals(containerStatus)) {
                // 已经不在跑了，只把记录收尾
                Integer exitCode = runtimeManager.containerExitCode(runtime.getContainerName());
                closeRun(runtime.getId(), STATUS_STOPPED, null,
                        "进程已退出（退出码 " + (exitCode == null ? "未知" : exitCode) + "）");
                continue;
            }
            runtimeManager.removeContainer(runtime.getContainerName());
            logService.trimIfOversized(runtime.getAppId(), runtime.getRunNo());
            metricsStore.clear(runtime.getAppId());
            closeRun(runtime.getId(), STATUS_STOPPED, null, "长时间无页面访问，已自动停止");
            stopped++;
        }
        return stopped;
    }

    /**
     * 启动时对账：把记录里「活着」但容器已消失的运行标记为失败。
     *
     * <p>后端重启后内存态全丢，这一步保证界面看到的不是假状态。
     */
    public int reconcileOnStartup() {
        List<AppRuntime> actives = appRuntimeMapper.selectListByQuery(
                QueryWrapper.create().in("status", List.of(STATUS_RUNNING, STATUS_STARTING)));
        int fixed = 0;
        for (AppRuntime runtime : actives) {
            String containerStatus = runtimeManager.containerStatus(runtime.getContainerName());
            if ("running".equals(containerStatus) || "created".equals(containerStatus)) {
                // 容器还活着：这是合法状态（平台重启不影响已运行的容器），保留
                log.info("对账：应用 {} 的容器 {} 仍在运行", runtime.getAppId(), runtime.getContainerName());
                continue;
            }            Integer exitCode = runtimeManager.containerExitCode(runtime.getContainerName());
            closeRun(runtime.getId(), STATUS_FAILED,
                    "平台重启后未找到运行容器", null);
            if (exitCode != null) {
                AppRuntime update = new AppRuntime();
                update.setId(runtime.getId());
                update.setExitCode(exitCode);
                appRuntimeMapper.update(update);
            }
            fixed++;
        }
        if (fixed > 0) {
            log.info("对账完成：{} 条运行记录被标记为已结束", fixed);
        }
        return fixed;
    }

    /**
     * 当前活着的运行记录（最多一条，由部分唯一索引保证）。 */
    public AppRuntime activeRun(Long appId) {
        List<AppRuntime> list = appRuntimeMapper.selectListByQuery(
                QueryWrapper.create()
                        .eq("app_id", appId)
                        .in("status", List.of(STATUS_RUNNING, STATUS_STARTING))
                        .orderBy("run_no", false)
                        .limit(1));
        return list.isEmpty() ? null : list.get(0);
    }

    /** 最近一次运行记录（含已结束的）。 */
    public AppRuntime latestRun(Long appId) {
        List<AppRuntime> list = appRuntimeMapper.selectListByQuery(
                QueryWrapper.create()
                        .eq("app_id", appId)
                        .orderBy("run_no", false)
                        .limit(1));
        return list.isEmpty() ? null : list.get(0);
    }

    /** 取某次运行记录；不存在返回 null。 */
    public AppRuntime runByNo(Long appId, int runNo) {
        List<AppRuntime> list = appRuntimeMapper.selectListByQuery(
                QueryWrapper.create().eq("app_id", appId).eq("run_no", runNo).limit(1));
        return list.isEmpty() ? null : list.get(0);
    }

    private int nextRunNo(Long appId) {
        AppRuntime latest = latestRun(appId);
        return latest == null || latest.getRunNo() == null ? 1 : latest.getRunNo() + 1;
    }

    private void closeRun(Long id, String status, String errorMessage, String note) {
        AppRuntime update = new AppRuntime();
        update.setId(id);
        update.setStatus(status);
        update.setStoppedAt(LocalDateTime.now());
        update.setUpdateTime(LocalDateTime.now());
        if (errorMessage != null) {
            update.setErrorMessage(errorMessage);
        }
        appRuntimeMapper.update(update);
        if (note != null) {
            log.info("运行记录 {} 收尾：{}（{}）", id, status, note);
        }
    }

    private void updateRecord(AppRuntime record) {
        record.setUpdateTime(LocalDateTime.now());
        appRuntimeMapper.update(record);
    }

    private static String toSpecJson(RuntimeSpec spec) {
        return cn.hutool.json.JSONUtil.toJsonStr(spec);
    }

    /**
     * 组装对外状态。
     *
     * <p>以容器实际状态为准做一次校正：记录说 RUNNING 但容器没了，这里直接反映为失败，
     * 不等对账任务跑。
     */
    private RuntimeStatusVO toVO(AppRuntime runtime, Path workspaceDir) {
        String containerStatus = runtimeManager.containerStatus(runtime.getContainerName());
        boolean alive = "running".equals(containerStatus) || "created".equals(containerStatus);

        String status = runtime.getStatus();
        Integer exitCode = runtime.getExitCode();
        String errorMessage = runtime.getErrorMessage();

        if (alive) {
            status = runtimeManager.isReady(runtime.getContainerName(), runtime.getGatewayPort())
                    ? STATUS_RUNNING : STATUS_STARTING;
            if (!status.equals(runtime.getStatus())) {
                runtime.setStatus(status);
                updateRecord(runtime);
            }
        } else if (STATUS_RUNNING.equals(status) || STATUS_STARTING.equals(status)) {
            // 记录说活着但容器没了：立刻校正，并顺手把记录收尾
            exitCode = runtimeManager.containerExitCode(runtime.getContainerName());
            status = exitCode != null && exitCode != 0 ? STATUS_FAILED : STATUS_STOPPED;
            errorMessage = exitCode != null && exitCode != 0
                    ? "进程异常退出（退出码 " + exitCode + "）" : errorMessage;
            recordIncidentIfAbnormal(runtime, exitCode);
            closeRun(runtime.getId(), status, errorMessage, "状态校正");
            // 崩溃运行的日志也要裁一次。它没走过 stop / 启动失败 / 空闲回收那几条路径，
            // 不在这里收的话，一次刷屏崩溃能留下几十 MB 没人管。
            logService.trimIfOversized(runtime.getAppId(), runtime.getRunNo());
        }

        // 运行版本落后：当前磁盘版本与启动时不一致
        String currentVersion = versioner.version(workspaceDir);
        boolean stale = currentVersion != null && runtime.getSourceVersion() != null
                && !currentVersion.equals(runtime.getSourceVersion());

        RuntimeSpec spec = parseSpec(runtime.getSpecJson());

        return RuntimeStatusVO.builder()
                .appId(runtime.getAppId())
                .runNo(runtime.getRunNo())
                .status(status)
                .running(STATUS_RUNNING.equals(status))
                .containerName(runtime.getContainerName())
                .gatewayPort(runtime.getGatewayPort())
                .kind(spec == null || spec.getKind() == null ? null : spec.getKind().name())
                .processes(spec == null ? List.of() : spec.getProcesses().stream()
                        .map(p -> RuntimeStatusVO.ProcessInfo.builder()
                                .name(p.getName())
                                .command(p.getCommand())
                                .port(p.getPort())
                                .build())
                        .toList())
                .sourceVersion(runtime.getSourceVersion())
                .currentVersion(currentVersion)
                .stale(stale)
                .startedAt(runtime.getStartedAt())
                .stoppedAt(runtime.getStoppedAt())
                .exitCode(exitCode)
                .errorMessage(errorMessage)
                // spec 解析不出来时给 null 而不是 true：没有记录只说明还没成功启动过，
                // 说它 supported 是在编 —— 前端会拿这个字段决定要不要给「运行」入口。
                .supported(spec == null ? null : spec.getKind() != RuntimeSpec.RuntimeKind.UNSUPPORTED)
                .build();
    }

    private static RuntimeSpec parseSpec(String json) {
        if (json == null || json.isBlank()) {
            return null;
        }
        try {
            return cn.hutool.json.JSONUtil.toBean(json, RuntimeSpec.class);
        } catch (RuntimeException e) {
            return null;
        }
    }

    /**
     * 容器异常退出时记录异常事件。
     *
     * <p>检测挂在这里而不是单开一个定时任务：退出这件事只有「启动」和「状态查询」
     * 两处第一时间知道，就近记录才不会漏。去重靠指纹（同次运行同一个错误只留一条），
     * 所以重复调用是安全的。
     */
    private void recordIncidentIfAbnormal(AppRuntime runtime, Integer exitCode) {
        if (exitCode == null || exitCode == 0) {
            return;
        }
        try {
            RuntimeLogService.LogSlice slice = logService.read(runtime.getAppId(), runtime.getRunNo(), 64 * 1024);
            long durationSeconds = runtime.getStartedAt() == null ? 0L
                    : Duration.between(runtime.getStartedAt(), LocalDateTime.now()).getSeconds();
            String command = describeProcesses(runtime);
            var created = incidentService.detectOnExit(runtime, exitCode, durationSeconds,
                    slice.content(), command, runtime.getSourceVersion());
            // 已经记下「异常退出」就不再逐行扫日志：一次崩溃只该弹一个框，
            // 而崩溃日志本来就已经进了这条记录的诊断详情。
            // 逐行扫描留给另一种情况 —— 进程还活着、但抛了未处理异常。
            if (created.isEmpty()) {
                incidentService.scanLogs(runtime, slice.content(), command, runtime.getSourceVersion(),
                        durationSeconds <= 150);
            }
        } catch (RuntimeException e) {
            // 记录异常失败不该影响状态返回
            log.warn("记录运行异常失败 appId={}: {}", runtime.getAppId(), e.getMessage());
        }
    }

    /** 把受管进程拼成一行人类可读的启动命令描述，进诊断包。 */
    private static String describeProcesses(AppRuntime runtime) {
        RuntimeSpec spec = parseSpec(runtime.getSpecJson());
        if (spec == null || spec.getProcesses() == null || spec.getProcesses().isEmpty()) {
            return null;
        }
        StringBuilder sb = new StringBuilder();
        for (RuntimeSpec.ManagedProcess process : spec.getProcesses()) {
            if (!sb.isEmpty()) {
                sb.append(" | ");
            }
            sb.append(process.getName()).append(": ").append(process.getCommand());
        }
        return sb.toString();
    }

    /** 供指标采集用：列出所有活着的运行。 */
    public List<AppRuntime> activeRuns() {
        return appRuntimeMapper.selectListByQuery(
                QueryWrapper.create().eq("status", STATUS_RUNNING));
    }

    /**
     * 应用被删除时清掉它的全部运行痕迹。
     *
     * <p>容器本身由 {@code AppServiceSupport} 先删掉，这里收尾四样：
     * 运行台账（{@code app_runtime}）、异常台账、以及内存里的指标窗口与流量计数。
     *
     * <p>不做的话，删掉的应用会在库里留下孤儿行，两个内存 map 也会一直涨
     * —— 它们没有任何回收路径，重启之前不会自己释放。
     *
     * @param appId 应用 id
     */
    public void purgeApp(Long appId) {
        if (appId == null) {
            return;
        }
        appRuntimeMapper.deleteByQuery(QueryWrapper.create().eq("app_id", appId));
        incidentService.removeAppIncidents(appId);
        metricsStore.clear(appId);
        trafficStats.clear(appId);
    }
}
