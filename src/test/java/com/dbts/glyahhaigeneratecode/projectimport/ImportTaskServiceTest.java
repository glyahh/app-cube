package com.dbts.glyahhaigeneratecode.projectimport;

import com.dbts.glyahhaigeneratecode.config.WorkspaceProperties;
import com.dbts.glyahhaigeneratecode.exception.MyException;
import com.dbts.glyahhaigeneratecode.service.support.ProjectImportStaging.StoredFile;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.FutureTask;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

import static com.dbts.glyahhaigeneratecode.service.support.ProjectImportManifest.*;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

class ImportTaskServiceTest {
    @TempDir Path temp;
    ImportTaskRepository repository;
    ImportProperties properties;
    WorkspaceProperties workspace;
    AtomicReference<ImportTask> task = new AtomicReference<>();

    @BeforeEach
    void setup() {
        repository = mock(ImportTaskRepository.class);
        properties = new ImportProperties();
        properties.setStagingRoot(temp.resolve("private"));
        properties.setStopTimeout(Duration.ofMillis(100));
        workspace = new WorkspaceProperties();
        workspace.setOutputRoot(temp.resolve("output"));
        workspace.setDeployRoot(temp.resolve("deploy"));
        doAnswer(call -> { task.set(call.getArgument(0)); return null; }).when(repository).insert(any());
        when(repository.find(any(), anyLong())).thenAnswer(call -> {
            var current = task.get();
            return current != null && current.taskId().equals(call.getArgument(0)) && current.ownerId() == (long) call.getArgument(1) ? current : null;
        });
        when(repository.transition(any(), any(), anyInt(), anyList(), nullable(String.class), nullable(String.class), anyString(), nullable(String.class)))
                .thenAnswer(call -> {
                    ImportTask previous = call.getArgument(0);
                    if (task.get().revision() != previous.revision() || task.get().status() != previous.status()) return null;
                    var updated = new ImportTask(previous.taskId(), previous.ownerId(), previous.definition(), call.getArgument(1),
                            previous.revision() + 1, call.getArgument(2), call.<List<StoredFile>>getArgument(3),
                            call.getArgument(4), call.getArgument(5), call.getArgument(6), call.getArgument(7));
                    task.set(updated);
                    return updated;
                });
    }

    ImportPreferenceService preferences = mock(ImportPreferenceService.class);
    ImportTaskService service() throws IOException { return new ImportTaskService(repository, properties, workspace, preferences); }

    @Test void rebuildingReleasesOnlyOldTaskBytesAndAdvancesMainVersion() throws Exception {
        try(var service=service()) {
            var created=service.create(12,"工程",List.of(new FileEntry("src/a",1)),Options.defaults());
            var ready=service.upload(12,created.taskId(),0,0,new ByteArrayInputStream(new byte[]{1}));
            var source=new ImportSourceTask(created.taskId(),12,new ImportSourceTask.Definition("新名称",ImportRepositorySource.parse("GitHub","https://github.com/octocat/Hello-World"),Options.defaults()),
                    ImportSourceTask.Status.READING_CONTENT,4,null,null,"main","a".repeat(40),List.of(),List.of(),true,null,UUID.randomUUID(),1,java.time.Instant.now(),java.time.Instant.now().plusSeconds(600),null,"NONE");
            var snapshot=new ImportGitReader.Snapshot("main","a".repeat(40),List.of(new ImportGitMetadata.Blob("src/a","b".repeat(40),1,false)));
            Path unrelated=Files.writeString(temp.resolve("keep"),"keep");
            when(repository.resetFromSource(any(),any(),eq(source),eq(snapshot))).thenAnswer(call->{
                ImportTask previous=call.getArgument(0);
                var updated=new ImportTask(previous.taskId(),12,call.getArgument(1),ImportTask.Status.READING_CONTENT,previous.revision()+1,0,List.of(),null,null,"NONE",null);
                task.set(updated);return updated;
            });
            var rebuilt=service.rebuildFromSource(source,snapshot);
            assertTrue(rebuilt.revision()>ready.revision());assertEquals("新名称",rebuilt.appName());assertNull(rebuilt.contentHash());
            assertFalse(Files.exists(properties.getStagingRoot().resolve(created.taskId()+"/0.bin")));
            assertEquals("keep",Files.readString(unrelated));
            assertThrows(MyException.class,()->service.upload(12,created.taskId(),ready.revision(),0,new ByteArrayInputStream(new byte[]{1})));
        }
    }

    @Test void invalidOrEmptyRangeIsRejectedBeforeDeletingExistingBytes() throws Exception {
        try(var service=service()) {
            var created=service.create(12,"工程",List.of(new FileEntry("dist/a",1)),Options.defaults());
            service.upload(12,created.taskId(),0,0,new ByteArrayInputStream(new byte[]{1}));
            assertThrows(MyException.class,()->service.checkRebuild(12,created.taskId(),new Options(false,true,false)));
            assertThrows(MyException.class,()->service.checkRebuild(13,created.taskId(),Options.defaults()));
            assertTrue(Files.isRegularFile(properties.getStagingRoot().resolve(created.taskId()+"/0.bin")));
            verify(repository,never()).resetFromSource(any(),any(),any(),any());
        }
    }

    @Test void gitMainTaskCannotClaimFullCancellationBeforeSourceStops() throws Exception {
        try(var service=service()) {
            var created=service.create(12,"工程",List.of(new FileEntry("a",1)),Options.defaults());
            when(repository.isGit(created.taskId(),12)).thenReturn(true);
            var error=assertThrows(MyException.class,()->service.cancel(12,created.taskId(),created.revision()));
            assertTrue(error.getMessage().contains("来源任务取消"));
            assertEquals(ImportTask.Status.READING_CONTENT,task.get().status());
        }
    }

    @Test
    void defaultsReadSavedPreferenceOnlyAtCreationAndExplicitOptionsNeverSaveIt() throws Exception {
        when(preferences.get(12)).thenReturn(new Options(true, false, false));
        try (var service = service()) {
            var entries = List.of(new FileEntry("node_modules/a", 1), new FileEntry("src/a", 1));
            var inherited = service.create(12, "x", entries, null);
            assertEquals(1, inherited.manifest().included().size());
            when(preferences.get(12)).thenReturn(Options.defaults());
            assertEquals(inherited.manifest(), service.get(12, inherited.taskId()).manifest());
            var explicit = service.create(12, "y", entries, Options.defaults());
            assertEquals(2, explicit.manifest().included().size());
            verify(preferences, times(1)).get(12);
            verifyNoMoreInteractions(preferences);
        }
    }

    @Test
    void frozenSourcePersistsAndActualFilesReachWaitingConfirmationNotSuccess() throws Exception {
        try (var service = service()) {
            var created = service.create(12, "工程", List.of(new FileEntry("src/a", 1), new FileEntry(".env", 2), new FileEntry("dist/b", 0)), Options.defaults());
            assertEquals(ImportTask.Status.READING_CONTENT, created.status());
            assertEquals(1, created.manifest().excluded().size());
            var first = service.upload(12, created.taskId(), created.revision(), 0, new ByteArrayInputStream(new byte[]{1}));
            assertEquals(1, first.nextFile());
            var last = service.upload(12, created.taskId(), first.revision(), 1, InputStream.nullInputStream());
            assertEquals(ImportTask.Status.WAITING_CONFIRMATION, last.status());
            assertEquals(64, last.contentHash().length());
            assertEquals(last, service.get(12, last.taskId()));
            assertFalse(Files.exists(temp.resolve("output")));
            assertEquals(12, task.get().ownerId());
        }
    }

    @Test
    void rejectsForeignOwnerStaleRevisionWrongOrderAndRepeatedUpload() throws Exception {
        try (var service = service()) {
            var created = service.create(12, "x", List.of(new FileEntry("a", 1)), Options.defaults());
            assertThrows(MyException.class, () -> service.get(13, created.taskId()));
            assertThrows(MyException.class, () -> service.cancel(13, created.taskId(), 0));
            assertThrows(MyException.class, () -> service.upload(13, created.taskId(), 0, 0, InputStream.nullInputStream()));
            assertThrows(MyException.class, () -> service.upload(12, created.taskId(), 10, 0, InputStream.nullInputStream()));
            assertThrows(MyException.class, () -> service.upload(12, created.taskId(), 0, 1, InputStream.nullInputStream()));
            var ready = service.upload(12, created.taskId(), 0, 0, new ByteArrayInputStream(new byte[]{1}));
            assertThrows(MyException.class, () -> service.upload(12, created.taskId(), ready.revision(), 0, new ByteArrayInputStream(new byte[]{2})));
            assertArrayEquals(new byte[]{1}, Files.readAllBytes(temp.resolve("private/" + created.taskId() + "/0.bin")));
        }
    }

    @Test
    void truncatedUploadFailsAndCleansOnlyThisTask() throws Exception {
        try (var service = service()) {
            var created = service.create(12, "x", List.of(new FileEntry("a", 1)), Options.defaults());
            var unrelated = UUID.randomUUID();
            Files.createDirectory(temp.resolve("private/" + unrelated));
            assertThrows(MyException.class, () -> service.upload(12, created.taskId(), 0, 0, InputStream.nullInputStream()));
            assertEquals(ImportTask.Status.FAILED, task.get().status());
            assertEquals("DONE", task.get().cleanup());
            assertFalse(Files.exists(temp.resolve("private/" + created.taskId())));
            assertTrue(Files.isDirectory(temp.resolve("private/" + unrelated)));
        }
    }

    @Test
    void cancellingFinishedUploadRevokesTaskAndDeletesOnlyPrivateFiles() throws Exception {
        try (var service = service()) {
            var created = service.create(12, "x", List.of(new FileEntry("a", 1)), Options.defaults());
            var ready = service.upload(12, created.taskId(), 0, 0, new ByteArrayInputStream(new byte[]{1}));
            var cancelled = service.cancel(12, ready.taskId(), ready.revision());
            assertEquals(ImportTask.Status.CANCELLED, cancelled.status());
            assertEquals(cancelled, service.cancel(12, ready.taskId(), ready.revision()));
            assertThrows(MyException.class, () -> service.upload(12, ready.taskId(), cancelled.revision(), 0, InputStream.nullInputStream()));
        }
    }

    @Test
    void invalidInputAndResourceQuotaCreateNoDirectoryOrTask() throws Exception {
        try (var service = service()) {
            for (String name : List.of("", " ", "x".repeat(81), "x\ny")) {
                assertThrows(MyException.class, () -> service.create(12, name, List.of(new FileEntry("a", 1)), Options.defaults()));
            }
            assertThrows(MyException.class, () -> service.create(0, "x", List.of(new FileEntry("a", 1)), Options.defaults()));
            assertThrows(MyException.class, () -> service.create(12, "x", List.of(new FileEntry("../a", 1)), Options.defaults()));
            when(repository.pending(12L)).thenReturn(2);
            assertThrows(MyException.class, () -> service.create(12, "x", List.of(new FileEntry("a", 1)), Options.defaults()));
            verify(repository, never()).insert(any());
        }
    }

    @Test
    void cancellationClosesActualBlockedStreamAndLateCompletionCannotSucceed() throws Exception {
        try (var service = service()) {
            var created = service.create(12, "x", List.of(new FileEntry("a", 1)), Options.defaults());
            CountDownLatch entered = new CountDownLatch(1);
            CountDownLatch closed = new CountDownLatch(1);
            InputStream input = new InputStream() {
                @Override public int read() throws IOException {
                    entered.countDown();
                    try { closed.await(5, TimeUnit.SECONDS); }
                    catch (InterruptedException e) { Thread.currentThread().interrupt(); }
                    throw new IOException("cancelled");
                }
                @Override public void close() { closed.countDown(); }
            };
            var future = new FutureTask<>(() -> service.upload(12, created.taskId(), 0, 0, input));
            Thread thread = new Thread(future);
            thread.start();
            assertTrue(entered.await(3, TimeUnit.SECONDS));
            var uploading = service.get(12, created.taskId());
            var cancelled = service.cancel(12, created.taskId(), uploading.revision());
            assertEquals(ImportTask.Status.CANCELLED, cancelled.status());
            assertThrows(java.util.concurrent.ExecutionException.class, () -> future.get(3, TimeUnit.SECONDS));
            assertEquals(ImportTask.Status.CANCELLED, task.get().status());
            assertFalse(Files.exists(temp.resolve("private/" + created.taskId())));
        }
    }

    @Test
    void timeoutClosesBlockedInputAndNeverBecomesReady() throws Exception {
        properties.setUploadTimeout(Duration.ofMillis(50));
        try (var service = service()) {
            var created = service.create(12, "x", List.of(new FileEntry("a", 1)), Options.defaults());
            CountDownLatch closed = new CountDownLatch(1);
            InputStream input = new InputStream() {
                @Override public int read() throws IOException {
                    try { closed.await(3, TimeUnit.SECONDS); } catch (InterruptedException e) { Thread.currentThread().interrupt(); }
                    throw new IOException("timeout");
                }
                @Override public void close() { closed.countDown(); }
            };
            assertThrows(MyException.class, () -> service.upload(12, created.taskId(), 0, 0, input));
            assertEquals(ImportTask.Status.FAILED, task.get().status());
            assertEquals("UPLOAD_TIMEOUT", task.get().error());
            assertEquals(0, closed.getCount());
        }
    }

    @Test
    void unresponsiveInputStaysStopFailedUntilActualExitAndRetryCleanup() throws Exception {
        try (var service = service()) {
            var created = service.create(12, "x", List.of(new FileEntry("a", 1)), Options.defaults());
            CountDownLatch entered = new CountDownLatch(1);
            CountDownLatch release = new CountDownLatch(1);
            InputStream input = new InputStream() {
                @Override public int read() throws IOException {
                    entered.countDown();
                    while (release.getCount() != 0) {
                        try { release.await(); } catch (InterruptedException ignored) { }
                    }
                    throw new IOException("exit");
                }
                @Override public void close() { }
            };
            var future = new FutureTask<>(() -> service.upload(12, created.taskId(), 0, 0, input));
            new Thread(future).start();
            try {
                assertTrue(entered.await(3, TimeUnit.SECONDS));
                var uploading = service.get(12, created.taskId());
                var stopped = service.cancel(12, created.taskId(), uploading.revision());
                assertEquals(ImportTask.Status.STOP_FAILED, stopped.status());
                assertEquals("UNVERIFIED", stopped.cleanup());
                assertTrue(Files.exists(temp.resolve("private/" + created.taskId())));
                release.countDown();
                assertThrows(java.util.concurrent.ExecutionException.class, () -> future.get(3, TimeUnit.SECONDS));
                assertEquals(ImportTask.Status.CANCELLED, service.cancel(12, created.taskId(), stopped.revision()).status());
            } finally { release.countDown(); }
        }
    }

    @Test
    void foreignOrPriorExecutorCannotBeClaimedStoppedAfterRestart() throws Exception {
        try (var service = service()) {
            var created = service.create(12, "x", List.of(new FileEntry("a", 1)), Options.defaults());
            ImportTask original = task.get();
            task.set(new ImportTask(original.taskId(), 12, original.definition(), ImportTask.Status.UPLOADING, 1, 0,
                    List.of(), null, null, "NONE", "prior-process"));
            var recovery = service.get(12, created.taskId());
            assertEquals(ImportTask.Status.RECOVERY_REQUIRED, recovery.status());
            assertEquals(ImportTask.Status.STOP_FAILED, service.cancel(12, created.taskId(), recovery.revision()).status());
            assertTrue(Files.exists(temp.resolve("private/" + created.taskId())));
        }
    }

    @Test
    void wrongFrozenHashNeverReservesApplication() throws Exception {
        try (var service = service()) {
            var created = service.create(12, "x", List.of(new FileEntry("a", 1)), Options.defaults());
            var ready = service.upload(12, created.taskId(), 0, 0, new ByteArrayInputStream(new byte[]{1}));
            assertThrows(MyException.class, () -> service.commit(12, ready.taskId(), ready.revision(), "b".repeat(64)));
            assertEquals(ImportTask.Status.WAITING_CONFIRMATION, task.get().status());
            verify(repository, never()).reservePublication(any(), anyLong(), anyString(), anyLong(), any());
        }
    }

    private AtomicReference<ImportTaskRepository.Publication> publicationFixture() {
        var publication = new AtomicReference<ImportTaskRepository.Publication>();
        when(repository.publicationByTask(any(), anyLong())).thenAnswer(call -> publication.get());
        when(repository.publicationByApp(anyLong())).thenAnswer(call -> publication.get());
        when(repository.reservePublication(any(), anyLong(), anyString(), anyLong(), any())).thenAnswer(call -> {
            ImportTask before = call.getArgument(0);
            publication.set(new ImportTaskRepository.Publication(before.taskId(), call.getArgument(1), before.ownerId(), "PUBLISHING", before.contentHash(), call.getArgument(3), call.getArgument(4)));
            return repository.transition(before, ImportTask.Status.COMMITTING, before.nextFile(), before.receipts(), before.contentHash(), null, "NONE", call.getArgument(2));
        });
        doAnswer(call -> {
            var before = publication.get();
            publication.set(new ImportTaskRepository.Publication(before.taskId(), before.appId(), before.ownerId(), "FILES_READY", before.contentHash(), before.executorPid(), before.executorStart()));
            return null;
        }).when(repository).filesReady(any());
        when(repository.finishPublication(any())).thenAnswer(call -> {
            ImportTask before = call.getArgument(0);
            var row = publication.get();
            publication.set(new ImportTaskRepository.Publication(row.taskId(), row.appId(), row.ownerId(), "READY", row.contentHash(), row.executorPid(), row.executorStart()));
            return repository.transition(before, ImportTask.Status.SUCCEEDED, before.nextFile(), before.receipts(), before.contentHash(), null, "NONE", null);
        });
        when(repository.reclaimPublication(any(), anyString(), anyLong(), any())).thenAnswer(call -> {
            ImportTask before = call.getArgument(0);
            return repository.transition(before, ImportTask.Status.COMMITTING, before.nextFile(), before.receipts(), before.contentHash(), null, "NONE", call.getArgument(1));
        });
        return publication;
    }

    @Test void successfulPublicationPreservesOriginalAndRetryNeverCreatesAnotherApplication() throws Exception {
        var publication = publicationFixture();
        try (var service = service()) {
            var created = service.create(12, "工程", List.of(new FileEntry("src/a", 1)), Options.defaults());
            var ready = service.upload(12, created.taskId(), 0, 0, new ByteArrayInputStream(new byte[]{1}));
            var result = service.commit(12, ready.taskId(), ready.revision(), ready.contentHash());
            assertEquals(ImportTask.Status.SUCCEEDED, task.get().status());
            assertArrayEquals(new byte[]{1}, Files.readAllBytes(temp.resolve("output/project_" + result.appId() + "/src/a")));
            assertArrayEquals(new byte[]{1}, Files.readAllBytes(temp.resolve("private/" + result.taskId() + "/0.bin")));
            assertEquals("READY", publication.get().state());
            assertEquals(result, service.commit(12, ready.taskId(), ready.revision(), ready.contentHash()));
            assertThrows(MyException.class, () -> service.cancel(12, ready.taskId(), task.get().revision()));
            verify(repository, times(1)).reservePublication(any(), anyLong(), anyString(), anyLong(), any());
            var row = publication.get();
            doReturn(new ImportTaskRepository.Publication(row.taskId(), row.appId(), row.ownerId(), "UNAVAILABLE", row.contentHash(), row.executorPid(), row.executorStart()))
                    .when(repository).publicationByApp(row.appId());
            assertThrows(MyException.class, () -> service.result(12, ready.taskId()));
            assertThrows(MyException.class, () -> service.commit(12, ready.taskId(), ready.revision(), ready.contentHash()));
        }
    }

    @Test void databaseFailureKeepsFilesUnreadableAndExplicitRecoveryReusesSameApplicationId() throws Exception {
        var publication = publicationFixture();
        var gate = new ImportWorkspaceGate(repository);
        when(repository.publicationByApp(anyLong())).thenAnswer(call -> publication.get());
        doThrow(new IllegalStateException("database unavailable")).when(repository).finishPublication(any());
        try (var service = service()) {
            var created = service.create(12, "工程", List.of(new FileEntry("a", 1)), Options.defaults());
            var ready = service.upload(12, created.taskId(), 0, 0, new ByteArrayInputStream(new byte[]{1}));
            assertThrows(MyException.class, () -> service.commit(12, ready.taskId(), ready.revision(), ready.contentHash()));
            long id = publication.get().appId();
            assertEquals(ImportTask.Status.RECOVERY_REQUIRED, task.get().status());
            assertThrows(MyException.class, () -> gate.requireReadable("project_" + id));
            assertTrue(Files.exists(temp.resolve("output/project_" + id + "/a")));
            doAnswer(call -> {
                ImportTask before = call.getArgument(0);
                var row = publication.get();
                publication.set(new ImportTaskRepository.Publication(row.taskId(), row.appId(), row.ownerId(), "READY", row.contentHash(), row.executorPid(), row.executorStart()));
                return repository.transition(before, ImportTask.Status.SUCCEEDED, before.nextFile(), before.receipts(), before.contentHash(), null, "NONE", null);
            }).when(repository).finishPublication(any());
            var recovered = service.recover(12, created.taskId(), task.get().revision(), task.get().contentHash());
            assertEquals(Long.toString(id), recovered.appId());
            assertNotNull(gate.requireReadable("project_" + id));
            verify(repository, times(1)).reservePublication(any(), anyLong(), anyString(), anyLong(), any());
        }
    }
    @Test void requestReplayBypassesNewQuotaAndCreation()throws Exception {
        when(repository.pending(any())).thenReturn(Integer.MAX_VALUE);
        try(var service=service()) {
            assertEquals("old",service.sourceRequest(12,()->"old",()->{fail("不能重复创建");return "new";}));
            assertThrows(MyException.class,()->service.sourceRequest(12,()->null,()->"new"));
        }
    }
    @Test void concurrentSameRequestCreatesOnlyOnceUnderAdmissionLock()throws Exception {
        try(var service=service()) {
            var existing=new AtomicReference<String>();var calls=new java.util.concurrent.atomic.AtomicInteger();
            var pool=java.util.concurrent.Executors.newFixedThreadPool(2);
            try {
                java.util.concurrent.Callable<String> request=()->service.sourceRequest(12,existing::get,()->{
                    calls.incrementAndGet();existing.set("original");return existing.get();
                });
                var one=pool.submit(request);var two=pool.submit(request);
                assertEquals("original",one.get(5,TimeUnit.SECONDS));assertEquals("original",two.get(5,TimeUnit.SECONDS));
                assertEquals(1,calls.get());
            }finally{pool.shutdownNow();}
        }
    }
}
