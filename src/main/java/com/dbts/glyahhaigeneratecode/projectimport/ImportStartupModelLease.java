package com.dbts.glyahhaigeneratecode.projectimport;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

/** 一个批准只能登记一个独占执行者；清理状态不能替代真实停止证明。 */
public record ImportStartupModelLease(UUID approvalId,long ownerId,long appId,long approvalRevision,UUID executorId,
        long executorPid,Instant executorStart,long revision,List<ImportProcessRunner.Resource> resources,String cleanup){
    public ImportStartupModelLease{
        if(approvalId==null||ownerId<=0||appId<=0||approvalRevision<=0||executorId==null||executorPid<=0||executorStart==null||revision<0
            ||resources==null||resources.size()>128||!List.of("PENDING","DONE","UNVERIFIED","FAILED").contains(cleanup))
            throw new IllegalArgumentException("模型执行租约无效。");
        resources=List.copyOf(resources);resources.forEach(ImportStartupScan::requireResource);
    }
}
