package com.dbts.glyahhaigeneratecode.projectimport;

import com.dbts.glyahhaigeneratecode.agent.*;
import com.fasterxml.jackson.databind.*;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import reactor.core.publisher.*;
import java.io.IOException;
import java.time.Duration;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicBoolean;

/** 两条正式传输共用的导入执行与合入；Python结果不能直接成为业务成功。 */
@Service
@RequiredArgsConstructor
@lombok.extern.slf4j.Slf4j
public class ImportAgentEditExecution {
    private final ImportAgentEditLedger ledger;
    private final ImportAgentEditPreparation preparation;
    private final ImportAgentContainerLifecycle containers;
    private final ImportAgentCandidateVerification verification;
    private final ImportAgentPublication publication;
    private final ImportWorkspaceCoordinator coordinator;
    private final AgentWorkerPool pool;
    private final AgentProperties properties;
    private final ObjectMapper json;
    private final ImportAgentToolPermissionService toolPermissions;
    private final ImportAgentCandidateReviewService reviews;
    private final Map<UUID,ImportNativeWorker> running=new ConcurrentHashMap<>();

    public Flux<AgentEvent> execute(ImportAgentEditPreparation.Prepared prepared){
        return Flux.<AgentEvent>create(sink->{
            var entry=prepared.row().identity();UUID id=entry.id();long owner=entry.ownerId();
            AtomicBoolean cancelled=new AtomicBoolean();
            sink.onCancel(()->{
                cancelled.set(true);
                Thread.ofVirtual().name("import-edit-cancel-"+id).start(()->{
                    try{
                        var row=ledger.require(id,owner);
                        if(List.of("PREPARING","EDITING","SEALING").contains(row.state())&&!row.cancelRequested())ledger.cancel(row);
                        var worker=running.get(id);if(worker!=null)worker.stop();
                    }catch(Exception ignored){ }
                });
            });
            ImportNativeWorker worker=null;UUID lease=null;boolean reserved=false,stopped=false,successful=false,handedOff=false;
            String stage="WORKER_START";
            String failureKey=null;boolean errorSent=false;int sequence=-1;
            IOException failure=null;
            try{
                // 1. 创建前登记原生执行意图，完成真实持久就绪握手后才绑定和下发。
                pool.acquireExternal();reserved=true;
                var row=ledger.require(id,owner);lease=ledger.planWorker(row);
                if(cancelled.get())throw new IOException("IMPORT_EDIT_CANCELLED");
                worker=new ImportNativeWorker(id,lease);running.put(id,worker);
                worker.start();
                ledger.workerLaunching(ledger.require(id,owner),lease,worker.pid(),worker.started());
                var ready=worker.ready(properties.getStartupTimeout());
                stage="CONTAINER_START";
                ledger.workerRunning(ledger.require(id,owner),lease,worker.pid(),worker.started().truncatedTo(java.time.temporal.ChronoUnit.MICROS),
                        ready.path("nativePid").asText(),ready.path("nativeStartTicks").asText());
                if(cancelled.get())throw new IOException("IMPORT_EDIT_CANCELLED");
                String container=containers.create(id,owner);containers.beginEditing(id,owner);
                var request=prepared.request().toBuilder().importContainerId(container).importWorkerLeaseId(lease.toString()).build();
                worker.send(request.toFrameJson(json));
                stage="WORKER_EVENTS";
                JsonNode result=null,finalText=null,audit=null;boolean ended=false,failed=false;
                String line;
                while((line=worker.read(properties.getReadTimeout()))!=null){
                    if(cancelled.get())throw new IOException("IMPORT_EDIT_CANCELLED");
                    var event=AgentEvent.parse(json,line);
                    if(!entry.runId().equals(event.runId())||event.seq()!=sequence+1||event.frame().path("v").asInt()!=1)
                        throw new IOException("IMPORT_EDIT_PROTOCOL_INVALID");
                    sequence=event.seq();
                    if(event.isEnd()){ended=true;break;}
                    if(AgentEvent.RUN_COMPLETED.equals(event.event()))throw new IOException("IMPORT_EDIT_PREMATURE_SUCCESS");
                    if("import.permission.requested".equals(event.event())){
                        var permission=toolPermissions.requested(id,owner,event.frame());
                        sink.next(frame(entry.runId(),sequence,"import.permission.requested",permission));
                    }else if("import.permission.consume".equals(event.event())){
                        toolPermissions.consume(id,owner,event.frame());
                    }else if("import.permission.result".equals(event.event())){
                        toolPermissions.result(id,owner,event.frame());
                    }else if("import.permission.heartbeat".equals(event.event())){
                        if(!id.toString().equals(event.frame().path("editId").asText())||!ledger.qualified(ledger.require(id,owner)))
                            throw new IOException("IMPORT_PERMISSION_REVOKED");
                    }else if("import.edit.stage".equals(event.event())){
                        String reported=event.frame().path("stage").asText();
                        if(!reported.matches("IMPORT_[A-Z_]{1,60}"))throw new IOException("IMPORT_EDIT_PROTOCOL_INVALID");
                        log.info("导入编辑阶段 edit={} stage={}",id,reported);
                    }else if("import.edit.result".equals(event.event())){
                        if(result!=null||!id.toString().equals(event.frame().path("editId").asText())
                                ||!event.frame().path("authorizedStateHash").isTextual()
                                ||!event.frame().path("authorizedStateHash").asText().matches("[0-9a-f]{64}"))throw new IOException("IMPORT_EDIT_RESULT_INVALID");
                        result=event.frame();
                    }else if("import.edit.model.audit".equals(event.event())){
                        JsonNode frame=event.frame();JsonNode digests=frame.path("digests");
                        if(audit!=null||!id.toString().equals(frame.path("editId").asText())
                                ||!frame.path("count").canConvertToInt()||frame.path("count").asInt()<1
                                ||!frame.path("blocked").canConvertToInt()||frame.path("blocked").asInt()!=0
                                ||!digests.isArray()||digests.size()!=frame.path("count").asInt())
                            throw new IOException("IMPORT_EDIT_MODEL_AUDIT_INVALID");
                        for(JsonNode digest:digests)
                            if(!digest.isTextual()||!digest.asText().matches("[0-9a-f]{64}"))
                                throw new IOException("IMPORT_EDIT_MODEL_AUDIT_INVALID");
                        audit=frame;
                    }else if(AgentEvent.TEXT_MESSAGE.equals(event.event()))finalText=event.frame();
                    else{if(event.isError()){
                        failed=true;errorSent=true;String reported=event.frame().path("stage").asText();
                        String type=event.frame().path("errorType").asText(),key=event.frame().path("failureKey").asText();
                        if(key.matches("IMPORT_[A-Z_]{1,70}"))failureKey=key;
                        if(reported.matches("IMPORT_[A-Z_]{1,60}"))log.warn("导入编辑Worker失败 edit={} stage={} type={} key={}",id,reported,
                                type.matches("[A-Za-z]{1,60}")?type:"UNKNOWN",key.matches("[A-Z][A-Z0-9_]{1,80}")?key:"UNCONFIRMED");
                    }sink.next(event);}
                }
                if(!ended||failed||result==null||audit==null)throw new IOException(failureKey==null?"IMPORT_EDIT_NOT_COMPLETED":failureKey);
                // 2. Worker及容器真实停止后才独立封存/验证；直到Java提交完成才发布成功。
                worker.stop();stopped=true;ledger.workerStopped(ledger.require(id,owner),lease,true,true);
                stage="CANDIDATE_VERIFY";
                if(cancelled.get())throw new IOException("IMPORT_EDIT_CANCELLED");
                containers.seal(id,owner);
                verification.verify(id,owner,result.path("authorizedStateHash").asText());
                stage="CANDIDATE_REVIEW";
                var review=reviews.prepare(id,owner);
                if("WAITING".equals(review.status())){
                    handedOff=true;
                    sink.next(frame(entry.runId(),++sequence,"import.review.requested",json.valueToTree(review)));
                    sink.next(frame(entry.runId(),++sequence,AgentEvent.END,json.createObjectNode()));
                    sink.complete();
                    return;
                }
                stage="PUBLICATION";
                publication.publish(id,owner,()->!cancelled.get());successful=true;
                if(!cancelled.get()){
                    sink.next(frame(entry.runId(),++sequence, "import.edit.model.audit", audit));
                    if(finalText!=null)sink.next(frame(entry.runId(),++sequence,AgentEvent.TEXT_MESSAGE,finalText));
                    var completed=json.createObjectNode().put("status","succeeded").put("summary","修改已合入。");
                    if(result.path("usage").isObject())completed.set("usage",result.path("usage"));
                    sink.next(frame(entry.runId(),++sequence,AgentEvent.RUN_COMPLETED,completed));
                    sink.next(frame(entry.runId(),++sequence,AgentEvent.END,json.createObjectNode()));sink.complete();
                }
            }catch(Exception error){
                String code=error instanceof ImportAgentCandidateVerification.Failure candidate?candidate.failureKey()
                        :error instanceof IOException?error.getMessage():null;
                if(code!=null&&code.matches("IMPORT_[A-Z_]{1,70}"))failureKey=code;
                log.warn("导入编辑未完成 edit={} stage={} type={} code={}",id,stage,error.getClass().getSimpleName(),
                        code!=null&&code.matches("IMPORT_[A-Z_]{1,80}")?code:"UNCONFIRMED");
                if(error instanceof InterruptedException)Thread.currentThread().interrupt();
                if(!errorSent&&failureKey!=null&&!cancelled.get()){
                    sink.next(frame(entry.runId(),++sequence,AgentEvent.ERROR,json.createObjectNode()
                            .put("code","TOOL_EXECUTION_FAILED").put("failureKey",failureKey)
                            .put("message","导入编辑未完成，请查看任务或恢复状态。").put("retryable",false)));
                }
                failure=new IOException("导入编辑未完成，请查看任务或恢复状态。");
            }finally{
                // 3. 独立线程完成有限时停止与账本收口，订阅取消不能再次中断清理。
                final var cleanupWorker=worker;final var cleanupLease=lease;
                final boolean workerStopped=stopped,committed=successful,waitingReview=handedOff;
                final String cleanupReason=failureKey;
                var cleanupTask=new java.util.concurrent.FutureTask<Boolean>(()->{
                    if(waitingReview){running.remove(id);return workerStopped;}
                    return cleanupResources(prepared,cleanupWorker,cleanupLease,workerStopped,committed,cancelled.get(),cleanupReason);
                });
                Thread.ofVirtual().name("import-edit-cleanup-"+id).start(cleanupTask);
                boolean interrupted=Thread.interrupted();
                try{
                    while(true){
                        try{if(cleanupTask.get()&&reserved)pool.releaseExternal();break;}
                        catch(InterruptedException error){interrupted=true;}
                        catch(java.util.concurrent.ExecutionException error){
                            log.warn("导入编辑清理未确认 edit={}",id);break;
                        }
                    }
                    if(failure!=null&&!cancelled.get())sink.error(failure);
                }finally{if(interrupted)Thread.currentThread().interrupt();}
            }
        }).takeUntilOther(Mono.delay(properties.getRunDeadline()).flatMap(ignored->Mono.error(new java.util.concurrent.TimeoutException("导入编辑超过时间上限"))));
    }

    /** 测试与旧调用方使用的固定 reason 入口；正式执行走带 reason 的重载。 */
    private boolean cleanupResources(ImportAgentEditPreparation.Prepared prepared,ImportNativeWorker worker,
            UUID lease,boolean stopped,boolean successful,boolean cancelled){
        return cleanupResources(prepared,worker,lease,stopped,successful,cancelled,null);
    }

    private boolean cleanupResources(ImportAgentEditPreparation.Prepared prepared,ImportNativeWorker worker,
            UUID lease,boolean stopped,boolean successful,boolean cancelled,String reason){
        var entry=prepared.row().identity();var id=entry.id();long owner=entry.ownerId();
                boolean released=successful;
                try{
                    if(worker==null&&lease!=null){
                        ImportNativeWorker.stopUnstarted(id,lease);
                        ledger.workerNotStarted(ledger.require(id,owner),lease);
                        stopped=true;
                    }
                    if(worker!=null&&!stopped){worker.stop();stopped=true;}
                    if(worker!=null&&lease!=null&&stopped)ledger.workerStopped(ledger.require(id,owner),lease,true,true);
                    if(!successful){
                        var row=ledger.require(id,owner);
                        if(!List.of("SUCCEEDED","FAILED","CANCELLED","STALE").contains(row.state())){
                            if(!"NONE".equals(row.publishPhase())){
                                row=ledger.publicationRecovery(row);var recoveryRow=row;
                                coordinator.withLock(entry.appId(),()->{try{publication.recover(recoveryRow);return null;}catch(IOException e){throw new IllegalStateException("PUBLICATION_RECOVERY_REQUIRED");}});
                            }else{
                                publication.discardOutputs(row);ledger.recoveryRequired(row);
                                if(reason==null)preparation.finishNotReady(prepared,cancelled);
                                else preparation.finishNotReady(prepared,cancelled,reason);
                            }
                        }
                        released=List.of("SUCCEEDED","FAILED","CANCELLED","STALE").contains(ledger.require(id,owner).state());
                    }
                }catch(Exception cleanup){
                    if(cleanup instanceof InterruptedException)Thread.currentThread().interrupt();
                    log.warn("导入编辑清理未完成 edit={} type={}",id,cleanup.getClass().getSimpleName());
                    try{var row=ledger.require(id,owner);if(!List.of("SUCCEEDED","FAILED","CANCELLED","STALE").contains(row.state())){
                        if("NONE".equals(row.publishPhase()))ledger.recoveryRequired(row);else ledger.publicationRecovery(row);
                    }}catch(RuntimeException ignored){ }
                }
                if(stopped)running.remove(id);
                return released;

    }

    private AgentEvent frame(String run,int seq,String event,JsonNode payload){
        var body=payload.deepCopy();var node=(com.fasterxml.jackson.databind.node.ObjectNode)body;
        node.put("v",1).put("runId",run).put("seq",seq).put("ts",System.currentTimeMillis()).put("event",event);
        return new AgentEvent(run,seq,node.path("ts").asLong(),event,node);
    }

    @jakarta.annotation.PreDestroy
    public void stopWorkers(){running.values().forEach(worker->{try{worker.stop();}catch(Exception ignored){ }});}
}
