package com.dbts.glyahhaigeneratecode.projectimport;

import com.dbts.glyahhaigeneratecode.exception.ErrorCode;
import com.dbts.glyahhaigeneratecode.exception.MyException;
import com.dbts.glyahhaigeneratecode.model.Entity.User;
import jakarta.annotation.PreDestroy;
import org.springframework.stereotype.Service;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.*;
import static com.dbts.glyahhaigeneratecode.projectimport.ImportStartupScan.Status.*;

/** 后台只读扫描；工作副本、辅助进程和发布资格按同一任务身份管理。 */
@Service
public class ImportStartupScanService {
    private static final org.slf4j.Logger log=org.slf4j.LoggerFactory.getLogger(ImportStartupScanService.class);
    private final ImportStartupService startup;
    private final ImportStartupScanRepository tasks;
    private final ImportStartupInspectionRepository evidence;
    private final ImportProperties properties;
    private final ImportStartupResourceGuard resources;
    private final ImportWorkspaceCoordinator coordinator;
    private final ScheduledExecutorService recovery=Executors.newSingleThreadScheduledExecutor(action->{var thread=new Thread(action,"import-startup-recovery");thread.setDaemon(true);return thread;});
    private final UUID executor=UUID.randomUUID();
    private final ConcurrentMap<UUID,CountDownLatch> active=new ConcurrentHashMap<>();
    private final ExecutorService workers=new ThreadPoolExecutor(2,2,0,TimeUnit.SECONDS,new ArrayBlockingQueue<>(20),action->{var thread=new Thread(action,"import-startup-scan");thread.setDaemon(true);return thread;},new ThreadPoolExecutor.AbortPolicy());
    private volatile boolean closing;
    public ImportStartupScanService(ImportStartupService startup,ImportStartupScanRepository tasks,ImportStartupInspectionRepository evidence,ImportProperties properties,ImportStartupResourceGuard resources,ImportWorkspaceCoordinator coordinator){
        this.startup=startup;this.tasks=tasks;this.evidence=evidence;this.properties=properties;
        this.resources=resources;
        this.coordinator=coordinator;
    }
    @org.springframework.context.event.EventListener(org.springframework.boot.context.event.ApplicationReadyEvent.class)
    public void startRecovery(){recovery.scheduleWithFixedDelay(this::recoverOutstanding,0,2,TimeUnit.SECONDS);}
    public ImportStartupScan.View create(User user,long appId,UUID requestId,long revision,long epoch,String scope){
        if(closing||requestId==null||revision<0||epoch<0)throw new MyException(ErrorCode.PARAMS_ERROR,"扫描请求或版本无效。");
        var existing=tasks.byRequest(user.getId(),requestId);
        if(existing!=null){
            if(existing.appId()!=appId||existing.configRevision()!=revision||existing.contextEpoch()!=epoch||!java.util.Objects.equals(existing.scope(),scope))throw conflict();
            return existing.view();
        }
        var config=startup.validateScan(user,appId,scope);
        if(!config.configRevision().equals(Long.toString(revision))||!config.contextEpoch().equals(Long.toString(epoch)))throw conflict();
        // 1. 新建扫描前确认磁盘剩余空间；不足或无法读取则失败，不截断、不排队。
        requireDisk();
        var process=ProcessHandle.current();
        var proposed=new ImportStartupScan(UUID.randomUUID(),requestId,appId,user.getId(),config.importTaskId(),revision,epoch,scope,QUEUED,0,
            executor,process.pid(),process.info().startInstant().orElseThrow(),Instant.now().plusSeconds(180),List.of(),null,"PENDING",null,false);
        var task=tasks.create(proposed,properties.getMaxPendingPerUser(),properties.getMaxPendingGlobal());
        // 2. 幂等找到旧任务只返回，不自动续跑、重新提交或替换执行者。
        if(!task.scanId().equals(proposed.scanId()))return task.view();
        var exited=new CountDownLatch(1);active.put(task.scanId(),exited);
        try{workers.submit(()->execute(user,task,exited));}
        catch(RejectedExecutionException error){active.remove(task.scanId());exited.countDown();tasks.transition(task,FAILED,null,"DONE","SCAN_EXECUTOR_UNAVAILABLE");throw new MyException(ErrorCode.OPERATION_ERROR,"识别任务未启动。");}
        return task.view();
    }
    private void execute(User user,ImportStartupScan queued,CountDownLatch exited){
        try{
            var lease=tasks.transition(queued,SCANNING,null,"PENDING",null);
            startup.inspectManaged(user,lease,()->!closing&&!Thread.currentThread().isInterrupted()&&tasks.qualified(lease),
                resource->tasks.resource(lease,resource),result->tasks.complete(lease,result,evidence));
        }catch(RuntimeException error){
          try{
            var current=tasks.find(queued.ownerId(),queued.scanId());
            if(current!=null&&current.reservesAdmission()){
                boolean stopped=startup.inputsAbsent(current.scanId())&&resources.stopped(current);
                var next=stopped?(current.cancelRequested()?CANCELLED:FAILED):(current.status()==QUEUED?RECOVERY_REQUIRED:STOP_FAILED);
                String diagnostic=next==CANCELLED?null:!stopped?"SCAN_RESOURCES_UNCONFIRMED":
                    error.getClass()==MyException.class?error.getMessage():"SCAN_INTERNAL_FAILURE";
                tasks.transition(current,next,null,stopped?"DONE":"UNVERIFIED",diagnostic);
            }
          }catch(RuntimeException recording){log.error("扫描状态未能更新 scanId={} errorType={}",queued.scanId(),recording.getClass().getSimpleName());}
        }finally{active.remove(queued.scanId(),exited);exited.countDown();}
    }
    public ImportStartupScan.View get(User user,long appId,UUID scanId){return owned(user,appId,scanId).view();}
    public ImportStartupScan.View byRequest(User user,long appId,UUID requestId){
        var task=tasks.byRequest(user.getId(),requestId);if(task==null||task.appId()!=appId)throw missing();return task.view();
    }
    public ImportStartupScan.View cancel(User user,long appId,UUID scanId,long revision){
        var task=owned(user,appId,scanId);if(task.revision()!=revision)throw conflict();
        if(!task.reservesAdmission()||task.status()==CANCELLING)return task.view();
        return tasks.transition(task,CANCELLING,task.contentHash(),"PENDING",null).view();
    }
    public ImportStartupScan.View reconcile(User user,long appId,UUID scanId,long revision){
        var task=owned(user,appId,scanId);if(task.revision()!=revision)throw conflict();
        reconcile(task);return owned(user,appId,scanId).view();
    }
    void recoverOutstanding(){
        try{for(var task:tasks.outstanding())try{reconcile(task);}catch(RuntimeException error){log.warn("扫描恢复未确认 scanId={} errorType={}",task.scanId(),error.getClass().getSimpleName());}}
        catch(RuntimeException error){log.warn("扫描恢复账本无法读取 errorType={}",error.getClass().getSimpleName());}
    }
    private void reconcile(ImportStartupScan original){
        if(!original.reservesAdmission()||closing)return;
        boolean own=executor.equals(original.executorId()),expired=!original.expiresAt().isAfter(Instant.now());
        if(!expired&&(own&&active.containsKey(original.scanId())||!own&&resources.parent(original)!=ImportStartupResourceGuard.Parent.GONE
            ||own&&(original.status()==QUEUED||original.status()==SCANNING)))return;
        // 同应用恢复串行化；锁内重新读状态，其他实例不得同时停止或清理同一资源。
        coordinator.withLock(original.appId(),()->{
            var current=tasks.find(original.ownerId(),original.scanId());if(current!=null){if(current.revision()!=original.revision())throw conflict();reconcileLocked(current);}return null;
        });
    }
    private void reconcileLocked(ImportStartupScan original){
        if(!original.reservesAdmission()||closing)return;
        boolean own=executor.equals(original.executorId()),busy=own&&active.containsKey(original.scanId());
        boolean expired=!original.expiresAt().isAfter(Instant.now());var parent=resources.parent(original);
        if(busy&&!expired)return;
        if(!own&&parent!=ImportStartupResourceGuard.Parent.GONE&&!expired)return;
        if(own&&!busy&&!expired&&(original.status()==QUEUED||original.status()==SCANNING))return;
        var lease=original.status()==RECOVERY_REQUIRED?original:tasks.transition(original,RECOVERY_REQUIRED,original.contentHash(),"UNVERIFIED",expired?"SCAN_EXPIRED":"SCAN_RECOVERY_REQUIRED");
        // 1. 撤销旧结果资格后，仍存活的执行者先自行退出；不在它写入时删除副本。
        if(busy||(!own&&parent!=ImportStartupResourceGuard.Parent.GONE))return;
        // helper 创建后、PID 尚未登记的崩溃窗口没有停止证明，保留私有输入等待核对。
        if(lease.resources().isEmpty()&&!startup.helperInputAbsent(lease.scanId()))return;
        if(!resources.stop(lease,resource->tasks.recoveryResource(lease,resource),properties.getStopTimeout())){
            tasks.transition(lease,STOP_FAILED,lease.contentHash(),"UNVERIFIED","SCAN_STOP_UNCONFIRMED");return;
        }
        // 2. 仅清本扫描的私有输入；不删除已导入应用、原工程或其他任务。
        try{startup.cleanupStoppedInputs(lease.scanId());}
        catch(java.io.IOException error){tasks.transition(lease,STOP_FAILED,lease.contentHash(),"FAILED","SCAN_INPUT_CLEANUP_FAILED");return;}
        if(!startup.inputsAbsent(lease.scanId())){tasks.transition(lease,STOP_FAILED,lease.contentHash(),"UNVERIFIED","SCAN_INPUT_CLEANUP_UNCONFIRMED");return;}
        tasks.transition(lease,lease.cancelRequested()?CANCELLED:FAILED,lease.contentHash(),"DONE",lease.cancelRequested()?null:"SCAN_INTERRUPTED");
    }
    public ImportStartupService.Inspection inspect(User user,long appId,String scope){
        var config=startup.configuration(user,appId);
        var task=create(user,appId,UUID.randomUUID(),Long.parseLong(config.configRevision()),Long.parseLong(config.contextEpoch()),scope);
        var wait=active.get(task.scanId());
        try{if(wait!=null&&!wait.await(180,TimeUnit.SECONDS))throw new MyException(ErrorCode.OPERATION_ERROR,"识别仍未完成，请查看任务状态。");}
        catch(InterruptedException error){Thread.currentThread().interrupt();throw new MyException(ErrorCode.OPERATION_ERROR,"识别等待中断，请查看任务状态。");}
        var completed=owned(user,appId,task.scanId());
        if(completed.status()!=SUCCEEDED)throw new MyException(ErrorCode.OPERATION_ERROR,"识别未完成（"+completed.status()+"）。");
        var restored=startup.inspection(user,appId,task.scanId());if(restored.inspection()==null)throw conflict();return restored.inspection();
    }
    private ImportStartupScan owned(User user,long appId,UUID scanId){
        if(user==null||user.getId()==null||scanId==null)throw missing();var task=tasks.find(user.getId(),scanId);
        if(task==null||task.appId()!=appId)throw missing();return task;
    }
    @PreDestroy void close(){closing=true;recovery.shutdownNow();workers.shutdown();try{workers.awaitTermination(40,TimeUnit.SECONDS);}catch(InterruptedException error){Thread.currentThread().interrupt();}}
    private void requireDisk(){
        try{ImportDiskAdmission.requireUsable(java.nio.file.Files.getFileStore(java.nio.file.Path.of("").toAbsolutePath()).getUsableSpace(),properties.getMinFreeBytes());}
        catch(java.io.IOException error){throw new MyException(ErrorCode.OPERATION_ERROR,"无法确认磁盘剩余空间，已停止识别。");}
    }
    private static MyException conflict(){return new MyException(ErrorCode.CONFLICT_ERROR,"扫描任务或上下文已变化。");}
    private static MyException missing(){return new MyException(ErrorCode.NOT_FOUND_ERROR,"识别任务不存在。");}
}
