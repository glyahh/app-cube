package com.dbts.glyahhaigeneratecode.projectimport;

import com.dbts.glyahhaigeneratecode.exception.MyException;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import java.time.Instant;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

class ImportStartupApprovalRepositoryTest {
    JdbcTemplate jdbc=mock(JdbcTemplate.class);
    ImportStartupApprovalRepository repository=new ImportStartupApprovalRepository(jdbc,new ObjectMapper());
    ImportStartupService.Inspection inspection=new ImportStartupService.Inspection(UUID.randomUUID(),"42",UUID.randomUUID(),"a".repeat(64),".","2","3",
        new ImportStartupScanner.Result("b".repeat(64),List.of(),List.of(new ImportStartupScanner.Scope("backend","ALGORITHM","UNIDENTIFIED","NO_ENTRY"))));
    ImportStartupApprovalRepository.Approval row(String state,boolean cancelled){return new ImportStartupApprovalRepository.Approval(UUID.randomUUID(),UUID.randomUUID(),7,42,inspection.scanId(),inspection.importTaskId(),inspection.contentHash(),2,3,
        ImportStartupInspectionRepository.RULES_VERSION,List.of("backend"),!state.equals("REJECTED"),state,0,Instant.now().plusSeconds(60),state.equals("SUCCEEDED")?"{}":null,null,cancelled);}
    @Test void identicalRequestReturnsOriginalDecisionWithoutInsert(){
        var previous=row("APPROVED",false);when(jdbc.query(anyString(),any(RowMapper.class),any(Object[].class))).thenReturn(List.of(previous));
        assertEquals(previous,repository.create(7,previous.requestId(),inspection,List.of("backend"),true,2,20));verify(jdbc,never()).update(anyString(),any(Object[].class));
    }
    @Test void changingDecisionCannotReuseRequestId(){
        var previous=row("APPROVED",false);when(jdbc.query(anyString(),any(RowMapper.class),any(Object[].class))).thenReturn(List.of(previous));
        assertThrows(MyException.class,()->repository.create(7,previous.requestId(),inspection,List.of("backend"),false,2,20));
    }
    @Test void quotaRejectsBeforeInsertInsideTransactionLock(){
        when(jdbc.query(anyString(),any(RowMapper.class),any(Object[].class))).thenReturn(List.of());
        when(jdbc.queryForObject(anyString(),eq(Integer.class),any(Object[].class))).thenReturn(2);
        assertThrows(MyException.class,()->repository.create(7,UUID.randomUUID(),inspection,List.of("backend"),true,2,20));
        verify(jdbc).queryForList(startsWith("SELECT pg_advisory_xact_lock"),any(Object[].class));verify(jdbc,never()).update(anyString(),any(Object[].class));
    }
    @Test void rejectionDoesNotReserveModelQuota(){
        var previous=row("REJECTED",false);when(jdbc.query(anyString(),any(RowMapper.class),any(Object[].class))).thenReturn(List.of(),List.of(previous));when(jdbc.update(anyString(),any(Object[].class))).thenReturn(1);
        assertEquals("REJECTED",repository.create(7,UUID.randomUUID(),inspection,List.of("backend"),false,1,1).state());
        verify(jdbc,never()).queryForObject(anyString(),eq(Integer.class),any(Object[].class));
    }
    @Test void oldRevisionCannotPublishSuccess(){
        when(jdbc.update(anyString(),any(Object[].class))).thenReturn(0);
        assertThrows(MyException.class,()->repository.transition(row("RUNNING",false),"SUCCEEDED","{}",null));
        var sql=org.mockito.ArgumentCaptor.forClass(String.class);verify(jdbc).update(sql.capture(),any(Object[].class));
        for(String guard:List.of("s.revision=?","s.expires_at>now()","c.context_epoch=s.context_epoch","i.content_hash=s.content_hash","a.user_id=i.owner_id","s.rules_version=?"))assertTrue(sql.getValue().contains(guard));
    }
    @Test void rejectionCancellationAndTerminalCannotBecomeRunning(){
        for(String state:List.of("REJECTED","CANCELLED","FAILED","SUCCEEDED"))assertThrows(IllegalArgumentException.class,()->repository.transition(row(state,state.equals("CANCELLED")),"RUNNING",null,null));
        verifyNoInteractions(jdbc);
    }
    @Test void persistedCancelIntentCannotBeConvertedToSuccessOrFailure(){
        var recovery=row("RECOVERY_REQUIRED",true);
        assertThrows(IllegalArgumentException.class,()->repository.transition(recovery,"FAILED",null,"interrupted"));
        assertThrows(IllegalArgumentException.class,()->repository.transition(recovery,"SUCCEEDED","{}",null));
        assertThrows(IllegalArgumentException.class,()->row("SUCCEEDED",true));
    }
    @Test void unknownDirectoryNeverTouchesDatabase(){
        assertThrows(IllegalArgumentException.class,()->repository.create(7,UUID.randomUUID(),inspection,List.of("other"),true,2,20));verifyNoInteractions(jdbc);
    }
    @Test void persistedFrozenHashIsRevalidatedBeforeReturningApproval()throws Exception{
        var approved=row("APPROVED",false);var stored=mock(java.sql.ResultSet.class);
        when(stored.getObject("approval_id",UUID.class)).thenReturn(approved.id());when(stored.getObject("request_id",UUID.class)).thenReturn(approved.requestId());
        when(stored.getObject("scan_id",UUID.class)).thenReturn(approved.scanId());when(stored.getObject("import_task_id",UUID.class)).thenReturn(approved.importTaskId());
        when(stored.getLong("owner_id")).thenReturn(7L);when(stored.getLong("app_id")).thenReturn(42L);when(stored.getLong("config_revision")).thenReturn(2L);when(stored.getLong("context_epoch")).thenReturn(3L);
        when(stored.getString("content_hash")).thenReturn(approved.contentHash());when(stored.getString("rules_version")).thenReturn(approved.rulesVersion());
        when(stored.getString("directories_json")).thenReturn("[\"backend\"]");when(stored.getString("decision")).thenReturn("APPROVE");when(stored.getString("state")).thenReturn("APPROVED");
        when(stored.getTimestamp("expires_at")).thenReturn(java.sql.Timestamp.from(approved.expiresAt()));
        var hash=ImportStartupApprovalRepository.class.getDeclaredMethod("requestHash",ImportStartupApprovalRepository.Approval.class);hash.setAccessible(true);
        when(stored.getString("request_hash")).thenReturn((String)hash.invoke(repository,approved));
        when(jdbc.query(anyString(),any(RowMapper.class),any(Object[].class))).thenAnswer(call->{RowMapper<?> mapper=call.getArgument(1);return List.of(mapper.mapRow(stored,0));});
        assertEquals(approved,repository.find(7,approved.id()));
        when(stored.getString("directories_json")).thenReturn("[\"other\"]");assertThrows(MyException.class,()->repository.find(7,approved.id()));
        when(stored.getString("directories_json")).thenReturn("[\"backend\"]");when(stored.getString("decision")).thenReturn("UNKNOWN");assertThrows(MyException.class,()->repository.find(7,approved.id()));
    }
}
