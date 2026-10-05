package com.dbts.glyahhaigeneratecode.projectimport;

import com.dbts.glyahhaigeneratecode.exception.MyException;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

class ImportStartupScanRepositoryTest {
    JdbcTemplate jdbc=mock(JdbcTemplate.class);
    ImportStartupScanRepository repository=new ImportStartupScanRepository(jdbc,new ObjectMapper().findAndRegisterModules());
    ImportStartupScan task(ImportStartupScan.Status state){return new ImportStartupScan(UUID.randomUUID(),UUID.randomUUID(),42,7,UUID.randomUUID(),0,0,".",state,0,UUID.randomUUID(),123,Instant.now(),Instant.now().plusSeconds(60),List.of(),null,"PENDING",null,false);}
    @Test void identicalRequestReturnsOriginalTaskWithoutAnotherInsert(){
        var proposed=task(ImportStartupScan.Status.QUEUED);
        when(jdbc.query(anyString(),any(RowMapper.class),any(Object[].class))).thenReturn(List.of(proposed));
        assertEquals(proposed,repository.create(proposed,2,20));verify(jdbc,never()).update(anyString(),any(Object[].class));
    }
    @Test void sameRequestIdWithChangedInputDoesNotReuseTask(){
        var original=task(ImportStartupScan.Status.QUEUED);
        var changed=new ImportStartupScan(UUID.randomUUID(),original.requestId(),42,7,original.importTaskId(),1,0,".",original.status(),0,UUID.randomUUID(),123,original.executorStart(),original.expiresAt(),List.of(),null,"PENDING",null,false);
        when(jdbc.query(anyString(),any(RowMapper.class),any(Object[].class))).thenReturn(List.of(original));
        assertThrows(MyException.class,()->repository.create(changed,2,20));verify(jdbc,never()).update(anyString(),any(Object[].class));
    }
    @Test void admissionLimitIsCheckedInsideDatabaseTransactionBeforeInsert(){
        when(jdbc.query(anyString(),any(RowMapper.class),any(Object[].class))).thenReturn(List.of());
        when(jdbc.queryForObject(anyString(),eq(Integer.class),any(Object[].class))).thenReturn(2);
        assertThrows(MyException.class,()->repository.create(task(ImportStartupScan.Status.QUEUED),2,20));
        verify(jdbc).queryForList(startsWith("SELECT pg_advisory_xact_lock"),any(Object[].class));verify(jdbc,never()).update(anyString(),any(Object[].class));
    }
    @Test void oldRevisionCannotOverwriteTaskOrRegisterResource(){
        when(jdbc.update(anyString(),any(Object[].class))).thenReturn(0);
        var lease=task(ImportStartupScan.Status.SCANNING);
        assertThrows(MyException.class,()->repository.transition(lease,ImportStartupScan.Status.SUCCEEDED,"a".repeat(64),"DONE",null));
        assertThrows(MyException.class,()->repository.resource(lease,new ImportProcessRunner.Resource(222,Instant.now())));
    }
    @Test void cancelAndUnstartedTaskAreNotQualifiedForParser(){
        assertFalse(repository.qualified(task(ImportStartupScan.Status.CANCELLING)));assertFalse(repository.qualified(task(ImportStartupScan.Status.QUEUED)));
        verifyNoInteractions(jdbc);
        var lease=task(ImportStartupScan.Status.SCANNING);when(jdbc.queryForObject(anyString(),eq(Integer.class),any(Object[].class))).thenReturn(0);
        assertFalse(repository.qualified(lease));var sql=org.mockito.ArgumentCaptor.forClass(String.class);
        verify(jdbc).queryForObject(sql.capture(),eq(Integer.class),any(Object[].class));
        assertTrue(sql.getValue().contains("c.context_epoch=s.context_epoch"));assertTrue(sql.getValue().contains("s.expires_at>now()"));assertTrue(sql.getValue().contains("a.user_id=s.owner_id"));
    }
    @Test void publicStatusDoesNotExposeInternalResourcesOrExecutor()throws Exception{
        String value=new ObjectMapper().findAndRegisterModules().writeValueAsString(task(ImportStartupScan.Status.SCANNING).view());
        assertFalse(value.contains("executor"));assertFalse(value.contains("resources"));assertFalse(value.contains("ownerId"));
    }
}
