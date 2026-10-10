package com.dbts.glyahhaigeneratecode.projectimport;

import com.dbts.glyahhaigeneratecode.exception.MyException;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import java.io.IOException;
import java.time.Instant;
import java.util.UUID;
import java.util.function.Supplier;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;
import static org.mockito.ArgumentMatchers.*;

class ImportAgentContainerLifecycleTest {
    record Fixture(ImportAgentContainerLifecycle service,ImportAgentEditLedger ledger,ImportDiskVolume volumes,ImportAgentEditLedger.Row row){ }
    Fixture fixture(){
        var ledger=mock(ImportAgentEditLedger.class);var volumes=mock(ImportDiskVolume.class);var coordinator=mock(ImportWorkspaceCoordinator.class);
        var process=ProcessHandle.current();
        var identity=new ImportAgentEditLedger.Entry(UUID.randomUUID(),7,101,UUID.randomUUID(),"201","a".repeat(64),0,0,UUID.randomUUID(),
                process.pid(),process.info().startInstant().orElseThrow(),Instant.now().plusSeconds(300));
        var row=new ImportAgentEditLedger.Row(identity,"PREPARING",2,false,"[{\"diskBytes\":67108864}]","PENDING","NONE",false);
        when(ledger.require(identity.id(),7)).thenReturn(row);when(ledger.qualified(row)).thenReturn(true);
        when(coordinator.withLock(anyLong(),any())).thenAnswer(call->((Supplier<?>)call.getArgument(1)).get());
        return new Fixture(new ImportAgentContainerLifecycle(ledger,coordinator,volumes),ledger,volumes,row);
    }
    @Test void persistIntentBeforePlatformCreateAndBindActualIdAfterReceipt()throws Exception{
        var f=fixture();var receipt=new ObjectMapper().createObjectNode().put("state","CONTAINER_CREATED").put("containerId","b".repeat(64));
        when(f.volumes().createEditContainer(f.row().identity(),64L<<20)).thenReturn(receipt);
        assertEquals("b".repeat(64),f.service().create(f.row().identity().id(),7));
        var order=inOrder(f.ledger(),f.volumes());order.verify(f.ledger()).planContainer(f.row());
        order.verify(f.volumes()).createEditContainer(f.row().identity(),64L<<20);order.verify(f.ledger()).containerCreated(f.row(),receipt);
        verify(f.ledger(),never()).recoveryRequired(any());
    }
    @Test void repeatAndRevocationDoNotCreateOrCancelExistingTask(){
        var f=fixture();when(f.ledger().hasContainer(f.row())).thenReturn(true);
        assertThrows(MyException.class,()->f.service().create(f.row().identity().id(),7));
        when(f.ledger().hasContainer(f.row())).thenReturn(false);when(f.ledger().qualified(f.row())).thenReturn(false);
        assertThrows(MyException.class,()->f.service().create(f.row().identity().id(),7));
        verifyNoInteractions(f.volumes());verify(f.ledger(),never()).planContainer(any());verify(f.ledger(),never()).recoveryRequired(any());
    }
    @Test void uncertainCreateKeepsIntentAndRecordsRecoveryWithoutRawError()throws Exception{
        var f=fixture();when(f.volumes().createEditContainer(any(),anyLong())).thenThrow(new IOException("fixture-private-value"));
        var error=assertThrows(MyException.class,()->f.service().create(f.row().identity().id(),7));
        assertFalse(error.getMessage().contains("fixture-private"));verify(f.ledger()).planContainer(f.row());
        verify(f.ledger()).recoveryRequired(f.row());verify(f.ledger(),never()).containerCreated(any(),any());
    }

    ImportAgentEditLedger.Row editing(Fixture f){
        var row=new ImportAgentEditLedger.Row(f.row().identity(),"EDITING",3,false,f.row().resources(),"PENDING","NONE",false);
        when(f.ledger().require(row.identity().id(),7)).thenReturn(row);when(f.ledger().qualified(row)).thenReturn(true);return row;
    }
    @Test void sealPersistsRealStopBeforeSealingCasWithoutReleasingVolume()throws Exception{
        var f=fixture();var row=editing(f);
        var receipt=new ObjectMapper().createObjectNode().put("state","STOP_CONFIRMED").put("containerName","glyahh-edit-"+row.identity().id()).put("containerId","b".repeat(64));
        var sealed=new ImportAgentEditLedger.Row(row.identity(),"SEALING",4,false,row.resources(),"PENDING","NONE",true);
        when(f.volumes().stopEditContainers(row.identity(),64L<<20)).thenReturn(receipt);when(f.ledger().beginSealing(row)).thenReturn(sealed);
        assertEquals(sealed,f.service().seal(row.identity().id(),7));
        var order=inOrder(f.ledger(),f.volumes());order.verify(f.volumes()).stopEditContainers(row.identity(),64L<<20);
        order.verify(f.ledger()).containerStopped(row,receipt);order.verify(f.ledger()).beginSealing(row);
        verify(f.volumes(),never()).releaseEdit(any(),anyLong());verify(f.ledger(),never()).recoveryRequired(any());
    }
    @Test void unconfirmedStopKeepsRecoveryAndDoesNotSealOrRelease()throws Exception{
        var f=fixture();var row=editing(f);
        when(f.volumes().stopEditContainers(any(),anyLong())).thenThrow(new IOException("private-stop-value"));
        var error=assertThrows(MyException.class,()->f.service().seal(row.identity().id(),7));assertFalse(error.getMessage().contains("private-stop"));
        verify(f.ledger()).recoveryRequired(row);verify(f.ledger(),never()).containerStopped(any(),any());verify(f.ledger(),never()).beginSealing(any());
        verify(f.volumes(),never()).releaseEdit(any(),anyLong());
    }
    @Test void revokedOrAlreadySealedCannotStopOrMutateTask(){
        var f=fixture();var row=editing(f);when(f.ledger().qualified(row)).thenReturn(false);
        assertThrows(MyException.class,()->f.service().seal(row.identity().id(),7));
        var sealed=new ImportAgentEditLedger.Row(row.identity(),"SEALING",4,false,row.resources(),"PENDING","NONE",true);
        when(f.ledger().require(row.identity().id(),7)).thenReturn(sealed);
        assertThrows(MyException.class,()->f.service().seal(row.identity().id(),7));
        verifyNoInteractions(f.volumes());verify(f.ledger(),never()).recoveryRequired(any());
    }
    @Test void editAdmissionStartsPersistedContainerBeforeEditingCas()throws Exception{
        var f=fixture();var row=editing(f);when(f.ledger().require(row.identity().id(),7)).thenReturn(f.row());
        when(f.ledger().beginEditing(f.row())).thenReturn(row);
        when(f.ledger().createdContainer(f.row())).thenReturn("b".repeat(64));
        assertEquals(row,f.service().beginEditing(row.identity().id(),7));
        var order=inOrder(f.ledger(),f.volumes());order.verify(f.ledger()).createdContainer(f.row());
        order.verify(f.volumes()).startEditContainer(row.identity(),64L<<20,"b".repeat(64));order.verify(f.ledger()).beginEditing(f.row());
    }

    @Test void failedStartNeverGrantsEditingAndRetainsRecovery()throws Exception{
        var f=fixture();when(f.ledger().createdContainer(f.row())).thenReturn("b".repeat(64));
        when(f.volumes().startEditContainer(any(),anyLong(),anyString())).thenThrow(new IOException("private-kernel-error"));
        var error=assertThrows(MyException.class,()->f.service().beginEditing(f.row().identity().id(),7));
        assertFalse(error.getMessage().contains("private-kernel"));verify(f.ledger(),never()).beginEditing(any());verify(f.ledger()).recoveryRequired(f.row());
    }
}
