package com.dbts.glyahhaigeneratecode.projectimport;

import com.dbts.glyahhaigeneratecode.exception.MyException;
import com.dbts.glyahhaigeneratecode.model.Entity.User;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

class ImportStartupPreparationServiceTest {
    ImportStartupPlanService plans=mock(ImportStartupPlanService.class);
    ImportStartupPreparationRepository preparations=mock(ImportStartupPreparationRepository.class);
    ImportExecutionActions execution=mock(ImportExecutionActions.class);
    ImportStartupPreparationService service=new ImportStartupPreparationService(plans,preparations,execution,mock(ImportStartupInstallJobRepository.class));
    User user=User.builder().id(7L).build();
    UUID planId=UUID.randomUUID();
    ImportStartupPlan.Content node;
    ImportStartupPlan.Content plain;
    ImportStartupPlan.Content pair;

    @BeforeEach void setup(){
        node=ImportStartupPlan.build(inspection(new ImportStartupScanner.Candidate("web","web",".","npm run dev",null,"STATIC","package.json")),
            Map.of("package.json","{\"name\":\"a\"}"),List.of(),List.of("web"));
        plain=ImportStartupPlan.build(inspection(new ImportStartupScanner.Candidate("site/index.html","site","site",null,null,"STATIC","site/index.html")),
            Map.of(),List.of(),List.of("site/index.html"));
        pair=ImportStartupPlan.build(new ImportStartupService.Inspection(UUID.randomUUID(),"42",UUID.randomUUID(),"c".repeat(64),".","1","1",
            new ImportStartupScanner.Result("d".repeat(64),List.of(
                new ImportStartupScanner.Candidate("web","web","web","node server.js",null,"STATIC","web/server.js"),
                new ImportStartupScanner.Candidate("api","api","api","node app.js",null,"STATIC","api/app.js")),List.of())),
            Map.of("web/server.js","","api/app.js",""),List.of(),List.of("web","api"));
        when(preparations.save(any())).thenAnswer(invocation->invocation.getArgument(0));
        when(execution.stopOtherPlans(anyLong(),any())).thenReturn(true);
        when(execution.executionState(any(),anyLong(),any(),any())).thenReturn("RUNNING");
    }

    @Test void rejectingInstallDoesNotExecuteOrStart(){
        when(plans.get(user,42,planId)).thenReturn(view(node));
        var result=service.install(user,42,planId,revision(node),false);
        assertEquals("REJECTED",result.state());
        assertFalse(result.servicesStarted());
        assertFalse(result.runPermitted());
        verifyNoInteractions(execution);
    }

    @Test void unknownDependencyWithoutDeclaredInstallDoesNotBecomeReadyOrRun(){
        when(plans.get(user,42,planId)).thenReturn(view(node));
        var installed=service.install(user,42,planId,revision(node),true);
        assertEquals("BLOCKED",installed.state());
        assertEquals("DEPENDENCY_UNKNOWN",installed.reason());
        assertEquals("UNKNOWN",installed.dependencies().getFirst().status());
        var ran=service.run(user,42,planId,revision(node));
        assertEquals("WAITING_INSTALL",ran.state());
        assertFalse(ran.servicesStarted());
        verifyNoInteractions(execution);
    }

    @Test void memoryRefusalDoesNotExecuteInstallOrStartServices(){
        UUID id=UUID.randomUUID();
        var manual=ImportStartupPlan.build(inspection(new ImportStartupScanner.Candidate("manual:"+id,"web",".","npm run dev",null,"MANUAL","manual")),
            Map.of("package.json","{\"name\":\"a\"}"),List.of(new ImportStartupConfiguration.Manual(id,"web",".","npm run dev","npm ci",null)),List.of("manual:"+id));
        when(plans.get(user,42,planId)).thenReturn(view(manual));
        when(execution.admit(eq(42L),any())).thenReturn(List.of("MEMORY_LIMIT_UNENFORCEABLE"));
        var installed=service.install(user,42,planId,revision(manual),true);
        assertEquals("REFUSED",installed.state());
        assertFalse(installed.servicesStarted());
        verify(execution,never()).execute(any(),anyLong(),any(),any(),any(),any());
        when(preparations.find(7L,42,planId,revision(manual),"INSTALL")).thenReturn(row(manual,"INSTALL","APPROVED","REFUSED","MEMORY_LIMIT_UNENFORCEABLE",List.of()));
        var ran=service.run(user,42,planId,revision(manual));
        assertEquals("REFUSED",ran.state());
        verify(execution,never()).execute(any(),anyLong(),any(),any(),any(),any());
    }

    @Test void staticPlanRefusedByAdmissionDoesNotStart(){
        when(plans.get(user,42,planId)).thenReturn(view(plain));
        when(execution.admit(eq(42L),any())).thenReturn(List.of("MEMORY_LIMIT_UNENFORCEABLE"));
        var ran=service.run(user,42,planId,revision(plain));
        assertEquals("NONE_READY",ran.state());
        assertEquals("REFUSED",ran.services().getFirst().state());
        assertFalse(ran.servicesStarted());
        assertFalse(ran.runPermitted());
        verify(execution,never()).execute(any(),anyLong(),any(),any(),any(),any());
    }

    @Test void partialFailureRetryRepeatsOnlyTheFailedService(){
        when(plans.get(user,42,planId)).thenReturn(view(pair));
        when(execution.admit(eq(42L),any())).thenReturn(List.of());
        var ok=UUID.randomUUID();var failed=UUID.randomUUID();
        when(execution.execute(eq(user),eq(42L),eq(planId),eq("web"),eq("node server.js"),any(),any(),any(),any(),nullable(Integer.class))).thenReturn(new ImportExecutionRunner.Result(ok,"RUNNING",null,null,0,null));
        when(execution.execute(eq(user),eq(42L),eq(planId),eq("api"),eq("node app.js"),any(),any(),any(),any(),nullable(Integer.class))).thenReturn(new ImportExecutionRunner.Result(failed,"FAILED","EXIT_NONZERO",1,0,null));
        var first=service.run(user,42,planId,revision(pair));
        assertEquals("PARTIAL",first.state());
        assertTrue(first.servicesStarted());
        var web=first.services().stream().filter(item->item.serviceId().equals("web")).findFirst().orElseThrow();
        var api=first.services().stream().filter(item->item.serviceId().equals("api")).findFirst().orElseThrow();
        assertEquals("READY",web.state());
        assertEquals("FAILED",api.state());
        when(preparations.find(7L,42,planId,revision(pair),"RUN")).thenReturn(row(pair,"RUN","APPROVED","PARTIAL","PARTIAL_FAILURE",first.services()));
        clearInvocations(execution);
        when(execution.admit(eq(42L),any())).thenReturn(List.of());
        when(execution.execute(eq(user),eq(42L),eq(planId),eq("api"),eq("node app.js"),any(),any(),any(),any(),nullable(Integer.class))).thenReturn(new ImportExecutionRunner.Result(UUID.randomUUID(),"RUNNING",null,null,0,null));
        var second=service.retry(user,42,planId,revision(pair));
        assertEquals("READY",second.state());
        assertEquals(web.executionId(),second.services().stream().filter(item->item.serviceId().equals("web")).findFirst().orElseThrow().executionId());
        verify(execution,never()).execute(eq(user),eq(42L),eq(planId),eq("web"),any(),any(),any(),any(),any(),nullable(Integer.class));
        verify(execution).execute(eq(user),eq(42L),eq(planId),eq("api"),eq("node app.js"),any(),any(),any(),any(),nullable(Integer.class));
    }

    @Test void repeatedRunDoesNotStartReadyServicesAgain(){
        var py=ImportStartupPlan.build(inspection(new ImportStartupScanner.Candidate("web","web",".","python3 app.py",null,"MANUAL","app.py")),
            Map.of("app.py","print(1)\n"),List.of(),List.of("web"));
        when(plans.get(user,42,planId)).thenReturn(view(py));
        when(execution.admit(eq(42L),any())).thenReturn(List.of());
        var id=UUID.randomUUID();
        when(execution.execute(eq(user),eq(42L),eq(planId),eq("."),eq("python3 app.py"),any(),any(),any(),any(),nullable(Integer.class)))
            .thenReturn(new ImportExecutionRunner.Result(id,"RUNNING",null,null,0,"http://127.0.0.1:9/"));
        var first=service.run(user,42,planId,revision(py));
        assertEquals("READY",first.state());
        when(preparations.find(7L,42,planId,revision(py),"RUN")).thenReturn(row(py,"RUN","APPROVED","READY",null,first.services()));
        clearInvocations(execution);
        when(execution.stopOtherPlans(anyLong(),any())).thenReturn(true);
        var second=service.run(user,42,planId,revision(py));
        assertEquals(id,second.services().getFirst().executionId());
        verify(execution,never()).execute(any(),anyLong(),any(),any(),any(),any(),any(),any(),any(),any());
    }

    @Test void stoppedServicesAreNotRetainedAndRunStartsANewExecution(){
        when(plans.get(user,42,planId)).thenReturn(view(pair));
        var old=UUID.randomUUID();
        var saved=new java.util.concurrent.atomic.AtomicReference<>(row(pair,"RUN","APPROVED","READY",null,
            List.of(new ImportStartupPreparation.ServiceView("web","READY","READY",null,old,"http://127.0.0.1:9/"))));
        when(preparations.find(7L,42,planId,revision(pair),"RUN")).thenAnswer(invocation->saved.get());
        when(preparations.save(any())).thenAnswer(invocation->{var value=invocation.getArgument(0,ImportStartupPreparationRepository.Row.class);saved.set(value);return value;});
        when(execution.stopExecution(old)).thenReturn("STOPPED");
        var stopped=service.stop(user,42,planId,revision(pair));
        assertFalse(stopped.servicesStarted());
        assertEquals("STOPPED",stopped.services().getFirst().state());
        assertNull(stopped.services().getFirst().address());
        var next=UUID.randomUUID();
        when(execution.admit(eq(42L),any())).thenReturn(List.of());
        when(execution.execute(eq(user),eq(42L),eq(planId),anyString(),anyString(),any(),any(),any(),any(),nullable(Integer.class)))
            .thenAnswer(invocation->new ImportExecutionRunner.Result(next,"RUNNING",null,null,0,null));
        var rerun=service.run(user,42,planId,revision(pair));
        assertEquals("READY",rerun.state());
        assertEquals(next,rerun.services().getFirst().executionId());
        verify(execution).execute(eq(user),eq(42L),eq(planId),eq("web"),anyString(),any(),any(),any(),any(),nullable(Integer.class));
    }

    @Test void partialStopRecordsOnlyUnconfirmedServiceAsStopFailed(){
        when(plans.get(user,42,planId)).thenReturn(view(pair));
        var web=UUID.randomUUID();var api=UUID.randomUUID();
        when(preparations.find(7L,42,planId,revision(pair),"RUN")).thenReturn(row(pair,"RUN","APPROVED","READY",null,List.of(
            new ImportStartupPreparation.ServiceView("web","READY","READY",null,web,"http://127.0.0.1:9/"),
            new ImportStartupPreparation.ServiceView("api","READY","READY",null,api,"http://127.0.0.1:10/"))));
        when(execution.stopExecution(web)).thenReturn("STOPPED");
        when(execution.stopExecution(api)).thenReturn("STOPPING");
        var stopped=service.stop(user,42,planId,revision(pair));
        assertEquals("STOP_FAILED",stopped.reason());
        assertEquals("STOPPED",stopped.services().getFirst().state());
        assertNull(stopped.services().getFirst().address());
        assertEquals("STOP_FAILED",stopped.services().getLast().state());
        assertTrue(stopped.servicesStarted());
        verify(preparations,times(1)).find(7L,42,planId,revision(pair),"RUN");
        verify(execution,never()).execute(any(),anyLong(),any(),any(),any(),any());
    }

    @Test void exitedServiceIsNoLongerReadyAndCanBeRetried(){
        when(plans.get(user,42,planId)).thenReturn(view(pair));
        var web=UUID.randomUUID();var api=UUID.randomUUID();
        when(preparations.find(7L,42,planId,revision(pair),"RUN")).thenReturn(row(pair,"RUN","APPROVED","READY",null,List.of(
            new ImportStartupPreparation.ServiceView("web","READY","READY",null,web,"http://127.0.0.1:9/"),
            new ImportStartupPreparation.ServiceView("api","READY","READY",null,api,"http://127.0.0.1:10/"))));
        when(execution.executionState(user,42,planId,api)).thenReturn("FAILED");
        when(execution.executionReason(user,42,planId,api)).thenReturn("OUTPUT_LIMIT");
        var current=service.current(user,42,planId);
        assertEquals("PARTIAL",current.state());
        assertEquals("FAILED",current.services().getLast().state());
        assertEquals("OUTPUT_LIMIT",current.services().getLast().reason());
        assertNull(current.services().getLast().address());
        when(execution.admit(eq(42L),any())).thenReturn(List.of());
        when(execution.execute(eq(user),eq(42L),eq(planId),eq("api"),any(),any(),any(),any(),any(),nullable(Integer.class)))
            .thenReturn(new ImportExecutionRunner.Result(UUID.randomUUID(),"RUNNING",null,null,0,null));
        assertEquals("READY",service.retry(user,42,planId,revision(pair)).state());
        verify(execution,never()).execute(eq(user),eq(42L),eq(planId),eq("web"),any(),any(),any(),any(),any(),nullable(Integer.class));
    }

    @Test void unknownRuntimeIsNotReadyAndCannotBeRelaunched(){
        when(plans.get(user,42,planId)).thenReturn(view(pair));
        var id=UUID.randomUUID();
        when(preparations.find(7L,42,planId,revision(pair),"RUN")).thenReturn(row(pair,"RUN","APPROVED","READY",null,
            List.of(new ImportStartupPreparation.ServiceView("web","READY","READY",null,id))));
        when(execution.executionState(user,42,planId,id)).thenReturn("UNKNOWN");
        assertEquals("STOP_FAILED",service.current(user,42,planId).reason());
        assertEquals("STOP_FAILED",service.run(user,42,planId,revision(pair)).reason());
        assertEquals("STOP_FAILED",service.retry(user,42,planId,revision(pair)).reason());
        verify(execution,never()).execute(any(),anyLong(),any(),any(),any(),any(),any(),any(),any(),any());
    }

    @Test void firstInstallFailureStopsRemainingCommands(){
        var web=UUID.randomUUID();var api=UUID.randomUUID();
        var two=ImportStartupPlan.build(new ImportStartupService.Inspection(UUID.randomUUID(),"42",UUID.randomUUID(),"c".repeat(64),".","1","1",
                new ImportStartupScanner.Result("d".repeat(64),List.of(
                    new ImportStartupScanner.Candidate("manual:"+web,"web","web","node server.js",null,"MANUAL","web"),
                    new ImportStartupScanner.Candidate("manual:"+api,"api","api","node app.js",null,"MANUAL","api")),List.of())),
            Map.of("web/package.json","{\"name\":\"a\"}","api/requirements.txt","flask\n"),
            List.of(new ImportStartupConfiguration.Manual(web,"web","web","node server.js","npm ci",null),
                    new ImportStartupConfiguration.Manual(api,"api","api","node app.js","npm install",null)),
            List.of("manual:"+web,"manual:"+api));
        when(plans.get(user,42,planId)).thenReturn(view(two));
        when(execution.admit(eq(42L),any())).thenReturn(List.of());
        when(execution.execute(any(),eq(42L),eq(planId),anyString(),anyString(),any(),anyString(),any(),any()))
            .thenReturn(new ImportExecutionRunner.Result(UUID.randomUUID(),"FAILED","EXIT_NONZERO",1,0,null));
        var installed=service.install(user,42,planId,revision(two),true);
        assertEquals("FAILED",installed.state());
        // 首条安装命令失败后不再开始后续命令，也不自动重试。
        verify(execution,times(1)).execute(any(),anyLong(),any(),any(),any(),any(),any(),any(),any());
    }

    @Test void changedPlanCannotBeRun(){
        when(plans.get(user,42,planId)).thenReturn(new ImportStartupPlanService.View(planId,"42",node.scanId(),revision(node),"STALE","1",false,null,false,node,"PLAN_CHANGED",List.of(),null));
        assertThrows(MyException.class,()->service.run(user,42,planId,revision(node)));
        verifyNoInteractions(execution);
    }

    @Test void stopDuringStartupCancelsLateReadyAndBlocksAnotherRun() throws Exception {
        when(plans.get(user,42,planId)).thenReturn(view(pair));
        var entered=new java.util.concurrent.CountDownLatch(1);
        var release=new java.util.concurrent.CountDownLatch(1);
        var id=UUID.randomUUID();
        when(execution.execute(eq(user),eq(42L),eq(planId),anyString(),anyString(),any(),any(),any(),any(),nullable(Integer.class)))
            .thenAnswer(invocation->{entered.countDown();assertTrue(release.await(5,java.util.concurrent.TimeUnit.SECONDS));return new ImportExecutionRunner.Result(id,"RUNNING",null,null,0,null);});
        when(execution.activeSlots(42L,"service:")).thenReturn(List.of(id));
        when(execution.stopExecution(id)).thenAnswer(invocation->{release.countDown();return "STOPPED";});
        try(var pool=java.util.concurrent.Executors.newSingleThreadExecutor()){
            var running=pool.submit(()->service.run(user,42,planId,revision(pair)));
            assertTrue(entered.await(5,java.util.concurrent.TimeUnit.SECONDS));
            assertThrows(MyException.class,()->service.run(user,42,planId,revision(pair)));
            var stopped=service.stop(user,42,planId,revision(pair));
            assertEquals("STOPPED",stopped.reason());
            var late=assertThrows(java.util.concurrent.ExecutionException.class,()->running.get(5,java.util.concurrent.TimeUnit.SECONDS));
            assertInstanceOf(MyException.class,late.getCause());
            verify(preparations,never()).save(argThat(row->"READY".equals(row.state())));
            verify(execution,times(1)).execute(eq(user),eq(42L),eq(planId),anyString(),anyString(),any(),any(),any(),any(),nullable(Integer.class));
        }finally{release.countDown();}
    }

    @Test void unconfirmedStartupStopRetainsFailureAndPreventsRerun() throws Exception {
        when(plans.get(user,42,planId)).thenReturn(view(pair));
        var saved=new java.util.concurrent.atomic.AtomicReference<ImportStartupPreparationRepository.Row>();
        when(preparations.find(7L,42,planId,revision(pair),"RUN")).thenAnswer(invocation->saved.get());
        when(preparations.save(any())).thenAnswer(invocation->{var row=invocation.getArgument(0,ImportStartupPreparationRepository.Row.class);saved.set(row);return row;});
        var entered=new java.util.concurrent.CountDownLatch(1);
        var release=new java.util.concurrent.CountDownLatch(1);
        var id=UUID.randomUUID();
        when(execution.execute(eq(user),eq(42L),eq(planId),anyString(),anyString(),any(),any(),any(),any(),nullable(Integer.class)))
            .thenAnswer(invocation->{entered.countDown();assertTrue(release.await(5,java.util.concurrent.TimeUnit.SECONDS));return new ImportExecutionRunner.Result(id,"RUNNING",null,null,0,null);});
        when(execution.activeSlots(42L,"service:")).thenReturn(List.of(id));
        when(execution.stopExecution(id)).thenAnswer(invocation->{release.countDown();return "STOP_FAILED";});
        try(var pool=java.util.concurrent.Executors.newSingleThreadExecutor()){
            var running=pool.submit(()->service.run(user,42,planId,revision(pair)));
            assertTrue(entered.await(5,java.util.concurrent.TimeUnit.SECONDS));
            assertEquals("STOP_FAILED",service.stop(user,42,planId,revision(pair)).reason());
            assertThrows(java.util.concurrent.ExecutionException.class,()->running.get(5,java.util.concurrent.TimeUnit.SECONDS));
            assertEquals("STOP_FAILED",service.run(user,42,planId,revision(pair)).reason());
            verify(execution,times(1)).execute(eq(user),eq(42L),eq(planId),anyString(),anyString(),any(),any(),any(),any(),nullable(Integer.class));
        }finally{release.countDown();}
    }

    private ImportStartupPreparationRepository.Row row(ImportStartupPlan.Content content,String kind,String decision,String state,String reason,List<ImportStartupPreparation.ServiceView> services){
        return new ImportStartupPreparationRepository.Row(UUID.randomUUID(),7,42,planId,revision(content),content.contentHash(),kind,decision,state,reason,services,0);
    }
    private ImportStartupPlanService.View view(ImportStartupPlan.Content content){
        return new ImportStartupPlanService.View(planId,"42",content.scanId(),revision(content),"CONFIRMED","1",false,Instant.now(),false,content,null,List.of(),null);
    }
    private static String revision(ImportStartupPlan.Content content){return ImportStartupPlan.revision(content);}
    private static ImportStartupService.Inspection inspection(ImportStartupScanner.Candidate candidate){
        return new ImportStartupService.Inspection(UUID.randomUUID(),"42",UUID.randomUUID(),"a".repeat(64),".","1","1",
            new ImportStartupScanner.Result("b".repeat(64),List.of(candidate),List.of()));
    }
}
