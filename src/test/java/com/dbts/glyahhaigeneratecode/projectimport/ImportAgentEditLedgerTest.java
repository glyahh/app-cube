package com.dbts.glyahhaigeneratecode.projectimport;

import com.dbts.glyahhaigeneratecode.exception.MyException;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import java.time.Instant;
import java.util.UUID;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

class ImportAgentEditLedgerTest {
    @Test void verifiedProofIsBoundToSealedReceiptAndCurrentQualification(){
        var jdbc=mock(JdbcTemplate.class);var ledger=new ImportAgentEditLedger(jdbc);var e=entry("201","a".repeat(64),0);
        var row=new ImportAgentEditLedger.Row(e,"SEALING",9,false,"[]","PENDING","NONE",true);
        var frozen=new ImportAgentCandidateVerifier.Frozen("e".repeat(64),"b".repeat(64),
                new ImportAgentCandidateVerifier.Inventory(java.util.Map.of(),java.util.Set.of(".")),java.util.Map.of(),java.util.Map.of(),java.util.Map.of());
        var verified=new ImportAgentCandidateVerifier.Verified("c".repeat(64),"d".repeat(64),1,10);
        when(jdbc.queryForObject(anyString(),eq(Boolean.class),any(Object[].class))).thenReturn(true);
        when(jdbc.update(anyString(),any(Object[].class))).thenReturn(1);
        ledger.candidateVerified(row,frozen,verified);
        verify(jdbc).update(argThat(sql->sql.contains("c.phase='SEALED'")&&sql.contains("NOT e.cancel_requested")&&sql.contains("e.expires_at>now()")
                        &&sql.contains("e.revision=?")&&sql.contains("candidateManifestHash")),
                eq("{\"sourceHash\":\""+e.sourceHash()+"\",\"candidateHash\":\""+"c".repeat(64)+"\",\"candidateManifestHash\":\""+"d".repeat(64)+"\",\"authorizedStateHash\":\""+"b".repeat(64)+"\",\"retainedOutputs\":{}}"),
                eq(e.id()),eq(7L),eq(9L),eq(e.sourceHash()),eq(e.executorId()),eq("b".repeat(64)),eq("c".repeat(64)),eq("d".repeat(64)));
    }
    @Test void verificationRevocationBadProofAndDuplicateCasCannotSucceed(){
        var jdbc=mock(JdbcTemplate.class);var ledger=new ImportAgentEditLedger(jdbc);var e=entry("201","a".repeat(64),0);
        var row=new ImportAgentEditLedger.Row(e,"SEALING",9,false,"[]","PENDING","NONE",true);
        var frozen=new ImportAgentCandidateVerifier.Frozen("e".repeat(64),"b".repeat(64),
                new ImportAgentCandidateVerifier.Inventory(java.util.Map.of(),java.util.Set.of(".")),java.util.Map.of(),java.util.Map.of(),java.util.Map.of());
        var proof=new ImportAgentCandidateVerifier.Verified("c".repeat(64),"d".repeat(64),1,10);
        assertThrows(MyException.class,()->ledger.candidateVerified(row,frozen,new ImportAgentCandidateVerifier.Verified("wrong","d".repeat(64),1,10)));
        verifyNoInteractions(jdbc);
        assertThrows(MyException.class,()->ledger.candidateVerified(row,frozen,proof));verify(jdbc,never()).update(anyString(),any(Object[].class));
        when(jdbc.queryForObject(anyString(),eq(Boolean.class),any(Object[].class))).thenReturn(true);
        assertThrows(MyException.class,()->ledger.candidateVerified(row,frozen,proof));
        when(jdbc.update(anyString(),any(Object[].class))).thenThrow(new org.springframework.dao.DataIntegrityViolationException("fixture-private"));
        var error=assertThrows(MyException.class,()->ledger.candidateVerified(row,frozen,proof));assertFalse(error.getMessage().contains("fixture-private"));
    }
    @Test void candidateIntentUsesStopQualificationAndFrozenPaths(){
        var jdbc=mock(JdbcTemplate.class);var ledger=new ImportAgentEditLedger(jdbc);
        var row=new ImportAgentEditLedger.Row(entry("201","a".repeat(64),0),"SEALING",9,false,"[]","PENDING","NONE",true);
        assertThrows(IllegalArgumentException.class,()->ledger.planCandidate(row,"invalid"));verifyNoInteractions(jdbc);
        assertThrows(MyException.class,()->ledger.planCandidate(row,"b".repeat(64)));
        verify(jdbc).update(argThat(sql->sql.contains("state='SEALING' AND stop_confirmed")&&sql.contains("revision=?")),
                eq(ImportDiskVolume.editPath(row.identity())+"/candidate"),eq(ImportDiskVolume.editPath(row.identity())+"/candidate-manifest.json"),
                eq("b".repeat(64)),eq(row.identity().id()),eq(7L),eq(9L));
    }
    @Test void candidateReceiptKeepsOnlyHashesAndRejectsForeignIdentity(){
        var jdbc=mock(JdbcTemplate.class);var ledger=new ImportAgentEditLedger(jdbc);var e=entry("201","a".repeat(64),0);
        var row=new ImportAgentEditLedger.Row(e,"SEALING",9,false,"[{\"diskBytes\":67108864}]","PENDING","NONE",true);
        String stateHash="b".repeat(64),base=ImportDiskVolume.editPath(e);
        var receipt=new com.fasterxml.jackson.databind.ObjectMapper().createObjectNode().put("appId","101").put("ownerId","7")
                .put("runId","201").put("editId",e.id().toString()).put("contentHash",e.sourceHash()).put("formatVersion",3)
                .put("diskBytes",64L<<20).put("state","CANDIDATE_SEALED").put("authorizedStateHash",stateHash)
                .put("candidatePath",base+"/candidate").put("candidateManifestPath",base+"/candidate-manifest.json")
                .put("candidateHash","c".repeat(64)).put("candidateManifestHash","d".repeat(64)).put("ignoredPlatformField","not-persisted");
        for(String field:java.util.List.of("ownerId","candidatePath","authorizedStateHash","candidateManifestHash")){
            String old=receipt.path(field).asText();receipt.put(field,"wrong");
            assertThrows(MyException.class,()->ledger.candidateReady(row,stateHash,receipt));receipt.put(field,old);
        }
        verifyNoInteractions(jdbc);
        when(jdbc.update(anyString(),any(Object[].class))).thenReturn(1);
        assertDoesNotThrow(()->ledger.candidateReady(row,stateHash,receipt));
        verify(jdbc).update(argThat(sql->sql.contains("c.phase='PLANNED'")&&sql.contains("c.authorized_state_hash=?")),
                eq("{\"candidateHash\":\""+"c".repeat(64)+"\",\"candidateManifestHash\":\""+"d".repeat(64)+"\"}"),eq(e.id()),eq(7L),eq(9L),eq(stateHash));
    }
    private ImportAgentEditLedger.Entry entry(String run,String hash,long epoch) {
        var start=Instant.now();
        return new ImportAgentEditLedger.Entry(UUID.randomUUID(),7,101,UUID.randomUUID(),run,hash,0,epoch,UUID.randomUUID(),1,start,start.plusSeconds(60));
    }
    @Test void identityRejectsFloatOverflowAndBadHash() {
        for(var value:new String[]{"1.0","0","-1","9223372036854775808","1e3",""})
            assertThrows(IllegalArgumentException.class,()->entry(value,"a".repeat(64),0));
        assertThrows(IllegalArgumentException.class,()->entry("201","secret",0));
        assertThrows(IllegalArgumentException.class,()->entry("201","a".repeat(64),-1));
        assertEquals("9223372036854775807",entry("9223372036854775807","a".repeat(64),0).runId());
    }
    @Test void staleCasCannotCancelOrDropRegisteredResources() {
        var jdbc=mock(JdbcTemplate.class);var ledger=new ImportAgentEditLedger(jdbc);
        var row=new ImportAgentEditLedger.Row(entry("201","a".repeat(64),0),"PREPARING",0,false,"[]","PENDING","NONE",false);
        assertThrows(MyException.class,()->ledger.cancel(row));
        assertThrows(MyException.class,()->ledger.finishWithoutResources(row,"PREPARATION_FAILED"));
        verify(jdbc,times(2)).update(anyString(),any(Object[].class));
        assertThrows(IllegalArgumentException.class,()->ledger.finishWithoutResources(row,"secret value"));
    }
}
