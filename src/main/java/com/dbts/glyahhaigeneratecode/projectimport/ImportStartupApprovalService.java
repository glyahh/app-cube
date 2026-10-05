package com.dbts.glyahhaigeneratecode.projectimport;

import com.dbts.glyahhaigeneratecode.exception.ErrorCode;
import com.dbts.glyahhaigeneratecode.exception.MyException;
import com.dbts.glyahhaigeneratecode.model.Entity.User;
import jakarta.annotation.PreDestroy;
import org.springframework.stereotype.Service;
import java.io.IOException;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.*;
import java.util.function.BooleanSupplier;

/** 识别批准业务：持久批准/拒绝、独占租约认领、取消与重启对账。回包经全内容复核后才发布为候选，不授权安装或运行。 */
@Service
public class ImportStartupApprovalService {
    private static final org.slf4j.Logger log=org.slf4j.LoggerFactory.getLogger(ImportStartupApprovalService.class);
    private static final List<String> ACTIVE=List.of("APPROVED","RUNNING","CANCELLING","RECOVERY_REQUIRED");
    public record View(UUID approvalId,UUID requestId,String appId,UUID scanId,UUID importTaskId,List<String> directories,boolean approved,
        String state,String revision,Instant expiresAt,boolean resultRecorded,String error,boolean cancelRequested,String cleanup){ }
    private enum Step {NONE,STALE,REVOKE,CLOSE}
    private final ImportStartupService startup;
    private final ImportStartupApprovalRepository approvals;
    private final ImportStartupModelLeaseRepository leases;
    private final ImportStartupModelExecutor model;
    private final ImportStartupResourceGuard resources;
    private final ImportProperties properties;
    private final ImportWorkspaceCoordinator coordinator;
    private final ScheduledExecutorService recovery=Executors.newSingleThreadScheduledExecutor(action->{var thread=new Thread(action,"import-startup-approval-recovery");thread.setDaemon(true);return thread;});
    private final UUID executor=UUID.randomUUID();
    private final ConcurrentMap<UUID,CountDownLatch> active=new ConcurrentHashMap<>();
    private final ExecutorService workers=new ThreadPoolExecutor(2,2,0,TimeUnit.SECONDS,new ArrayBlockingQueue<>(20),action->{var thread=new Thread(action,"import-startup-approval");thread.setDaemon(true);return thread;},new ThreadPoolExecutor.AbortPolicy());
    private volatile boolean closing;
    public ImportStartupApprovalService(ImportStartupService startup,ImportStartupApprovalRepository approvals,ImportStartupModelLeaseRepository leases,ImportStartupModelExecutor model,
            ImportStartupResourceGuard resources,ImportProperties properties,ImportWorkspaceCoordinator coordinator){
        this.startup=startup;this.approvals=approvals;this.leases=leases;this.model=model;
        this.resources=resources;this.properties=properties;this.coordinator=coordinator;
    }
    @org.springframework.context.event.EventListener(org.springframework.boot.context.event.ApplicationReadyEvent.class)
    public void startRecovery(){recovery.scheduleWithFixedDelay(this::recoverOutstanding,0,2,TimeUnit.SECONDS);}

    public View decide(User user,long appId,UUID requestId,UUID scanId,List<String> directories,boolean approved){
        if(closing||user==null||user.getId()==null||requestId==null||scanId==null||directories==null)throw new MyException(ErrorCode.PARAMS_ERROR,"批准请求无效。");
        // 1. 同一请求只返回原记录，不重新复核、不再次调度执行。
        var existing=approvals.byRequest(user.getId(),requestId);
        if(existing!=null){
            if(existing.appId()!=appId||!existing.scanId().equals(scanId)||existing.approved()!=approved||!existing.directories().equals(directories))throw conflict();
            return view(existing);
        }
        // 2. 在工作区写入锁内复核当前证据后落账；拒绝也留持久记录，但不占执行配额。
        ImportStartupApprovalRepository.Approval created;
        try{created=startup.withCurrentInspection(user,appId,scanId,inspection->approvals.create(user.getId(),requestId,inspection,directories,approved,
            properties.getMaxPendingPerUser(),properties.getMaxPendingGlobal()));}
        catch(IllegalArgumentException error){throw new MyException(ErrorCode.PARAMS_ERROR,"识别范围已变化，请重新确认。");}
        if(created==null||!created.requestId().equals(requestId)||created.appId()!=appId)throw conflict();
        // 3. 只有新建且仍未认领的批准进入有限工作线程；认领 CAS 保证同一批准只执行一次。
        if(created.approved()&&"APPROVED".equals(created.state())&&created.revision()==0)submit(user,created);
        return view(approvals.find(created.ownerId(),created.id()));
    }
    public View get(User user,long appId,UUID approvalId){return view(owned(user,appId,approvalId));}
    public View byRequest(User user,long appId,UUID requestId){
        if(user==null||user.getId()==null||requestId==null)throw missing();
        var approval=approvals.byRequest(user.getId(),requestId);if(approval==null||approval.appId()!=appId)throw missing();return view(approval);
    }
    public View cancel(User user,long appId,UUID approvalId,long revision){
        var current=owned(user,appId,approvalId);if(current.revision()!=revision)throw conflict();
        // 1. 先撤销旧批准版本：未认领的直接取消，运行中的转 CANCELLING，由执行者或对账确认停止后再记终态。
        return switch(current.state()){
            case "APPROVED" -> view(approvals.transition(current,"CANCELLED",null,null));
            case "RUNNING" -> view(approvals.transition(current,"CANCELLING",null,null));
            default -> view(current);
        };
    }
    public View reconcile(User user,long appId,UUID approvalId,long revision){
        var current=owned(user,appId,approvalId);if(current.revision()!=revision)throw conflict();
        reconcile(current);return view(owned(user,appId,approvalId));
    }

    private void submit(User user,ImportStartupApprovalRepository.Approval approval){
        var exited=new CountDownLatch(1);
        if(active.putIfAbsent(approval.id(),exited)!=null)return;
        try{workers.submit(()->execute(user,approval,exited));}
        catch(RejectedExecutionException error){
            active.remove(approval.id(),exited);exited.countDown();
            // 未认领前没有进程和私有输入，排队失败直接释放配额。
            try{approvals.transition(approval,"FAILED",null,"MODEL_EXECUTOR_UNAVAILABLE");}
            catch(RuntimeException recording){log.error("识别批准状态未能更新 approvalId={} errorType={}",approval.id(),recording.getClass().getSimpleName());}
            throw new MyException(ErrorCode.OPERATION_ERROR,"识别补充任务未启动。");
        }
    }
    private void execute(User user,ImportStartupApprovalRepository.Approval queued,CountDownLatch exited){
        try{
            ImportStartupModelLease lease;
            try{var process=ProcessHandle.current();lease=leases.claim(queued,executor,process.pid(),process.info().startInstant().orElseThrow());}
            catch(RuntimeException error){
                // 1. 认领失败不启动模型；仍是原版本的批准按是否过期释放配额，其他状态由取消或其他执行者负责。
                var current=approvals.find(queued.ownerId(),queued.id());
                if(current!=null&&"APPROVED".equals(current.state())&&current.revision()==queued.revision()){
                    boolean expired=!current.expiresAt().isAfter(Instant.now());
                    approvals.transition(current,expired?"STALE":"FAILED",null,expired?"APPROVAL_EXPIRED":"MODEL_CLAIM_FAILED");
                }
                return;
            }
            String result=null,failure=null;
            try{
                var running=approvals.find(lease.ownerId(),lease.approvalId());
                BooleanSupplier eligible=()->!closing&&!Thread.currentThread().isInterrupted()&&leases.qualified(lease);
                var input=startup.approvedModelInput(user,running,eligible);
                result=model.execute(running,input,eligible,resource->leases.resource(lease,resource)).json();
            }catch(ImportStartupModelExecutor.Failure error){failure=error.code();}
            catch(MyException error){failure="APPROVAL_CONTEXT_CHANGED";}
            catch(IOException|RuntimeException error){failure="MODEL_INTERNAL_FAILURE";}
            // 2. 执行者已返回；按账本资源和本任务私有输入确认退出后才记终态。
            var current=approvals.find(queued.ownerId(),queued.id());
            if(current!=null&&ACTIVE.contains(current.state()))settle(user,current,leases.find(queued.ownerId(),queued.id()),result,failure);
        }catch(RuntimeException error){log.error("识别批准执行未能收尾 approvalId={} errorType={}",queued.id(),error.getClass().getSimpleName());}
        finally{active.remove(queued.id(),exited);exited.countDown();}
    }

    void recoverOutstanding(){
        try{for(var key:approvals.outstanding())try{var approval=approvals.find(key.ownerId(),key.id());if(approval!=null)reconcile(approval);}catch(RuntimeException error){log.warn("识别批准恢复未确认 approvalId={} errorType={}",key.id(),error.getClass().getSimpleName());}}
        catch(RuntimeException error){log.warn("识别批准账本无法读取 errorType={}",error.getClass().getSimpleName());}
    }
    private void reconcile(ImportStartupApprovalRepository.Approval original){
        if(step(original,leases.find(original.ownerId(),original.id()))==Step.NONE)return;
        // 同应用恢复串行化；锁内重新读取，旧版本不能停止或清理新状态的资源。
        coordinator.withLock(original.appId(),()->{
            var current=approvals.find(original.ownerId(),original.id());
            if(current!=null){if(current.revision()!=original.revision())throw conflict();reconcileLocked(current);}
            return null;
        });
    }
    private void reconcileLocked(ImportStartupApprovalRepository.Approval current){
        var lease=leases.find(current.ownerId(),current.id());boolean expired=!current.expiresAt().isAfter(Instant.now());
        switch(step(current,lease)){
            case STALE -> approvals.transition(current,"STALE",null,"APPROVAL_EXPIRED");
            // 1. 执行者仍存活：只撤销运行资格让它自行退出，不在它写入时删除输入。
            case REVOKE -> approvals.transition(current,"RECOVERY_REQUIRED",null,"APPROVAL_EXPIRED");
            case CLOSE -> {
                // 2. 执行者已不在：先撤销旧版本，再核对资源与输入；不重新执行模型。
                var revoked="RECOVERY_REQUIRED".equals(current.state())?current
                    :approvals.transition(current,"RECOVERY_REQUIRED",null,expired?"APPROVAL_EXPIRED":"MODEL_EXECUTOR_LOST");
                settle(null,revoked,lease,null,revoked.error());
            }
            case NONE -> { }
        }
    }
    private Step step(ImportStartupApprovalRepository.Approval current,ImportStartupModelLease lease){
        if(closing||!ACTIVE.contains(current.state()))return Step.NONE;
        boolean expired=!current.expiresAt().isAfter(Instant.now()),busy=active.containsKey(current.id());
        // 未认领的批准可能仍在其他实例排队；只在过期后释放，认领 CAS 同样拒绝过期批准。
        if("APPROVED".equals(current.state()))return expired&&!busy?Step.STALE:Step.NONE;
        boolean alive=busy||lease!=null&&!executor.equals(lease.executorId())
            &&resources.parent(lease.executorPid(),lease.executorStart())!=ImportStartupResourceGuard.Parent.GONE;
        if(alive)return expired&&"RUNNING".equals(current.state())?Step.REVOKE:Step.NONE;
        return Step.CLOSE;
    }

    /** 调用前执行者已退出；资源和私有输入都确认退出才写终态，否则保留恢复状态与配额。 */
    private void settle(User user,ImportStartupApprovalRepository.Approval current,ImportStartupModelLease lease,String result,String failure){
        if(lease!=null&&!"DONE".equals(lease.cleanup())){
            // 1. PID 登记前已有私有输入但没有资源：没有停止证明，不猜测已经停止。
            if(lease.resources().isEmpty()&&!model.inputAbsent(current.id())){hold(current,lease,"UNVERIFIED","MODEL_INPUT_UNCONFIRMED");return;}
            if(!resources.stopResources(lease.resources(),resource->leases.resource(lease,resource),properties.getStopTimeout())){hold(current,lease,"UNVERIFIED","MODEL_STOP_UNCONFIRMED");return;}
            // 2. 只清本批准的私有输入，不碰导入工程、保护快照或其他任务。
            if(!model.inputAbsent(current.id()))try{model.cleanupStoppedInput(current.id());}
                catch(IOException error){hold(current,lease,"FAILED","MODEL_INPUT_CLEANUP_FAILED");return;}
            if(!model.inputAbsent(current.id())){hold(current,lease,"UNVERIFIED","MODEL_INPUT_CLEANUP_UNCONFIRMED");return;}
            leases.cleanup(lease,"DONE");
        }else if(lease==null&&!model.inputAbsent(current.id())){hold(current,null,null,"MODEL_INPUT_UNCONFIRMED");return;}
        finish(user,current,result,failure);
    }
    private void hold(ImportStartupApprovalRepository.Approval current,ImportStartupModelLease lease,String cleanup,String code){
        if(lease!=null&&!"DONE".equals(lease.cleanup())&&!lease.cleanup().equals(cleanup))leases.cleanup(lease,cleanup);
        if(!"RECOVERY_REQUIRED".equals(current.state()))approvals.transition(current,"RECOVERY_REQUIRED",null,code);
    }
    private void finish(User user,ImportStartupApprovalRepository.Approval current,String result,String failure){
        for(int attempt=0;;attempt++){
            if(current==null||!ACTIVE.contains(current.state()))return;
            String next,error=null;
            if(current.cancelRequested())next="CANCELLED";
            else if(result!=null&&"RUNNING".equals(current.state()))next="SUCCEEDED";
            else{next="FAILED";error=result!=null?"MODEL_RESULT_REVOKED":failure!=null?failure:current.error()!=null?current.error():"MODEL_INTERRUPTED";}
            var committing=new java.util.concurrent.atomic.AtomicBoolean();
            try{
                if("SUCCEEDED".equals(next)){
                    // 3. 发布前在工作区锁内重新做全内容与当前候选校验，通过后同锁内写 SUCCEEDED。
                    String stored=result;
                    startup.publishSupplement(user,current,stored,()->!closing,approval->{committing.set(true);return approvals.transition(approval,"SUCCEEDED",stored,null);});
                }else approvals.transition(current,next,null,error);
                return;
            }catch(ImportStartupService.SupplementRejected rejected){
                // 4. 过期、取消或内容变化的回包作废，不进入当前候选。
                result=null;failure=rejected.code();current=approvals.find(current.ownerId(),current.id());
            }catch(MyException conflict){
                if(attempt>=3)throw conflict;
                var fresh=approvals.find(current.ownerId(),current.id());
                if("SUCCEEDED".equals(next)&&fresh!=null&&fresh.revision()==current.revision()){
                    // 5. 提交时数据库资格失效则作废；锁忙或复核未完成只短暂重试，仍不能确认就作废，不宣称成功。
                    if(committing.get()){result=null;failure="MODEL_RESULT_REVOKED";}
                    else if(attempt>=2||closing){result=null;failure="MODEL_RESULT_UNVERIFIED";}
                    else try{Thread.sleep(500);}catch(InterruptedException interrupted){Thread.currentThread().interrupt();result=null;failure="MODEL_RESULT_UNVERIFIED";}
                }
                current=fresh;
            }
        }
    }

    private View view(ImportStartupApprovalRepository.Approval approval){
        if(approval==null)throw missing();var lease=leases.find(approval.ownerId(),approval.id());
        return new View(approval.id(),approval.requestId(),Long.toString(approval.appId()),approval.scanId(),approval.importTaskId(),approval.directories(),approval.approved(),
            approval.state(),Long.toString(approval.revision()),approval.expiresAt(),approval.result()!=null,approval.error(),approval.cancelRequested(),lease==null?null:lease.cleanup());
    }
    private ImportStartupApprovalRepository.Approval owned(User user,long appId,UUID approvalId){
        if(user==null||user.getId()==null||approvalId==null)throw missing();var approval=approvals.find(user.getId(),approvalId);
        if(approval==null||approval.appId()!=appId)throw missing();return approval;
    }
    @PreDestroy void close(){closing=true;recovery.shutdownNow();workers.shutdown();try{workers.awaitTermination(40,TimeUnit.SECONDS);}catch(InterruptedException error){Thread.currentThread().interrupt();}}
    private static MyException conflict(){return new MyException(ErrorCode.CONFLICT_ERROR,"识别批准、内容或上下文已变化。");}
    private static MyException missing(){return new MyException(ErrorCode.NOT_FOUND_ERROR,"识别批准不存在。");}
}
