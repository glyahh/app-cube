package com.dbts.glyahhaigeneratecode.projectimport;

import com.dbts.glyahhaigeneratecode.service.support.ProjectImportManifest.Options;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

class ImportSourceRepositoryTest {
    final JdbcTemplate jdbc=mock(JdbcTemplate.class);
    final ImportSourceRepository repository=new ImportSourceRepository(jdbc,new ObjectMapper().findAndRegisterModules());
    ImportSourceTask task(){return task(ImportSourceTask.Status.RESOLVING_SOURCE);}
    ImportSourceTask task(ImportSourceTask.Status status){return new ImportSourceTask(UUID.randomUUID(),12,
            new ImportSourceTask.Definition("工程",ImportRepositorySource.parse("GitHub","https://github.com/octocat/Hello-World"),new Options(false,false,false)),
            status,0,null,null,null,null,List.of(),List.of(),false,null,
            UUID.randomUUID(),123,Instant.parse("2026-10-04T00:00:00Z"),Instant.parse("2026-10-04T00:15:00Z"),null,"NONE");}
    @Test void expiryIncludesLinkedReadingAndConfirmationInsteadOfOnlyPreparatoryTasks() {
        when(jdbc.query(anyString(),any(org.springframework.jdbc.core.RowMapper.class))).thenReturn(List.of());
        assertTrue(repository.expired().isEmpty());
        var sql=org.mockito.ArgumentCaptor.forClass(String.class);
        verify(jdbc).query(sql.capture(),any(org.springframework.jdbc.core.RowMapper.class));
        assertTrue(sql.getValue().contains("expires_at<=now()"));
        assertTrue(sql.getValue().contains("'WAITING_CONFIRMATION'"));
        assertFalse(sql.getValue().contains("main_task_created=false"));
        assertFalse(sql.getValue().contains("'SUCCEEDED'"));
    }
    @Test void preparationInsertDoesNotInventFilesOrCreateApp() {
        repository.insert(task());
        var sql=org.mockito.ArgumentCaptor.forClass(String.class);
        verify(jdbc).update(sql.capture(),any(Object[].class));
        assertTrue(sql.getValue().contains("biz.project_import_source"));
        assertFalse(sql.getValue().contains("biz.app"));assertFalse(sql.getValue().contains("biz.project_import_task"));
        verifyNoMoreInteractions(jdbc);
    }
    @Test void processResourceRequiresStartIdentityAndConditionalOwnerRevisionExecutor() {
        var task=task();
        assertThrows(IllegalArgumentException.class,()->repository.resource(task,new ImportProcessRunner.Resource(1,null)));
        verifyNoInteractions(jdbc);
        when(jdbc.update(anyString(),any(Object[].class))).thenReturn(1);
        repository.resource(task,new ImportProcessRunner.Resource(123,Instant.parse("2026-10-04T00:00:00Z")));
        var sql=org.mockito.ArgumentCaptor.forClass(String.class);
        verify(jdbc).update(sql.capture(),any(Object[].class));
        assertTrue(sql.getValue().contains("owner_id=? AND revision=? AND status=? AND executor_id=?"));
        assertFalse(sql.getValue().contains("revision=revision+1"));
    }
    @Test void resourceJournalFailureCannotBeIgnored() {
        when(jdbc.update(anyString(),any(Object[].class))).thenReturn(0);
        assertThrows(RuntimeException.class,()->repository.resource(task(),new ImportProcessRunner.Resource(123,Instant.now())));
    }
    @Test void emptyBranchOutputDoesNotPretendSourceIsReady() {
        assertThrows(IllegalArgumentException.class,()->repository.branchesReady(task(),List.of()));
        verifyNoInteractions(jdbc);
    }
    @Test void branchCompletionUsesConditionalRevisionAndExpiryAndRejectsLateResults() {
        when(jdbc.update(anyString(),any(Object[].class))).thenReturn(0);
        when(jdbc.queryForObject(anyString(),eq(Integer.class),any(Object[].class))).thenReturn(1);
        assertThrows(RuntimeException.class,()->repository.branchesReady(task(),List.of(new ImportGitMetadata.Branch("master","a".repeat(40)))));
        var sql=org.mockito.ArgumentCaptor.forClass(String.class);
        verify(jdbc).update(sql.capture(),any(Object[].class));
        assertTrue(sql.getValue().contains("revision=? AND status='RESOLVING_SOURCE' AND executor_id=? AND expires_at>now()"));
    }
    @Test void revokedOrExpiredQualificationCannotPublishBranches() {
        when(jdbc.queryForObject(anyString(),eq(Integer.class),any(Object[].class))).thenReturn(0);
        assertThrows(RuntimeException.class,()->repository.branchesReady(task(),List.of(new ImportGitMetadata.Branch("master","a".repeat(40)))));
        verify(jdbc,never()).update(anyString(),any(Object[].class));
        var sql=org.mockito.ArgumentCaptor.forClass(String.class);
        verify(jdbc).queryForObject(sql.capture(),eq(Integer.class),any(Object[].class));
        assertTrue(sql.getValue().contains("c.current_version=s.credential_version"));
        assertTrue(sql.getValue().contains("s.expires_at>now()"));
    }
    @Test void cancellationAndLateFailureAreConditionalRatherThanUnqualifiedOverwrite() {
        when(jdbc.update(anyString(),any(Object[].class))).thenReturn(0);
        assertFalse(repository.transition(task(),ImportSourceTask.Status.FAILED,"SOURCE_READ_FAILED","UNVERIFIED"));
        var sql=org.mockito.ArgumentCaptor.forClass(String.class);
        verify(jdbc).update(sql.capture(),any(Object[].class));
        assertTrue(sql.getValue().contains("WHERE task_id=? AND owner_id=? AND revision=? AND status=? AND executor_id=?"));
    }
    @Test void publicViewDoesNotExposeExecutorOrProcessResourceLedger()throws Exception {
        String encoded=new ObjectMapper().findAndRegisterModules().writeValueAsString(task().view());
        assertFalse(encoded.contains("executor"));assertFalse(encoded.contains("resources"));assertFalse(encoded.contains("ownerId"));
        assertTrue(encoded.contains("\"revision\":\"0\""));
    }
    @Test void contentCompletionQualifiesBothRowsAndDisambiguatesRevision() {
        when(jdbc.update(anyString(),any(Object[].class))).thenReturn(1);
        repository.contentReady(task(ImportSourceTask.Status.READING_CONTENT),"a".repeat(64));
        var sql=org.mockito.ArgumentCaptor.forClass(String.class);
        verify(jdbc).update(sql.capture(),any(Object[].class));
        assertTrue(sql.getValue().contains("revision=s.revision+1"));
        assertTrue(sql.getValue().contains("t.owner_id=s.owner_id AND t.status='WAITING_CONFIRMATION' AND t.content_hash=?"));
        assertTrue(sql.getValue().contains("s.main_task_created=true"));
    }
    ImportSourceTask credentialTask(ImportSourceTask.Status status) {
        var source=task(status);
        return new ImportSourceTask(source.taskId(),source.ownerId(),source.definition(),source.status(),source.revision(),UUID.randomUUID(),1L,
                source.branch(),source.commit(),source.branches(),source.resources(),source.mainTaskCreated(),source.contentHash(),source.executorId(),source.executorPid(),
                source.executorStart(),source.expiresAt(),source.error(),source.cleanup());
    }
    @Test void credentialPreparationLocksExactOwnerAndCurrentSemanticVersionBeforeInsert() {
        var source=credentialTask(ImportSourceTask.Status.RESOLVING_SOURCE);
        when(jdbc.queryForList(anyString(),any(Object[].class))).thenReturn(List.of(java.util.Map.of("provider","GitHub","current_version",1L,"state","ACTIVE")));
        when(jdbc.queryForObject(anyString(),eq(Integer.class),any(Object[].class))).thenReturn(1);
        repository.insert(source);
        var order=inOrder(jdbc);
        order.verify(jdbc).queryForList(contains("FOR UPDATE"),eq(source.credentialId()),eq(12L));
        order.verify(jdbc).queryForObject(contains("state='ACTIVE'"),eq(Integer.class),eq(source.credentialId()),eq(1L));
        order.verify(jdbc).update(contains("INSERT INTO biz.project_import_source"),any(Object[].class));
    }
    @Test void wrongPlatformRevokedAndStaleCredentialCannotCreateSourceOrRunNetwork() {
        var source=credentialTask(ImportSourceTask.Status.RESOLVING_SOURCE);
        for(var row:List.<java.util.Map<String,Object>>of(java.util.Map.of("provider","Gitee","current_version",1L,"state","ACTIVE"),
                java.util.Map.of("provider","GitHub","current_version",2L,"state","ACTIVE"),
                java.util.Map.of("provider","GitHub","current_version",1L,"state","DELETED"))) {
            when(jdbc.queryForList(anyString(),any(Object[].class))).thenReturn(List.of(row));
            assertThrows(RuntimeException.class,()->repository.insert(source));
        }
        when(jdbc.queryForList(anyString(),any(Object[].class))).thenReturn(List.of());
        assertThrows(RuntimeException.class,()->repository.insert(source));
        verify(jdbc,never()).update(anyString(),any(Object[].class));
    }
    @Test void credentialRevocationBlocksBothContentReadAndConfirmationPublication() {
        when(jdbc.queryForList(anyString(),any(Object[].class))).thenReturn(List.of(java.util.Map.of("credential_id",UUID.randomUUID())));
        when(jdbc.queryForObject(anyString(),eq(Integer.class),any(Object[].class))).thenReturn(0);
        assertThrows(RuntimeException.class,()->repository.select(credentialTask(ImportSourceTask.Status.WAITING_BRANCH),"main","a".repeat(40),UUID.randomUUID(),1,Instant.now(),1024));
        assertThrows(RuntimeException.class,()->repository.contentReady(credentialTask(ImportSourceTask.Status.READING_CONTENT),"a".repeat(64)));
        verify(jdbc,never()).update(anyString(),any(Object[].class));
    }
    @Test void rangeClaimRevokesOldConfirmationAndNeverChangesTheFixedCommit() {
        var original=task(ImportSourceTask.Status.WAITING_CONFIRMATION);
        var source=new ImportSourceTask(original.taskId(),12,original.definition(),original.status(),4,null,null,"main","a".repeat(40),List.of(),List.of(),true,
                "a".repeat(64),original.executorId(),123,Instant.now(),Instant.now().plusSeconds(600),null,"NONE");
        when(jdbc.queryForObject(anyString(),eq(Integer.class),any(Object[].class))).thenReturn(1);
        when(jdbc.update(anyString(),any(Object[].class))).thenReturn(1);
        repository.rebuild(source,new ImportSourceTask.Definition("新名称",source.definition().source(),new Options(true,false,false)),UUID.randomUUID(),123,Instant.now());
        var sql=org.mockito.ArgumentCaptor.forClass(String.class);
        verify(jdbc).update(sql.capture(),any(Object[].class));
        assertTrue(sql.getValue().contains("content_hash=NULL"));assertTrue(sql.getValue().contains("revision=s.revision+1"));
        assertTrue(sql.getValue().contains("NOT EXISTS(SELECT 1 FROM biz.project_import_publication"));
        assertFalse(sql.getValue().contains("commit_id="));assertFalse(sql.getValue().contains("manifest_json="));
    }
    @Test void revokedRangeClaimCannotMutateSourceOrReadObjects() {
        var previous=task(ImportSourceTask.Status.WAITING_CONFIRMATION);
        assertThrows(RuntimeException.class,()->repository.rebuild(previous,previous.definition(),UUID.randomUUID(),123,Instant.now()));
        verify(jdbc,never()).update(anyString(),any(Object[].class));
    }
    @Test void requestLookupCannotCrossOwnerBoundary() {
        when(jdbc.query(anyString(),any(org.springframework.jdbc.core.RowMapper.class),anyLong(),any())).thenReturn(List.of());
        UUID key=UUID.randomUUID();assertNull(repository.findByRequest(13,key));
        verify(jdbc).query(contains("owner_id=? AND client_request_id=?"),any(org.springframework.jdbc.core.RowMapper.class),eq(13L),eq(key));
    }
    @Test void creationFingerprintIsPersistedAndBindsDefinitionAndCredentialVersion() {
        var source=task();UUID key=UUID.randomUUID();repository.insert(source,key);
        var inserted=org.mockito.ArgumentCaptor.forClass(Object[].class);
        verify(jdbc).update(contains("client_request_hash"),inserted.capture());
        Object[] values=inserted.getValue();assertEquals(key,values[9]);
        String digest=(String)values[10];assertTrue(digest.matches("[0-9a-f]{64}"));
        when(jdbc.queryForObject(anyString(),eq(Integer.class),any(Object[].class))).thenAnswer(call->{
            Object[] args=(Object[])call.getRawArguments()[2];return digest.equals(args[2])?1:0;
        });
        assertTrue(repository.requestMatches(12,key,source.definition(),null,null));
        assertFalse(repository.requestMatches(12,key,new ImportSourceTask.Definition("改名",source.definition().source(),source.definition().options()),null,null));
        assertFalse(repository.requestMatches(12,key,new ImportSourceTask.Definition("工程",source.definition().source(),new Options(true,false,false)),null,null));
        UUID credential=UUID.randomUUID();
        assertFalse(repository.requestMatches(12,key,source.definition(),credential,1L));
        assertFalse(repository.requestMatches(12,key,source.definition(),credential,2L));
    }
}
