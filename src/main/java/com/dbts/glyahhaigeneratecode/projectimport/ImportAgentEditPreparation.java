package com.dbts.glyahhaigeneratecode.projectimport;

import com.dbts.glyahhaigeneratecode.agent.AgentRunRequest;
import com.dbts.glyahhaigeneratecode.exception.ErrorCode;
import com.dbts.glyahhaigeneratecode.exception.MyException;
import org.springframework.stereotype.Service;

import java.io.IOException;
import java.nio.file.Path;
import java.time.Instant;
import java.util.List;
import java.util.UUID;

/** 正式入口的只读冻结准备；隔离执行和发布尚未接入，不能据此宣称编辑成功。 */
@Service
public class ImportAgentEditPreparation {
    private final ImportWorkspaceCoordinator coordinator;
    private final ImportWorkspaceGate gate;
    private final ImportStartupConfigurationRepository configurations;
    private final ImportAgentEditLedger ledger;
    private final ImportStartupSnapshotStore snapshots;
    private final ImportDiskVolume volumes;
    private final long diskBytes=ImportExecutionSandbox.Limits.defaults().diskBytes();
    private final UUID executorId=UUID.randomUUID();
    private final Instant executorStart=ProcessHandle.current().info().startInstant().orElseThrow();

    public record Prepared(AgentRunRequest request,ImportAgentEditLedger.Row row) { }

    public ImportAgentEditPreparation(ImportWorkspaceCoordinator coordinator,ImportWorkspaceGate gate,
            ImportStartupConfigurationRepository configurations,ImportAgentEditLedger ledger,
            ImportProperties properties,ImportDiskVolume volumes)throws IOException {
        this.coordinator=coordinator;this.gate=gate;this.configurations=configurations;this.ledger=ledger;
        this.volumes=volumes;
        snapshots=new ImportStartupSnapshotStore(properties.getStagingRoot().resolveSibling("project_edit_private"),
                List.of(Path.of("temp/code_output"),Path.of("temp/code_deploy"),properties.getStagingRoot()),
                properties.getMaxFiles(),properties.getMaxSourceBytes());
    }

    /** 调用者已持有主Agent应用锁；再协调扫描、文件编辑和来源变更。 */
    public Prepared prepare(AgentRunRequest request) {
        if(request.getImportTaskId()==null)return new Prepared(request,null);
        long appId=Long.parseLong(request.getAppId()),ownerId=Long.parseLong(request.getUserId());
        return coordinator.withLock(appId,()->{
            // 1. 重读权威来源与上下文，禁止排队请求携带旧身份。
            var publication=gate.requireAgentEditAllowed(appId);
            if(publication==null||publication.ownerId()!=ownerId
                    ||!publication.taskId().toString().equals(request.getImportTaskId()))throw conflict();
            var configuration=configurations.current(appId,ownerId,publication.taskId());
            UUID editId=UUID.randomUUID();
            ImportStartupSnapshotStore.Snapshot snapshot;
            try{snapshot=snapshots.describe(editId,Path.of(request.getWorkspaceRoot()).resolve(request.getWorkspaceName()),
                    ()->!Thread.currentThread().isInterrupted());}
            catch(IOException error){throw new MyException(ErrorCode.OPERATION_ERROR,"编辑内容冻结失败，本次未执行。");}
            // 2. 数据库独立校验归属、来源和上下文；没有资源登记就不创建执行副本。
            long deadline=request.getDeadlineMs()==null?1_800_000L:request.getDeadlineMs();
            if(deadline<=0||deadline>86_400_000L)throw new MyException(ErrorCode.PARAMS_ERROR,"编辑时限无效。");
            var entry=new ImportAgentEditLedger.Entry(editId,ownerId,appId,publication.taskId(),request.getRunId(),
                    snapshot.contentHash(),configuration.configRevision(),configuration.contextEpoch(),executorId,
                    ProcessHandle.current().pid(),executorStart,Instant.now().plusMillis(deadline));
            var row=ledger.open(entry);
            try{
                // 3. 创建前持久登记意图，平台独占卷核对成功后再更新READY；不使用宿主目录兜底。
                row=ledger.planVolume(row,diskBytes);
                if(!ledger.qualified(row))throw conflict();
                row=ledger.volumeReady(row,volumes.createEdit(entry,diskBytes));
                if(!ledger.qualified(row))throw conflict();
                // 4. 先登记视图与映射，再在已登记容量卷内复制；原配置始终留在平台侧。
                ledger.planView(row);
                var view=volumes.prepareEditView(entry,diskBytes,Path.of(request.getWorkspaceRoot()).resolve(request.getWorkspaceName()));
                snapshots.verify(snapshot,Path.of(request.getWorkspaceRoot()).resolve(request.getWorkspaceName()),
                        ()->!Thread.currentThread().isInterrupted());
                if(!ledger.qualified(row))throw conflict();
                ledger.viewReady(row,view);
                return new Prepared(request.toBuilder().importEditId(editId.toString()).importSourceHash(snapshot.contentHash())
                        .importConfigRevision(Long.toString(configuration.configRevision()))
                        .importContextEpoch(Long.toString(configuration.contextEpoch()))
                        .importVolumePath(ImportDiskVolume.editPath(entry)).importDiskBytes(diskBytes)
                        .importViewPath(view.path("viewPath").asText()).importViewHash(view.path("viewHash").asText())
                        .importViewStateHash(view.path("viewStateHash").asText()).build(),row);
            }catch(IOException|RuntimeException error){
                finishPrepared(ledger.require(entry.id(),ownerId),false,"EDIT_PREPARATION_FAILED");
                throw new MyException(ErrorCode.OPERATION_ERROR,"编辑隔离卷准备失败，本次未执行。");
            }
        });
    }

    /** 编辑未完成时释放已确认停止的资源，取消与失败原因分别保留。 */
    public void finishNotReady(Prepared prepared,boolean cancelled) {
        finishNotReady(prepared,cancelled,"IMPORT_EDIT_NOT_READY");
    }

    public void finishNotReady(Prepared prepared,boolean cancelled,String reason) {
        if(reason==null||!reason.matches("IMPORT_[A-Z_]{1,70}"))throw new IllegalArgumentException("编辑失败原因无效。");
        if(prepared.row()==null)return;
        var row=ledger.require(prepared.row().identity().id(),prepared.row().identity().ownerId());
        finishPrepared(row,cancelled,cancelled?"CLIENT_CANCELLED":reason);
    }

    private void finishPrepared(ImportAgentEditLedger.Row row,boolean cancelled,String reason){
        if(List.of("FAILED","CANCELLED","STALE").contains(row.state()))return;
        if(cancelled&&!row.cancelRequested())row=ledger.cancel(row);
        if("[]".equals(row.resources())){ledger.finishWithoutResources(row,reason);return;}
          try{
              ledger.containerStopped(row,volumes.stopEditContainers(row.identity(),diskBytes));
              volumes.releaseEdit(row.identity(),diskBytes);
            row=ledger.volumeReleased(row);
            ledger.candidateReleased(row);
            ledger.viewReleased(row);
            ledger.finishReleasedVolume(row,reason);
        }catch(IOException|RuntimeException error){
            ledger.recoveryRequired(ledger.require(row.identity().id(),row.identity().ownerId()));
            throw new MyException(ErrorCode.OPERATION_ERROR,"编辑资源释放未确认，请查看恢复状态。");
        }
    }

    private static MyException conflict(){return new MyException(ErrorCode.CONFLICT_ERROR,"编辑来源或归属已变化，请重新确认。");}
}
