package com.dbts.glyahhaigeneratecode.projectimport;

import com.dbts.glyahhaigeneratecode.agent.AgentRunRequest;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.UUID;
import java.util.function.Supplier;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

class ImportAgentEditPreparationTest {
    @TempDir Path root;

    @Test void ordinaryGenerationDoesNotFreezeImportOrCreateLedger()throws Exception {
        var ledger=mock(ImportAgentEditLedger.class);var gate=mock(ImportWorkspaceGate.class);
        var properties=new ImportProperties();properties.setStagingRoot(root.resolve("import"));
        var service=new ImportAgentEditPreparation(mock(ImportWorkspaceCoordinator.class),gate,
                mock(ImportStartupConfigurationRepository.class),ledger,properties,mock(ImportDiskVolume.class),mock(ImportAgentPermissionServiceImpl.class));
        var request=AgentRunRequest.builder().appId("101").userId("7").runId("201").build();
        assertSame(request,service.prepare(request).request());verifyNoInteractions(ledger,gate);
    }

    @Test void releaseFailureKeepsRecoveryRequiredAndNeverClosesSlot()throws Exception {
        var ledger=mock(ImportAgentEditLedger.class);var volumes=mock(ImportDiskVolume.class);
        var properties=new ImportProperties();properties.setStagingRoot(root.resolve("import"));
        var service=new ImportAgentEditPreparation(mock(ImportWorkspaceCoordinator.class),mock(ImportWorkspaceGate.class),
                mock(ImportStartupConfigurationRepository.class),ledger,properties,volumes,mock(ImportAgentPermissionServiceImpl.class));
        var start=Instant.now();var e=new ImportAgentEditLedger.Entry(UUID.randomUUID(),7,101,UUID.randomUUID(),"201",
                "a".repeat(64),0,0,UUID.randomUUID(),1,start,start.plusSeconds(60));
        var row=new ImportAgentEditLedger.Row(e,"PREPARING",2,false,"[{\"kind\":\"VOLUME\",\"state\":\"READY\"}]","PENDING","NONE",false);
        when(ledger.require(e.id(),7)).thenReturn(row);
        doThrow(new java.io.IOException("release failed")).when(volumes).releaseEdit(e,1L<<30);
        assertThrows(RuntimeException.class,()->service.finishNotReady(new ImportAgentEditPreparation.Prepared(null,row),false));
        verify(ledger).recoveryRequired(row);verify(ledger,never()).finishReleasedVolume(any(),anyString());
        verify(ledger,never()).finishWithoutResources(any(),anyString());verify(ledger,never()).volumeReleased(any());
    }

    @Test void cancelledPreparationClosesOnlyAfterActualVolumeRelease()throws Exception {
        var ledger=mock(ImportAgentEditLedger.class);var volumes=mock(ImportDiskVolume.class);
        var properties=new ImportProperties();properties.setStagingRoot(root.resolve("import"));
        var service=new ImportAgentEditPreparation(mock(ImportWorkspaceCoordinator.class),mock(ImportWorkspaceGate.class),
                mock(ImportStartupConfigurationRepository.class),ledger,properties,volumes,mock(ImportAgentPermissionServiceImpl.class));
        var start=Instant.now();var e=new ImportAgentEditLedger.Entry(UUID.randomUUID(),7,101,UUID.randomUUID(),"201",
                "a".repeat(64),0,0,UUID.randomUUID(),1,start,start.plusSeconds(60));
        var row=new ImportAgentEditLedger.Row(e,"PREPARING",2,false,"[{\"state\":\"READY\"}]","PENDING","NONE",false);
        var cancelled=new ImportAgentEditLedger.Row(e,"CANCELLING",3,true,row.resources(),"PENDING","NONE",false);
        var released=new ImportAgentEditLedger.Row(e,"CANCELLING",4,true,"[{\"state\":\"RELEASED\"}]","PENDING","NONE",false);
        when(ledger.require(e.id(),7)).thenReturn(row);when(ledger.cancel(row)).thenReturn(cancelled);
        when(ledger.volumeReleased(cancelled)).thenReturn(released);
        service.finishNotReady(new ImportAgentEditPreparation.Prepared(null,row),true);
        var order=inOrder(ledger,volumes);order.verify(ledger).cancel(row);order.verify(volumes).stopEditContainers(e,1L<<30);
        order.verify(ledger).containerStopped(eq(cancelled),any());order.verify(volumes).releaseEdit(e,1L<<30);
        order.verify(ledger).volumeReleased(cancelled);order.verify(ledger).candidateReleased(released);order.verify(ledger).viewReleased(released);
        order.verify(ledger).finishReleasedVolume(released,"CLIENT_CANCELLED");
    }

    @Test void unconfirmedContainerStopDoesNotReleaseCancelledResources()throws Exception {
        var ledger=mock(ImportAgentEditLedger.class);var volumes=mock(ImportDiskVolume.class);
        var properties=new ImportProperties();properties.setStagingRoot(root.resolve("import"));
        var service=new ImportAgentEditPreparation(mock(ImportWorkspaceCoordinator.class),mock(ImportWorkspaceGate.class),
                mock(ImportStartupConfigurationRepository.class),ledger,properties,volumes,mock(ImportAgentPermissionServiceImpl.class));
        var start=Instant.now();var e=new ImportAgentEditLedger.Entry(UUID.randomUUID(),7,101,UUID.randomUUID(),"201",
                "a".repeat(64),0,0,UUID.randomUUID(),1,start,start.plusSeconds(60));
        var row=new ImportAgentEditLedger.Row(e,"CANCELLING",3,true,"[{\"state\":\"READY\"}]","PENDING","NONE",false);
        when(ledger.require(e.id(),7)).thenReturn(row);when(volumes.stopEditContainers(e,1L<<30)).thenThrow(new java.io.IOException("private-stop-error"));
        assertThrows(RuntimeException.class,()->service.finishNotReady(new ImportAgentEditPreparation.Prepared(null,row),true));
        verify(ledger).recoveryRequired(row);verify(volumes,never()).releaseEdit(any(),anyLong());
        verify(ledger,never()).volumeReleased(any());verify(ledger,never()).finishReleasedVolume(any(),anyString());
    }

    @Test void formalPreparationFreezesFullContentAndContextUnderLockBeforeSendingFrame()throws Exception {
        var coordinator=mock(ImportWorkspaceCoordinator.class);var gate=mock(ImportWorkspaceGate.class);
        var configurations=mock(ImportStartupConfigurationRepository.class);var ledger=mock(ImportAgentEditLedger.class);
        when(coordinator.withLock(eq(101L),any())).thenAnswer(call->((Supplier<?>)call.getArgument(1)).get());
        UUID task=UUID.randomUUID();
        when(gate.requireAgentEditAllowed(101L)).thenReturn(new ImportTaskRepository.Publication(task,101,7,"READY","a".repeat(64),1,Instant.now()));
        when(configurations.current(101,7,task)).thenReturn(new ImportStartupConfiguration.State(101,7,task,4,9,".",java.util.List.of()));
        var permissions=mock(ImportAgentPermissionServiceImpl.class);
        var policy=new ImportAgentSettingsRepository.Settings(7,101,"app:101","MANUAL","default",null,2,
                UUID.randomUUID(),"d".repeat(64),UUID.randomUUID(),Instant.now().plusSeconds(3600));
        when(permissions.freeze(any())).thenReturn(policy);
        when(ledger.open(any(),eq(policy))).thenAnswer(call->new ImportAgentEditLedger.Row(call.getArgument(0),"PREPARING",0,false,"[]","PENDING","NONE",false));
        var properties=new ImportProperties();properties.setStagingRoot(root.resolve("import"));
        var volumes=mock(ImportDiskVolume.class);
        when(ledger.planVolume(any(),anyLong())).thenAnswer(call->call.getArgument(0));
        when(ledger.volumeReady(any(),any())).thenAnswer(call->call.getArgument(0));
        when(ledger.qualified(any())).thenReturn(true);
        when(volumes.createEdit(any(),anyLong())).thenReturn(new ObjectMapper().createObjectNode());
        when(volumes.prepareEditView(any(),anyLong(),any())).thenAnswer(call->{
            var e=(ImportAgentEditLedger.Entry)call.getArgument(0);
            return new ObjectMapper().createObjectNode().put("viewPath",ImportDiskVolume.editPath(e)+"/view")
                    .put("viewHash","b".repeat(64)).put("viewStateHash","c".repeat(64));
        });
        var service=new ImportAgentEditPreparation(coordinator,gate,configurations,ledger,properties,volumes,permissions);
        var workspace=Files.createDirectory(root.resolve("project_101"));Files.writeString(workspace.resolve("app.py"),"before");
        Files.writeString(workspace.resolve(".env"),"test-private-marker");Files.createDirectory(workspace.resolve("empty"));
        var request=AgentRunRequest.builder().appId("101").userId("7").runId("201").importTaskId(task.toString())
                .workspaceRoot(root.toString()).workspaceName("project_101").deadlineMs(60_000L).build();
        var prepared=service.prepare(request);var entry=prepared.row().identity();
        assertEquals(4,entry.configRevision());assertEquals(9,entry.contextEpoch());assertEquals("201",entry.runId());
        assertEquals(entry.id().toString(),prepared.request().getImportEditId());
        var frame=new ObjectMapper().readTree(prepared.request().toFrameJson(new ObjectMapper()));
        assertTrue(frame.path("workspace").path("contextEpoch").isTextual());
        assertFalse(frame.toString().contains("test-private-marker"));
        assertEquals(ImportDiskVolume.editPath(entry)+"/view",prepared.request().getImportViewPath());
        assertEquals("MANUAL",prepared.request().getImportPermissionMode());
        assertEquals("2",prepared.request().getImportPolicyRevision());
        assertEquals(policy.sessionEpoch().toString(),prepared.request().getImportSessionEpoch());
        assertEquals(policy.serverGeneration().toString(),prepared.request().getImportServerGeneration());
        assertEquals("default",prepared.request().getImportModelProfileId());
        var order=inOrder(permissions,ledger,volumes);
        order.verify(permissions).freeze(request);
        order.verify(ledger).open(any(),eq(policy));
        order.verify(ledger).planView(prepared.row());
        order.verify(volumes).prepareEditView(eq(entry),eq(1L<<30),eq(workspace));
        order.verify(ledger).viewReady(eq(prepared.row()),any());
        Files.writeString(workspace.resolve(".env"),"next-private-marker");
        var changed=service.prepare(request);assertNotEquals(entry.sourceHash(),changed.row().identity().sourceHash());
        when(ledger.require(entry.id(),7)).thenReturn(prepared.row());
        service.finishNotReady(prepared,false);verify(ledger).finishWithoutResources(prepared.row(),"IMPORT_EDIT_NOT_READY");
        when(gate.requireAgentEditAllowed(101L)).thenReturn(new ImportTaskRepository.Publication(task,101,8,"READY","a".repeat(64),1,Instant.now()));
        assertThrows(RuntimeException.class,()->service.prepare(request));
        verify(ledger,times(2)).open(any(),eq(policy));
        reset(volumes);
        when(permissions.freeze(any())).thenThrow(new IllegalStateException("POLICY_REVOKED"));
        when(gate.requireAgentEditAllowed(101L)).thenReturn(new ImportTaskRepository.Publication(task,101,7,"READY","a".repeat(64),1,Instant.now()));
        assertThrows(RuntimeException.class,()->service.prepare(request));
        verifyNoInteractions(volumes);
        verify(ledger,times(2)).open(any(),eq(policy));
    }
}
