package com.dbts.glyahhaigeneratecode.projectimport;

import com.dbts.glyahhaigeneratecode.exception.ErrorCode;
import com.dbts.glyahhaigeneratecode.exception.MyException;
import com.dbts.glyahhaigeneratecode.model.enums.CodeGenTypeEnum;
import com.dbts.glyahhaigeneratecode.service.support.WorkspacePathResolver;
import org.springframework.stereotype.Service;
import java.io.IOException;
import java.nio.file.Path;
import java.util.*;

/** 正式封存后的独立验证服务；只登记VERIFIED，不发布文件或发送业务成功。 */
@Service
public final class ImportAgentCandidateVerification {
    private final ImportAgentEditLedger ledger;
    private final ImportWorkspaceCoordinator coordinator;
    private final ImportWorkspaceGate gate;
    private final WorkspacePathResolver paths;
    private final ImportDiskVolume volumes;
    private final ImportStartupSnapshotStore snapshots;
    private final ImportAgentCandidateVerifier verifier;
    public ImportAgentCandidateVerification(ImportAgentEditLedger ledger,ImportWorkspaceCoordinator coordinator,
            ImportWorkspaceGate gate,WorkspacePathResolver paths,ImportDiskVolume volumes,ImportProperties properties)throws IOException{
        this.ledger=ledger;this.coordinator=coordinator;this.gate=gate;this.paths=paths;this.volumes=volumes;
        snapshots=new ImportStartupSnapshotStore(properties.getStagingRoot().resolveSibling("project_edit_private"),
                List.of(Path.of("temp/code_output"),Path.of("temp/code_deploy"),properties.getStagingRoot()),properties.getMaxFiles(),properties.getMaxSourceBytes());
        verifier=new ImportAgentCandidateVerifier(properties.getMaxFiles(),properties.getMaxSourceBytes());
    }

    /** 只接受平台冻结任务及授权摘要；路径由当前应用生成，不接受模型路径。 */
    public ImportAgentCandidateVerifier.Verified verify(UUID editId,long ownerId,String stateHash){
        var initial=ledger.require(editId,ownerId);
        return coordinator.withLock(initial.identity().appId(),()->{
            var row=ledger.require(editId,ownerId);var entry=row.identity();
            // 非当前执行者或非封存任务不能重入，也不能借失败处理修改正在发布/已结束的任务。
            var currentProcess=ProcessHandle.current();
            if(!"SEALING".equals(row.state())||row.cancelRequested()||!row.stopConfirmed()
                    ||entry.executorPid()!=currentProcess.pid()
                    ||!entry.executorStart().equals(currentProcess.info().startInstant().orElse(null)))throw conflict();
            try{
                // 1. 核对正式归属、来源和完整Java内容/属性版本，不能把Python库存摘要当内容版本。
                if(!ledger.qualifiedSealing(row))throw conflict();
                var publication=gate.requireAgentEditAllowed(entry.appId());
                if(publication==null||publication.ownerId()!=ownerId||!publication.taskId().equals(entry.importTaskId()))throw conflict();
                Path source=paths.outputDir(entry.appId(),CodeGenTypeEnum.AUTO);
                java.util.function.BooleanSupplier eligible=()->!Thread.currentThread().isInterrupted()&&ledger.qualifiedSealing(row);
                var snapshot=snapshots.describe(editId,source,eligible);
                if(!snapshot.contentHash().equals(entry.sourceHash()))throw conflict();
                // 2. 先登记候选意图；平台必须真正确认容器消失，回执只能进入SEALED。
                long bytes=new com.fasterxml.jackson.databind.ObjectMapper().readTree(row.resources()).get(0).path("diskBytes").asLong();
                ledger.planCandidate(row,stateHash);
                var receipt=volumes.sealEditCandidate(entry,bytes,stateHash);
                ledger.candidateReady(row,stateHash,receipt);
                String candidateHash=receipt.path("candidateHash").asText(),manifestHash=receipt.path("candidateManifestHash").asText();
                byte[] state=volumes.readEditDocument(entry,bytes,stateHash,candidateHash,manifestHash,"private-state",eligible);
                var frozen=verifier.freeze(state,stateHash,entry,volumes.platformPath(source),ledger.sealedViewSourceHash(row),
                        volumes.sourceAccess(entry,bytes,stateHash,candidateHash,manifestHash,verifier,eligible),
                        volumes.viewAccess(entry,bytes,stateHash,candidateHash,manifestHash,verifier,eligible),eligible);
                byte[] manifest=volumes.readEditDocument(entry,bytes,stateHash,candidateHash,manifestHash,"manifest",eligible);
                var verified=verifier.verify(manifest,manifestHash,candidateHash,entry,bytes,frozen,
                        volumes.candidateAccess(entry,bytes,stateHash,candidateHash,manifestHash,verifier,eligible),eligible);
                requireChanged(frozen,verified);
                // 3. 原内容与当前资格再次核对，成功只表示候选验证，不代表发布或业务成功。
                snapshots.verify(snapshot,source,eligible);
                if(!eligible.getAsBoolean())throw conflict();
                ledger.candidateVerified(row,frozen,verified);
                return verified;
            }catch(IOException|RuntimeException error){
                var current=ledger.require(editId,ownerId);
                if(List.of("SEALING","CANCELLING").contains(current.state()))ledger.recoveryRequired(current);
                String key=error instanceof IOException&&"IMPORT_EDIT_NO_CHANGES".equals(error.getMessage())
                        ?"IMPORT_EDIT_NO_CHANGES":"IMPORT_EDIT_CANDIDATE_VERIFY_FAILED";
                throw new Failure(key);
            }
        });
    }
    /** 修复链路不能将模型自评或原样候选发布成成功。 */
    static void requireChanged(ImportAgentCandidateVerifier.Frozen frozen,ImportAgentCandidateVerifier.Verified verified)throws IOException{
        if(verified.candidateHash().equals(ImportAgentCandidateVerifier.inventoryHash(frozen.original())))throw new IOException("IMPORT_EDIT_NO_CHANGES");
    }
    /** 只向执行层传递固定原因，不暴露验证输入或平台异常。 */
    static final class Failure extends MyException {
        private final String failureKey;
        Failure(String failureKey){super(ErrorCode.OPERATION_ERROR,"候选验证未完成，请检查恢复状态。");this.failureKey=failureKey;}
        String failureKey(){return failureKey;}
    }
    private static MyException conflict(){return new MyException(ErrorCode.CONFLICT_ERROR,"候选验证资格或原内容已变化。");}
}
