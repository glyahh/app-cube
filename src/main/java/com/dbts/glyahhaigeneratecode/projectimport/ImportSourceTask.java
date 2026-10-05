package com.dbts.glyahhaigeneratecode.projectimport;

import com.dbts.glyahhaigeneratecode.service.support.ProjectImportManifest.Options;
import java.time.Instant;
import java.util.List;
import java.util.UUID;

/** 尚无完整文件清单时保留真实来源与资源，不伪造主上传任务。 */
public record ImportSourceTask(UUID taskId,long ownerId,Definition definition,Status status,long revision,
        UUID credentialId,Long credentialVersion,String branch,String commit,List<ImportGitMetadata.Branch> branches,
        List<ImportProcessRunner.Resource> resources,boolean mainTaskCreated,String contentHash,UUID executorId,
        long executorPid,Instant executorStart,Instant expiresAt,String error,String cleanup) {
    public enum Status { RESOLVING_SOURCE,WAITING_BRANCH,READING_CONTENT,WAITING_CONFIRMATION,CANCELLING,CANCELLED,
        FAILED,STOP_FAILED,RECOVERY_REQUIRED,SUCCEEDED }
    public record Definition(String appName,ImportRepositorySource source,Options options){ }
    public record View(UUID taskId,Definition definition,Status status,String revision,String credentialVersion,UUID credentialId,
            String branch,String commit,List<ImportGitMetadata.Branch> branches,boolean mainTaskCreated,
            String contentHash,String error,String cleanup){ }
    public ImportSourceTask{branches=List.copyOf(branches);resources=List.copyOf(resources);}
    public View view(){return new View(taskId,definition,status,Long.toString(revision),credentialVersion==null?null:credentialVersion.toString(),
            credentialId,branch,commit,branches,mainTaskCreated,contentHash,error,cleanup);}
}
