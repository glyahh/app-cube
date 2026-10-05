package com.dbts.glyahhaigeneratecode.projectimport;

import com.dbts.glyahhaigeneratecode.exception.MyException;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

class ImportExecutionLedgerTest {
    JdbcTemplate jdbc=mock(JdbcTemplate.class);
    ImportExecutionLedger ledger=new ImportExecutionLedger(jdbc,new ObjectMapper());
    UUID id=UUID.randomUUID();
    ImportExecutionLedger.Row row=new ImportExecutionLedger.Row(id,1,7,UUID.randomUUID(),123,Instant.now(),"n",null,"PREPARING",null,"PENDING",2);
    ImportExecutionLedger.Entry entry(){return new ImportExecutionLedger.Entry(id,1,7,UUID.randomUUID(),"a".repeat(64),"b".repeat(64),".","c".repeat(64),ImportExecutionSandbox.Limits.defaults(),123,Instant.now());}

    @Test void entryRejectsMissingIdentity(){
        assertThrows(IllegalArgumentException.class,()->new ImportExecutionLedger.Entry(id,1,7,UUID.randomUUID(),"x","b".repeat(64),".","c".repeat(64),ImportExecutionSandbox.Limits.defaults(),123,Instant.now()));
        assertThrows(IllegalArgumentException.class,()->new ImportExecutionLedger.Entry(id,1,7,UUID.randomUUID(),"a".repeat(64),"b".repeat(64),".","c".repeat(64),ImportExecutionSandbox.Limits.defaults(),0,Instant.now()));
        assertThrows(IllegalArgumentException.class,()->ledger.open(entry(),"RUNNING",null));
        assertThrows(IllegalArgumentException.class,()->ledger.open(entry(),"REFUSED"," "));verifyNoInteractions(jdbc);
    }
    @Test void secondActiveExecutionOfSameAppIsConflict(){
        when(jdbc.update(anyString(),any(Object[].class))).thenThrow(new org.springframework.dao.DuplicateKeyException("active"));
        assertThrows(MyException.class,()->ledger.open(entry(),"PREPARING",null));
    }
    @Test void openRecordsLimitsNetworkAndExecutorIdentity(){
        when(jdbc.update(anyString(),any(Object[].class))).thenReturn(1);when(jdbc.query(anyString(),any(RowMapper.class),any(Object[].class))).thenReturn(List.of(row));
        ledger.open(entry(),"PREPARING",null);
        var args=ArgumentCaptor.forClass(Object[].class);verify(jdbc).update(anyString(),args.capture());
        String limits=(String)args.getValue()[8];
        for(String field:List.of("\"network\":\"none\"","\"memory\":\"REQUIRE_HARD\"","\"processes\":128","\"wallSeconds\":900"))assertTrue(limits.contains(field),field);
        assertEquals(ImportExecutionSandbox.PREFIX+id,args.getValue()[11]);
    }
    @Test void everyTransitionIsRevisionCasAndFinishedRowsAreNotReopened(){
        when(jdbc.update(anyString(),any(Object[].class))).thenReturn(0);
        assertThrows(MyException.class,()->ledger.container(row,"d".repeat(64)));
        assertThrows(MyException.class,()->ledger.finish(row,"STOPPED","STOP_REQUESTED",137,0,"DONE"));
        var sql=ArgumentCaptor.forClass(String.class);verify(jdbc,times(2)).update(sql.capture(),any(Object[].class));
        assertTrue(sql.getAllValues().get(0).contains("revision=?")&&sql.getAllValues().get(0).contains("container_id IS NULL"));
        assertTrue(sql.getAllValues().get(1).contains("finished_at IS NULL"));
        assertThrows(IllegalArgumentException.class,()->ledger.finish(row,"RUNNING",null,null,0,"DONE"));
        assertThrows(IllegalArgumentException.class,()->ledger.finish(row,"FAILED","X",null,0,"PENDING"));
    }
    @Test void cleanedOnlyCompletesFailedCleanupOfFinishedRows(){
        when(jdbc.update(anyString(),any(Object[].class))).thenReturn(1);when(jdbc.query(anyString(),any(RowMapper.class),any(Object[].class))).thenReturn(List.of(row));
        ledger.cleaned(row);
        var sql=ArgumentCaptor.forClass(String.class);verify(jdbc).update(sql.capture(),any(Object[].class));
        assertTrue(sql.getValue().contains("finished_at IS NOT NULL AND cleanup='FAILED'"));
    }
}
