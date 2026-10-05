package com.dbts.glyahhaigeneratecode.projectimport;

import com.dbts.glyahhaigeneratecode.exception.MyException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

class ImportStartupModelLeaseRepositoryTest {
    JdbcTemplate jdbc=mock(JdbcTemplate.class);
    ImportStartupApprovalRepository approvals=mock(ImportStartupApprovalRepository.class);
    ImportStartupModelLeaseRepository repository=new ImportStartupModelLeaseRepository(jdbc,new ObjectMapper().registerModule(new JavaTimeModule()),approvals);
    UUID id=UUID.randomUUID(),executor=UUID.randomUUID();Instant started=Instant.now();
    ImportStartupModelLease lease=new ImportStartupModelLease(id,7,42,1,executor,123,started,0,List.of(),"PENDING");
    ImportStartupApprovalRepository.Approval approval(String state,long revision){return new ImportStartupApprovalRepository.Approval(id,UUID.randomUUID(),7,42,UUID.randomUUID(),UUID.randomUUID(),"a".repeat(64),0,0,
        ImportStartupInspectionRepository.RULES_VERSION,List.of("."),true,state,revision,Instant.now().plusSeconds(60),null,null,false);}

    @Test void invalidExecutorNeverConsumesApproval(){
        assertThrows(IllegalArgumentException.class,()->repository.claim(approval("APPROVED",0),executor,0,started));verifyNoInteractions(approvals,jdbc);
    }
    @Test void nonApprovedLeaseCannotBeClaimedAgain(){
        assertThrows(MyException.class,()->repository.claim(approval("RUNNING",1),executor,123,started));verifyNoInteractions(approvals,jdbc);
    }
    @Test void claimBindsRunningRevisionAndExclusiveExecutor()throws Exception{
        var approved=approval("APPROVED",0);when(approvals.transition(approved,"RUNNING",null,null)).thenReturn(approval("RUNNING",1));
        when(jdbc.update(anyString(),any(Object[].class))).thenReturn(1);when(jdbc.query(anyString(),any(RowMapper.class),any(Object[].class))).thenReturn(List.of(lease));
        assertEquals(lease,repository.claim(approved,executor,123,started));
        var sql=org.mockito.ArgumentCaptor.forClass(String.class);verify(jdbc).update(sql.capture(),any(Object[].class));assertTrue(sql.getValue().contains("NOT cancel_requested AND expires_at>now()"));
        assertNotNull(ImportStartupModelLeaseRepository.class.getDeclaredMethod("claim",ImportStartupApprovalRepository.Approval.class,UUID.class,long.class,Instant.class)
            .getAnnotation(org.springframework.transaction.annotation.Transactional.class));
    }
    @Test void currentQualificationChecksEveryBusinessAndExecutorBoundary(){
        when(jdbc.queryForObject(anyString(),eq(Integer.class),any(Object[].class))).thenReturn(1);
        assertTrue(repository.qualified(lease));var sql=org.mockito.ArgumentCaptor.forClass(String.class);verify(jdbc).queryForObject(sql.capture(),eq(Integer.class),any(Object[].class));
        for(String guard:List.of("s.revision=e.approval_revision","NOT s.cancel_requested","s.expires_at>now()","e.executor_start=?","e.executor_pid=?","e.executor_id=?","i.content_hash=s.content_hash","c.context_epoch=s.context_epoch","a.is_delete=0","s.rules_version=?"))assertTrue(sql.getValue().contains(guard),guard);
    }
    @Test void resourceJournalSurvivesRevocationButNeverCrossesLeaseIdentity(){
        when(jdbc.update(anyString(),any(Object[].class))).thenReturn(1);repository.resource(lease,new ImportProcessRunner.Resource(456,started));
        var sql=org.mockito.ArgumentCaptor.forClass(String.class);verify(jdbc).update(sql.capture(),any(Object[].class));
        assertTrue(sql.getValue().contains("e.cleanup<>'DONE'"));assertTrue(sql.getValue().contains("e.executor_id=?"));assertFalse(sql.getValue().contains("expires_at"));
        when(jdbc.update(anyString(),any(Object[].class))).thenReturn(0);assertThrows(MyException.class,()->repository.resource(lease,new ImportProcessRunner.Resource(456,started)));
    }
    @Test void malformedResourceNeverTouchesDatabase(){
        assertThrows(IllegalArgumentException.class,()->repository.resource(lease,new ImportProcessRunner.Resource(0,started)));verifyNoInteractions(jdbc);
    }
    @Test void cleanupRequiresMatchingRevisionAndCannotReopenDone(){
        when(jdbc.update(anyString(),any(Object[].class))).thenReturn(0);assertThrows(MyException.class,()->repository.cleanup(lease,"DONE"));
        var done=new ImportStartupModelLease(id,7,42,1,executor,123,started,1,List.of(),"DONE");clearInvocations(jdbc);
        assertFalse(repository.qualified(done));assertThrows(MyException.class,()->repository.cleanup(done,"FAILED"));verifyNoInteractions(jdbc);
    }
    @Test void leaseRejectsUnboundedOrUnprovenResources(){
        assertThrows(IllegalArgumentException.class,()->new ImportStartupModelLease(id,7,42,1,executor,123,started,0,java.util.Collections.nCopies(129,new ImportProcessRunner.Resource(456,started)),"PENDING"));
        assertThrows(IllegalArgumentException.class,()->new ImportStartupModelLease(id,7,42,1,executor,123,started,0,List.of(new ImportProcessRunner.Resource(456,null)),"PENDING"));
    }
}
