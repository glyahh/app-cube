package com.dbts.glyahhaigeneratecode.projectimport;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Objects;

/** 安装阶段的共同定义与终态计算；不读写账本，也不决定资源是否可释放。 */
final class ImportInstallStages {

    static final List<String> NAMES = List.of(
            "PLAN_VALIDATE", "ADMISSION", "COPY_PROJECT", "PREPARE_WORKSPACE",
            "PREPARE_VOLUME", "CREATE_CONTAINER", "START_CONTAINER", "NPM_INSTALL",
            "PIP_INSTALL", "BUILD_PREPARE", "VERIFY", "CLEANUP");

    private ImportInstallStages() {
    }

    static String failureStage(ImportStartupInstallJob job) {
        return job.stages().stream()
                .filter(stage -> List.of("FAILED", "REFUSED").contains(stage.status()))
                .map(ImportStartupInstallJob.Stage::stage)
                .findFirst()
                .orElseGet(() -> job.stages().stream()
                        .filter(stage -> unfinished(job, stage))
                        .reduce((previous, latest) -> latest)
                        .map(ImportStartupInstallJob.Stage::stage)
                        .orElse(null));
    }

    static boolean unfinished(ImportStartupInstallJob job, ImportStartupInstallJob.Stage stage) {
        return "STARTED".equals(stage.status()) && job.stages().stream().noneMatch(end ->
                !"STARTED".equals(end.status())
                        && end.stage().equals(stage.stage())
                        && Objects.equals(end.startTime(), stage.startTime())
                        && Objects.equals(end.executionId(), stage.executionId()));
    }

    /** 中断只登记最后观测，不补造结束时间和耗时。 */
    static List<ImportStartupInstallJob.Stage> terminalStages(
            ImportStartupInstallJob job, ImportStartupInstallJob.State next) {
        // 1. 标记尚未结束的阶段，保留原启动时刻和执行身份。
        var stages = new ArrayList<ImportStartupInstallJob.Stage>();
        boolean cancelled = next == ImportStartupInstallJob.State.CANCELLED;
        for (var stage : job.stages()) {
            if (unfinished(job, stage)) {
                stages.add(new ImportStartupInstallJob.Stage(
                        stage.stage(), stage.commandType(),
                        cancelled ? "CANCELLED" : "INTERRUPTED",
                        cancelled ? "INSTALL_CANCELLED" : "INSTALL_INTERRUPTED",
                        null, stage.startTime(), null, null, stage.executionId()));
            }
        }

        // 2. 缺失阶段明确记为未开始，不能当作成功。
        stages.addAll(notStarted(job));
        return stages;
    }

    static List<ImportStartupInstallJob.Stage> notStarted(ImportStartupInstallJob job) {
        var seen = new HashSet<String>();
        job.stages().forEach(stage -> seen.add(stage.stage()));

        var stages = new ArrayList<ImportStartupInstallJob.Stage>();
        for (String name : NAMES) {
            if (!seen.contains(name)) {
                stages.add(new ImportStartupInstallJob.Stage(
                        name, name, "NOT_STARTED", null, null, null, null, null, null));
            }
        }
        return stages;
    }
}
