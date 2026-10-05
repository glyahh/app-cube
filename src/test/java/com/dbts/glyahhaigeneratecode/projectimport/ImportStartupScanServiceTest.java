package com.dbts.glyahhaigeneratecode.projectimport;

import com.dbts.glyahhaigeneratecode.exception.MyException;
import com.dbts.glyahhaigeneratecode.model.Entity.User;
import org.junit.jupiter.api.Test;
import java.util.*;
import java.time.Instant;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Consumer;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;
import static com.dbts.glyahhaigeneratecode.projectimport.ImportStartupScan.Status.*;

class ImportStartupScanServiceTest {
    ImportStartupService startup=mock(ImportStartupService.class);ImportStartupScanRepository repository=mock(ImportStartupScanRepository.class);
    ImportStartupInspectionRepository evidence=mock(ImportStartupInspectionRepository.class);User user=User.builder().id(7L).build();UUID imported=UUID.randomUUID();
    AtomicReference<ImportStartupScan> state=new AtomicReference<>();CountDownLatch entered=new CountDownLatch(1),release=new CountDownLatch(1),finished=new CountDownLatch(1);
    ImportStartupResourceGuard resources=mock(ImportStartupResourceGuard.class);ImportWorkspaceCoordinator coordinator=mock(ImportWorkspaceCoordinator.class);
    ImportStartupScanService setup(boolean late,boolean clean){
        when(startup.validateScan(user,42,".")).thenReturn(new ImportStartupConfiguration.View("42",imported,"0","0",".",List.of()));
        when(repository.create(any(),anyInt(),anyInt())).thenAnswer(call->{var current=state.get();if(current!=null)return current;var proposed=(ImportStartupScan)call.getArgument(0);state.set(proposed);return proposed;});
        when(repository.find(anyLong(),any())).thenAnswer(call->call.<Long>getArgument(0)==7?state.get():null);
        when(repository.byRequest(anyLong(),any())).thenAnswer(call->{var current=state.get();return current!=null&&current.requestId().equals(call.getArgument(1))?current:null;});
        when(repository.qualified(any())).thenAnswer(call->state.get().status()==SCANNING);
        when(startup.inputsAbsent(any())).thenReturn(clean);
        when(resources.stopped(any())).thenReturn(true);
        doAnswer(call->call.<java.util.function.Supplier<?>>getArgument(1).get()).when(coordinator).withLock(anyLong(),any());
        when(repository.transition(any(),any(),any(),anyString(),any())).thenAnswer(call->{
            var previous=(ImportStartupScan)call.getArgument(0);var current=state.get();if(current.revision()!=previous.revision())throw new MyException(40900,"revoked");
            var next=(ImportStartupScan.Status)call.getArgument(1);
            var updated=new ImportStartupScan(current.scanId(),current.requestId(),42,7,imported,0,0,".",next,current.revision()+1,current.executorId(),current.executorPid(),current.executorStart(),current.expiresAt(),current.resources(),call.getArgument(2),call.getArgument(3),call.getArgument(4),current.cancelRequested()||next==CANCELLING);
            state.set(updated);if(!updated.reservesAdmission())finished.countDown();return updated;
        });
        doAnswer(call->{if(state.get().status()!=SCANNING)throw new MyException(40900,"revoked");repository.transition(call.getArgument(0),SUCCEEDED,"a".repeat(64),"DONE",null);return null;}).when(repository).complete(any(),any(),any());
        when(startup.inspectManaged(any(),any(),any(),any(),any())).thenAnswer(call->{
            var lease=(ImportStartupScan)call.getArgument(1);entered.countDown();if(late)assertTrue(release.await(3,TimeUnit.SECONDS));
            call.<Consumer<ImportStartupService.Inspection>>getArgument(4).accept(new ImportStartupService.Inspection(lease.scanId(),"42",imported,"a".repeat(64),".","0","0",new ImportStartupScanner.Result("input",List.of(),List.of())));return null;
        });
        return new ImportStartupScanService(startup,repository,evidence,new ImportProperties(),resources,coordinator);
    }
    @Test void creationRunsBackgroundTaskAndCommitsOneQualifiedResult()throws Exception{
        var service=setup(false,true);try{
            var task=service.create(user,42,UUID.randomUUID(),0,0,".");assertTrue(finished.await(3,TimeUnit.SECONDS));
            assertEquals(SUCCEEDED,service.get(user,42,task.scanId()).status());verify(repository,times(1)).complete(any(),any(),eq(evidence));
        }finally{release.countDown();service.close();}
    }
    @Test void cancellationBlocksLateCompletionAndWaitsForCleanup()throws Exception{
        var service=setup(true,true);try{
            var task=service.create(user,42,UUID.randomUUID(),0,0,".");assertTrue(entered.await(3,TimeUnit.SECONDS));
            var cancelling=service.cancel(user,42,task.scanId(),1);assertEquals(CANCELLING,cancelling.status());release.countDown();
            assertTrue(finished.await(3,TimeUnit.SECONDS));assertEquals(CANCELLED,state.get().status());assertEquals("DONE",state.get().cleanup());
        }finally{release.countDown();service.close();}
    }
    @Test void unconfirmedPrivateInputDoesNotBecomeCancelled()throws Exception{
        var service=setup(true,false);try{
            var task=service.create(user,42,UUID.randomUUID(),0,0,".");assertTrue(entered.await(3,TimeUnit.SECONDS));
            service.cancel(user,42,task.scanId(),1);release.countDown();service.close();assertEquals(STOP_FAILED,state.get().status());assertEquals("UNVERIFIED",state.get().cleanup());
        }finally{release.countDown();service.close();}
    }
    @Test void repeatedRequestDoesNotScheduleAnotherWorker()throws Exception{
        var service=setup(true,true);try{
            UUID request=UUID.randomUUID();var task=service.create(user,42,request,0,0,".");assertTrue(entered.await(3,TimeUnit.SECONDS));
            var repeated=service.create(user,42,request,0,0,".");assertEquals(task.scanId(),repeated.scanId());
            verify(startup,times(1)).inspectManaged(any(),any(),any(),any(),any());
            verify(startup,times(1)).validateScan(user,42,".");
        }finally{release.countDown();service.close();}
    }
    @Test void foreignOwnerAndOldCancelVersionCannotChangeJob()throws Exception{
        var service=setup(true,true);try{
            var task=service.create(user,42,UUID.randomUUID(),0,0,".");assertTrue(entered.await(3,TimeUnit.SECONDS));
            assertThrows(MyException.class,()->service.get(User.builder().id(8L).build(),42,task.scanId()));
            assertThrows(MyException.class,()->service.cancel(user,42,task.scanId(),0));assertEquals(SCANNING,state.get().status());
        }finally{release.countDown();service.close();}
    }
    @Test void deadExecutorIsReconciledWithoutRerunningScanner()throws Exception{
        var service=setup(false,true);
        try{
            var process=ProcessHandle.current();
            var orphan=new ImportStartupScan(UUID.randomUUID(),UUID.randomUUID(),42,7,imported,0,0,".",SCANNING,1,UUID.randomUUID(),process.pid(),Instant.now(),Instant.now().plusSeconds(60),List.of(),null,"PENDING",null,false);
            state.set(orphan);when(resources.parent(any())).thenReturn(ImportStartupResourceGuard.Parent.GONE);
            when(resources.stop(any(),any(),any())).thenReturn(true);when(startup.helperInputAbsent(any())).thenReturn(true);
            when(repository.outstanding()).thenReturn(List.of(orphan));service.recoverOutstanding();
            assertEquals(FAILED,state.get().status());assertEquals("DONE",state.get().cleanup());
            verify(startup,never()).inspectManaged(any(),any(),any(),any(),any());verify(startup).cleanupStoppedInputs(orphan.scanId());
        }finally{service.close();}
    }
    @Test void livingForeignExecutorDoesNotLosePrivateInputs()throws Exception{
        var service=setup(false,true);
        try{
            var orphan=new ImportStartupScan(UUID.randomUUID(),UUID.randomUUID(),42,7,imported,0,0,".",SCANNING,1,UUID.randomUUID(),123,Instant.now(),Instant.now().plusSeconds(60),List.of(),null,"PENDING",null,false);
            state.set(orphan);when(resources.parent(any())).thenReturn(ImportStartupResourceGuard.Parent.ALIVE);when(repository.outstanding()).thenReturn(List.of(orphan));
            service.recoverOutstanding();assertEquals(SCANNING,state.get().status());verify(startup,never()).cleanupStoppedInputs(any());verify(resources,never()).stop(any(),any(),any());
        }finally{service.close();}
    }
    @Test void unjournaledHelperInputIsPreservedForRecovery()throws Exception{
        var service=setup(false,false);
        try{
            var orphan=new ImportStartupScan(UUID.randomUUID(),UUID.randomUUID(),42,7,imported,0,0,".",SCANNING,1,UUID.randomUUID(),123,Instant.now(),Instant.now().plusSeconds(60),List.of(),null,"PENDING",null,false);
            state.set(orphan);when(resources.parent(any())).thenReturn(ImportStartupResourceGuard.Parent.GONE);when(startup.helperInputAbsent(any())).thenReturn(false);
            when(repository.outstanding()).thenReturn(List.of(orphan));service.recoverOutstanding();assertEquals(RECOVERY_REQUIRED,state.get().status());
            verify(startup,never()).cleanupStoppedInputs(any());verify(resources,never()).stop(any(),any(),any());
        }finally{service.close();}
    }
    @Test void recoveryPreservesCancelIntentIndependentlyOfErrorText()throws Exception{
        var service=setup(false,true);
        try{
            var orphan=new ImportStartupScan(UUID.randomUUID(),UUID.randomUUID(),42,7,imported,0,0,".",STOP_FAILED,2,UUID.randomUUID(),123,Instant.now(),Instant.now().plusSeconds(60),List.of(),null,"UNVERIFIED","previous unrelated error",true);
            state.set(orphan);when(resources.parent(any())).thenReturn(ImportStartupResourceGuard.Parent.GONE);when(startup.helperInputAbsent(any())).thenReturn(true);when(resources.stop(any(),any(),any())).thenReturn(true);
            when(repository.outstanding()).thenReturn(List.of(orphan));service.recoverOutstanding();assertEquals(CANCELLED,state.get().status());assertTrue(state.get().cancelRequested());assertNull(state.get().error());
        }finally{service.close();}
    }
    @Test void expiredForeignLiveExecutorIsRevokedButNotCleanedWhileAlive()throws Exception{
        var service=setup(false,true);
        try{
            var orphan=new ImportStartupScan(UUID.randomUUID(),UUID.randomUUID(),42,7,imported,0,0,".",SCANNING,1,UUID.randomUUID(),123,Instant.now().minusSeconds(200),Instant.now().minusSeconds(1),List.of(),null,"PENDING",null,false);
            state.set(orphan);when(resources.parent(any())).thenReturn(ImportStartupResourceGuard.Parent.ALIVE);when(repository.outstanding()).thenReturn(List.of(orphan));
            service.recoverOutstanding();assertEquals(RECOVERY_REQUIRED,state.get().status());verify(startup,never()).cleanupStoppedInputs(any());verify(resources,never()).stop(any(),any(),any());
        }finally{service.close();}
    }
}
