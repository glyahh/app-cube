package com.dbts.glyahhaigeneratecode.projectimport;

import com.dbts.glyahhaigeneratecode.service.support.ProjectImportManifest.Options;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicReference;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

class ImportSourceServiceTest {
    @TempDir Path root;
    ImportSourceTask task(ImportSourceTask.Status status,List<ImportProcessRunner.Resource> resources) {
        return new ImportSourceTask(UUID.randomUUID(),12,new ImportSourceTask.Definition("工程",ImportRepositorySource.parse("GitHub","https://github.com/octocat/Hello-World"),new Options(false,false,false)),
                status,0,null,null,null,null,List.of(),resources,false,null,UUID.randomUUID(),123,Instant.now(),Instant.now().plusSeconds(600),null,"NONE");
    }
    ImportSourceTask copy(ImportSourceTask previous,ImportSourceTask.Status next,String error,String cleanup) {
        return new ImportSourceTask(previous.taskId(),previous.ownerId(),previous.definition(),next,previous.revision()+1,null,null,null,null,
                previous.branches(),previous.resources(),false,null,previous.executorId(),previous.executorPid(),previous.executorStart(),previous.expiresAt(),error,cleanup);
    }
    ImportSourceRepository repository(AtomicReference<ImportSourceTask> state) {
        var repository=mock(ImportSourceRepository.class);
        when(repository.find(anyLong(),any())).thenAnswer(call->{var task=state.get();return task.ownerId()==(long)call.getArgument(0) && task.taskId().equals(call.getArgument(1))?task:null;});
        when(repository.transition(any(),any(),nullable(String.class),anyString())).thenAnswer(call->{
            ImportSourceTask previous=call.getArgument(0);if(state.get().revision()!=previous.revision())return false;
            state.set(copy(previous,call.getArgument(1),call.getArgument(2),call.getArgument(3)));return true;
        });
        return repository;
    }
    ImportSourceService service(ImportSourceRepository repository) {
        var properties=new ImportProperties();properties.setStagingRoot(root);
        return new ImportSourceService(repository,properties,mock(ImportTaskService.class),mock(ImportPreferenceService.class),mock(ImportCredentialService.class));
    }
    @Test void credentialCancellationUsesOwnedSourceAndKeepsSuccessfulSource() {
        var state=new AtomicReference<>(task(ImportSourceTask.Status.SUCCEEDED,List.of()));
        var repository=repository(state);
        try(var service=service(repository)) {
            assertNull(service.cancelIfPresent(13,state.get().taskId()));
            assertEquals(ImportSourceTask.Status.SUCCEEDED,service.cancelIfPresent(12,state.get().taskId()).status());
            verify(repository,never()).transition(any(),any(),nullable(String.class),anyString());
        }
    }
    @Test void unresolvedCredentialChannelBlocksCleanupUntilActualStopIsConfirmed()throws Exception {
        var state=new AtomicReference<>(task(ImportSourceTask.Status.WAITING_BRANCH,List.of()));
        Path directory=Files.createDirectory(root.resolve(state.get().taskId()+".git"));
        var broker=mock(ImportCredentialBroker.class);
        try(var service=service(repository(state))) {
            @SuppressWarnings("unchecked") var brokers=(java.util.Map<UUID,ImportCredentialBroker>)org.springframework.test.util.ReflectionTestUtils.getField(service,"brokers");
            brokers.put(state.get().taskId(),broker);
            var unknown=service.cancel(12,state.get().taskId(),0);
            assertEquals(ImportSourceTask.Status.STOP_FAILED,unknown.status());assertTrue(Files.isDirectory(directory));
            when(broker.confirmedStopped()).thenReturn(true);
            var stopped=service.cancel(12,state.get().taskId(),Long.parseLong(unknown.revision()));
            assertEquals(ImportSourceTask.Status.CANCELLED,stopped.status());assertEquals("DONE",stopped.cleanup());
            assertFalse(Files.exists(directory));assertTrue(brokers.isEmpty());
        }
    }
    @Test void missingGitAndInvalidSourceFailBeforeJobOrNetwork() {
        var repository=mock(ImportSourceRepository.class);
        try(var service=service(repository)) {
            assertThrows(RuntimeException.class,()->service.preparePublic(12,"工程","GitHub","https://github.com/octocat/Hello-World",null));
            assertThrows(RuntimeException.class,()->service.preparePublic(12,"工程","GitHub","https://127.0.0.1/repo/x",null));
            verifyNoInteractions(repository);
        }
    }
    @Test void waitingSourceCancelConfirmsStopAndDeletesOnlyOwnedMetadata()throws Exception {
        var state=new AtomicReference<>(task(ImportSourceTask.Status.WAITING_BRANCH,List.of()));
        Path directory=Files.createDirectory(root.resolve(state.get().taskId()+".git"));Files.writeString(directory.resolve("metadata"),"fixture");
        Path unrelated=Files.writeString(root.resolve("keep"),"keep");
        try(var service=service(repository(state))) {
            var result=service.cancel(12,state.get().taskId(),0);
            assertEquals(ImportSourceTask.Status.CANCELLED,result.status());assertEquals("DONE",result.cleanup());
            assertFalse(Files.exists(directory));assertEquals("keep",Files.readString(unrelated));
        }
    }
    @Test void foreignOwnerAndStaleRevisionCannotCancel() {
        var state=new AtomicReference<>(task(ImportSourceTask.Status.WAITING_BRANCH,List.of()));var repository=repository(state);
        try(var service=service(repository)) {
            assertThrows(RuntimeException.class,()->service.cancel(13,state.get().taskId(),0));
            assertThrows(RuntimeException.class,()->service.cancel(12,state.get().taskId(),1));
            verify(repository,never()).transition(any(),any(),nullable(String.class),anyString());
        }
    }
    @Test void unknownBranchStaleRevisionAndRevisionExpressionsCannotStartFetch() {
        var state=new AtomicReference<>(task(ImportSourceTask.Status.WAITING_BRANCH,List.of()));var repository=repository(state);
        try(var service=service(repository)) {
            assertThrows(RuntimeException.class,()->service.selectBranch(12,state.get().taskId(),1,"master"));
            assertThrows(RuntimeException.class,()->service.selectBranch(12,state.get().taskId(),0,"unknown"));
            assertThrows(RuntimeException.class,()->service.selectBranch(12,state.get().taskId(),0,"master~1"));
            verify(repository,never()).select(any(),anyString(),anyString(),any(),anyLong(),any(),anyLong());
        }
    }
    @Test void knownLiveProcessDoesNotProduceCancelledOrCleanedResult()throws Exception {
        var process=ProcessHandle.current();var resource=new ImportProcessRunner.Resource(process.pid(),process.info().startInstant().orElseThrow());
        var state=new AtomicReference<>(task(ImportSourceTask.Status.WAITING_BRANCH,List.of(resource)));
        Path directory=Files.createDirectory(root.resolve(state.get().taskId()+".git"));Files.writeString(directory.resolve("keep"),"keep");
        try(var service=service(repository(state))) {
            var result=service.cancel(12,state.get().taskId(),0);
            assertEquals(ImportSourceTask.Status.STOP_FAILED,result.status());assertEquals("UNVERIFIED",result.cleanup());assertTrue(Files.exists(directory));
        }
    }
    @Test void restartLostContextRemainsUnconfirmedOnRepeatedCancel()throws Exception {
        var state=new AtomicReference<>(task(ImportSourceTask.Status.RESOLVING_SOURCE,List.of()));
        Path directory=Files.createDirectory(root.resolve(state.get().taskId()+".git"));
        try(var service=service(repository(state))) {
            assertEquals(ImportSourceTask.Status.RECOVERY_REQUIRED,service.get(12,state.get().taskId()).status());
            var result=service.cancel(12,state.get().taskId(),state.get().revision());
            assertEquals(ImportSourceTask.Status.STOP_FAILED,result.status());
            result=service.cancel(12,state.get().taskId(),state.get().revision());
            assertEquals(ImportSourceTask.Status.STOP_FAILED,result.status());assertEquals("SOURCE_EXECUTOR_UNCONFIRMED",result.error());assertTrue(Files.exists(directory));
        }
    }
    @Test void failedStopWithMissingResourceCannotBeDeclaredCleanOnRetry()throws Exception {
        var initial=task(ImportSourceTask.Status.STOP_FAILED,List.of());
        var state=new AtomicReference<>(copy(initial,ImportSourceTask.Status.STOP_FAILED,"STOP_FAILED","UNVERIFIED"));
        Path directory=Files.createDirectory(root.resolve(initial.taskId()+".git"));
        try(var service=service(repository(state))) {
            var result=service.cancel(12,initial.taskId(),state.get().revision());
            assertEquals(ImportSourceTask.Status.STOP_FAILED,result.status());assertEquals("UNVERIFIED",result.cleanup());assertTrue(Files.exists(directory));
        }
    }
    @Test void requestLookupReturnsCurrentTerminalStateWithoutNewAdmissionOrNetwork() {
        for(var status:List.of(ImportSourceTask.Status.CANCELLED,ImportSourceTask.Status.SUCCEEDED,ImportSourceTask.Status.WAITING_BRANCH)) {
            var state=new AtomicReference<>(task(status,List.of()));var repository=repository(state);UUID key=UUID.randomUUID();
            when(repository.findByRequest(12,key)).thenReturn(state.get());
            try(var service=service(repository)) {
                assertEquals(state.get().taskId(),service.getByRequest(12,key).taskId());
                assertEquals(status,service.getByRequest(12,key).status());
                assertThrows(RuntimeException.class,()->service.getByRequest(13,key));
                verify(repository,never()).insert(any());verify(repository,never()).insert(any(),any());
                verify(repository,never()).transition(any(),any(),nullable(String.class),anyString());
            }
        }
    }
    @Test void originalCreationRequestReplaysCurrentTaskAndRejectsChangedIdentity() {
        var state=new AtomicReference<>(task(ImportSourceTask.Status.CANCELLED,List.of()));var repository=repository(state);
        var tasks=mock(ImportTaskService.class);UUID key=UUID.randomUUID();
        when(tasks.sourceRequest(eq(12L),any(),any())).thenAnswer(call->((java.util.function.Supplier<?>)call.getArgument(1)).get());
        when(repository.findByRequest(12,key)).thenReturn(state.get());
        when(repository.requestMatches(eq(12L),eq(key),eq(state.get().definition()),isNull(),isNull())).thenReturn(true);
        try(var service=new ImportSourceService(repository,new ImportProperties(),tasks,mock(ImportPreferenceService.class),mock(ImportCredentialService.class))) {
            var result=service.prepare(12,"工程","GitHub","https://github.com/octocat/Hello-World",new Options(false,false,false),null,null,key);
            assertEquals(state.get().taskId(),result.taskId());assertEquals(ImportSourceTask.Status.CANCELLED,result.status());
            assertThrows(RuntimeException.class,()->service.prepare(12,"改名","GitHub","https://github.com/octocat/Hello-World",new Options(false,false,false),null,null,key));
            assertThrows(RuntimeException.class,()->service.prepare(12,"工程","GitHub","https://github.com/octocat/Hello-World",new Options(false,false,false),UUID.randomUUID(),1L,key));
            verify(repository,never()).insert(any(),any());verify(tasks,never()).admitSource(anyLong(),any());
        }
    }
    @Test void malformedReplayCannotAccessStoredRequest() {
        var repository=mock(ImportSourceRepository.class);
        try(var service=service(repository)) {
            assertThrows(RuntimeException.class,()->service.getByRequest(0,UUID.randomUUID()));
            assertThrows(RuntimeException.class,()->service.getByRequest(12,null));
            assertThrows(RuntimeException.class,()->service.prepare(12,"工程","GitHub","https://github.com/octocat/Hello-World",new Options(false,false,false),UUID.randomUUID(),null,UUID.randomUUID()));
            verifyNoInteractions(repository);
        }
    }
    @Test void ownedReadOnlyGitObjectsAreDeletedAfterConfirmedStop()throws Exception {
        var state=new AtomicReference<>(task(ImportSourceTask.Status.WAITING_BRANCH,List.of()));
        Path directory=Files.createDirectory(root.resolve(state.get().taskId()+".git"));
        Path object=Files.writeString(directory.resolve("object"),"git object");
        var dos=Files.getFileAttributeView(object,java.nio.file.attribute.DosFileAttributeView.class);
        if(dos!=null)dos.setReadOnly(true);
        Path unrelated=Files.writeString(root.resolve("keep"),"keep");
        try(var service=service(repository(state))) {
            var stopped=service.cancel(12,state.get().taskId(),0);
            assertEquals(ImportSourceTask.Status.CANCELLED,stopped.status());assertEquals("DONE",stopped.cleanup());
            assertFalse(Files.exists(directory));assertEquals("keep",Files.readString(unrelated));
        }
    }
    @Test void cancelledButFailedCleanupCanBeRetriedWithoutRestartingRead()throws Exception {
        var original=task(ImportSourceTask.Status.CANCELLED,List.of());
        var state=new AtomicReference<>(copy(original,ImportSourceTask.Status.CANCELLED,"SOURCE_CLEANUP_FAILED","FAILED"));
        Path directory=Files.createDirectory(root.resolve(state.get().taskId()+".git"));Files.writeString(directory.resolve("remaining"),"remaining");
        var repository=repository(state);
        try(var service=service(repository)) {
            var stopped=service.cancelIfPresent(12,state.get().taskId());
            assertEquals(ImportSourceTask.Status.CANCELLED,stopped.status());assertEquals("DONE",stopped.cleanup());
            assertFalse(Files.exists(directory));verify(repository,never()).insert(any());
        }
    }
}
