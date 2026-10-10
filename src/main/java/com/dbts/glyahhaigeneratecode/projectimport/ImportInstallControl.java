package com.dbts.glyahhaigeneratecode.projectimport;

import java.util.UUID;

/** 安装线程的任务上下文；资格与取消读取持久任务，不依赖 HTTP 生命周期。 */
final class ImportInstallControl implements AutoCloseable {

    private static final ThreadLocal<ImportStartupPreparationService.InstallProgress> CURRENT = new ThreadLocal<>();

    private final ImportStartupPreparationService.InstallProgress previous = CURRENT.get();

    ImportInstallControl(ImportStartupPreparationService.InstallProgress progress) {
        if (progress == null) {
            CURRENT.remove();
        } else {
            CURRENT.set(progress);
        }
    }

    static String job() {
        var progress = CURRENT.get();
        return progress == null ? null : progress.jobId();
    }

    static boolean cancelled() {
        var progress = CURRENT.get();
        return progress != null && progress.cancelled();
    }

    static void check() {
        var progress = CURRENT.get();
        if (progress != null) {
            progress.check();
        }
    }

    static void execution(UUID id) {
        var progress = CURRENT.get();
        if (progress != null) {
            progress.execution(id);
        }
    }

    static void probe(String name, String id) {
        var progress = CURRENT.get();
        if (progress != null) {
            progress.probe(name, id);
        }
    }

    @Override
    public void close() {
        if (previous == null) {
            CURRENT.remove();
        } else {
            CURRENT.set(previous);
        }
    }
}
