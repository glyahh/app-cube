package com.dbts.glyahhaigeneratecode.projectimport;

import com.dbts.glyahhaigeneratecode.config.WorkspaceProperties;
import com.dbts.glyahhaigeneratecode.exception.ErrorCode;
import com.dbts.glyahhaigeneratecode.exception.MyException;
import com.dbts.glyahhaigeneratecode.service.support.ProjectImportStaging;
import com.dbts.glyahhaigeneratecode.service.support.ProjectImportStaging.StoredFile;
import jakarta.annotation.PreDestroy;
import org.springframework.stereotype.Service;

import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.*;
import java.util.concurrent.*;

import static com.dbts.glyahhaigeneratecode.service.support.ProjectImportManifest.*;
import static com.dbts.glyahhaigeneratecode.projectimport.ImportTask.Status.*;

@Service
@lombok.extern.slf4j.Slf4j
public class ImportTaskService implements AutoCloseable {
    private final ImportTaskRepository repository;
    private final ImportProperties properties;
    private final ImportPreferenceService preferences;
    private final WorkspaceProperties workspace;
    private final ProjectImportStaging staging;
    private final String executorId = UUID.randomUUID().toString();
    // ponytail: 单后端进程的短状态锁，不覆盖文件传输；多实例须补资源停止协调，不能只靠此锁。
    private final Object coordinator = new Object();
    private final Map<UUID, ActiveUpload> active = new HashMap<>();
    private final ScheduledExecutorService deadlines = Executors.newSingleThreadScheduledExecutor(r -> {
        Thread thread = new Thread(r, "project-import-deadlines");
        thread.setDaemon(true);
        return thread;
    });

    public ImportTaskService(ImportTaskRepository repository, ImportProperties properties, WorkspaceProperties workspace, ImportPreferenceService preferences) throws IOException {
        this.repository = repository;
        this.properties = properties;
        this.preferences = preferences;
        this.workspace = workspace;
        new Limits(properties.getMaxFiles(), properties.getMaxSourceBytes());
        if (properties.getMaxPendingPerUser() < 1 || properties.getMaxPendingGlobal() < 1 || properties.getMinFreeBytes() < 0
                || properties.getUploadTimeout().isNegative() || properties.getUploadTimeout().isZero()
                || properties.getStopTimeout().isNegative() || properties.getStopTimeout().isZero()) {
            throw new IllegalArgumentException("导入资源配置无效。");
        }
        staging = new ProjectImportStaging(properties.getStagingRoot(), List.of(workspace.getOutputRoot(), workspace.getDeployRoot()));
    }

    public ImportTask.View create(long ownerId, String appName, List<FileEntry> files, Options options) {
        return create(ownerId,appName,files,options,null,null);
    }

    ImportTask.View createFromSource(ImportSourceTask source,ImportGitReader.Snapshot snapshot) {
        return create(source.ownerId(),source.definition().appName(),snapshot.blobs().stream().map(blob->new FileEntry(blob.path(),blob.size())).toList(),
                source.definition().options(),source,snapshot);
    }

    ImportTask.View rebuildFromSource(ImportSourceTask source,ImportGitReader.Snapshot snapshot) throws IOException {
        var files=snapshot.blobs().stream().map(blob->new FileEntry(blob.path(),blob.size())).toList();
        var manifest=validate(files,source.definition().options(),new Limits(properties.getMaxFiles(),properties.getMaxSourceBytes()));
        var definition=new ImportTask.Definition(source.definition().appName(),files,source.definition().options(),manifest);
        synchronized(coordinator) {
            var previous=owned(source.ownerId(),source.taskId());
            if(previous.status()!=WAITING_CONFIRMATION || active.containsKey(source.taskId()) || repository.publicationByTask(source.taskId(),source.ownerId())!=null)throw conflict();
            long available=Files.getFileStore(properties.getStagingRoot()).getUsableSpace();
            long reserved=repository.reservedBytes();
            if(reserved>available || manifest.totalBytes()>available-reserved || properties.getMinFreeBytes()>available-reserved-manifest.totalBytes())
                throw new IOException("重建暂存空间不足。");
            // 来源已撤销旧提交资格；清理仅限本任务字节，不动固定 Git 对象或原工程。
            staging.cleanup(source.taskId());staging.create(source.taskId());
            return repository.resetFromSource(previous,definition,source,snapshot).view();
        }
    }

    void checkRebuild(long owner,UUID id,Options options) {
        synchronized(coordinator) {
            var current=owned(owner,id);
            if(current.status()!=WAITING_CONFIRMATION || active.containsKey(id))throw conflict();
            try{validate(current.definition().files(),options,new Limits(properties.getMaxFiles(),properties.getMaxSourceBytes()));}
            catch(IllegalArgumentException error){throw badInput(error.getMessage());}
        }
    }

    private ImportTask.View create(long ownerId,String appName,List<FileEntry> files,Options options,ImportSourceTask source,ImportGitReader.Snapshot snapshot) {
        // 1. 冻结当前来源与选项，归属只来自已登录会话。
        if (ownerId <= 0 || appName == null || appName.strip().isEmpty() || appName.strip().length() > 80
                || appName.chars().anyMatch(Character::isISOControl)) throw badInput("应用名称无效，最多 80 个字符。");
        Manifest manifest;
        if (options == null) options = preferences.get(ownerId);
        try { manifest = validate(files, options, new Limits(properties.getMaxFiles(), properties.getMaxSourceBytes())); }
        catch (IllegalArgumentException e) { throw badInput(e.getMessage()); }
        var definition = new ImportTask.Definition(appName.strip(), files, options, manifest);
        synchronized (coordinator) {
            int replaced=source==null?0:1;
            if (repository.pending(ownerId)-replaced >= properties.getMaxPendingPerUser() || repository.pending(null)-replaced >= properties.getMaxPendingGlobal()) {
                throw new MyException(ErrorCode.TOO_MANY_REQUEST, "未完成导入过多，请先完成或取消已有任务。");
            }
            try {
                long available = Files.getFileStore(properties.getStagingRoot()).getUsableSpace();
                long reserved = repository.reservedBytes();
                if (reserved > available || manifest.totalBytes() > available - reserved
                        || properties.getMinFreeBytes() > available - reserved - manifest.totalBytes()) {
                    throw new MyException(ErrorCode.OPERATION_ERROR, "暂存空间不足，未创建导入任务。");
                }
            } catch (IOException e) { throw new MyException(ErrorCode.OPERATION_ERROR, "无法检查导入暂存空间。"); }

            // 2. 先持久化任务再建立其私有目录，不创建可读 App。
            var task = new ImportTask(source==null?UUID.randomUUID():source.taskId(), ownerId, definition, READING_CONTENT, 0, 0, List.of(), null, null, "NONE", null);
            if(source==null)repository.insert(task);else repository.insertFromSource(task,source,snapshot);
            try { staging.create(task.taskId()); }
            catch (IOException e) {
                changed(task, FAILED, 0, List.of(), null, "STAGING_CREATE_FAILED", "UNVERIFIED", null);
                throw new MyException(ErrorCode.OPERATION_ERROR, "无法建立导入暂存目录。");
            }
            return task.view();
        }
    }

    /** 来源准备复用当前项的准入锁，避免和文件夹各自判断名额。 */
    <T> T admitSource(long ownerId,java.util.function.Supplier<T> reserve) {
        synchronized(coordinator) {
            if(repository.pending(ownerId)>=properties.getMaxPendingPerUser() || repository.pending(null)>=properties.getMaxPendingGlobal())
                throw new MyException(ErrorCode.TOO_MANY_REQUEST,"未完成导入过多，请先完成或取消已有任务。");
            return reserve.get();
        }
    }

    /** 原请求找回与新准入共用短锁；复读不占新名额，也不启动新读取。 */
    <T> T sourceRequest(long ownerId,java.util.function.Supplier<T> existing,java.util.function.Supplier<T> create) {
        synchronized(coordinator) {
            T previous=existing.get();
            return previous!=null?previous:admitSource(ownerId,create);
        }
    }

    public ImportTask.View get(long ownerId, UUID id) {
        synchronized (coordinator) {
            ImportTask task = owned(ownerId, id);
            if ((task.status() == UPLOADING || task.status() == COMMITTING) && !active.containsKey(id)) {
                task = changed(task, RECOVERY_REQUIRED, task.nextFile(), task.receipts(), task.contentHash(), "UPLOAD_STATE_UNCONFIRMED", "UNVERIFIED", task.executorId());
            }
            return task.view();
        }
    }

    public ImportTask.View upload(long ownerId, UUID id, long revision, int index, InputStream input) {
        ImportTask lease;
        ActiveUpload context = new ActiveUpload(input, Thread.currentThread());
        synchronized (coordinator) {
            ImportTask task = owned(ownerId, id);
            if (task.status() != READING_CONTENT || task.revision() != revision || task.nextFile() != index
                    || index < 0 || index >= task.definition().manifest().included().size()) throw conflict();
            lease = changed(task, UPLOADING, index, task.receipts(), null, null, "NONE", executorId);
            active.put(id, context);
        }
        ScheduledFuture<?> deadline = deadlines.schedule(() -> expire(lease, context), properties.getUploadTimeout().toMillis(), TimeUnit.MILLISECONDS);
        try {
            // 1. 字节长度、取消、时限和内容摘要都在真实接收过程中核对。
            FileEntry entry = lease.definition().manifest().included().get(index);
            StoredFile stored = staging.receive(id, index, entry, input, () -> !context.revoked, properties.getUploadTimeout());
            List<StoredFile> receipts = new ArrayList<>(lease.receipts());
            receipts.add(stored);
            boolean complete = receipts.size() == lease.definition().manifest().included().size();
            String hash = complete ? staging.seal(id, receipts, () -> !context.revoked, properties.getUploadTimeout()) : null;

            // 2. 旧租约和取消意图不能被迟到的文件结果覆盖。
            synchronized (coordinator) {
                if (context.revoked) throw conflict();
                return changed(lease, complete ? WAITING_CONFIRMATION : READING_CONTENT, index + 1, receipts, hash, null, "NONE", null).view();
            }
        } catch (Exception e) {
            synchronized (coordinator) {
                ImportTask current = owned(ownerId, id);
                boolean ownLease = executorId.equals(current.executorId()) && current.revision() == lease.revision() && current.status() == UPLOADING;
                boolean ownTimeout = context.timedOut && executorId.equals(current.executorId()) && current.status() == STOP_FAILED
                        && "UPLOAD_TIMEOUT".equals(current.error());
                if ((!context.cancelled || context.timedOut) && (ownLease || ownTimeout)) {
                    fail(current, context.timedOut ? "UPLOAD_TIMEOUT" : "UPLOAD_FAILED");
                }
            }
            throw new MyException(ErrorCode.OPERATION_ERROR, context.cancelled ? "导入已取消，请查看任务状态。" : "文件接收失败，请查看任务状态。");
        } finally {
            deadline.cancel(false);
            synchronized (coordinator) { active.remove(id, context); }
            context.exited.countDown();
            if (context.revoked) Thread.interrupted();
        }
    }

    public ImportTask.View cancel(long ownerId, UUID id, long revision) {
        return cancel(ownerId,id,revision,false);
    }
    ImportTask.View cancelFromSource(long ownerId,UUID id,long revision){return cancel(ownerId,id,revision,true);}
    private ImportTask.View cancel(long ownerId,UUID id,long revision,boolean sourceStopped) {
        ActiveUpload context;
        ImportTask cancelling;
        synchronized (coordinator) {
            ImportTask task = owned(ownerId, id);
            if(!sourceStopped && repository.isGit(id,ownerId))throw new MyException(ErrorCode.CONFLICT_ERROR,"请通过来源任务取消，以核实 Git 读取停止。");
            if (task.status() == CANCELLED) return task.view();
            if (task.status() == SUCCEEDED) throw new MyException(ErrorCode.CONFLICT_ERROR, "应用已导入，不能取消已完成任务。");
            if (task.revision() != revision) throw conflict();
            context = active.get(id);
            cancelling = changed(task, CANCELLING, task.nextFile(), task.receipts(), task.contentHash(), null, "PENDING", task.executorId());
            if (context != null) { context.cancelled = true; context.revoked = true; }
            else if (task.executorId() != null && !task.executorId().equals(executorId)) {
                return changed(cancelling, STOP_FAILED, cancelling.nextFile(), cancelling.receipts(), cancelling.contentHash(),
                        "RESOURCE_STOP_UNCONFIRMED", "UNVERIFIED", cancelling.executorId()).view();
            }
        }
        if (context != null) {
            context.stop();
            try {
                if (!context.exited.await(properties.getStopTimeout().toMillis(), TimeUnit.MILLISECONDS)) {
                    return stoppedFailed(ownerId, id).view();
                }
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                return stoppedFailed(ownerId, id).view();
            }
        }
        String cleanup = clean(id, ownerId);
        synchronized (coordinator) {
            ImportTask current = owned(ownerId, id);
            return changed(current, CANCELLED, current.nextFile(), current.receipts(), current.contentHash(),
                    cleanup.equals("DONE") ? null : "CLEANUP_FAILED", cleanup, null).view();
        }
    }

    public record CommitResult(String appId, UUID taskId, String contentHash, String redirectPath) { }

    public CommitResult result(long ownerId, UUID id) {
        ImportTask task = owned(ownerId, id);
        var publication = repository.publicationByTask(id, ownerId);
        if (task.status() != SUCCEEDED || publication == null || !publication.state().equals("READY")) throw conflict();
        var available = repository.publicationByApp(publication.appId());
        if (available == null || !available.state().equals("READY") || available.ownerId() != ownerId) throw conflict();
        return new CommitResult(Long.toString(publication.appId()), id, publication.contentHash(), "/app/" + publication.appId() + "/chat");
    }

    public CommitResult commit(long ownerId, UUID id, long revision, String expectedHash) {
        ImportTask lease;
        ActiveUpload context = new ActiveUpload(InputStream.nullInputStream(), Thread.currentThread());
        synchronized (coordinator) {
            ImportTask task = owned(ownerId, id);
            if (task.status() == SUCCEEDED && Objects.equals(task.contentHash(), expectedHash)) return result(ownerId, id);
            if (task.status() != WAITING_CONFIRMATION || task.revision() != revision || !Objects.equals(task.contentHash(), expectedHash)
                    || expectedHash == null || !expectedHash.matches("[0-9a-f]{64}")) throw conflict();
            lease = repository.reservePublication(task, cn.hutool.core.util.IdUtil.getSnowflakeNextId(), executorId,
                    ProcessHandle.current().pid(), processStarted());
            active.put(id, context);
        }
        return publish(lease, context, false);
    }

    public CommitResult recover(long ownerId, UUID id, long revision, String expectedHash) {
        ImportTask lease;
        ActiveUpload context = new ActiveUpload(InputStream.nullInputStream(), Thread.currentThread());
        synchronized (coordinator) {
            ImportTask task = owned(ownerId, id);
            if (task.status() == SUCCEEDED && Objects.equals(task.contentHash(), expectedHash)) return result(ownerId, id);
            var publication = repository.publicationByTask(id, ownerId);
            if ((task.status() != RECOVERY_REQUIRED && task.status() != COMMITTING) || task.revision() != revision
                    || expectedHash == null || !Objects.equals(task.contentHash(), expectedHash) || publication == null || active.containsKey(id)) throw conflict();
            if (!executorId.equals(task.executorId())) {
                var process = ProcessHandle.of(publication.executorPid());
                if (process.isPresent() && process.get().isAlive()) {
                    var started = process.get().info().startInstant();
                    if (started.isEmpty() || started.get().truncatedTo(java.time.temporal.ChronoUnit.MILLIS).equals(publication.executorStart())) {
                        throw new MyException(ErrorCode.CONFLICT_ERROR, "原提交进程尚未确认停止，不能恢复。");
                    }
                }
            }
            lease = repository.reclaimPublication(task, executorId, ProcessHandle.current().pid(), processStarted());
            active.put(id, context);
        }
        return publish(lease, context, true);
    }

    private CommitResult publish(ImportTask lease, ActiveUpload context, boolean recovery) {
        UUID id = lease.taskId();
        String stage="QUALIFICATION";
        try {
            // 1. 原始封存副本与候选副本隔离，不在确认或复制过程中执行工程。
            var publication = repository.publicationByTask(id, lease.ownerId());
            if (publication == null || !publication.contentHash().equals(lease.contentHash())) throw conflict();
            Path target = publicationTarget(publication.appId());
            ProjectImportStaging.guardManagedPath(target);
            Files.createDirectories(target.getParent());
            stage="SEAL";
            if (!staging.seal(id, lease.receipts(), () -> !context.revoked, properties.getUploadTimeout()).equals(lease.contentHash())) throw conflict();
            Path prepared = staging.preparedWorkspace(id);
            if (Files.exists(target, java.nio.file.LinkOption.NOFOLLOW_LINKS)) {
                if (!recovery || Files.exists(prepared, java.nio.file.LinkOption.NOFOLLOW_LINKS)) throw conflict();
            } else {
                if (Files.exists(prepared, java.nio.file.LinkOption.NOFOLLOW_LINKS)) {
                    if (!recovery) throw conflict();
                    staging.cleanupPreparedWorkspace(id);
                }
                stage="PREPARE_WORKSPACE";
                prepared = staging.prepareWorkspace(id, lease.receipts(), () -> !context.revoked, properties.getUploadTimeout());
                synchronized (coordinator) {
                    if (context.revoked || owned(lease.ownerId(), id).revision() != lease.revision()) throw conflict();
                    // 不使用 REPLACE_EXISTING；发布期间读取资格仍关闭。
                    stage="MOVE_WORKSPACE";
                    Files.move(prepared, target);
                }
            }
            // 2. 最终文件再次核对，通过后才在短事务中创建可用 App。
            stage="VERIFY_WORKSPACE";
            if (!staging.verifyWorkspace(target, lease.receipts(), () -> !context.revoked, properties.getUploadTimeout()).equals(lease.contentHash())) throw conflict();
            synchronized (coordinator) {
                if (context.revoked) throw conflict();
                stage="FILES_READY";
                repository.filesReady(lease);
                stage="FINAL_TRANSACTION";
                repository.finishPublication(lease);
                return result(lease.ownerId(), id);
            }
        } catch (Exception e) {
            // 只记录阶段与异常类型，不把工程内容、秘密或原始数据库参数写入日志。
            log.warn("导入发布未确认，taskId={}，stage={}，type={}",id,stage,e.getClass().getSimpleName());
            synchronized (coordinator) {
                ImportTask current = owned(lease.ownerId(), id);
                if (current.status() == COMMITTING && current.revision() == lease.revision()) {
                    changed(current, RECOVERY_REQUIRED, current.nextFile(), current.receipts(), current.contentHash(),
                            "PUBLICATION_UNCONFIRMED", "UNVERIFIED", executorId);
                }
            }
            throw new MyException(ErrorCode.OPERATION_ERROR, "应用提交未完成，请查看任务并恢复；未发布为可用应用。");
        } finally {
            synchronized (coordinator) { active.remove(id, context); }
            context.exited.countDown();
            if (context.revoked) Thread.interrupted();
        }
    }

    private Path publicationTarget(long appId) {
        return workspace.getOutputRoot().toAbsolutePath().normalize().resolve("project_" + appId);
    }

    private static java.time.Instant processStarted() {
        return ProcessHandle.current().info().startInstant().orElseThrow(() -> new IllegalStateException("无法登记当前进程身份。"))
                .truncatedTo(java.time.temporal.ChronoUnit.MILLIS);
    }

    private void expire(ImportTask lease, ActiveUpload context) {
        try {
            synchronized (coordinator) {
                if (active.get(lease.taskId()) != context) return;
                context.timedOut = true;
                context.revoked = true;
                repository.transition(lease, STOP_FAILED, lease.nextFile(), lease.receipts(), null, "UPLOAD_TIMEOUT", "UNVERIFIED", executorId);
            }
        } finally {
            if (context.timedOut) context.stop();
        }
    }

    private ImportTask stoppedFailed(long ownerId, UUID id) {
        synchronized (coordinator) {
            ImportTask current = owned(ownerId, id);
            return changed(current, STOP_FAILED, current.nextFile(), current.receipts(), current.contentHash(),
                    "RESOURCE_STOP_UNCONFIRMED", "UNVERIFIED", current.executorId());
        }
    }

    private void fail(ImportTask task, String error) {
        ImportTask failed = changed(task, FAILED, task.nextFile(), task.receipts(), task.contentHash(), error, "PENDING", task.executorId());
        String cleanup = clean(task.taskId(), task.ownerId());
        changed(failed, FAILED, failed.nextFile(), failed.receipts(), failed.contentHash(), error, cleanup, null);
    }

    private String clean(UUID id, long ownerId) {
        try {
            var publication = repository.publicationByTask(id, ownerId);
            if (publication != null) {
                if (publication.state().equals("READY")) return "FAILED";
                Path target = publicationTarget(publication.appId());
                if (Files.exists(target, java.nio.file.LinkOption.NOFOLLOW_LINKS)) {
                    if (!publication.state().equals("FILES_READY")) return "FAILED";
                    staging.removeVerifiedWorkspace(target, owned(ownerId, id).receipts(), properties.getUploadTimeout());
                }
                staging.cleanupPreparedWorkspace(id);
                repository.abortPublication(id, ownerId);
            }
            staging.cleanup(id); return "DONE";
        }
        catch (IOException e) { return "FAILED"; }
    }

    private ImportTask owned(long ownerId, UUID id) {
        if (ownerId <= 0 || id == null) throw badInput("导入任务标识无效。");
        ImportTask task = repository.find(id, ownerId);
        if (task == null) throw new MyException(ErrorCode.NOT_FOUND_ERROR, "导入任务不存在。");
        return task;
    }

    private ImportTask changed(ImportTask previous, ImportTask.Status status, int nextFile, List<StoredFile> receipts,
                               String hash, String error, String cleanup, String executorId) {
        ImportTask updated = repository.transition(previous, status, nextFile, receipts, hash, error, cleanup, executorId);
        if (updated == null) throw conflict();
        return updated;
    }

    private static MyException conflict() { return new MyException(ErrorCode.CONFLICT_ERROR, "导入任务状态已变化，请刷新后重试。"); }
    private static MyException badInput(String message) { return new MyException(ErrorCode.PARAMS_ERROR, message); }

    @Override @PreDestroy
    public void close() {
        synchronized (coordinator) { active.values().forEach(ActiveUpload::stop); }
        deadlines.shutdownNow();
    }

    private static final class ActiveUpload {
        final InputStream input;
        final Thread thread;
        final CountDownLatch exited = new CountDownLatch(1);
        volatile boolean revoked;
        volatile boolean cancelled;
        volatile boolean timedOut;
        ActiveUpload(InputStream input, Thread thread) { this.input = Objects.requireNonNull(input); this.thread = thread; }
        void stop() {
            revoked = true;
            thread.interrupt();
            Thread.startVirtualThread(() -> { try { input.close(); } catch (IOException ignored) { /* 停止是否完成由 exited 确认。 */ } });
        }
    }
}
