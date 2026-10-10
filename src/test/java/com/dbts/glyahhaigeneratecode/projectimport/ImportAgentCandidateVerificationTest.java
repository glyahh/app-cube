package com.dbts.glyahhaigeneratecode.projectimport;

import com.dbts.glyahhaigeneratecode.exception.MyException;
import com.dbts.glyahhaigeneratecode.service.support.WorkspacePathResolver;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import java.nio.file.*;
import java.time.Instant;
import java.util.*;
import java.util.function.Supplier;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;
import static org.mockito.ArgumentMatchers.*;

class ImportAgentCandidateVerificationTest {
    @TempDir Path temporary;
    record Fixture(ImportAgentCandidateVerification service,ImportAgentEditLedger ledger,ImportDiskVolume volumes,
                   ImportWorkspaceGate gate,ImportAgentEditLedger.Row row,Path source,ImportProperties properties){ }
    Fixture fixture()throws Exception{return fixture(mock(ImportAgentEditLedger.class));}
    Fixture fixture(ImportAgentEditLedger ledger)throws Exception{
        var volumes=mock(ImportDiskVolume.class);var gate=mock(ImportWorkspaceGate.class);
        var coordinator=mock(ImportWorkspaceCoordinator.class);var paths=mock(WorkspacePathResolver.class);
        when(coordinator.withLock(anyLong(),any())).thenAnswer(call->((Supplier<?>)call.getArgument(1)).get());
        var properties=new ImportProperties();properties.setStagingRoot(temporary.resolve("private/staging"));
        var now=Instant.now();var process=ProcessHandle.current();var e=new ImportAgentEditLedger.Entry(UUID.randomUUID(),7,101,UUID.randomUUID(),"201","a".repeat(64),0,0,UUID.randomUUID(),process.pid(),process.info().startInstant().orElseThrow(),now.plusSeconds(60));
        var row=new ImportAgentEditLedger.Row(e,"SEALING",9,false,"[]","PENDING","NONE",true);
        if(mockingDetails(ledger).isMock())when(ledger.require(e.id(),7)).thenReturn(row);
        Path source=temporary.resolve("source");Files.createDirectory(source);Files.writeString(source.resolve("app.py"),"original");
        when(paths.outputDir(anyLong(),any())).thenReturn(source);
        return new Fixture(new ImportAgentCandidateVerification(ledger,coordinator,gate,paths,volumes,properties),ledger,volumes,gate,row,source,properties);
    }
    @Test void validServiceFlowVerifiesSourceAndCandidateBeforePersistingProof()throws Exception{
        validServiceFlow(mock(ImportAgentEditLedger.class),null);
    }
    ImportAgentCandidateVerifier.Verified validServiceFlow(ImportAgentEditLedger ledger,
            java.util.function.BiFunction<ImportAgentEditLedger.Entry,String,ImportAgentEditLedger.Row> prepare)throws Exception{
        var f=fixture(ledger);var helper=new ImportAgentFrozenTest();var core=helper.fixture();
        Files.delete(f.source().resolve("app.py"));
        for(var file:core.source().files().entrySet())Files.write(f.source().resolve(file.getKey()),file.getValue());
        var old=f.row().identity();
        var snapshots=new ImportStartupSnapshotStore(temporary.resolve("snapshot-check"),List.of(),100,1<<20);
        var snapshot=snapshots.describe(old.id(),f.source(),()->true);
        var entry=new ImportAgentEditLedger.Entry(old.id(),7,101,old.importTaskId(),old.runId(),snapshot.contentHash(),0,0,old.executorId(),old.executorPid(),old.executorStart(),old.expiresAt());
        long bytes=64L<<20;var row=prepare==null?new ImportAgentEditLedger.Row(entry,"SEALING",9,false,"[{\"diskBytes\":67108864}]","PENDING","NONE",true)
                :prepare.apply(entry,core.state().path("sourceHash").asText());
        // 数据库时间按微秒持久化；平台夹具绑定重读后的真实身份，而不是插入前的纳秒对象。
        entry=row.identity();
        if(mockingDetails(ledger).isMock()){
            when(ledger.require(entry.id(),7)).thenReturn(row);when(ledger.qualifiedSealing(row)).thenReturn(true);
        }
        when(f.gate().requireAgentEditAllowed(101L)).thenReturn(new ImportTaskRepository.Publication(entry.importTaskId(),101,7,"READY","c".repeat(64),1,Instant.now()));
        var json=new com.fasterxml.jackson.databind.ObjectMapper();
        core.state().put("root",ImportDiskVolume.editPath(entry)+"/view");
        byte[] state=json.writeValueAsBytes(core.state());String stateHash=helper.hash(state),viewSourceHash=core.state().path("sourceHash").asText();
        byte[] fixed=helper.bytes("API_TOKEN=\"fixture-private-456\"\nPORT=9090\n");
        var candidate=helper.access(Map.of("config.py",fixed,".env",core.source().files().get(".env")));
        String candidateHash=ImportAgentCandidateVerifier.inventoryHash(candidate.inventory());
        var manifest=json.createObjectNode().put("appId","101").put("ownerId","7").put("runId","201").put("editId",entry.id().toString())
                .put("contentHash",entry.sourceHash()).put("diskBytes",bytes).put("schemaVersion",1).put("formatVersion",3)
                .put("authorizedStateHash",stateHash).put("viewSourceHash",viewSourceHash).put("candidateHash",candidateHash);
        manifest.set("files",helper.stamps(candidate.inventory().files()));manifest.set("directories",json.valueToTree(List.of(".")));
        manifest.set("authorized",core.state().path("authorized"));manifest.set("retainedOutputs",helper.stamps(Map.of("test-output.json",core.view().inventory().files().get("test-output.json"))));
        var change=manifest.putArray("changes").addObject().put("path","config.py");
        var before=core.source().inventory().files().get("config.py");var after=candidate.inventory().files().get("config.py");
        change.set("before",json.valueToTree(List.of(before.sha256(),before.bytes(),before.mode())));
        change.set("after",json.valueToTree(List.of(after.sha256(),after.bytes(),after.mode())));
        byte[] body=json.writeValueAsBytes(manifest);String manifestHash=helper.hash(body);
        var receipt=json.createObjectNode().put("candidateHash",candidateHash).put("candidateManifestHash",manifestHash)
                .put("appId","101").put("ownerId","7").put("runId","201").put("editId",entry.id().toString())
                .put("contentHash",entry.sourceHash()).put("formatVersion",3).put("diskBytes",bytes).put("state","CANDIDATE_SEALED")
                .put("authorizedStateHash",stateHash).put("candidatePath",ImportDiskVolume.editPath(entry)+"/candidate")
                .put("candidateManifestPath",ImportDiskVolume.editPath(entry)+"/candidate-manifest.json");
        when(f.volumes().sealEditCandidate(entry,bytes,stateHash)).thenReturn(receipt);
        when(f.volumes().platformPath(f.source())).thenReturn("/trusted/source");
        if(mockingDetails(ledger).isMock())when(ledger.sealedViewSourceHash(row)).thenReturn(viewSourceHash);
        when(f.volumes().readEditDocument(eq(entry),eq(bytes),eq(stateHash),eq(candidateHash),eq(manifestHash),eq("private-state"),any())).thenReturn(state);
        when(f.volumes().readEditDocument(eq(entry),eq(bytes),eq(stateHash),eq(candidateHash),eq(manifestHash),eq("manifest"),any())).thenReturn(body);
        when(f.volumes().sourceAccess(eq(entry),eq(bytes),eq(stateHash),eq(candidateHash),eq(manifestHash),any(),any())).thenReturn(core.source());
        when(f.volumes().viewAccess(eq(entry),eq(bytes),eq(stateHash),eq(candidateHash),eq(manifestHash),any(),any())).thenReturn(core.view());
        when(f.volumes().candidateAccess(eq(entry),eq(bytes),eq(stateHash),eq(candidateHash),eq(manifestHash),any(),any())).thenReturn(candidate);
        var result=f.service().verify(entry.id(),7,stateHash);assertEquals(candidateHash,result.candidateHash());
        if(mockingDetails(ledger).isMock()){
            var order=inOrder(ledger,f.volumes());order.verify(ledger).planCandidate(row,stateHash);
            order.verify(f.volumes()).sealEditCandidate(entry,bytes,stateHash);order.verify(ledger).candidateReady(row,stateHash,receipt);
            order.verify(ledger).candidateVerified(eq(row),any(),eq(result));verify(ledger,never()).recoveryRequired(any());
        }
        return result;
    }
    @Test void revokedSealingQualificationDoesNotTouchPlatformAndKeepsRecovery()throws Exception{
        var f=fixture();var error=assertThrows(MyException.class,()->f.service().verify(f.row().identity().id(),7,"b".repeat(64)));
        assertFalse(error.getMessage().contains("original"));verifyNoInteractions(f.volumes());verify(f.ledger()).recoveryRequired(f.row());
        verify(f.ledger(),never()).planCandidate(any(),anyString());
    }
    @Test void foreignPublicationDoesNotCreateCandidate()throws Exception{
        var f=fixture();when(f.ledger().qualifiedSealing(f.row())).thenReturn(true);
        when(f.gate().requireAgentEditAllowed(101L)).thenReturn(new ImportTaskRepository.Publication(f.row().identity().importTaskId(),101,8,"READY","c".repeat(64),1,Instant.now()));
        assertThrows(MyException.class,()->f.service().verify(f.row().identity().id(),7,"b".repeat(64)));
        verifyNoInteractions(f.volumes());verify(f.ledger(),never()).planCandidate(any(),anyString());
    }
    @Test void fullJavaSourceVersionConflictPreventsCandidateIntent()throws Exception{
        var f=fixture();when(f.ledger().qualifiedSealing(f.row())).thenReturn(true);
        when(f.gate().requireAgentEditAllowed(101L)).thenReturn(new ImportTaskRepository.Publication(f.row().identity().importTaskId(),101,7,"READY","c".repeat(64),1,Instant.now()));
        assertThrows(MyException.class,()->f.service().verify(f.row().identity().id(),7,"b".repeat(64)));
        verifyNoInteractions(f.volumes());verify(f.ledger(),never()).planCandidate(any(),anyString());verify(f.ledger()).recoveryRequired(f.row());
    }
    @Test void previousExecutorPidOrReusedPidWithDifferentStartCannotVerifyOrMutate()throws Exception{
        var f=fixture();var e=f.row().identity();
        for(boolean reused:List.of(false,true)){
            var old=new ImportAgentEditLedger.Entry(e.id(),7,101,e.importTaskId(),e.runId(),e.sourceHash(),0,0,e.executorId(),
                    reused?e.executorPid():e.executorPid()+1,reused?e.executorStart().minusSeconds(1):e.executorStart(),e.expiresAt());
            var row=new ImportAgentEditLedger.Row(old,"SEALING",9,false,"[]","PENDING","NONE",true);
            when(f.ledger().require(e.id(),7)).thenReturn(row);
            assertThrows(MyException.class,()->f.service().verify(e.id(),7,"b".repeat(64)));
        }
        verifyNoInteractions(f.volumes(),f.gate());verify(f.ledger(),never()).recoveryRequired(any());
    }
    @Test void committingTerminalCancelledOrRecoveryTasksCannotBeChangedByRevalidation()throws Exception{
        var f=fixture();var e=f.row().identity();
        for(String state:List.of("COMMITTING","SUCCEEDED","FAILED","CANCELLED","RECOVERY_REQUIRED","EDITING")){
            when(f.ledger().require(e.id(),7)).thenReturn(new ImportAgentEditLedger.Row(e,state,10,false,"[]","PENDING","NONE",true));
            assertThrows(MyException.class,()->f.service().verify(e.id(),7,"b".repeat(64)));
        }
        when(f.ledger().require(e.id(),7)).thenReturn(new ImportAgentEditLedger.Row(e,"SEALING",10,true,"[]","PENDING","NONE",true));
        assertThrows(MyException.class,()->f.service().verify(e.id(),7,"b".repeat(64)));
        verifyNoInteractions(f.volumes(),f.gate());verify(f.ledger(),never()).recoveryRequired(any());
    }
}
