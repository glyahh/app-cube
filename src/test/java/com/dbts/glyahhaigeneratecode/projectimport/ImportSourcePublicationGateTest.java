package com.dbts.glyahhaigeneratecode.projectimport;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.util.ReflectionTestUtils;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import static com.dbts.glyahhaigeneratecode.service.support.ProjectImportManifest.*;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

class ImportSourcePublicationGateTest {
    final JdbcTemplate jdbc=mock(JdbcTemplate.class);
    final ObjectMapper json=new ObjectMapper().findAndRegisterModules();
    final ImportTaskRepository repository=new ImportTaskRepository(mock(javax.sql.DataSource.class),json);
    final List<FileEntry> files=List.of(new FileEntry("index.html",1));
    final ImportTask task=new ImportTask(UUID.randomUUID(),12,new ImportTask.Definition("工程",files,Options.defaults(),
            validate(files,Options.defaults(),new Limits(10,100))),ImportTask.Status.WAITING_CONFIRMATION,1,1,List.of(),"a".repeat(64),null,"NONE",null);
    final Map<String,Object> source=new HashMap<>();
    ImportSourcePublicationGateTest()throws Exception {
        ReflectionTestUtils.setField(repository,"jdbc",jdbc);
        source.put("status","WAITING_CONFIRMATION");source.put("main_task_created",true);source.put("content_hash",task.contentHash());
        source.put("commit_id","b".repeat(40));source.put("branch_name","main");
        source.put("manifest_json",json.writeValueAsString(new ImportGitReader.Snapshot("main","b".repeat(40),List.of(new ImportGitMetadata.Blob("index.html","c".repeat(40),1,false)))));
        source.put("definition_json",json.writeValueAsString(new ImportSourceTask.Definition("工程",ImportRepositorySource.parse("GitHub","https://github.com/example/project"),Options.defaults())));
        when(jdbc.queryForObject(contains("SELECT source_kind"),eq(String.class),any(Object[].class))).thenReturn("GIT");
        when(jdbc.queryForList(contains("SELECT * FROM biz.project_import_source"),any(Object[].class))).thenReturn(List.of(source));
    }
    boolean gate(){return Boolean.TRUE.equals(ReflectionTestUtils.invokeMethod(repository,"requireSource",task));}
    @Test void matchingImmutableSourceQualifiesWithoutWritingBusinessState() {
        assertTrue(gate());verify(jdbc,never()).update(anyString(),any(Object[].class));
    }
    @Test void missingOrExpiredSourceCannotBecomeAnonymousFolder() {
        when(jdbc.queryForList(anyString(),any(Object[].class))).thenReturn(List.of());
        assertThrows(RuntimeException.class,this::gate);
        var sql=org.mockito.ArgumentCaptor.forClass(String.class);
        verify(jdbc).queryForList(sql.capture(),any(Object[].class));assertTrue(sql.getValue().contains("expires_at>now() FOR UPDATE"));
    }
    @Test void differentCommitBranchContentOrMissingSnapshotCannotPublish() {
        for(String key:List.of("commit_id","branch_name","content_hash","manifest_json")) {
            Object original=source.put(key,"different");assertThrows(RuntimeException.class,this::gate);source.put(key,original);
        }
    }
    @Test void changedFileListOrFrozenOptionsCannotPublish()throws Exception {
        String original=(String)source.get("manifest_json");
        source.put("manifest_json",json.writeValueAsString(new ImportGitReader.Snapshot("main","b".repeat(40),List.of(new ImportGitMetadata.Blob("other.html","c".repeat(40),1,false)))));
        assertThrows(RuntimeException.class,this::gate);source.put("manifest_json",original);
        source.put("definition_json",json.writeValueAsString(new ImportSourceTask.Definition("工程",ImportRepositorySource.parse("GitHub","https://github.com/example/project"),new Options(true,false,false))));
        assertThrows(RuntimeException.class,this::gate);
    }
    @Test void privateSourceRequiresExactActiveMainLeaseAndProvider() {
        UUID credential=UUID.randomUUID();source.put("credential_id",credential);source.put("credential_version",1L);
        when(jdbc.queryForObject(contains("SELECT count(*)"),eq(Integer.class),any(Object[].class))).thenReturn(0);
        assertThrows(RuntimeException.class,this::gate);
        when(jdbc.queryForObject(contains("SELECT count(*)"),eq(Integer.class),any(Object[].class))).thenReturn(1);
        assertTrue(gate());
        var sql=org.mockito.ArgumentCaptor.forClass(String.class);
        verify(jdbc,times(2)).queryForObject(sql.capture(),eq(Integer.class),eq(task.taskId()),eq(12L),eq(credential),eq(1L));
        assertTrue(sql.getValue().contains("l.credential_id=? AND l.credential_version=?"));
        assertTrue(sql.getValue().contains("c.provider=s.definition_json->'source'->>'provider'"));
    }
}
