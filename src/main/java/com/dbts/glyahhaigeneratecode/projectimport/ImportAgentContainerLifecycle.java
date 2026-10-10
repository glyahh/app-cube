package com.dbts.glyahhaigeneratecode.projectimport;

import com.dbts.glyahhaigeneratecode.exception.ErrorCode;
import com.dbts.glyahhaigeneratecode.exception.MyException;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import java.util.UUID;

/** 当前执行者的容器创建准入；创建不等于启动、安装批准或模型完成。 */
@Service
@RequiredArgsConstructor
@Slf4j
public class ImportAgentContainerLifecycle {
    private final ImportAgentEditLedger ledger;
    private final ImportWorkspaceCoordinator coordinator;
    private final ImportDiskVolume volumes;

    /** 编辑准入仍不启动脚本；Worker必须另核对正式执行契约与硬限额。 */
    public ImportAgentEditLedger.Row beginEditing(UUID editId,long ownerId){
        var initial=ledger.require(editId,ownerId);
        return coordinator.withLock(initial.identity().appId(),()->{
            var row=ledger.require(editId,ownerId);
            requireCurrent(row,"PREPARING");
            String containerId=ledger.createdContainer(row);
            try{
                long bytes=new ObjectMapper().readTree(row.resources()).get(0).path("diskBytes").asLong();
                volumes.startEditContainer(row.identity(),bytes,containerId);
                return ledger.beginEditing(row);
            }catch(java.io.IOException|RuntimeException error){
                String reason=error.getMessage();
                log.warn("编辑容器启动未确认 editId={} reason={}",editId,
                        reason!=null&&reason.matches("DISK_VOLUME_[A-Z0-9_:]{1,100}")?reason:"START_UNCONFIRMED");
                ledger.recoveryRequired(ledger.require(editId,ownerId));
                throw new MyException(ErrorCode.OPERATION_ERROR,"编辑容器启动或硬限额未确认，请检查恢复状态。");
            }
        });
    }

    /** 当前执行者结束：真实停止/删除、持久证明、封存CAS，任何失败均不发布。 */
    public ImportAgentEditLedger.Row seal(UUID editId,long ownerId){
        var initial=ledger.require(editId,ownerId);
        return coordinator.withLock(initial.identity().appId(),()->{
            var row=ledger.require(editId,ownerId);
            requireCurrent(row,"EDITING");
            try{
                // 1. 平台核对冻结身份并确认实际容器消失，再保存长期停止证明。
                long bytes=new ObjectMapper().readTree(row.resources()).get(0).path("diskBytes").asLong();
                ledger.containerStopped(row,volumes.stopEditContainers(row.identity(),bytes));
                // 2. 重新核对业务资格与revision；停止不等于允许合入或成功。
                return ledger.beginSealing(row);
            }catch(java.io.IOException|RuntimeException error){
                ledger.recoveryRequired(ledger.require(editId,ownerId));
                throw new MyException(ErrorCode.OPERATION_ERROR,"编辑容器停止或封存未确认，请检查恢复状态。");
            }
        });
    }

    private void requireCurrent(ImportAgentEditLedger.Row row,String state){
        var e=row.identity();var process=ProcessHandle.current();
        if(!state.equals(row.state())||e.executorPid()!=process.pid()
                ||!e.executorStart().equals(process.info().startInstant().orElse(null))||!ledger.qualified(row))
            throw new MyException(ErrorCode.CONFLICT_ERROR,"编辑执行资格已变化。");
    }

    public String create(UUID editId,long ownerId){
        var initial=ledger.require(editId,ownerId);
        return coordinator.withLock(initial.identity().appId(),()->{
            var row=ledger.require(editId,ownerId);var e=row.identity();var process=ProcessHandle.current();
            if(e.executorPid()!=process.pid()||!e.executorStart().equals(process.info().startInstant().orElse(null))||!ledger.qualified(row)||ledger.hasContainer(row))
                throw new MyException(ErrorCode.CONFLICT_ERROR,"编辑容器创建资格已变化。");
            try{
                ledger.planContainer(row);
                long bytes=new ObjectMapper().readTree(row.resources()).get(0).path("diskBytes").asLong();
                var receipt=volumes.createEditContainer(e,bytes);ledger.containerCreated(row,receipt);
                return receipt.path("containerId").asText();
            }catch(java.io.IOException|RuntimeException error){
                ledger.recoveryRequired(ledger.require(editId,ownerId));
                throw new MyException(ErrorCode.OPERATION_ERROR,"编辑容器创建未确认，请检查恢复状态。");
            }
        });
    }
}
