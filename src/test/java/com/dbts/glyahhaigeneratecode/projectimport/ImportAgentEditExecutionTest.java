package com.dbts.glyahhaigeneratecode.projectimport;

import com.dbts.glyahhaigeneratecode.agent.*;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import java.io.IOException;
import java.time.Instant;
import java.util.UUID;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

class ImportAgentEditExecutionTest {
    @Test void modelFailureKeepsReasonAndNeverVerifiesOrPublishes() throws Exception {
        for (String key : java.util.List.of("IMPORT_EDIT_MODEL_BUDGET_EXHAUSTED", "IMPORT_EDIT_REPEATED_COMMAND_REJECTION")) {
            var ledger=mock(ImportAgentEditLedger.class);var preparation=mock(ImportAgentEditPreparation.class);
            var containers=mock(ImportAgentContainerLifecycle.class);var verification=mock(ImportAgentCandidateVerification.class);
            var publication=mock(ImportAgentPublication.class);var pool=mock(AgentWorkerPool.class);
            var id=UUID.randomUUID();var lease=UUID.randomUUID();var json=new ObjectMapper();
            var entry=new ImportAgentEditLedger.Entry(id,7,101,UUID.randomUUID(),"201","a".repeat(64),0,0,
                    UUID.randomUUID(),1,Instant.EPOCH,Instant.now().plusSeconds(60));
            var row=new ImportAgentEditLedger.Row(entry,"EDITING",1,false,"[]","PENDING","NONE",false);
            var closed=new ImportAgentEditLedger.Row(entry,"FAILED",2,true,"[]","DONE","NONE",true);
            var prepared=new ImportAgentEditPreparation.Prepared(AgentRunRequest.builder().runId("201").build(),row);
            when(ledger.require(id,7)).thenReturn(row);when(ledger.planWorker(row)).thenReturn(lease);
            when(containers.create(id,7)).thenReturn("unit-only");
            doAnswer(call->{when(ledger.require(id,7)).thenReturn(closed);return null;})
                    .when(preparation).finishNotReady(prepared,false,key);
            try(var construction=mockConstruction(ImportNativeWorker.class,(worker,context)->{
                when(worker.started()).thenReturn(Instant.EPOCH);
                when(worker.ready(any())).thenReturn(json.createObjectNode().put("nativePid","1").put("nativeStartTicks","1"));
                when(worker.read(any())).thenReturn(json.createObjectNode().put("v",1).put("runId","201").put("seq",0)
                        .put("ts",1).put("event","error").put("failureKey",key).toString(),
                        "{\"v\":1,\"runId\":\"201\",\"seq\":1,\"ts\":1,\"event\":\"__end__\"}");
            })) {
                var execution=new ImportAgentEditExecution(ledger,preparation,containers,verification,publication,
                        mock(ImportWorkspaceCoordinator.class),pool,new AgentProperties(),json);
                var events=new java.util.ArrayList<AgentEvent>();
                assertThrows(RuntimeException.class,()->execution.execute(prepared).doOnNext(events::add).blockLast());
                assertEquals(key,events.getFirst().frame().path("failureKey").asText());
                assertTrue(events.stream().noneMatch(event->AgentEvent.RUN_COMPLETED.equals(event.event())));
                verifyNoInteractions(verification);
                verify(publication,never()).publish(any(),anyLong(),any());
                verify(preparation).finishNotReady(prepared,false,key);
                verify(pool).releaseExternal();
            }
        }
    }
    @Test void launchFailureStopsWorkerBeforeClosingResourcesAndReturningPoolSlot() throws Exception {
        var ledger=mock(ImportAgentEditLedger.class);var preparation=mock(ImportAgentEditPreparation.class);
        var pool=mock(AgentWorkerPool.class);var id=UUID.randomUUID();var lease=UUID.randomUUID();
        var entry=new ImportAgentEditLedger.Entry(id,7,101,UUID.randomUUID(),"201","a".repeat(64),0,0,
                UUID.randomUUID(),1,Instant.EPOCH,Instant.now().plusSeconds(60));
        var row=new ImportAgentEditLedger.Row(entry,"PREPARING",1,false,"[]","PENDING","NONE",false);
        var closed=new ImportAgentEditLedger.Row(entry,"FAILED",2,true,"[]","DONE","NONE",true);
        var prepared=new ImportAgentEditPreparation.Prepared(AgentRunRequest.builder().runId("201").build(),row);
        when(ledger.require(id,7)).thenReturn(row);when(ledger.planWorker(row)).thenReturn(lease);
        doAnswer(call->{when(ledger.require(id,7)).thenReturn(closed);return null;})
                .when(preparation).finishNotReady(prepared,false,"IMPORT_NATIVE_START_FAILED");
        try(var construction=mockConstruction(ImportNativeWorker.class,(worker,context)->{
            doThrow(new IOException("IMPORT_NATIVE_START_FAILED")).when(worker).start();
        })){
            var execution=new ImportAgentEditExecution(ledger,preparation,mock(ImportAgentContainerLifecycle.class),
                    mock(ImportAgentCandidateVerification.class),mock(ImportAgentPublication.class),mock(ImportWorkspaceCoordinator.class),
                    pool,new AgentProperties(),new ObjectMapper());
            assertThrows(RuntimeException.class,()->execution.execute(prepared).blockLast());
            var worker=construction.constructed().getFirst();var order=inOrder(worker,ledger,preparation,pool);
            order.verify(worker).start();order.verify(worker).stop();
            order.verify(ledger).workerStopped(row,lease,true,true);
            order.verify(preparation).finishNotReady(prepared,false,"IMPORT_NATIVE_START_FAILED");order.verify(pool).releaseExternal();
            verify(ledger,never()).workerLaunching(any(),any(),anyLong(),any());
        }
    }

    @Test void unstartedLeaseNeedsStopTombstoneBeforeCancelledOrFailedTaskCanClose() throws Exception {
        for(boolean cancelled:new boolean[]{false,true}){
            var ledger=mock(ImportAgentEditLedger.class);var preparation=mock(ImportAgentEditPreparation.class);
            var entry=new ImportAgentEditLedger.Entry(UUID.randomUUID(),7,101,UUID.randomUUID(),"201","a".repeat(64),0,0,
                    UUID.randomUUID(),1,Instant.EPOCH,Instant.now().plusSeconds(60));
            var row=new ImportAgentEditLedger.Row(entry,"PREPARING",1,cancelled,"[]","PENDING","NONE",false);
            var closed=new ImportAgentEditLedger.Row(entry,cancelled?"CANCELLED":"FAILED",2,true,"[]","DONE","NONE",true);
            var prepared=new ImportAgentEditPreparation.Prepared(AgentRunRequest.builder().runId("201").build(),row);
            var lease=UUID.randomUUID();when(ledger.require(entry.id(),7)).thenReturn(row);
            doAnswer(call->{when(ledger.require(entry.id(),7)).thenReturn(closed);return null;})
                    .when(preparation).finishNotReady(prepared,cancelled);
            var execution=new ImportAgentEditExecution(ledger,preparation,mock(ImportAgentContainerLifecycle.class),
                    mock(ImportAgentCandidateVerification.class),mock(ImportAgentPublication.class),mock(ImportWorkspaceCoordinator.class),
                    mock(AgentWorkerPool.class),new AgentProperties(),new ObjectMapper());
            var cleanup=ImportAgentEditExecution.class.getDeclaredMethod("cleanupResources",ImportAgentEditPreparation.Prepared.class,
                    ImportNativeWorker.class,UUID.class,boolean.class,boolean.class,boolean.class);
            cleanup.setAccessible(true);
            try(var platform=mockStatic(ImportNativeWorker.class)){
                platform.when(()->ImportNativeWorker.stopUnstarted(entry.id(),lease)).thenAnswer(call->{
                    verify(ledger,never()).workerNotStarted(any(),any());return null;
                });
                assertEquals(true,cleanup.invoke(execution,prepared,null,lease,false,false,cancelled));
                platform.verify(()->ImportNativeWorker.stopUnstarted(entry.id(),lease));
                var order=inOrder(ledger,preparation);order.verify(ledger).workerNotStarted(row,lease);
                order.verify(preparation).finishNotReady(prepared,cancelled);
                verify(ledger,never()).workerStopped(any(),any(),anyBoolean(),anyBoolean());
            }
        }
    }

    @Test void unconfirmedUnstartedTombstoneKeepsResourcesAndSlotBlocked() throws Exception {
        var ledger=mock(ImportAgentEditLedger.class);var preparation=mock(ImportAgentEditPreparation.class);
        var publication=mock(ImportAgentPublication.class);
        var entry=new ImportAgentEditLedger.Entry(UUID.randomUUID(),7,101,UUID.randomUUID(),"201","a".repeat(64),0,0,
                UUID.randomUUID(),1,Instant.EPOCH,Instant.now().plusSeconds(60));
        var row=new ImportAgentEditLedger.Row(entry,"PREPARING",1,false,"[]","PENDING","NONE",false);
        var prepared=new ImportAgentEditPreparation.Prepared(AgentRunRequest.builder().runId("201").build(),row);
        when(ledger.require(entry.id(),7)).thenReturn(row);var lease=UUID.randomUUID();
        var execution=new ImportAgentEditExecution(ledger,preparation,mock(ImportAgentContainerLifecycle.class),
                mock(ImportAgentCandidateVerification.class),publication,mock(ImportWorkspaceCoordinator.class),
                mock(AgentWorkerPool.class),new AgentProperties(),new ObjectMapper());
        var cleanup=ImportAgentEditExecution.class.getDeclaredMethod("cleanupResources",ImportAgentEditPreparation.Prepared.class,
                ImportNativeWorker.class,UUID.class,boolean.class,boolean.class,boolean.class);
        cleanup.setAccessible(true);
        try(var platform=mockStatic(ImportNativeWorker.class)){
            platform.when(()->ImportNativeWorker.stopUnstarted(entry.id(),lease)).thenThrow(new IOException("IMPORT_NATIVE_STOP_UNCONFIRMED"));
            assertEquals(false,cleanup.invoke(execution,prepared,null,lease,false,false,true));
            verify(ledger,never()).workerNotStarted(any(),any());verify(ledger).recoveryRequired(row);
            verifyNoInteractions(preparation,publication);
        }
    }

    @Test void interruptedReadStillStopsWorkerAndClosesResourcesBeforeRestoringInterrupt() throws Exception {
        var ledger=mock(ImportAgentEditLedger.class);
        var preparation=mock(ImportAgentEditPreparation.class);
        var publication=mock(ImportAgentPublication.class);
        var pool=mock(AgentWorkerPool.class);
        var id=UUID.randomUUID();var lease=UUID.randomUUID();
        var entry=new ImportAgentEditLedger.Entry(id,7,101,UUID.randomUUID(),"201","a".repeat(64),0,0,
                UUID.randomUUID(),1,Instant.EPOCH,Instant.now().plusSeconds(60));
        var row=new ImportAgentEditLedger.Row(entry,"EDITING",1,false,"[]","PENDING","NONE",false);
        var closed=new ImportAgentEditLedger.Row(entry,"FAILED",2,true,"[]","DONE","NONE",true);
        when(ledger.require(id,7)).thenReturn(row);
        when(ledger.planWorker(row)).thenReturn(lease);
        var request=AgentRunRequest.builder().runId("201").build();
        var prepared=new ImportAgentEditPreparation.Prepared(request,row);
        var subscriberThread=Thread.currentThread();
        doAnswer(call->{assertFalse(Thread.currentThread().isInterrupted());when(ledger.require(id,7)).thenReturn(closed);return null;})
                .when(preparation).finishNotReady(prepared,false);
        try(var construction=mockConstruction(ImportNativeWorker.class,(worker,context)->{
            when(worker.started()).thenReturn(Instant.EPOCH);
            when(worker.ready(any())).thenThrow(new InterruptedException());
            doAnswer(call->{
                assertNotSame(subscriberThread,Thread.currentThread());
                subscriberThread.interrupt();
                Thread.sleep(20);
                assertFalse(Thread.currentThread().isInterrupted());
                return null;
            }).when(worker).stop();
        })) {
            var execution=new ImportAgentEditExecution(ledger,preparation,mock(ImportAgentContainerLifecycle.class),
                    mock(ImportAgentCandidateVerification.class),publication,mock(ImportWorkspaceCoordinator.class),
                    pool,new AgentProperties(),new ObjectMapper());
            try {
                assertThrows(RuntimeException.class,()->execution.execute(prepared).blockLast());
                assertTrue(Thread.currentThread().isInterrupted());
                verify(construction.constructed().getFirst()).stop();
                verify(ledger).workerStopped(row,lease,true,true);
                verify(preparation).finishNotReady(prepared,false);
                verify(pool).releaseExternal();
            } finally { Thread.interrupted(); }
        }
    }
}
