package com.dbts.glyahhaigeneratecode.projectimport;

import org.junit.jupiter.api.Test;
import java.io.IOException;
import java.util.UUID;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

@com.dbts.glyahhaigeneratecode.ExternalTest
class ImportNativeWorkerTest {
    @Test void stoppedBeforeStartCannotLaunchLateProcess() throws Exception {
        var edit=UUID.randomUUID();var lease=UUID.randomUUID();var worker=new ImportNativeWorker(edit,lease);
        try(var platform=mockStatic(ImportNativeWorker.class,CALLS_REAL_METHODS)){
            platform.when(()->ImportNativeWorker.stopRegistered(eq(edit),eq(lease),anyString())).thenAnswer(call->null);
            worker.stop();
            assertEquals("IMPORT_NATIVE_START_REVOKED",assertThrows(IOException.class,worker::start).getMessage());
            platform.verify(()->ImportNativeWorker.stopRegistered(eq(edit),eq(lease),anyString()));
        }
    }

    @Test void failedProcessCreationStillAllowsStopTombstone() throws Exception {
        var edit=UUID.randomUUID();var lease=UUID.randomUUID();var worker=new ImportNativeWorker(edit,lease);
        var field=ImportNativeWorker.class.getDeclaredField("builder");field.setAccessible(true);
        ((ProcessBuilder)field.get(worker)).command("missing-import-worker-"+UUID.randomUUID());
        assertThrows(IOException.class,worker::start);
        try(var platform=mockStatic(ImportNativeWorker.class,CALLS_REAL_METHODS)){
            platform.when(()->ImportNativeWorker.stopRegistered(eq(edit),eq(lease),anyString())).thenAnswer(call->null);
            worker.stop();
            platform.verify(()->ImportNativeWorker.stopRegistered(eq(edit),eq(lease),anyString()));
            assertThrows(IOException.class,worker::start);
        }
    }
}
