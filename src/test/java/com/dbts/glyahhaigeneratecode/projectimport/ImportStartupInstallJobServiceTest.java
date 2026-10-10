package com.dbts.glyahhaigeneratecode.projectimport;
import com.dbts.glyahhaigeneratecode.model.Entity.User;
import com.dbts.glyahhaigeneratecode.service.UserService;
import com.dbts.glyahhaigeneratecode.exception.MyException;
import org.junit.jupiter.api.*;
import java.time.Instant;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.*;
import java.util.function.Supplier;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;
import static org.mockito.ArgumentMatchers.*;
import static com.dbts.glyahhaigeneratecode.projectimport.ImportStartupInstallJob.State.*;

class ImportStartupInstallJobServiceTest {
    final UUID plan=UUID.randomUUID();
    final String revision="a".repeat(64);
    final User user=User.builder().id(7L).build();
    final ImportStartupPreparationService preparations=mock(ImportStartupPreparationService.class);
    final ImportStartupInstallJobRepository jobs=mock(ImportStartupInstallJobRepository.class);
    final ImportExecutionActions executions=mock(ImportExecutionActions.class);
    final ImportWorkspaceCoordinator coordinator=mock(ImportWorkspaceCoordinator.class);
    final ImportInstallProbeRecovery probes=mock(ImportInstallProbeRecovery.class);
    final UserService users=mock(UserService.class);
    final AtomicReference<ImportStartupInstallJob> row=new AtomicReference<>();
    final CountDownLatch entered=new CountDownLatch(1),release=new CountDownLatch(1),finished=new CountDownLatch(1);
    ImportStartupInstallJobService service;
    ImportStartupInstallJob copy(ImportStartupInstallJob old,ImportStartupInstallJob.State state,long rev,String reason){
        return new ImportStartupInstallJob(old.jobId(),old.requestId(),42,7,plan,revision,old.contentHash(),old.requestHash(),state,rev,
            old.cancelRequested()||state==CANCELLING||state==CANCELLED,null,reason,old.executorId(),old.executorPid(),old.executorStart(),
            Instant.now(),ImportStartupInstallJob.isTerminal(state)?Instant.now():null,old.executionIds(),old.resources(),old.stages(),old.preparationId(),
            ImportStartupInstallJob.isTerminal(state)?"DONE":old.cleanup(),old.approved(),old.probes(),old.createdAt());
    }
    @BeforeEach void setup(){
        when(coordinator.withLock(eq(42L),any())).thenAnswer(c->c.<Supplier<?>>getArgument(1).get());
        when(users.getById(7L)).thenReturn(user);
        when(jobs.byRequest(eq(7L),any())).thenAnswer(c->row.get()!=null&&row.get().requestId().equals(c.getArgument(1))?row.get():null);
        when(jobs.create(any(),any(),anyLong(),anyLong(),any(),anyString(),any(),anyLong(),any(),anyInt(),anyInt(),anyBoolean())).thenAnswer(c->{
            var job=new ImportStartupInstallJob(c.getArgument(0),c.getArgument(1),42,7,plan,revision,"b".repeat(64),"c".repeat(64),PENDING,0,false,null,null,
                c.getArgument(6),c.getArgument(7),c.getArgument(8),null,null,List.of(),List.of(),List.of(),null,"PENDING",c.getArgument(11),List.of(),Instant.now());
            row.set(job);return job;
        });
        when(jobs.find(eq(7L),any())).thenAnswer(c->row.get());
        when(jobs.qualified(any())).thenAnswer(c->row.get()!=null&&!row.get().cancelRequested());
        when(jobs.outstanding()).thenAnswer(c->row.get()==null?List.of():List.of(row.get()));
        when(jobs.transition(any(),any(),any(),any(),anyString())).thenAnswer(c->{
            var old=c.<ImportStartupInstallJob>getArgument(0);if(old.revision()!=row.get().revision())throw new MyException(40900,"changed");
            var next=copy(row.get(),c.getArgument(1),old.revision()+1,c.getArgument(3));row.set(next);return next;
        });
        when(jobs.finish(any(),any(),any(),any(),any(),any(),any(),anyString())).thenAnswer(c->{
            var old=c.<ImportStartupInstallJob>getArgument(0);if(old.revision()!=row.get().revision())throw new MyException(40900,"changed");
            var next=copy(row.get(),c.getArgument(1),old.revision()+1,c.getArgument(6));row.set(next);finished.countDown();return next;
        });
        when(jobs.requestCancel(eq(7L),any(),anyLong())).thenAnswer(c->{var next=copy(row.get(),CANCELLING,row.get().revision()+1,null);row.set(next);return next;});
        when(executions.activeSlots(anyLong(),anyString())).thenReturn(List.of());
        when(executions.installClean(any())).thenReturn(true);
        when(executions.installAppClean(anyLong())).thenReturn(true);
        when(probes.confirmed(any(),anyBoolean())).thenReturn(true);
        when(preparations.install(any(),anyLong(),any(),anyString(),anyBoolean(),any())).thenAnswer(c->{
            entered.countDown();release.await(5,TimeUnit.SECONDS);
            c.<ImportStartupPreparationService.InstallProgress>getArgument(5).advance(VERIFYING);
            return new ImportStartupPreparationService.View(UUID.randomUUID(),"42",plan,revision,"INSTALL","APPROVED","SUCCEEDED",null,false,false,List.of(),List.of());
        });
        service=new ImportStartupInstallJobService(preparations,jobs,executions,new ImportProperties(),coordinator,probes,users);
    }
    @AfterEach void close(){release.countDown();service.close();}
    @Test void responseDoesNotWaitForInstallation()throws Exception{
        var start=System.nanoTime();var view=service.submit(user,42,UUID.randomUUID(),plan,revision,true);
        assertTrue(TimeUnit.NANOSECONDS.toMillis(System.nanoTime()-start)<1000);
        assertNotNull(view.jobId());assertTrue(entered.await(2,TimeUnit.SECONDS));
        assertFalse(ImportStartupInstallJob.isTerminal(row.get().state()));
        release.countDown();assertTrue(finished.await(2,TimeUnit.SECONDS));assertEquals(SUCCEEDED,row.get().state());
        verify(users).getById(7L);
    }
    @Test void duplicateRequestRunsOnce()throws Exception{
        var id=UUID.randomUUID();var first=service.submit(user,42,id,plan,revision,true);assertTrue(entered.await(2,TimeUnit.SECONDS));
        assertEquals(first.jobId(),service.submit(user,42,id,plan,revision,true).jobId());
        verify(jobs,times(1)).create(any(),any(),anyLong(),anyLong(),any(),anyString(),any(),anyLong(),any(),anyInt(),anyInt(),anyBoolean());
    }
    @Test void duplicateCannotChangeApproval()throws Exception{
        var id=UUID.randomUUID();service.submit(user,42,id,plan,revision,true);
        assertThrows(MyException.class,()->service.submit(user,42,id,plan,revision,false));
    }
    @Test void cancelNeverSettlesWhileWorkerStillRunning()throws Exception{
        service.submit(user,42,UUID.randomUUID(),plan,revision,true);assertTrue(entered.await(2,TimeUnit.SECONDS));
        var current=row.get();service.cancel(user,42,current.jobId(),current.revision());service.recoverOutstanding();
        assertEquals(CANCELLING,row.get().state());verify(jobs,never()).finish(any(),any(),any(),any(),any(),any(),any(),anyString());
        release.countDown();assertTrue(finished.await(2,TimeUnit.SECONDS));assertEquals(CANCELLED,row.get().state());
    }
    @Test void unresolvedCleanupNeverReleasesAdmission()throws Exception{
        when(executions.installClean(any())).thenReturn(false);
        service.submit(user,42,UUID.randomUUID(),plan,revision,true);assertTrue(entered.await(2,TimeUnit.SECONDS));
        release.countDown();
        for(int i=0;i<30&&row.get().state()!=STOP_FAILED;i++)Thread.sleep(20);
        assertEquals(STOP_FAILED,row.get().state());assertTrue(row.get().reservesAdmission());
    }
    @Test void foreignLiveProcessMustNotBeClaimedOrStopped(){
        var process=ProcessHandle.current();
        var foreign=new ImportStartupInstallJob(UUID.randomUUID(),UUID.randomUUID(),42,7,plan,revision,"b".repeat(64),"c".repeat(64),INSTALLING,1,false,null,null,
            UUID.randomUUID(),process.pid(),process.info().startInstant().orElseThrow(),Instant.now(),null,List.of(),List.of(),List.of(),null,"PENDING");
        row.set(foreign);service.recoverOutstanding();
        assertEquals(INSTALLING,row.get().state());verifyNoInteractions(executions,probes);
    }
    @Test void deadProcessSettlesWithoutRerunningInstallation(){
        var foreign=new ImportStartupInstallJob(UUID.randomUUID(),UUID.randomUUID(),42,7,plan,revision,"b".repeat(64),"c".repeat(64),INSTALLING,1,false,null,null,
            UUID.randomUUID(),9999999,Instant.EPOCH,Instant.now(),null,List.of(),List.of(),List.of(),null,"PENDING");
        row.set(foreign);service.recoverOutstanding();assertEquals(FAILED,row.get().state());verifyNoInteractions(preparations);
    }
    @Test void unboundLedgerMustBeStoppedAndConfirmedBeforeClosing(){
        var foreign=new ImportStartupInstallJob(UUID.randomUUID(),UUID.randomUUID(),42,7,plan,revision,"b".repeat(64),"c".repeat(64),INSTALLING,1,false,null,null,
            UUID.randomUUID(),9999999,Instant.EPOCH,Instant.now(),null,List.of(),List.of(),List.of(),null,"PENDING");
        var unbound=UUID.randomUUID();row.set(foreign);
        when(executions.activeSlots(42,"install")).thenReturn(List.of(unbound));
        when(executions.installAppClean(42)).thenReturn(false);
        service.recoverOutstanding();
        verify(executions).stopExecution(unbound);assertEquals(STOP_FAILED,row.get().state());
        verify(jobs,never()).finish(any(),any(),any(),any(),any(),any(),any(),anyString());
    }
    @Test void recoveryPreservesInterruptedStageWithoutInventingDuration(){
        var begin=new ImportStartupInstallJob.Stage("NPM_INSTALL","NPM_INSTALL","STARTED",null,null,Instant.now(),null,null,UUID.randomUUID().toString());
        row.set(new ImportStartupInstallJob(UUID.randomUUID(),UUID.randomUUID(),42,7,plan,revision,"b".repeat(64),"c".repeat(64),INSTALLING,1,false,null,null,
            UUID.randomUUID(),9999999,Instant.EPOCH,Instant.now(),null,List.of(),List.of(),List.of(begin),null,"PENDING"));
        service.recoverOutstanding();
        var stages=org.mockito.ArgumentCaptor.forClass(List.class);var stage=org.mockito.ArgumentCaptor.forClass(String.class);
        verify(jobs).finish(any(),eq(FAILED),stages.capture(),any(),any(),stage.capture(),eq("INSTALL_INTERRUPTED"),eq("DONE"));
        assertEquals("NPM_INSTALL",stage.getValue());
        var interrupted=(ImportStartupInstallJob.Stage)stages.getValue().stream().filter(s->"NPM_INSTALL".equals(((ImportStartupInstallJob.Stage)s).stage())).findFirst().orElseThrow();
        assertEquals("INTERRUPTED",interrupted.status());assertNull(interrupted.endTime());assertNull(interrupted.durationMs());
        assertTrue(stages.getValue().stream().anyMatch(s->"PIP_INSTALL".equals(((ImportStartupInstallJob.Stage)s).stage())&&"NOT_STARTED".equals(((ImportStartupInstallJob.Stage)s).status())));
    }
    @Test void foreignOwnerCannotRead(){
        assertThrows(MyException.class,()->service.get(User.builder().id(8L).build(),42,UUID.randomUUID()));
    }
}
