package com.dbts.glyahhaigeneratecode.projectimport;

import org.junit.jupiter.api.Test;
import java.io.IOException;
import java.time.Instant;
import java.util.*;
import java.util.function.Supplier;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;
import static org.mockito.ArgumentMatchers.*;

class ImportAgentEditRecoveryTest {
    record Fixture(ImportAgentEditLedger ledger,ImportDiskVolume volumes,ImportAgentEditRecovery service,
                   ImportAgentEditLedger.Row original,ImportAgentEditLedger.Row revoked){ }
    Fixture fixture(String resources)throws Exception{
        var ledger=mock(ImportAgentEditLedger.class);var volumes=mock(ImportDiskVolume.class);var coordinator=mock(ImportWorkspaceCoordinator.class);
        when(coordinator.withLock(anyLong(),any())).thenAnswer(call->((Supplier<?>)call.getArgument(1)).get());
        var entry=new ImportAgentEditLedger.Entry(UUID.randomUUID(),7,101,UUID.randomUUID(),"201","a".repeat(64),0,0,UUID.randomUUID(),Long.MAX_VALUE,Instant.EPOCH,Instant.now().plusSeconds(60));
        var original=new ImportAgentEditLedger.Row(entry,"EDITING",4,false,resources,"PENDING","NONE",false);
        var revoked=new ImportAgentEditLedger.Row(entry,"RECOVERY_REQUIRED",5,true,resources,"PENDING","NONE",false);
        when(ledger.openAfter(null)).thenReturn(List.of(original));when(ledger.openAfter(entry.id())).thenReturn(List.of());
        when(ledger.require(entry.id(),7)).thenReturn(original);when(ledger.recoveryRequired(original)).thenReturn(revoked);
        when(ledger.discardable(revoked)).thenReturn(true);
        return new Fixture(ledger,volumes,new ImportAgentEditRecovery(ledger,coordinator,volumes,mock(ImportAgentPublication.class)),original,revoked);
    }
    @Test void revokeBeforeReleaseAndCloseOnlyAfterChildrenRelease()throws Exception{
        var f=fixture("[{\"diskBytes\":67108864}]");when(f.ledger().volumeReleased(f.revoked())).thenReturn(f.revoked());
        assertEquals(1,f.service().reconcile().recovered());
        var order=inOrder(f.ledger(),f.volumes());order.verify(f.ledger()).recoveryRequired(f.original());
        order.verify(f.volumes()).stopEditContainers(f.original().identity(),64L<<20);
        order.verify(f.ledger()).containerStopped(f.revoked(),null);
        order.verify(f.volumes()).releaseEdit(f.original().identity(),64L<<20);order.verify(f.ledger()).volumeReleased(f.revoked());
        order.verify(f.ledger()).candidateReleased(f.revoked());order.verify(f.ledger()).viewReleased(f.revoked());
        order.verify(f.ledger()).finishReleasedVolume(f.revoked(),"EXECUTOR_RESTARTED");
    }
    @Test void failedPlatformReleaseKeepsRegisteredResourcesAndRecovery()throws Exception{
        var f=fixture("[{\"diskBytes\":67108864}]");doThrow(new IOException("private-value-not-exposed")).when(f.volumes()).releaseEdit(any(),anyLong());
        var result=f.service().reconcile();assertEquals(0,result.recovered());assertTrue(result.unresolved().getFirst().endsWith("RESOURCE_RELEASE_UNCONFIRMED"));
        assertFalse(result.toString().contains("private-value"));verify(f.ledger(),never()).volumeReleased(any());
        verify(f.ledger(),never()).finishReleasedVolume(any(),anyString());
    }
    @Test void failedContainerStopNeverReleasesVolumeOrClosesSlot()throws Exception{
        var f=fixture("[{\"diskBytes\":67108864}]");doThrow(new IOException("stop unknown")).when(f.volumes()).stopEditContainers(any(),anyLong());
        assertEquals(0,f.service().reconcile().recovered());verify(f.volumes(),never()).releaseEdit(any(),anyLong());
        verify(f.ledger(),never()).volumeReleased(any());verify(f.ledger(),never()).finishReleasedVolume(any(),anyString());
    }
    @Test void publicationRecoveryPreservesCandidateAndCurrentExecutorIsUntouched()throws Exception{
        var f=fixture("[{\"diskBytes\":67108864}]");when(f.ledger().discardable(f.revoked())).thenReturn(false);
        assertTrue(f.service().reconcile().unresolved().getFirst().endsWith("PUBLICATION_RECOVERY_REQUIRED"));verifyNoInteractions(f.volumes());
        var old=f.original().identity();var process=ProcessHandle.current();
        var current=new ImportAgentEditLedger.Entry(old.id(),7,101,old.importTaskId(),"201",old.sourceHash(),0,0,old.executorId(),process.pid(),process.info().startInstant().orElseThrow(),old.expiresAt());
        var row=new ImportAgentEditLedger.Row(current,"PREPARING",0,false,"[]","PENDING","NONE",false);
        reset(f.ledger());when(f.ledger().openAfter(null)).thenReturn(List.of(row));when(f.ledger().openAfter(current.id())).thenReturn(List.of());
        assertEquals(0,f.service().reconcile().recovered());verify(f.ledger(),never()).recoveryRequired(any());verifyNoInteractions(f.volumes());
    }
    @Test void emptyOrInvalidCapacityCannotCauseUnverifiedRelease()throws Exception{
        var f=fixture("[]");assertEquals(1,f.service().reconcile().recovered());verifyNoInteractions(f.volumes());
        verify(f.ledger()).finishWithoutResources(f.revoked(),"EXECUTOR_RESTARTED");
        for(String resource:List.of("{}","[{\"diskBytes\":1.5}]","[{\"diskBytes\":9223372036854775808}]","[{\"diskBytes\":1}]")){
            var invalid=fixture(resource);assertTrue(invalid.service().reconcile().unresolved().getFirst().endsWith("RESOURCE_IDENTITY_INVALID"));
            verifyNoInteractions(invalid.volumes());verify(invalid.ledger(),never()).volumeReleased(any());
        }
    }
}
