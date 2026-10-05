package com.dbts.glyahhaigeneratecode.projectimport;

import org.junit.jupiter.api.Test;
import java.nio.file.Path;
import java.util.List;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

class ImportDiskVolumeTest {
    @Test void failedPlatformCommandNeverReturnsWindowsFallback() {
        var engine=mock(ImportExecutionSandbox.Engine.class);
        when(engine.path(any())).thenReturn("/mnt/d/sealed");
        when(engine.platform(anyList(),anyInt())).thenReturn(new ImportExecutionSandbox.Engine.Result(false,1,"{\"state\":\"FAILED\",\"reason\":\"HOST_SPACE_INSUFFICIENT\"}",""));
        var volumes=new ImportDiskVolume(engine,Path.of("scripts/import_disk_volume.py"));
        assertThrows(java.io.IOException.class,()->volumes.prepare(7,"a".repeat(64),64L<<20,Path.of("source"),Path.of("manifest")));
        verify(engine,times(1)).platform(anyList(),anyInt());
    }

    @Test void onlyVerifiedSealedMountCanEnterExecution() throws Exception {
        var engine=mock(ImportExecutionSandbox.Engine.class);
        when(engine.path(any())).thenReturn("/mnt/d/sealed");
        String body="{\"appId\":\"7\",\"contentHash\":\""+"a".repeat(64)+"\",\"diskBytes\":67108864,\"actualBytes\":67108864,\"formatVersion\":2,\"state\":\"READY\",\"seedState\":\"SEALED\",\"journal\":true,\"reservedBytes\":67108864,\"mountPath\":\"/var/lib/glyahh/import-disks/7/work\"}";
        when(engine.platform(anyList(),anyInt())).thenReturn(new ImportExecutionSandbox.Engine.Result(true,0,body,""));
        var volumes=new ImportDiskVolume(engine,Path.of("scripts/import_disk_volume.py"));
        assertEquals("/var/lib/glyahh/import-disks/7/work",volumes.prepare(7,"a".repeat(64),64L<<20,Path.of("source"),Path.of("manifest")));
        for(String changed:List.of(body.replace("SEALED","COPYING"),body.replace("/7/work","/8/work"),body.replace("\"journal\":true","\"journal\":false"))) {
            when(engine.platform(anyList(),anyInt())).thenReturn(new ImportExecutionSandbox.Engine.Result(true,0,changed,""));
            assertThrows(java.io.IOException.class,()->volumes.prepare(7,"a".repeat(64),64L<<20,Path.of("source"),Path.of("manifest")));
        }
    }
}
