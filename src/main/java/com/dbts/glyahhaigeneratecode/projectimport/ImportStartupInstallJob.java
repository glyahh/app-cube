package com.dbts.glyahhaigeneratecode.projectimport;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

/**
 * 安装任务的冻结身份与状态。状态只登记进度，不代替真实执行、停止和清理证据。
 * 同一 requestId 只对应一个任务；重新安装必须开新 requestId 和新任务。
 */
public record ImportStartupInstallJob(UUID jobId,UUID requestId,long appId,long ownerId,
        UUID planId,String planRevision,String contentHash,String requestHash,
        State state,long revision,boolean cancelRequested,
        String failureStage,String failureReason,
        UUID executorId,long executorPid,Instant executorStart,
        Instant startedAt,Instant finishedAt,
        List<UUID> executionIds,List<ImportProcessRunner.Resource> resources,
        List<Stage> stages,UUID preparationId,String cleanup,Boolean approved,List<Probe> probes,Instant createdAt){
    public record Probe(String name,String containerId){
        public Probe{if(name==null||!name.matches("glyahh-imp-exec-pinprobe-[0-9a-f-]{36}")||containerId!=null&&!containerId.matches("[0-9a-f]{64}"))throw new IllegalArgumentException("准入资源身份无效。");}
    }
    public ImportStartupInstallJob(UUID jobId,UUID requestId,long appId,long ownerId,UUID planId,String planRevision,String contentHash,String requestHash,
        State state,long revision,boolean cancelRequested,String failureStage,String failureReason,UUID executorId,long executorPid,Instant executorStart,
        Instant startedAt,Instant finishedAt,List<UUID> executionIds,List<ImportProcessRunner.Resource> resources,List<Stage> stages,UUID preparationId,String cleanup){
        this(jobId,requestId,appId,ownerId,planId,planRevision,contentHash,requestHash,state,revision,cancelRequested,failureStage,failureReason,executorId,executorPid,executorStart,
            startedAt,finishedAt,executionIds,resources,stages,preparationId,cleanup,true,List.of(),Instant.now());
    }
    public enum State {PENDING,PREPARING,INSTALLING,VERIFYING,SUCCEEDED,FAILED,REJECTED,CANCELLING,CANCELLED,STOP_FAILED,RECOVERY_REQUIRED}

    /** 阶段摘要：脱敏的固定类型和耗时，不含命令正文、路径、环境变量或异常消息。 */
    public record Stage(String stage,String commandType,String status,String reason,Integer exitCode,
                        Instant startTime,Instant endTime,Long durationMs,String executionId){
        public Stage{
            if(stage==null||stage.isBlank()||status==null||status.isBlank())throw new IllegalArgumentException("安装阶段身份无效。");
            if(reason!=null&&!reason.matches("[A-Za-z][A-Za-z0-9_:,]{0,160}"))throw new IllegalArgumentException("安装阶段原因无效。");
        }
    }

    /** 最终 preparation 结果的只读摘要；查询任务时直接读登记表，不重新读取计划。 */
    public record PreparationSummary(String preparationId,String kind,String decision,String state,String reason){ }

    public record View(String jobId,String requestId,String appId,String planId,String planRevision,
        String state,String revision,boolean cancelRequested,String failureStage,String failureReason,
        Instant startedAt,Instant finishedAt,List<String> executionIds,List<Stage> stages,
        String preparationId,String cleanup,PreparationSummary preparation){ }

    public ImportStartupInstallJob{
        if(jobId==null||requestId==null||appId<=0||ownerId<=0||planId==null||state==null||revision<0
            ||executorId==null||executorPid<=0||executorStart==null)throw new IllegalArgumentException("安装任务身份或状态无效。");
        if(planRevision==null||!planRevision.matches("[0-9a-f]{64}"))throw new IllegalArgumentException("安装计划版本无效。");
        if(contentHash==null||!contentHash.matches("[0-9a-f]{64}"))throw new IllegalArgumentException("安装内容身份无效。");
        if(requestHash==null||!requestHash.matches("[0-9a-f]{64}"))throw new IllegalArgumentException("安装请求摘要无效。");
        if(!List.of("PENDING","DONE","UNVERIFIED","FAILED").contains(cleanup))throw new IllegalArgumentException("安装清理状态无效。");
        probes=List.copyOf(probes==null?List.of():probes);
        executionIds=List.copyOf(executionIds==null?List.of():executionIds);
        resources=List.copyOf(resources==null?List.of():resources);
        resources.forEach(ImportStartupInstallJob::requireResource);
        stages=List.copyOf(stages==null?List.of():stages);
        boolean terminal=isTerminal(state);
        if(terminal!=(finishedAt!=null))throw new IllegalArgumentException("安装终态与结束时刻不一致。");
        if(state==State.SUCCEEDED&&cancelRequested)throw new IllegalArgumentException("安装成功不能带取消意图。");
        if(state==State.CANCELLED&&!cancelRequested)throw new IllegalArgumentException("安装取消缺少取消意图。");
    }

    public static boolean isTerminal(State state){
        return state==State.SUCCEEDED||state==State.FAILED||state==State.REJECTED||state==State.CANCELLED;
    }
    /** 占用额度和删除保护的状态：未完成或资源尚未核清。 */
    public boolean reservesAdmission(){return !isTerminal(state);}

    public View view(PreparationSummary preparation){
        return new View(jobId.toString(),requestId.toString(),Long.toString(appId),planId.toString(),planRevision,
            state.name(),Long.toString(revision),cancelRequested,failureStage,failureReason,startedAt,finishedAt,
            executionIds.stream().map(UUID::toString).toList(),stages,
            preparationId==null?null:preparationId.toString(),cleanup,preparation);
    }

    public void requireTransition(State next,String reason){
        boolean allowed=switch(state){
            case PENDING -> List.of(State.PREPARING,State.SUCCEEDED,State.REJECTED,State.CANCELLING,State.FAILED,State.RECOVERY_REQUIRED,State.STOP_FAILED).contains(next);
            case PREPARING,INSTALLING,VERIFYING -> List.of(State.PREPARING,State.INSTALLING,State.VERIFYING,State.SUCCEEDED,State.REJECTED,State.FAILED,State.CANCELLING,State.STOP_FAILED,State.RECOVERY_REQUIRED).contains(next);
            case CANCELLING -> List.of(State.CANCELLED,State.STOP_FAILED,State.RECOVERY_REQUIRED).contains(next);
            case STOP_FAILED,RECOVERY_REQUIRED -> List.of(State.CANCELLING,State.CANCELLED,State.FAILED,State.RECOVERY_REQUIRED,State.STOP_FAILED).contains(next)&&next!=state;
            default -> false;
        };
        if(!allowed)throw new IllegalArgumentException("安装状态不能这样迁移。");
        if(List.of(State.FAILED,State.REJECTED,State.STOP_FAILED,State.RECOVERY_REQUIRED).contains(next)
            &&(reason==null||!reason.matches("[A-Za-z][A-Za-z0-9_:,]{0,160}")))
            throw new IllegalArgumentException("安装失败缺少固定原因。");
    }

    public static void requireResource(ImportProcessRunner.Resource resource){
        if(resource==null||resource.pid()<=0||resource.started()==null)throw new IllegalArgumentException("安装资源身份无法核实。");
    }
}
