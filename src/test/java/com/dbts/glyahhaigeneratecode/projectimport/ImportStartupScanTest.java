package com.dbts.glyahhaigeneratecode.projectimport;

import org.junit.jupiter.api.Test;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import static org.junit.jupiter.api.Assertions.*;
import static com.dbts.glyahhaigeneratecode.projectimport.ImportStartupScan.Status.*;

class ImportStartupScanTest {
    ImportStartupScan task(ImportStartupScan.Status status){
        boolean terminal=List.of(SUCCEEDED,CANCELLED,FAILED,STALE).contains(status);
        return new ImportStartupScan(UUID.randomUUID(),UUID.randomUUID(),42,7,UUID.randomUUID(),0,0,".",status,0,
            UUID.randomUUID(),123,Instant.now(),Instant.now().plusSeconds(60),List.of(),status==SUCCEEDED?"a".repeat(64):null,terminal?"DONE":"PENDING",null,status==CANCELLING||status==CANCELLED);
    }
    @Test void identityAndTimeCannotBeIncomplete(){
        var task=task(QUEUED);
        assertThrows(IllegalArgumentException.class,()->new ImportStartupScan(null,task.requestId(),42,7,task.importTaskId(),0,0,".",QUEUED,0,task.executorId(),123,task.executorStart(),task.expiresAt(),List.of(),null,"PENDING",null,false));
        assertThrows(IllegalArgumentException.class,()->new ImportStartupScan(task.scanId(),task.requestId(),42,7,task.importTaskId(),-1,0,".",QUEUED,0,task.executorId(),123,task.executorStart(),task.expiresAt(),List.of(),null,"PENDING",null,false));
    }
    @Test void successfulResultNeedsCompleteContentAndConfirmedCleanup(){
        var task=task(SCANNING);
        assertThrows(IllegalArgumentException.class,()->task.requireTransition(SUCCEEDED,null,"DONE"));
        assertThrows(IllegalArgumentException.class,()->task.requireTransition(SUCCEEDED,"a".repeat(64),"UNVERIFIED"));
        assertDoesNotThrow(()->task.requireTransition(SUCCEEDED,"a".repeat(64),"DONE"));
    }
    @Test void cancelCannotSkipStoppingOrAcceptLateSuccess(){
        var task=task(CANCELLING);
        assertThrows(IllegalArgumentException.class,()->task.requireTransition(CANCELLED,null,"PENDING"));
        assertThrows(IllegalArgumentException.class,()->task.requireTransition(SUCCEEDED,"a".repeat(64),"DONE"));
        assertDoesNotThrow(()->task.requireTransition(CANCELLED,null,"DONE"));
        assertDoesNotThrow(()->task.requireTransition(STOP_FAILED,null,"UNVERIFIED"));
    }
    @Test void failureCannotPretendUnstoppedResourcesWereReleased(){
        var task=task(SCANNING);
        assertThrows(IllegalArgumentException.class,()->task.requireTransition(FAILED,null,"UNVERIFIED"));
        assertDoesNotThrow(()->task.requireTransition(RECOVERY_REQUIRED,null,"UNVERIFIED"));
        assertDoesNotThrow(()->task.requireTransition(FAILED,null,"DONE"));
    }
    @Test void terminalTasksCannotResumeOrChangeIdentity(){
        for(var state:List.of(CANCELLED,FAILED,STALE)){
            var task=task(state);assertThrows(IllegalArgumentException.class,()->task.requireTransition(SCANNING,null,"PENDING"));
            assertThrows(IllegalArgumentException.class,()->task.requireTransition(SUCCEEDED,"a".repeat(64),"DONE"));
        }
        assertDoesNotThrow(()->task(SUCCEEDED).requireTransition(STALE,"a".repeat(64),"DONE"));
    }
    @Test void unconfirmedResourcesContinueToConsumeAdmission(){
        assertTrue(task(STOP_FAILED).reservesAdmission());assertTrue(task(RECOVERY_REQUIRED).reservesAdmission());
        assertTrue(task(QUEUED).reservesAdmission());assertFalse(task(SUCCEEDED).reservesAdmission());
    }
    @Test void resourcesAreImmutableAndInvalidPidIdentityIsRejected(){
        var task=task(SCANNING);assertThrows(UnsupportedOperationException.class,()->task.resources().add(new ImportProcessRunner.Resource(1,Instant.now())));
        assertThrows(IllegalArgumentException.class,()->ImportStartupScan.requireResource(new ImportProcessRunner.Resource(0,Instant.now())));
        assertThrows(IllegalArgumentException.class,()->ImportStartupScan.requireResource(new ImportProcessRunner.Resource(1,null)));
    }
}
