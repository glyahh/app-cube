package com.dbts.glyahhaigeneratecode.projectimport;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

/** 扫描过程的冻结身份与状态；状态转换不能代替真实停止和清理证据。 */
public record ImportStartupScan(UUID scanId,UUID requestId,long appId,long ownerId,UUID importTaskId,long configRevision,long contextEpoch,
        String scope,Status status,long revision,UUID executorId,long executorPid,Instant executorStart,Instant expiresAt,
        List<ImportProcessRunner.Resource> resources,String contentHash,String cleanup,String error,boolean cancelRequested){
    public enum Status {QUEUED,SCANNING,CANCELLING,CANCELLED,SUCCEEDED,FAILED,STALE,STOP_FAILED,RECOVERY_REQUIRED}
    public record View(UUID scanId,UUID requestId,String appId,UUID importTaskId,String configRevision,String contextEpoch,
        String scope,Status status,String revision,String contentHash,String cleanup,String error,boolean cancelRequested){ }
    public ImportStartupScan{
        if(scanId==null||requestId==null||appId<=0||ownerId<=0||importTaskId==null||configRevision<0||contextEpoch<0||scope==null||scope.isBlank()
            ||status==null||revision<0||executorId==null||executorPid<=0||executorStart==null||expiresAt==null||!expiresAt.isAfter(executorStart)
            ||resources==null||!List.of("PENDING","DONE","UNVERIFIED","FAILED").contains(cleanup))throw new IllegalArgumentException("扫描身份或状态无效。");
        resources=List.copyOf(resources);resources.forEach(ImportStartupScan::requireResource);
        if(contentHash!=null&&!contentHash.matches("[0-9a-f]{64}"))throw new IllegalArgumentException("扫描内容身份无效。");
        if(List.of(Status.SUCCEEDED,Status.CANCELLED,Status.FAILED,Status.STALE).contains(status)&&!"DONE".equals(cleanup))throw new IllegalArgumentException("终态缺少停止清理确认。");
        if(status==Status.SUCCEEDED&&contentHash==null)throw new IllegalArgumentException("完成扫描缺少内容身份。");
        if(status==Status.SUCCEEDED&&cancelRequested||status==Status.CANCELLED&&!cancelRequested)throw new IllegalArgumentException("扫描终态与取消意图不一致。");
    }
    public View view(){return new View(scanId,requestId,Long.toString(appId),importTaskId,Long.toString(configRevision),Long.toString(contextEpoch),scope,status,Long.toString(revision),contentHash,cleanup,error,cancelRequested);}
    public boolean reservesAdmission(){return List.of(Status.QUEUED,Status.SCANNING,Status.CANCELLING,Status.STOP_FAILED,Status.RECOVERY_REQUIRED).contains(status);}
    public void requireTransition(Status next,String hash,String cleaned){
        boolean allowed=switch(status){
            case QUEUED -> List.of(Status.SCANNING,Status.CANCELLING,Status.FAILED,Status.STALE,Status.RECOVERY_REQUIRED).contains(next);
            case SCANNING -> List.of(Status.SUCCEEDED,Status.CANCELLING,Status.FAILED,Status.STALE,Status.STOP_FAILED,Status.RECOVERY_REQUIRED).contains(next);
            case CANCELLING -> List.of(Status.CANCELLED,Status.STOP_FAILED,Status.RECOVERY_REQUIRED).contains(next);
            case STOP_FAILED,RECOVERY_REQUIRED -> List.of(Status.CANCELLING,Status.CANCELLED,Status.FAILED,Status.RECOVERY_REQUIRED,Status.STOP_FAILED).contains(next)&&next!=status;
            case SUCCEEDED -> next==Status.STALE;
            default -> false;
        };
        if(!allowed||!List.of("PENDING","DONE","UNVERIFIED","FAILED").contains(cleaned))throw new IllegalArgumentException("扫描状态不能这样迁移。");
        if(List.of(Status.SUCCEEDED,Status.CANCELLED,Status.FAILED,Status.STALE).contains(next)&&!"DONE".equals(cleaned))
            throw new IllegalArgumentException("扫描停止或清理尚未确认。");
        if(next==Status.SUCCEEDED&&(hash==null||!hash.matches("[0-9a-f]{64}")))throw new IllegalArgumentException("缺少完整内容身份。");
    }
    public static void requireResource(ImportProcessRunner.Resource resource){
        if(resource==null||resource.pid()<=0||resource.started()==null)throw new IllegalArgumentException("扫描进程身份无法核实。");
    }
}
