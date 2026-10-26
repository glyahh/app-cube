package com.dbts.glyahhaigeneratecode.projectimport;

import com.dbts.glyahhaigeneratecode.exception.MyException;
import com.dbts.glyahhaigeneratecode.model.Entity.User;
import com.dbts.glyahhaigeneratecode.service.AppService;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import java.nio.file.*;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Supplier;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

class ImportStartupServiceTest {
    @TempDir Path root;
    AppService apps=mock(AppService.class);ImportTaskRepository repository=mock(ImportTaskRepository.class);
    ImportTaskService tasks=mock(ImportTaskService.class);ImportWorkspaceCoordinator coordinator=mock(ImportWorkspaceCoordinator.class);
    ImportStartupConfigurationRepository configurations=mock(ImportStartupConfigurationRepository.class);
    ImportStartupInspectionRepository inspections=mock(ImportStartupInspectionRepository.class);
    ImportStartupApprovalRepository approvals=mock(ImportStartupApprovalRepository.class);
    UUID task=UUID.randomUUID();User user=User.builder().id(7L).userRole("user").build();Path workspace;
    ImportStartupService setup()throws Exception{
        workspace=Files.createDirectory(root.resolve("workspace"));Files.writeString(workspace.resolve("index.html"),"fixture");
        when(repository.publicationByApp(42L)).thenReturn(new ImportTaskRepository.Publication(task,42,7,"READY","origin",0,null));
        when(tasks.result(7,task)).thenReturn(new ImportTaskService.CommitResult("42",task,"origin","/app/42/chat"));
        when(apps.resolveWorkspaceDir(user,42L)).thenReturn(workspace);
        when(apps.getById(42L)).thenReturn(com.dbts.glyahhaigeneratecode.model.Entity.App.builder().id(42L).userId(7L).build());
        doAnswer(call->((Supplier<?>)call.getArgument(1)).get()).when(coordinator).withLock(anyLong(),any());
        var properties=new ImportProperties();properties.setStagingRoot(root.resolve("imports"));
        var state=new ImportStartupConfiguration.State(42,7,task,0,0,".",java.util.List.of());
        when(configurations.current(42,7,task)).thenReturn(state);when(configurations.read(42,7,task)).thenReturn(state);
        return new ImportStartupService(apps,repository,tasks,coordinator,properties,new ImportPythonStartupReader(properties),configurations,inspections,approvals);
    }
    @Test void cleanupOwnershipDoesNotRequireCurrentSourceOrImportEligibility()throws Exception{
        var service=setup();
        when(repository.publicationByApp(42L)).thenReturn(null);
        when(apps.resolveWorkspaceDir(user,42L)).thenThrow(new MyException(com.dbts.glyahhaigeneratecode.exception.ErrorCode.NOT_FOUND_ERROR,"missing source"));
        service.requireCleanupOwner(user,42);
        verify(apps,never()).resolveWorkspaceDir(any(),anyLong());
        verifyNoInteractions(repository,tasks,coordinator);
        user.setId(8L);
        assertThrows(MyException.class,()->service.requireCleanupOwner(user,42));
        assertThrows(MyException.class,()->service.requireCleanupOwner(null,42));
    }

    @Test void publishedOwnerGetsBoundInspectionAndTemporaryInputIsRemoved()throws Exception{
        var service=setup();var result=service.inspect(user,42,".");
        assertEquals("42",result.appId());assertEquals(task,result.importTaskId());assertEquals(64,result.contentHash().length());
        assertEquals(1,result.result().candidates().size());assertEquals("fixture",Files.readString(workspace.resolve("index.html")));
        try(var files=Files.list(root.resolve("project_startup_private"))){assertEquals(0,files.count());}
        verify(coordinator,times(2)).withLock(eq(42L),any());
        assertNotNull(result.scanId());verify(inspections).save(7,result);
    }
    @Test void foreignOwnerIncludingAdminCannotReadImportedSource()throws Exception{
        var service=setup();user.setId(8L);user.setUserRole("admin");
        assertThrows(MyException.class,()->service.inspect(user,42,"."));verifyNoInteractions(coordinator);
    }
    @Test void unpublishedOrMismatchedImportResultCannotReadSource()throws Exception{
        var service=setup();when(repository.publicationByApp(42L)).thenReturn(new ImportTaskRepository.Publication(task,42,7,"COPYING","origin",0,null));
        assertThrows(MyException.class,()->service.inspect(user,42,"."));verifyNoInteractions(coordinator);
        when(repository.publicationByApp(42L)).thenReturn(new ImportTaskRepository.Publication(task,42,7,"READY","origin",0,null));
        when(tasks.result(7,task)).thenReturn(new ImportTaskService.CommitResult("43",task,"origin","/app/43/chat"));
        assertThrows(MyException.class,()->service.inspect(user,42,"."));
    }
    @Test void editBetweenCaptureAndResultRejectsLateInspection()throws Exception{
        var service=setup();var count=new AtomicInteger();
        doAnswer(call->{if(count.incrementAndGet()==2)Files.writeString(workspace.resolve("index.html"),"changed");return ((Supplier<?>)call.getArgument(1)).get();})
            .when(coordinator).withLock(anyLong(),any());
        assertThrows(MyException.class,()->service.inspect(user,42,"."));
        try(var files=Files.list(root.resolve("project_startup_private"))){assertEquals(0,files.count());}
    }
    @Test void invalidScopeDoesNotCreateSnapshot()throws Exception{
        var service=setup();for(String scope:new String[]{"../other","missing","/etc"})assertThrows(MyException.class,()->service.inspect(user,42,scope));
        try(var files=Files.list(root.resolve("project_startup_private"))){assertEquals(0,files.count());}
    }
    @Test void validEmptyDirectoryIsIncompleteNotInvalidOrAgentEligible()throws Exception{
        var service=setup();Files.createDirectory(workspace.resolve("empty"));
        var result=service.inspect(user,42,"empty");assertEquals("INCOMPLETE",result.result().scopes().getFirst().status());
        assertTrue(result.result().candidates().isEmpty());
    }
    @Test void changedContextRejectsResultEvenWhenSourceBytesStayTheSame()throws Exception{
        var service=setup();when(configurations.read(42,7,task)).thenReturn(new ImportStartupConfiguration.State(42,7,task,0,1,".",java.util.List.of()));
        assertThrows(MyException.class,()->service.inspect(user,42,"."));
    }
    @Test void staleConfigRevisionDoesNotWriteManualEntries()throws Exception{
        var service=setup();assertThrows(MyException.class,()->service.saveConfiguration(user,42,1,0,".",java.util.List.of()));
        verify(configurations,never()).save(any(),anyString(),anyList());
    }
    @Test void manualEntriesAreShownFirstWithoutDeletingAutomaticEvidence()throws Exception{
        var service=setup();var manual=new ImportStartupConfiguration.Manual(UUID.randomUUID(),"Manual",".","unknown --fixture",null,null);
        var state=new ImportStartupConfiguration.State(42,7,task,1,2,".",java.util.List.of(manual));
        when(configurations.current(42,7,task)).thenReturn(state);when(configurations.read(42,7,task)).thenReturn(state);
        var result=service.inspect(user,42,".");assertEquals("1",result.configRevision());assertEquals("2",result.contextEpoch());
        assertEquals("MANUAL",result.result().candidates().getFirst().source());assertEquals(2,result.result().candidates().size());
    }
    @Test void changedActualAppOwnerCannotUseOriginalPublicationEvenAsAdmin()throws Exception{
        var service=setup();user.setUserRole("admin");
        when(apps.getById(42L)).thenReturn(com.dbts.glyahhaigeneratecode.model.Entity.App.builder().id(42L).userId(8L).build());
        assertThrows(MyException.class,()->service.configuration(user,42));verifyNoInteractions(coordinator);
    }

    private ImportStartupService.Inspection stored(ImportStartupService service){
        var result=service.inspect(user,42,".");
        when(inspections.find(42,7,task,result.scanId())).thenReturn(new ImportStartupInspectionRepository.Stored("SUCCEEDED",ImportStartupInspectionRepository.RULES_VERSION,result));
        return result;
    }
    @Test void completedInspectionCanBeRestoredWithoutRepeatingParser()throws Exception{
        var service=setup();var previous=stored(service);var restored=service.inspection(user,42,previous.scanId());
        assertEquals("SUCCEEDED",restored.status());assertEquals(previous,restored.inspection());
        verify(inspections,times(1)).save(anyLong(),any());
        try(var files=Files.list(root.resolve("project_startup_private"))){assertEquals(0,files.count());}
    }
    @Test void sameVersionExternalDiskEditPermanentlyRevokesStoredResult()throws Exception{
        var service=setup();var previous=stored(service);Files.writeString(workspace.resolve("index.html"),"changed");
        var restored=service.inspection(user,42,previous.scanId());assertEquals("STALE",restored.status());assertNull(restored.inspection());
        verify(inspections).stale(42,7,previous.scanId());
    }
    @Test void configurationChangeRevokesStoredCandidatesWithoutTakingSnapshot()throws Exception{
        var service=setup();var previous=stored(service);
        when(configurations.current(42,7,task)).thenReturn(new ImportStartupConfiguration.State(42,7,task,1,1,".",java.util.List.of()));
        var restored=service.inspection(user,42,previous.scanId());assertEquals("STALE",restored.status());assertNull(restored.inspection());
        verify(inspections).stale(42,7,previous.scanId());
    }
    @Test void rulesChangeRevokesStoredCandidates()throws Exception{
        var service=setup();var previous=stored(service);
        when(inspections.find(42,7,task,previous.scanId())).thenReturn(new ImportStartupInspectionRepository.Stored("SUCCEEDED","old",previous));
        assertEquals("STALE",service.inspection(user,42,previous.scanId()).status());
    }
    @Test void foreignOwnerCannotRestoreCompletedRecord()throws Exception{
        var service=setup();var previous=stored(service);clearInvocations(inspections);user.setId(8L);user.setUserRole("admin");
        assertThrows(MyException.class,()->service.inspection(user,42,previous.scanId()));verifyNoInteractions(inspections);
    }
    @Test void staleRecordCannotBecomeCurrentWhenOriginalBytesReturn()throws Exception{
        var service=setup();var previous=stored(service);
        when(inspections.find(42,7,task,previous.scanId())).thenReturn(new ImportStartupInspectionRepository.Stored("STALE",ImportStartupInspectionRepository.RULES_VERSION,previous));
        var restored=service.inspection(user,42,previous.scanId());assertEquals("STALE",restored.status());assertNull(restored.inspection());
        verifyNoMoreInteractionsAfterRead();
    }
    private void verifyNoMoreInteractionsAfterRead(){verify(inspections,never()).stale(anyLong(),anyLong(),any());}
    @Test void approvalCallbackRunsUnderTheSameVerifiedWorkspaceLock()throws Exception{
        var service=setup();var previous=stored(service);var locked=new java.util.concurrent.atomic.AtomicBoolean();
        clearInvocations(coordinator);
        doAnswer(call->{assertTrue(locked.compareAndSet(false,true));try{return ((Supplier<?>)call.getArgument(1)).get();}finally{locked.set(false);}})
            .when(coordinator).withLock(anyLong(),any());
        var result=service.withCurrentInspection(user,42,previous.scanId(),inspection->{assertTrue(locked.get());assertEquals(previous,inspection);return "decision";});
        assertEquals("decision",result);assertFalse(locked.get());verify(coordinator,times(1)).withLock(eq(42L),any());
    }
    @Test void staleInspectionNeverInvokesApprovalCallback()throws Exception{
        var service=setup();var previous=stored(service);Files.writeString(workspace.resolve("index.html"),"external edit");var calls=new AtomicInteger();
        assertThrows(MyException.class,()->service.withCurrentInspection(user,42,previous.scanId(),inspection->{calls.incrementAndGet();return null;}));
        assertEquals(0,calls.get());verify(inspections).stale(42,7,previous.scanId());
    }
    @Test void approvedProjectionUsesCurrentSealedBytesAndNeverExposesSecretPaths()throws Exception{
        var service=setup();Files.delete(workspace.resolve("index.html"));Files.writeString(workspace.resolve("README.md"),"start with service");
        Files.writeString(workspace.resolve(".env"),"TEST_ONLY=secret");var previous=stored(service);
        var approval=new ImportStartupApprovalRepository.Approval(UUID.randomUUID(),UUID.randomUUID(),7,42,previous.scanId(),task,previous.contentHash(),0,0,
            ImportStartupInspectionRepository.RULES_VERSION,java.util.List.of("."),true,"RUNNING",1,java.time.Instant.now().plusSeconds(60),null,null,false);
        var input=service.approvedModelInput(user,approval,()->true);assertEquals(java.util.Map.of("README.md","start with service"),input.files());
        assertThrows(UnsupportedOperationException.class,()->input.files().put("extra","bad"));
        assertEquals(java.util.List.of(new ImportStartupScanner.Scope(".","ALGORITHM","UNIDENTIFIED","NO_ENTRY")),input.ranges());assertTrue(input.known().isEmpty());
        try(var files=Files.list(root.resolve("project_startup_private"))){assertEquals(0,files.count());}
        Files.writeString(workspace.resolve("README.md"),"changed");assertThrows(MyException.class,()->service.approvedModelInput(user,approval,()->true));
    }
    @Test void projectionRejectsForeignOrUnapprovedLeaseBeforeWorkspaceRead()throws Exception{
        var service=setup();var previous=stored(service);clearInvocations(coordinator);
        var approval=new ImportStartupApprovalRepository.Approval(UUID.randomUUID(),UUID.randomUUID(),8,42,previous.scanId(),task,previous.contentHash(),0,0,
            ImportStartupInspectionRepository.RULES_VERSION,java.util.List.of("."),true,"RUNNING",1,java.time.Instant.now().plusSeconds(60),null,null,false);
        assertThrows(MyException.class,()->service.approvedModelInput(user,approval,()->true));verifyNoInteractions(coordinator);
    }

    // 以下为发布路径：批准与模型回包为受控替身，工作区与完整内容复核使用真实文件。
    static String sha(String text)throws Exception{return java.util.HexFormat.of().formatHex(java.security.MessageDigest.getInstance("SHA-256").digest(text.getBytes(java.nio.charset.StandardCharsets.UTF_8)));}
    ImportStartupApprovalRepository.Approval running(ImportStartupService.Inspection inspection,String directory,java.time.Instant expires){
        return new ImportStartupApprovalRepository.Approval(UUID.randomUUID(),UUID.randomUUID(),7,42,inspection.scanId(),task,inspection.contentHash(),0,0,
            ImportStartupInspectionRepository.RULES_VERSION,java.util.List.of(directory),true,"RUNNING",1,expires,null,null,false);
    }
    ImportStartupApprovalRepository.Approval succeeded(ImportStartupApprovalRepository.Approval approval,String output){
        return new ImportStartupApprovalRepository.Approval(approval.id(),approval.requestId(),7,42,approval.scanId(),task,approval.contentHash(),0,0,approval.rulesVersion(),approval.directories(),true,
            "SUCCEEDED",approval.revision()+1,approval.expiresAt(),output,null,false);
    }
    String reply(ImportStartupApprovalRepository.Approval approval,String command,String evidence,String hash)throws Exception{
        var service=new java.util.LinkedHashMap<String,Object>();service.put("name","补充服务");service.put("directory",approval.directories().getFirst());service.put("command",command);service.put("build",null);
        service.put("evidence",java.util.List.of(evidence));service.put("source","AGENT");service.put("certainty","INFERRED");service.put("evidenceHashes",java.util.Map.of(evidence,hash));
        var output=new java.util.LinkedHashMap<String,Object>();output.put("approvalId",approval.id().toString());output.put("scanId",approval.scanId().toString());output.put("appId","42");
        output.put("importTaskId",task.toString());output.put("contentHash",approval.contentHash());output.put("configRevision","0");output.put("contextEpoch","0");
        output.put("services",java.util.List.of(service));output.put("unidentified",java.util.List.of());return new com.fasterxml.jackson.databind.ObjectMapper().writeValueAsString(output);
    }
    static final String SERVER="require('http').createServer().listen(3000)";
    ImportStartupService workerProject()throws Exception{
        var service=setup();Files.createDirectories(workspace.resolve("worker"));
        Files.writeString(workspace.resolve("worker/package.json"),"{\"name\":\"worker\",\"scripts\":{}}");Files.writeString(workspace.resolve("worker/server.js"),SERVER);return service;
    }
    void publishedAs(ImportStartupApprovalRepository.Approval... records){
        var ids=new java.util.ArrayList<UUID>();for(var record:records){ids.add(record.id());when(approvals.find(7,record.id())).thenReturn(record);}
        when(approvals.published(eq(7L),eq(42L),any())).thenReturn(ids);
    }
    void assertNoPrivateInput()throws Exception{try(var files=Files.list(root.resolve("project_startup_private"))){assertEquals(0,files.count());}}

    @Test void replyIsPublishedOnlyAfterLockedFullContentRecheckAndReadBackSeparately()throws Exception{
        var service=workerProject();var previous=stored(service);var approval=running(previous,"worker",java.time.Instant.now().plusSeconds(60));
        String output=reply(approval,"node server.js","worker/server.js",sha(SERVER));var locked=new java.util.concurrent.atomic.AtomicBoolean();
        doAnswer(call->{assertTrue(locked.compareAndSet(false,true));try{return ((Supplier<?>)call.getArgument(1)).get();}finally{locked.set(false);}}).when(coordinator).withLock(anyLong(),any());
        var committed=service.publishSupplement(user,approval,output,()->true,current->{assertTrue(locked.get());assertEquals(approval,current);return succeeded(current,output);});
        assertEquals("SUCCEEDED",committed.state());assertNoPrivateInput();
        publishedAs(committed);var restored=service.inspection(user,42,previous.scanId());
        assertEquals("SUCCEEDED",restored.status());var candidates=restored.inspection().result().candidates();
        assertEquals(previous.result().candidates().size()+1,candidates.size());assertEquals("AGENT",candidates.getLast().source());assertEquals("node server.js",candidates.getLast().command());
        var scopes=restored.inspection().result().scopes();
        assertTrue(scopes.contains(new ImportStartupScanner.Scope("worker","FIXED","UNIDENTIFIED","PACKAGE_MANAGER_UNKNOWN")));
        assertTrue(scopes.contains(new ImportStartupScanner.Scope("worker","AGENT","IDENTIFIED",null)));
        assertTrue(ImportStartupAgentPolicy.eligibleDirectories(restored.inspection()).isEmpty());
        verify(inspections,never()).stale(anyLong(),anyLong(),any());verify(approvals,never()).transition(any(),anyString(),any(),any());assertNoPrivateInput();
    }
    @Test void diskEditBeforePublishRejectsReplyWithoutCommit()throws Exception{
        var service=workerProject();var previous=stored(service);var approval=running(previous,"worker",java.time.Instant.now().plusSeconds(60));
        String output=reply(approval,"node server.js","worker/server.js",sha(SERVER));Files.writeString(workspace.resolve("worker/server.js"),"changed after model input");
        var calls=new AtomicInteger();
        var rejected=assertThrows(ImportStartupService.SupplementRejected.class,()->service.publishSupplement(user,approval,output,()->true,current->{calls.incrementAndGet();return current;}));
        assertEquals("MODEL_RESULT_STALE",rejected.code());assertEquals(0,calls.get());verify(inspections).stale(42,7,previous.scanId());assertNoPrivateInput();
    }
    @Test void contextChangeBeforePublishRejectsReplyEvenWithSameBytes()throws Exception{
        var service=workerProject();var previous=stored(service);var approval=running(previous,"worker",java.time.Instant.now().plusSeconds(60));
        String output=reply(approval,"node server.js","worker/server.js",sha(SERVER));
        when(configurations.current(42,7,task)).thenReturn(new ImportStartupConfiguration.State(42,7,task,0,1,".",java.util.List.of()));
        assertEquals("MODEL_RESULT_STALE",assertThrows(ImportStartupService.SupplementRejected.class,()->service.publishSupplement(user,approval,output,()->true,current->current)).code());
    }
    @Test void expiredOrCancelledReplyNeverReachesWorkspace()throws Exception{
        var service=workerProject();var previous=stored(service);clearInvocations(coordinator);
        var expired=running(previous,"worker",java.time.Instant.now().minusSeconds(1));String output=reply(expired,"node server.js","worker/server.js",sha(SERVER));
        assertEquals("APPROVAL_EXPIRED",assertThrows(ImportStartupService.SupplementRejected.class,()->service.publishSupplement(user,expired,output,()->true,current->current)).code());
        var live=running(previous,"worker",java.time.Instant.now().plusSeconds(60));
        var cancelling=new ImportStartupApprovalRepository.Approval(live.id(),live.requestId(),7,42,live.scanId(),task,live.contentHash(),0,0,live.rulesVersion(),live.directories(),true,"CANCELLING",2,live.expiresAt(),null,null,true);
        assertEquals("MODEL_RESULT_STALE",assertThrows(ImportStartupService.SupplementRejected.class,()->service.publishSupplement(user,cancelling,output,()->true,current->current)).code());
        verifyNoInteractions(coordinator);
        var stopping=running(previous,"worker",java.time.Instant.now().plusSeconds(60));String stoppingReply=reply(stopping,"node server.js","worker/server.js",sha(SERVER));
        assertEquals("MODEL_RESULT_UNVERIFIED",assertThrows(ImportStartupService.SupplementRejected.class,()->service.publishSupplement(user,stopping,stoppingReply,()->false,current->current)).code());
    }
    @Test void duplicateOfKnownCandidateIsRejectedAndIndependentRangeIsPublishedSeparately()throws Exception{
        var service=setup();Files.createDirectories(workspace.resolve("app"));
        Files.writeString(workspace.resolve("app/package.json"),"{\"name\":\"app\",\"packageManager\":\"npm@10.0.0\",\"scripts\":{\"dev\":\"vite\"}}");
        String pom="<project><modelVersion>4.0.0</modelVersion></project>";Files.writeString(workspace.resolve("app/pom.xml"),pom);
        var previous=stored(service);var approval=running(previous,"app",java.time.Instant.now().plusSeconds(60));
        var input=service.approvedModelInput(user,approval,()->true);
        assertEquals(java.util.List.of(new ImportStartupScanner.Scope("app","ALGORITHM","UNIDENTIFIED","NO_SUPPORTED_JAVA_PLUGIN")),input.ranges());
        assertEquals(java.util.List.of("npm run dev"),input.known().stream().map(ImportStartupScanner.Candidate::command).toList());
        assertEquals(java.util.Set.of("app/package.json","app/pom.xml"),input.files().keySet());
        String duplicate=reply(approval,"npm run dev","app/pom.xml",sha(pom));
        assertEquals("MODEL_RESULT_CONFLICT",assertThrows(ImportStartupService.SupplementRejected.class,()->service.publishSupplement(user,approval,duplicate,()->true,current->current)).code());
        String maven=reply(approval,"mvn exec:java","app/pom.xml",sha(pom));
        var committed=service.publishSupplement(user,approval,maven,()->true,current->succeeded(current,maven));publishedAs(committed);
        var result=service.inspection(user,42,previous.scanId()).inspection().result();
        assertEquals(java.util.List.of("npm run dev","mvn exec:java"),result.candidates().stream().filter(c->c.directory().equals("app")).map(ImportStartupScanner.Candidate::command).toList());
        assertTrue(result.scopes().contains(new ImportStartupScanner.Scope("app","FIXED","IDENTIFIED",null)));
        assertTrue(result.scopes().contains(new ImportStartupScanner.Scope("app","ALGORITHM","UNIDENTIFIED","NO_SUPPORTED_JAVA_PLUGIN")));
    }
    @Test void secondReplyForAlreadyPublishedDirectoryIsRejected()throws Exception{
        var service=workerProject();var previous=stored(service);var first=running(previous,"worker",java.time.Instant.now().plusSeconds(60));
        String output=reply(first,"node server.js","worker/server.js",sha(SERVER));publishedAs(service.publishSupplement(user,first,output,()->true,current->succeeded(current,output)));
        var second=running(previous,"worker",java.time.Instant.now().plusSeconds(60));String other=reply(second,"node worker.js","worker/server.js",sha(SERVER));
        assertEquals("MODEL_RESULT_CONFLICT",assertThrows(ImportStartupService.SupplementRejected.class,()->service.publishSupplement(user,second,other,()->true,current->current)).code());
        // 已发布目录的排队批准不再取得模型输入。
        assertThrows(MyException.class,()->service.approvedModelInput(user,second,()->true));assertNoPrivateInput();
    }
    @Test void externalEditAfterPublishHidesAndRevokesPublishedCandidates()throws Exception{
        var service=workerProject();var previous=stored(service);var approval=running(previous,"worker",java.time.Instant.now().plusSeconds(60));
        String output=reply(approval,"node server.js","worker/server.js",sha(SERVER));var committed=succeeded(approval,output);publishedAs(committed);
        assertEquals(previous.result().candidates().size()+1,service.inspection(user,42,previous.scanId()).inspection().result().candidates().size());
        Files.writeString(workspace.resolve("worker/server.js"),"external edit");
        var restored=service.inspection(user,42,previous.scanId());assertEquals("STALE",restored.status());assertNull(restored.inspection());
        verify(approvals).transition(committed,"STALE",null,"MODEL_RESULT_STALE");
    }
    @Test void tamperedPublishedRecordIsRevokedAndOtherCandidatesStayCurrent()throws Exception{
        var service=workerProject();var previous=stored(service);var approval=running(previous,"worker",java.time.Instant.now().plusSeconds(60));
        var tampered=succeeded(approval,reply(approval,"node server.js","worker/server.js","b".repeat(64)));publishedAs(tampered);
        var restored=service.inspection(user,42,previous.scanId());assertEquals("SUCCEEDED",restored.status());assertEquals(previous,restored.inspection());
        verify(approvals).transition(tampered,"STALE",null,"MODEL_RESULT_STALE");
    }
    @Test void unreadablePublishedRecordIsSkippedWithoutBlockingRead()throws Exception{
        var service=workerProject();var previous=stored(service);UUID broken=UUID.randomUUID();
        when(approvals.published(eq(7L),eq(42L),any())).thenReturn(java.util.List.of(broken));when(approvals.find(7,broken)).thenThrow(new MyException(50000,"broken"));
        assertEquals(previous,service.inspection(user,42,previous.scanId()).inspection());
    }
    @Test void planInputUsesTheSameVerifiedSnapshotAndExcludesSecrets()throws Exception{
        var service=setup();Files.writeString(workspace.resolve("package.json"),"{\"scripts\":{\"dev\":\"vite\"}}");Files.writeString(workspace.resolve(".env"),"TEST_ONLY=secret");
        var previous=stored(service);var locked=new java.util.concurrent.atomic.AtomicBoolean();clearInvocations(coordinator);
        doAnswer(call->{assertTrue(locked.compareAndSet(false,true));try{return ((Supplier<?>)call.getArgument(1)).get();}finally{locked.set(false);}})
            .when(coordinator).withLock(anyLong(),any());
        var files=service.withPlanInput(user,42,previous.scanId(),input->{assertTrue(locked.get());assertEquals(previous,input.inspection());assertTrue(input.manual().isEmpty());return input.files();});
        assertEquals("{\"scripts\":{\"dev\":\"vite\"}}",files.get("package.json"));assertFalse(files.containsKey(".env"));
        verify(coordinator,times(1)).withLock(eq(42L),any());assertNoPrivateInput();
    }
    @Test void externalEditGivesPlanCallbackNoCurrentInput()throws Exception{
        var service=setup();var previous=stored(service);Files.writeString(workspace.resolve("index.html"),"external edit");
        assertEquals("none",service.withPlanInput(user,42,previous.scanId(),input->input==null?"none":"current"));
        verify(inspections).stale(42,7,previous.scanId());assertNoPrivateInput();
    }
    @Test void planRevocationReportsReplacedTaskAndLostQualification()throws Exception{
        var service=setup();
        assertNull(service.planRevoked(user,42,task));assertEquals("IMPORT_TASK_CHANGED",service.planRevoked(user,42,UUID.randomUUID()));
        when(repository.publicationByApp(42L)).thenReturn(new ImportTaskRepository.Publication(task,42,7,"COPYING","origin",0,null));
        assertEquals("PERMISSION_CHANGED",service.planRevoked(user,42,task));
        when(repository.publicationByApp(42L)).thenThrow(new MyException(50000,"database unavailable"));
        assertThrows(MyException.class,()->service.planRevoked(user,42,task));verifyNoInteractions(coordinator);
    }
}
