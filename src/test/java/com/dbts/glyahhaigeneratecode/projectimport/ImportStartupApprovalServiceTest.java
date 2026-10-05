package com.dbts.glyahhaigeneratecode.projectimport;

import com.dbts.glyahhaigeneratecode.exception.MyException;
import com.dbts.glyahhaigeneratecode.model.Entity.User;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import java.time.Instant;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.*;
import java.util.function.*;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

class ImportStartupApprovalServiceTest {
    ImportStartupService startup=mock(ImportStartupService.class);ImportStartupApprovalRepository approvals=mock(ImportStartupApprovalRepository.class);
    ImportStartupModelLeaseRepository leases=mock(ImportStartupModelLeaseRepository.class);ImportStartupModelExecutor model=mock(ImportStartupModelExecutor.class);
    ImportStartupResourceGuard resources=mock(ImportStartupResourceGuard.class);ImportWorkspaceCoordinator coordinator=mock(ImportWorkspaceCoordinator.class);
    User user=User.builder().id(7L).build();UUID scan=UUID.randomUUID(),imported=UUID.randomUUID(),approvalId=UUID.randomUUID();
    AtomicReference<ImportStartupApprovalRepository.Approval> state=new AtomicReference<>();AtomicReference<ImportStartupModelLease> lease=new AtomicReference<>();
    AtomicBoolean inputAbsent=new AtomicBoolean(true),databaseQualified=new AtomicBoolean(true);
    Deque<RuntimeException> publishFailures=new ConcurrentLinkedDeque<>();AtomicInteger publishCalls=new AtomicInteger();
    ImportProcessRunner.Resource helper=new ImportProcessRunner.Resource(4242,Instant.now());
    CountDownLatch entered=new CountDownLatch(1),release=new CountDownLatch(1);
    ImportStartupApprovalService service;

    ImportStartupApprovalRepository.Approval approval(String status,long revision,Instant expires,boolean cancel,String result,String error){
        return new ImportStartupApprovalRepository.Approval(approvalId,state.get()==null?UUID.randomUUID():state.get().requestId(),7,42,scan,imported,"a".repeat(64),0,0,
            ImportStartupInspectionRepository.RULES_VERSION,List.of("svc"),!"REJECTED".equals(status),status,revision,expires,result,error,cancel);
    }
    ImportStartupModelLease leaseOf(UUID executor,long revision,List<ImportProcessRunner.Resource> owned,String cleanup){
        return new ImportStartupModelLease(approvalId,7,42,revision,executor,ProcessHandle.current().pid(),Instant.now(),0,owned,cleanup);
    }
    ImportStartupApprovalService setup(boolean late)throws Exception{
        var inspection=new ImportStartupService.Inspection(scan,"42",imported,"a".repeat(64),".","0","0",new ImportStartupScanner.Result("input",List.of(),List.of()));
        when(startup.withCurrentInspection(any(),anyLong(),any(),any())).thenAnswer(call->call.<Function<ImportStartupService.Inspection,Object>>getArgument(3).apply(inspection));
        when(approvals.create(anyLong(),any(),any(),any(),anyBoolean(),anyInt(),anyInt())).thenAnswer(call->{
            if(state.get()!=null)return state.get();boolean approved=call.getArgument(4);
            var created=new ImportStartupApprovalRepository.Approval(approvalId,call.getArgument(1),7,42,scan,imported,"a".repeat(64),0,0,ImportStartupInspectionRepository.RULES_VERSION,
                call.getArgument(3),approved,approved?"APPROVED":"REJECTED",0,Instant.now().plusSeconds(180),null,null,false);
            state.set(created);return created;
        });
        when(approvals.find(anyLong(),any())).thenAnswer(call->call.<Long>getArgument(0)==7&&state.get()!=null&&state.get().id().equals(call.getArgument(1))?state.get():null);
        when(approvals.byRequest(anyLong(),any())).thenAnswer(call->{var current=state.get();return call.<Long>getArgument(0)==7&&current!=null&&current.requestId().equals(call.getArgument(1))?current:null;});
        when(approvals.outstanding()).thenAnswer(call->state.get()==null?List.of():List.of(new ImportStartupApprovalRepository.Key(state.get().ownerId(),state.get().id())));
        when(approvals.transition(any(),anyString(),any(),any())).thenAnswer(call->{
            var previous=(ImportStartupApprovalRepository.Approval)call.getArgument(0);var current=state.get();String next=call.getArgument(1);
            if(current.revision()!=previous.revision()||!current.state().equals(previous.state()))throw new MyException(40900,"revoked");
            if(List.of("RUNNING","SUCCEEDED").contains(next)&&(!databaseQualified.get()||!current.expiresAt().isAfter(Instant.now())))throw new MyException(40900,"unqualified");
            var updated=new ImportStartupApprovalRepository.Approval(current.id(),current.requestId(),7,42,scan,imported,current.contentHash(),0,0,current.rulesVersion(),current.directories(),current.approved(),
                next,current.revision()+1,current.expiresAt(),call.getArgument(2),call.getArgument(3),current.cancelRequested()||List.of("CANCELLING","CANCELLED").contains(next));
            state.set(updated);return updated;
        });
        when(leases.claim(any(),any(),anyLong(),any())).thenAnswer(call->{
            var approved=(ImportStartupApprovalRepository.Approval)call.getArgument(0);
            if(!"APPROVED".equals(approved.state())||!approved.expiresAt().isAfter(Instant.now()))throw new MyException(40900,"refused");
            var running=approvals.transition(approved,"RUNNING",null,null);
            lease.set(new ImportStartupModelLease(approvalId,7,42,running.revision(),call.getArgument(1),call.getArgument(2),call.getArgument(3),0,List.of(),"PENDING"));return lease.get();
        });
        when(leases.find(anyLong(),any())).thenAnswer(call->lease.get());
        when(leases.qualified(any())).thenAnswer(call->"RUNNING".equals(state.get().state())&&!state.get().cancelRequested());
        doAnswer(call->{var current=lease.get();var owned=new ArrayList<>(current.resources());owned.add(call.getArgument(1));
            lease.set(new ImportStartupModelLease(approvalId,7,42,current.approvalRevision(),current.executorId(),current.executorPid(),current.executorStart(),current.revision(),owned,current.cleanup()));return null;})
            .when(leases).resource(any(),any());
        when(leases.cleanup(any(),anyString())).thenAnswer(call->{var current=lease.get();
            lease.set(new ImportStartupModelLease(approvalId,7,42,current.approvalRevision(),current.executorId(),current.executorPid(),current.executorStart(),current.revision()+1,current.resources(),call.getArgument(1)));return lease.get();});
        when(startup.approvedModelInput(any(),any(),any())).thenReturn(new ImportStartupService.ModelInput(Map.of("svc/main.py","print(1)"),
            List.of(new ImportStartupScanner.Scope("svc","ALGORITHM","UNIDENTIFIED","NO_ENTRY")),List.of()));
        // 发布复核替身：按队列抛出预设失败，否则在回调中执行真实账本提交替身。
        when(startup.publishSupplement(any(),any(),any(),any(),any())).thenAnswer(call->{
            publishCalls.incrementAndGet();assertEquals(user,call.getArgument(0));assertEquals("RUNNING",call.<ImportStartupApprovalRepository.Approval>getArgument(1).state());
            var failure=publishFailures.poll();if(failure!=null)throw failure;
            return call.<UnaryOperator<ImportStartupApprovalRepository.Approval>>getArgument(4).apply(call.getArgument(1));
        });
        when(model.execute(any(),any(),any(),any())).thenAnswer(call->{
            call.<Consumer<ImportProcessRunner.Resource>>getArgument(3).accept(helper);entered.countDown();
            if(late)assertTrue(release.await(3,TimeUnit.SECONDS));
            if(!call.<BooleanSupplier>getArgument(2).getAsBoolean())throw new ImportStartupModelExecutor.Failure("APPROVAL_REVOKED");
            return new ImportStartupModelExecutor.Output("{\"services\":[]}",null);
        });
        when(model.inputAbsent(any())).thenAnswer(call->inputAbsent.get());
        when(resources.stopResources(any(),any(),any())).thenReturn(true);
        when(resources.parent(anyLong(),any())).thenReturn(ImportStartupResourceGuard.Parent.GONE);
        doAnswer(call->call.<Supplier<?>>getArgument(1).get()).when(coordinator).withLock(anyLong(),any());
        service=new ImportStartupApprovalService(startup,approvals,leases,model,resources,new ImportProperties(),coordinator);return service;
    }
    @AfterEach void stop(){release.countDown();if(service!=null)service.close();}
    void awaitSettled()throws InterruptedException{
        long deadline=System.nanoTime()+TimeUnit.SECONDS.toNanos(3);
        while(System.nanoTime()<deadline){var current=state.get();if(current!=null&&!List.of("APPROVED","RUNNING","CANCELLING").contains(current.state()))return;Thread.sleep(10);}
        fail("批准未进入稳定状态："+state.get().state());
    }

    @Test void approvedRequestClaimsOnceAndOnlyRecordsResult()throws Exception{
        setup(false);var view=service.decide(user,42,UUID.randomUUID(),scan,List.of("svc"),true);awaitSettled();
        assertEquals(approvalId,view.approvalId());assertEquals("SUCCEEDED",state.get().state());assertEquals("DONE",lease.get().cleanup());
        assertTrue(service.get(user,42,approvalId).resultRecorded());assertEquals(List.of(helper),lease.get().resources());
        verify(leases,times(1)).claim(any(),any(),anyLong(),any());verify(model,times(1)).execute(any(),any(),any(),any());assertEquals(1,publishCalls.get());
    }
    @Test void rejectionIsPersistedButNeverClaimed()throws Exception{
        setup(false);var view=service.decide(user,42,UUID.randomUUID(),scan,List.of("svc"),false);
        assertEquals("REJECTED",view.state());assertFalse(view.approved());Thread.sleep(100);
        verify(leases,never()).claim(any(),any(),anyLong(),any());verify(model,never()).execute(any(),any(),any(),any());
    }
    @Test void repeatedRequestReturnsOriginalWithoutSecondExecution()throws Exception{
        setup(true);UUID request=UUID.randomUUID();var first=service.decide(user,42,request,scan,List.of("svc"),true);assertTrue(entered.await(3,TimeUnit.SECONDS));
        var repeated=service.decide(user,42,request,scan,List.of("svc"),true);assertEquals(first.approvalId(),repeated.approvalId());
        assertThrows(MyException.class,()->service.decide(user,42,request,scan,List.of("svc"),false));
        verify(startup,times(1)).withCurrentInspection(any(),anyLong(),any(),any());verify(leases,times(1)).claim(any(),any(),anyLong(),any());
    }
    @Test void expiredApprovalIsNeverClaimedAndReleasesQuota()throws Exception{
        setup(false);state.set(approval("APPROVED",0,Instant.now().minusSeconds(1),false,null,null));
        service.recoverOutstanding();assertEquals("STALE",state.get().state());assertEquals("APPROVAL_EXPIRED",state.get().error());
        verify(leases,never()).claim(any(),any(),anyLong(),any());verify(model,never()).execute(any(),any(),any(),any());
    }
    @Test void unclaimedCancellationRevokesBeforeAnyExecution()throws Exception{
        setup(false);state.set(approval("APPROVED",0,Instant.now().plusSeconds(60),false,null,null));
        var view=service.cancel(user,42,approvalId,0);assertEquals("CANCELLED",view.state());assertTrue(view.cancelRequested());
        verify(model,never()).execute(any(),any(),any(),any());
    }
    @Test void runningCancellationRevokesThenConfirmsStopAndInput()throws Exception{
        setup(true);service.decide(user,42,UUID.randomUUID(),scan,List.of("svc"),true);assertTrue(entered.await(3,TimeUnit.SECONDS));
        var cancelling=service.cancel(user,42,approvalId,Long.parseLong(service.get(user,42,approvalId).revision()));assertEquals("CANCELLING",cancelling.state());
        release.countDown();awaitSettled();
        assertEquals("CANCELLED",state.get().state());assertNull(state.get().error());assertNull(state.get().result());assertEquals("DONE",lease.get().cleanup());
        verify(resources).stopResources(eq(List.of(helper)),any(),any());
    }
    @Test void cancellationWithUnconfirmedInputKeepsRecoveryAndQuota()throws Exception{
        setup(true);service.decide(user,42,UUID.randomUUID(),scan,List.of("svc"),true);assertTrue(entered.await(3,TimeUnit.SECONDS));
        inputAbsent.set(false);service.cancel(user,42,approvalId,Long.parseLong(service.get(user,42,approvalId).revision()));release.countDown();awaitSettled();
        assertEquals("RECOVERY_REQUIRED",state.get().state());assertTrue(state.get().cancelRequested());assertEquals("UNVERIFIED",lease.get().cleanup());
        verify(model).cleanupStoppedInput(approvalId);
    }
    @Test void staleQualificationAtCommitDiscardsResult()throws Exception{
        setup(true);service.decide(user,42,UUID.randomUUID(),scan,List.of("svc"),true);assertTrue(entered.await(3,TimeUnit.SECONDS));
        databaseQualified.set(false);release.countDown();awaitSettled();
        assertEquals("FAILED",state.get().state());assertEquals("MODEL_RESULT_REVOKED",state.get().error());assertNull(state.get().result());assertEquals("DONE",lease.get().cleanup());
    }
    @Test void deadExecutorAfterRestartIsStoppedAndNotRerun()throws Exception{
        setup(false);state.set(approval("RUNNING",1,Instant.now().plusSeconds(60),false,null,null));lease.set(leaseOf(UUID.randomUUID(),1,List.of(helper),"PENDING"));
        service.recoverOutstanding();
        assertEquals("FAILED",state.get().state());assertEquals("MODEL_EXECUTOR_LOST",state.get().error());assertEquals("DONE",lease.get().cleanup());
        verify(resources).stopResources(eq(List.of(helper)),any(),any());verify(model,never()).execute(any(),any(),any(),any());verify(startup,never()).approvedModelInput(any(),any(),any());
    }
    @Test void cancellingRecordOfDeadExecutorBecomesCancelled()throws Exception{
        setup(false);state.set(approval("CANCELLING",2,Instant.now().plusSeconds(60),true,null,null));lease.set(leaseOf(UUID.randomUUID(),1,List.of(helper),"PENDING"));
        service.recoverOutstanding();assertEquals("CANCELLED",state.get().state());assertNull(state.get().error());assertEquals("DONE",lease.get().cleanup());
    }
    @Test void inputBeforePidRegistrationIsPreservedForRecovery()throws Exception{
        setup(false);inputAbsent.set(false);state.set(approval("RUNNING",1,Instant.now().plusSeconds(60),false,null,null));lease.set(leaseOf(UUID.randomUUID(),1,List.of(),"PENDING"));
        service.recoverOutstanding();service.recoverOutstanding();
        assertEquals("RECOVERY_REQUIRED",state.get().state());assertEquals("UNVERIFIED",lease.get().cleanup());
        verify(resources,never()).stopResources(any(),any(),any());verify(model,never()).cleanupStoppedInput(any());
    }
    @Test void unconfirmedStopKeepsRecovery()throws Exception{
        setup(false);when(resources.stopResources(any(),any(),any())).thenReturn(false);
        state.set(approval("RUNNING",1,Instant.now().plusSeconds(60),false,null,null));lease.set(leaseOf(UUID.randomUUID(),1,List.of(helper),"PENDING"));
        service.recoverOutstanding();assertEquals("RECOVERY_REQUIRED",state.get().state());assertEquals("UNVERIFIED",lease.get().cleanup());verify(model,never()).cleanupStoppedInput(any());
    }
    @Test void livingForeignExecutorIsLeftAlone()throws Exception{
        setup(false);when(resources.parent(anyLong(),any())).thenReturn(ImportStartupResourceGuard.Parent.ALIVE);
        state.set(approval("RUNNING",1,Instant.now().plusSeconds(60),false,null,null));lease.set(leaseOf(UUID.randomUUID(),1,List.of(helper),"PENDING"));
        service.recoverOutstanding();assertEquals("RUNNING",state.get().state());verify(resources,never()).stopResources(any(),any(),any());
    }
    @Test void expiredLivingForeignExecutorIsRevokedButNotCleaned()throws Exception{
        setup(false);when(resources.parent(anyLong(),any())).thenReturn(ImportStartupResourceGuard.Parent.ALIVE);
        state.set(approval("RUNNING",1,Instant.now().minusSeconds(1),false,null,null));lease.set(leaseOf(UUID.randomUUID(),1,List.of(helper),"PENDING"));
        service.recoverOutstanding();assertEquals("RECOVERY_REQUIRED",state.get().state());assertEquals("PENDING",lease.get().cleanup());
        verify(resources,never()).stopResources(any(),any(),any());verify(model,never()).cleanupStoppedInput(any());
    }
    @Test void foreignOwnerAndOldRevisionCannotChangeApproval()throws Exception{
        setup(false);state.set(approval("APPROVED",0,Instant.now().plusSeconds(60),false,null,null));
        assertThrows(MyException.class,()->service.get(User.builder().id(8L).build(),42,approvalId));
        assertThrows(MyException.class,()->service.get(user,43,approvalId));
        assertThrows(MyException.class,()->service.cancel(user,42,approvalId,5));assertEquals("APPROVED",state.get().state());
    }
    @Test void rejectedPublicationDiscardsReplyAfterCleanup()throws Exception{
        setup(false);publishFailures.add(new ImportStartupService.SupplementRejected("MODEL_RESULT_STALE"));
        service.decide(user,42,UUID.randomUUID(),scan,List.of("svc"),true);awaitSettled();
        assertEquals("FAILED",state.get().state());assertEquals("MODEL_RESULT_STALE",state.get().error());assertNull(state.get().result());assertEquals("DONE",lease.get().cleanup());
        assertFalse(service.get(user,42,approvalId).resultRecorded());assertEquals(1,publishCalls.get());
    }
    @Test void busyWorkspaceRetriesBrieflyThenDiscardsWithoutSuccess()throws Exception{
        setup(false);for(int i=0;i<3;i++)publishFailures.add(new MyException(40900,"busy"));
        service.decide(user,42,UUID.randomUUID(),scan,List.of("svc"),true);awaitSettled();
        assertEquals("FAILED",state.get().state());assertEquals("MODEL_RESULT_UNVERIFIED",state.get().error());assertNull(state.get().result());assertEquals(3,publishCalls.get());
    }
    @Test void transientBusyWorkspaceStillPublishesAfterRecheck()throws Exception{
        setup(false);publishFailures.add(new MyException(40900,"busy"));
        service.decide(user,42,UUID.randomUUID(),scan,List.of("svc"),true);awaitSettled();
        assertEquals("SUCCEEDED",state.get().state());assertEquals(2,publishCalls.get());assertEquals("DONE",lease.get().cleanup());
    }
    @Test void cancellationWhileRunningNeverPublishes()throws Exception{
        setup(true);service.decide(user,42,UUID.randomUUID(),scan,List.of("svc"),true);assertTrue(entered.await(3,TimeUnit.SECONDS));
        service.cancel(user,42,approvalId,Long.parseLong(service.get(user,42,approvalId).revision()));release.countDown();awaitSettled();
        assertEquals("CANCELLED",state.get().state());assertEquals(0,publishCalls.get());
    }
}
