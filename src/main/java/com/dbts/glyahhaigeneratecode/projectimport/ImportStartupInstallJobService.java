package com.dbts.glyahhaigeneratecode.projectimport;

import com.dbts.glyahhaigeneratecode.exception.ErrorCode;
import com.dbts.glyahhaigeneratecode.exception.MyException;
import com.dbts.glyahhaigeneratecode.model.Entity.User;
import com.dbts.glyahhaigeneratecode.service.UserService;
import jakarta.annotation.PreDestroy;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.time.Instant;
import java.util.*;
import java.util.concurrent.*;
import java.util.function.Consumer;
import java.util.function.Supplier;

/** 持久安装任务；HTTP 只受理，断连不会取消后台执行。 */
@Service
@Slf4j
public class ImportStartupInstallJobService {

    private final ImportStartupPreparationService preparations;
    private final ImportStartupInstallJobRepository jobs;
    private final ImportExecutionActions executions;
    private final ImportProperties properties;
    private final ImportWorkspaceCoordinator coordinator;
    private final ImportInstallProbeRecovery probes;
    private final UserService users;

    private final UUID executor = UUID.randomUUID();
    private final ConcurrentMap<UUID, CountDownLatch> active = new ConcurrentHashMap<>();
    private final ScheduledExecutorService recovery = Executors.newSingleThreadScheduledExecutor(task -> {
        var thread = new Thread(task, "import-install-recovery");
        thread.setDaemon(true);
        return thread;
    });
    private final ExecutorService workers = new ThreadPoolExecutor(
            1, 1, 0, TimeUnit.SECONDS, new ArrayBlockingQueue<>(20), task -> {
                var thread = new Thread(task, "import-install-job");
                thread.setDaemon(true);
                return thread;
            }, new ThreadPoolExecutor.AbortPolicy());

    private volatile boolean closing;

    public ImportStartupInstallJobService(
            ImportStartupPreparationService preparations, ImportStartupInstallJobRepository jobs,
            ImportExecutionActions executions, ImportProperties properties,
            ImportWorkspaceCoordinator coordinator, ImportInstallProbeRecovery probes, UserService users) {
        this.preparations = preparations;
        this.jobs = jobs;
        this.executions = executions;
        this.properties = properties;
        this.coordinator = coordinator;
        this.probes = probes;
        this.users = users;
    }

    @org.springframework.context.event.EventListener(org.springframework.boot.context.event.ApplicationReadyEvent.class)
    public void startRecovery() {
        recovery.scheduleWithFixedDelay(this::recoverOutstanding, 0, 2, TimeUnit.SECONDS);
    }


    public ImportStartupInstallJob.View submit(
            User user, long appId, UUID requestId, UUID planId, String planRevision, boolean approved) {
        // 1. 先检查请求身份；同一请求不能更换计划或批准决定。
        if (closing) {
            throw new MyException(ErrorCode.OPERATION_ERROR, "服务正在关闭，暂不受理安装。");
        }
        if (user == null || user.getId() == null || appId <= 0 || requestId == null
                || planId == null || planRevision == null || !planRevision.matches("[0-9a-f]{64}")) {
            throw conflict();
        }

        var existing = jobs.byRequest(user.getId(), requestId);
        if (existing != null) {
            if (existing.appId() != appId || !existing.planId().equals(planId)
                    || !existing.planRevision().equals(planRevision)
                    || !Objects.equals(existing.approved(), approved)) {
                throw conflict();
            }
            return view(existing);
        }

        // 2. 锁内登记任务与调度标记，避免删除或恢复线程抢占新任务。
        var admitted = new CountDownLatch(1);
        try {
            return coordinator.withLock(appId, () -> {
                var process = ProcessHandle.current();
                var id = UUID.randomUUID();
                var job = jobs.create(id, requestId, appId, user.getId(), planId, planRevision,
                        executor, process.pid(), process.info().startInstant().orElseThrow(),
                        properties.getMaxPendingPerUser(), properties.getMaxPendingGlobal(), approved);
                if (!id.equals(job.jobId())) {
                    return view(job);
                }

                schedule(job, admitted);
                return view(jobs.find(job.ownerId(), id));
            });
        } finally {
            // 后台任务必须等到受理流程离开应用锁。
            admitted.countDown();
        }
    }

    private void schedule(ImportStartupInstallJob job, CountDownLatch admitted) {
        var id = job.jobId();
        var exited = new CountDownLatch(1);
        active.put(id, exited);

        try {
            workers.submit(() -> {
                try {
                    admitted.await();
                    execute(job, exited);
                } catch (InterruptedException failure) {
                    Thread.currentThread().interrupt();
                    active.remove(id);
                    exited.countDown();
                }
            });
        } catch (RejectedExecutionException failure) {
            try {
                jobs.finish(job, ImportStartupInstallJob.State.FAILED, List.of(), List.of(),
                        null, null, "INSTALL_EXECUTOR_UNAVAILABLE", "DONE");
            } finally {
                active.remove(id);
                exited.countDown();
            }
        }
    }

    public ImportStartupInstallJob.View get(User user, long appId, UUID jobId) {
        return view(owned(user, appId, jobId));
    }

    public ImportStartupInstallJob.View current(User user, long appId, UUID planId) {
        if (user == null || user.getId() == null || appId <= 0 || planId == null) {
            throw missing();
        }
        var row = jobs.current(user.getId(), appId, planId);
        return row == null ? null : view(row);
    }

    public ImportStartupInstallJob.View byRequest(User user, long appId, UUID requestId) {
        if (user == null || user.getId() == null || requestId == null) {
            throw missing();
        }
        var job = jobs.byRequest(user.getId(), requestId);
        if (job == null || job.appId() != appId) {
            throw missing();
        }
        return view(job);
    }

    public ImportStartupInstallJob.View cancel(User user, long appId, UUID id, long revision) {
        var job = owned(user, appId, id);
        if (job.cancelRequested() || ImportStartupInstallJob.isTerminal(job.state())) {
            return view(job);
        }
        if (job.revision() != revision) {
            throw conflict();
        }
        return view(jobs.requestCancel(job.ownerId(), id, revision));
    }


    private void execute(ImportStartupInstallJob queued, CountDownLatch exited) {
        var progress = new Progress(queued);
        try {
            // 1. 重新核对持久资格，再执行已批准计划。
            progress.check();
            progress.advance(ImportStartupInstallJob.State.PREPARING);
            var user = users.getById(queued.ownerId());
            if (user == null) {
                throw conflict();
            }
            var result = preparations.install(user, queued.appId(), queued.planId(),
                    queued.planRevision(), Boolean.TRUE.equals(queued.approved()), progress);

            // 2. 旧内部入口未调用 complete 时，仍按实际资源状态收口。
            var current = jobs.find(queued.ownerId(), queued.jobId());
            if (current != null && !ImportStartupInstallJob.isTerminal(current.state())) {
                settle(current, terminal(result.state(), current.cancelRequested()), result.reason());
            }
        } catch (RuntimeException failure) {
            try {
                var current = jobs.find(queued.ownerId(), queued.jobId());
                if (current != null && !ImportStartupInstallJob.isTerminal(current.state())) {
                    settle(current,
                            current.cancelRequested() ? ImportStartupInstallJob.State.CANCELLED
                                    : ImportStartupInstallJob.State.FAILED,
                            current.cancelRequested() ? null : "INSTALL_INTERRUPTED");
                }
            } catch (RuntimeException recording) {
                log.error("安装未收口 jobId={} errorType={}",
                        queued.jobId(), recording.getClass().getSimpleName());
            }
        } finally {
            active.remove(queued.jobId(), exited);
            exited.countDown();
        }
    }

    private void settle(ImportStartupInstallJob current, ImportStartupInstallJob.State next, String reason) {
        // 1. 先确认全部资源；停止未确认时保留准入占用。
        if (!executions.installClean(current.executionIds()) || !executions.installAppClean(current.appId())
                || !probes.confirmed(current, true)) {
            if (current.state() != ImportStartupInstallJob.State.STOP_FAILED) {
                jobs.transition(current, ImportStartupInstallJob.State.STOP_FAILED,
                        "CLEANUP", "INSTALL_RESOURCE_UNCONFIRMED", "UNVERIFIED");
            }
            return;
        }

        // 2. 清理确认后登记终态，不补造中断阶段的耗时。
        jobs.finish(current, next, terminalStages(current, next), List.of(),
                current.preparationId(), failureStage(current), reason, "DONE");
    }

    private static String failureStage(ImportStartupInstallJob job) {
        return ImportInstallStages.failureStage(job);
    }

    /** 崩溃后只登记中断与未开始，保留最后观测。 */
    private static List<ImportStartupInstallJob.Stage> terminalStages(
            ImportStartupInstallJob job, ImportStartupInstallJob.State next) {
        return ImportInstallStages.terminalStages(job, next);
    }

    private static ImportStartupInstallJob.State terminal(String state, boolean cancel) {
        if (cancel) {
            return ImportStartupInstallJob.State.CANCELLED;
        }
        return switch (state) {
            case "SUCCEEDED", "NOT_REQUIRED" -> ImportStartupInstallJob.State.SUCCEEDED;
            case "REJECTED" -> ImportStartupInstallJob.State.REJECTED;
            default -> ImportStartupInstallJob.State.FAILED;
        };
    }

    void recoverOutstanding() {
        if (closing) {
            return;
        }
        try {
            for (var row : jobs.outstanding()) {
                try {
                    align(row);
                } catch (RuntimeException failure) {
                    log.warn("安装恢复未确认 jobId={} errorType={}",
                            row.jobId(), failure.getClass().getSimpleName());
                }
            }
        } catch (RuntimeException failure) {
            log.warn("安装账本无法读取 errorType={}", failure.getClass().getSimpleName());
        }
    }

    private void align(ImportStartupInstallJob original) {
        // 1. 活线程自行收口，恢复不能提前释放删除保护。
        if (ImportStartupInstallJob.isTerminal(original.state()) || closing) {
            return;
        }
        if (executor.equals(original.executorId()) && active.containsKey(original.jobId())) {
            if (original.cancelRequested()) {
                for (var id : executions.activeSlots(original.appId(), "install")) {
                    executions.stopExecution(id);
                }
            }
            return;
        }
        if (!executor.equals(original.executorId()) && !parentGone(original)) {
            return;
        }

        // 2. 应用锁内重读身份，再停止已登记执行资源。
        coordinator.withLock(original.appId(), () -> {
            var current = jobs.find(original.ownerId(), original.jobId());
            if (current == null || ImportStartupInstallJob.isTerminal(current.state())
                    || active.containsKey(current.jobId())) {
                return null;
            }
            if (executor.equals(current.executorId())
                    && current.createdAt().isAfter(Instant.now().minusSeconds(5))) {
                return null;
            }
            if (!executor.equals(current.executorId()) && !parentGone(current)) {
                return null;
            }

            var ids = new LinkedHashSet<>(current.executionIds());
            ids.addAll(executions.activeSlots(current.appId(), "install"));
            for (var id : ids) {
                executions.stopExecution(id);
            }

            // 创建进程仍存活时保留现场。
            for (var resource : current.resources()) {
                var process = ProcessHandle.of(resource.pid());
                if (process.isPresent() && process.get().isAlive()) {
                    return null;
                }
            }
            settle(current,
                    current.cancelRequested() ? ImportStartupInstallJob.State.CANCELLED
                            : ImportStartupInstallJob.State.FAILED,
                    current.cancelRequested() ? null : "INSTALL_INTERRUPTED");
            return null;
        });
    }

    static boolean parentGone(ImportStartupInstallJob job) {
        try {
            var process = ProcessHandle.of(job.executorPid());
            if (process.isEmpty() || !process.get().isAlive()) {
                return true;
            }
            var start = process.get().info().startInstant();
            return start.isPresent() && !start.get().equals(job.executorStart());
        } catch (RuntimeException failure) {
            return false;
        }
    }

    private ImportStartupInstallJob owned(User user, long appId, UUID id) {
        if (user == null || user.getId() == null || id == null) {
            throw missing();
        }
        var row = jobs.find(user.getId(), id);
        if (row == null || row.appId() != appId) {
            throw missing();
        }
        return row;
    }

    private ImportStartupInstallJob.View view(ImportStartupInstallJob job) {
        if (job == null) {
            throw missing();
        }
        var preparation = preparations.installation(
                job.ownerId(), job.appId(), job.planId(), job.planRevision());

        // 旧 preparation 不能拼接成新任务的成功结果。
        String preparationId = job.preparationId() == null ? null : job.preparationId().toString();
        boolean samePreparation = preparation != null
                && Objects.equals(preparation.preparationId(), preparationId);
        return job.view(samePreparation ? preparation : null);
    }

    @PreDestroy
    void close() {
        closing = true;
        recovery.shutdownNow();
        workers.shutdown();
        try {
            workers.awaitTermination(40, TimeUnit.SECONDS);
        } catch (InterruptedException failure) {
            Thread.currentThread().interrupt();
        }
    }

    private static MyException conflict() {
        return new MyException(ErrorCode.CONFLICT_ERROR, "安装任务或上下文已变化。");
    }

    private static MyException missing() {
        return new MyException(ErrorCode.NOT_FOUND_ERROR, "安装任务不存在。");
    }


    final class Progress implements ImportStartupPreparationService.InstallProgress {

        private ImportStartupInstallJob lease;

        Progress(ImportStartupInstallJob row) {
            lease = row;
        }

        @Override
        public String jobId() {
            return lease.jobId().toString();
        }

        @Override
        public Consumer<ImportStartupInstallJob.Stage> stages() {
            return stage -> jobs.stage(lease.ownerId(), lease.jobId(), stage);
        }

        @Override
        public boolean cancelled() {
            var current = jobs.find(lease.ownerId(), lease.jobId());
            return closing || current == null || current.cancelRequested()
                    || ImportStartupInstallJob.isTerminal(current.state());
        }

        @Override
        public void check() {
            if (cancelled() || !jobs.qualified(lease)) {
                throw conflict();
            }
        }

        @Override
        public void advance(ImportStartupInstallJob.State state) {
            check();
            if (lease.state() != state) {
                lease = jobs.transition(lease, state, null, null, lease.cleanup());
            }
        }

        @Override
        public void execution(UUID id) {
            jobs.execution(lease.ownerId(), lease.jobId(), id);
        }

        @Override
        public void probe(String name, String id) {
            jobs.probe(lease.ownerId(), lease.jobId(), name, id);
        }

        @Override
        public ImportStartupPreparationService.View complete(
                Supplier<ImportStartupPreparationService.View> save) {
            // 1. 提交前重新核对资格和真实资源。
            check();
            var current = jobs.find(lease.ownerId(), lease.jobId());
            if (!executions.installClean(current.executionIds()) || !executions.installAppClean(current.appId())
                    || !probes.confirmed(current, false)) {
                throw conflict();
            }

            // 2. 补齐未开始阶段，缺失记录不能当作成功零耗时。
            for (var stage : ImportInstallStages.notStarted(current)) {
                jobs.stage(current.ownerId(), current.jobId(), stage);
            }

            // 3. 应用锁内提交结果与任务终态。
            var result = new java.util.concurrent.atomic.AtomicReference<ImportStartupPreparationService.View>();
            lease = coordinator.withLock(current.appId(), () -> jobs.complete(current, () -> {
                var value = save.get();
                result.set(value);
                return value;
            }));
            return result.get();
        }
    }
}
