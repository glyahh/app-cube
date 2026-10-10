package com.dbts.glyahhaigeneratecode.projectimport;

import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import java.util.*;

/** 重启撤销旧编辑资格，确认平台资源消失后才收口；未决发布不丢弃。 */
@Service
@RequiredArgsConstructor
@Slf4j
public class ImportAgentEditRecovery {
    private final ImportAgentEditLedger ledger;
    private final ImportWorkspaceCoordinator coordinator;
    private final ImportDiskVolume volumes;
    private final ImportAgentPublication publication;
    public record Result(int scanned,int recovered,List<String> unresolved){ }

    @org.springframework.context.event.EventListener(org.springframework.boot.context.event.ApplicationReadyEvent.class)
    public void onStartup(){
        var thread=new Thread(()->{
            try{
                var result=reconcile();
                log.info("导入编辑恢复完成 scanned={} recovered={} unresolved={}",result.scanned(),result.recovered(),result.unresolved());
            }catch(RuntimeException error){log.warn("导入编辑恢复未完成：{}",error.getClass().getSimpleName());}
        },"import-agent-edit-recovery");
        thread.setDaemon(true);thread.start();
    }

    public Result reconcile(){
        UUID cursor=null;int scanned=0,recovered=0;var unresolved=new ArrayList<String>();
        while(true){
            var page=ledger.openAfter(cursor);if(page.isEmpty())break;
            for(var observed:page){
                cursor=observed.identity().id();scanned++;
                if(currentExecutor(observed.identity()))continue;
                try{
                    String outcome=coordinator.withLock(observed.identity().appId(),()->recover(observed));
                    if(outcome==null)recovered++;else if(unresolved.size()<100)unresolved.add(cursor+":"+outcome);
                }catch(RuntimeException error){if(unresolved.size()<100)unresolved.add(cursor+":RECOVERY_UNCONFIRMED");}
            }
        }
        return new Result(scanned,recovered,List.copyOf(unresolved));
    }

    private String recover(ImportAgentEditLedger.Row observed){
        var e=observed.identity();var row=ledger.require(e.id(),e.ownerId());
        if(List.of("SUCCEEDED","FAILED","CANCELLED","STALE").contains(row.state()))return null;
        if(currentExecutor(row.identity()))return "CURRENT_EXECUTOR";
        // 1. 先撤销资格；活着或身份不能确认的执行者不能被当作已停止。
        boolean publishing=!"NONE".equals(row.publishPhase());
        if(publishing){if(!"RECOVERY_REQUIRED".equals(row.state()))row=ledger.publicationRecovery(row);}
        else if(!"RECOVERY_REQUIRED".equals(row.state())||!row.cancelRequested())row=ledger.recoveryRequired(row);
        var process=ProcessHandle.of(row.identity().executorPid());
        if(process.isPresent()&&process.get().isAlive()){
            var start=process.get().info().startInstant();
            if(start.isEmpty()||start.get().equals(row.identity().executorStart()))return "EXECUTOR_STILL_ALIVE";
        }
        // 2. 先确认旧原生执行者停止，再按持久阶段与实际目录恢复发布。
        var worker=ledger.workerInfo(row);
        if(worker!=null&&!worker.isEmpty()&&!"STOPPED".equals(worker.get("phase"))){
            try{
                ImportNativeWorker.recover(row,worker);
                ledger.workerStopped(row,(UUID)worker.get("lease_id"),true,true);
            }catch(InterruptedException error){Thread.currentThread().interrupt();return "WORKER_STOP_UNCONFIRMED";}
            catch(java.io.IOException|RuntimeException error){return "WORKER_STOP_UNCONFIRMED";}
        }
        if(publishing){
            try{publication.recover(row);return null;}
            catch(java.io.IOException|RuntimeException error){return "PUBLICATION_RECOVERY_REQUIRED";}
        }
        try{publication.discardOutputs(row);}catch(java.io.IOException|RuntimeException error){return "OUTPUT_RELEASE_UNCONFIRMED";}
        if(!ledger.discardable(row))return "PUBLICATION_RECOVERY_REQUIRED";
        try{
            var resources=new ObjectMapper().readTree(row.resources());
            if(!resources.isArray())return "RESOURCE_IDENTITY_INVALID";
            if(resources.isEmpty()){ledger.finishWithoutResources(row,"EXECUTOR_RESTARTED");return null;}
            if(resources.size()!=1)return "RESOURCE_IDENTITY_INVALID";
            var resource=resources.get(0);var capacity=resource.path("diskBytes");
            if(!capacity.isIntegralNumber()||!capacity.canConvertToLong())return "RESOURCE_IDENTITY_INVALID";
            long bytes=capacity.asLong();
            if(bytes<(16L<<20)||bytes>(8L<<30)||bytes%4096!=0)return "RESOURCE_IDENTITY_INVALID";
            // 3. 助手核对冻结身份、真实容器/挂载/loop消失；失败保留额度，不强制卸载。
            ledger.containerStopped(row,volumes.stopEditContainers(row.identity(),bytes));
            volumes.releaseEdit(row.identity(),bytes);
            row=ledger.volumeReleased(row);ledger.candidateReleased(row);ledger.viewReleased(row);
            ledger.finishReleasedVolume(row,"EXECUTOR_RESTARTED");return null;
        }catch(java.io.IOException|RuntimeException error){return "RESOURCE_RELEASE_UNCONFIRMED";}
    }

    private static boolean currentExecutor(ImportAgentEditLedger.Entry entry){
        var process=ProcessHandle.current();
        return entry.executorPid()==process.pid()&&entry.executorStart().equals(process.info().startInstant().orElse(null));
    }
}
