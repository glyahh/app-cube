package com.dbts.glyahhaigeneratecode.projectimport;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import java.nio.file.Path;
import java.time.Instant;
import java.util.UUID;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;
import static org.mockito.ArgumentMatchers.*;

class ImportEditVolumeClientTest {
    @Test void containerStartRequiresExactIdAndStrictKernelLimits()throws Exception {
        var engine=mock(ImportExecutionSandbox.Engine.class);when(engine.path(any())).thenReturn("/helper.py");
        var start=Instant.now();var e=new ImportAgentEditLedger.Entry(UUID.randomUUID(),7,101,UUID.randomUUID(),"201","a".repeat(64),0,0,UUID.randomUUID(),1,start,start.plusSeconds(60));
        var json=new ObjectMapper();String id="b".repeat(64);
        var receipt=json.createObjectNode().put("appId","101").put("ownerId","7").put("runId","201").put("editId",e.id().toString())
                .put("contentHash",e.sourceHash()).put("diskBytes",64L<<20).put("formatVersion",3).put("state","CONTAINER_RUNNING").put("containerId",id);
        var limits=receipt.putObject("limits").put("memoryBytes",1L<<30).put("swapBytes",0).put("pids",128).put("cpuQuota",100000).put("cpuPeriod",100000);
        when(engine.platform(anyList(),eq(180))).thenAnswer(call->new ImportExecutionSandbox.Engine.Result(true,0,receipt.toString(),""));
        var volumes=new ImportDiskVolume(engine,Path.of("scripts/import_disk_volume.py"));
        assertEquals("CONTAINER_RUNNING",volumes.startEditContainer(e,64L<<20,id).path("state").asText());
        for(String field:java.util.List.of("memoryBytes","swapBytes","pids","cpuQuota","cpuPeriod")){
            var original=limits.get(field);limits.put(field,"0");assertThrows(java.io.IOException.class,()->volumes.startEditContainer(e,64L<<20,id));limits.set(field,original);
        }
        limits.put("swapBytes",1);assertThrows(java.io.IOException.class,()->volumes.startEditContainer(e,64L<<20,id));limits.put("swapBytes",0);
        limits.put("cpuQuota",100001);assertThrows(java.io.IOException.class,()->volumes.startEditContainer(e,64L<<20,id));limits.put("cpuQuota",100000);
        receipt.put("containerId","c".repeat(64));assertThrows(java.io.IOException.class,()->volumes.startEditContainer(e,64L<<20,id));
        reset(engine);assertThrows(java.io.IOException.class,()->volumes.startEditContainer(e,64L<<20,"wrong"));verifyNoInteractions(engine);
    }
    @Test void candidateReceiptCannotRedirectIdentityPathOrState()throws Exception {
        var engine=mock(ImportExecutionSandbox.Engine.class);when(engine.path(any())).thenReturn("/helper.py");
        var start=Instant.now();var e=new ImportAgentEditLedger.Entry(UUID.randomUUID(),7,101,UUID.randomUUID(),"201","a".repeat(64),0,0,UUID.randomUUID(),1,start,start.plusSeconds(60));
        String hash="b".repeat(64);
        var receipt=new ObjectMapper().createObjectNode().put("appId","101").put("ownerId","7").put("runId","201")
                .put("editId",e.id().toString()).put("contentHash",e.sourceHash()).put("diskBytes",64L<<20).put("formatVersion",3)
                .put("state","CANDIDATE_SEALED").put("authorizedStateHash",hash).put("candidateHash","c".repeat(64))
                .put("candidateManifestHash","d".repeat(64)).put("candidatePath",ImportDiskVolume.editPath(e)+"/candidate")
                .put("candidateManifestPath",ImportDiskVolume.editPath(e)+"/candidate-manifest.json");
        when(engine.platform(anyList(),eq(180))).thenAnswer(call->new ImportExecutionSandbox.Engine.Result(true,0,receipt.toString(),""));
        var volumes=new ImportDiskVolume(engine,Path.of("scripts/import_disk_volume.py"));
        assertEquals("CANDIDATE_SEALED",volumes.sealEditCandidate(e,64L<<20,hash).path("state").asText());
        verify(engine).platform(argThat(args->args.containsAll(java.util.List.of("edit-candidate","--state-hash",hash))),eq(180));
        for(String field:java.util.List.of("ownerId","runId","editId","state","candidatePath","candidateManifestPath","authorizedStateHash","candidateHash","candidateManifestHash")){
            String old=receipt.path(field).asText();receipt.put(field,"wrong");
            assertThrows(java.io.IOException.class,()->volumes.sealEditCandidate(e,64L<<20,hash));receipt.put(field,old);
        }
        receipt.put("ownerId",7);assertThrows(java.io.IOException.class,()->volumes.sealEditCandidate(e,64L<<20,hash));
        reset(engine);assertThrows(java.io.IOException.class,()->volumes.sealEditCandidate(e,64L<<20,"not-hash"));verifyNoInteractions(engine);
    }
    @Test void editReceiptRequiresRealBusinessIdentityAndHardCapacity()throws Exception {
        var engine=mock(ImportExecutionSandbox.Engine.class);when(engine.path(any())).thenReturn("/helper.py");
        var start=Instant.now();var e=new ImportAgentEditLedger.Entry(UUID.randomUUID(),7,101,UUID.randomUUID(),"201","a".repeat(64),0,0,UUID.randomUUID(),1,start,start.plusSeconds(60));
        var json=new ObjectMapper();var receipt=json.createObjectNode().put("appId","101").put("ownerId","7")
                .put("runId","201").put("editId",e.id().toString()).put("contentHash",e.sourceHash()).put("diskBytes",64L<<20)
                .put("formatVersion",3).put("state","READY").put("mountPath",ImportDiskVolume.editPath(e))
                .put("actualBytes",64L<<20).put("reservedBytes",64L<<20).put("journal",true).put("loopDevice","/dev/loop17");
        when(engine.platform(anyList(),eq(180))).thenAnswer(call->new ImportExecutionSandbox.Engine.Result(true,0,receipt.toString(),""));
        var volumes=new ImportDiskVolume(engine,Path.of("scripts/import_disk_volume.py"));
        assertEquals("READY",volumes.createEdit(e,64L<<20).path("state").asText());
        verify(engine).platform(argThat(args->args.containsAll(java.util.List.of("--owner-id","7","--run-id","201","--edit-id",e.id().toString()))),eq(180));
        for(String field:java.util.List.of("ownerId","runId","editId","contentHash")){
            String old=receipt.path(field).asText();receipt.put(field,"wrong");
            assertThrows(java.io.IOException.class,()->volumes.createEdit(e,64L<<20));receipt.put(field,old);
        }
        receipt.put("reservedBytes",1);assertThrows(java.io.IOException.class,()->volumes.createEdit(e,64L<<20));
        receipt.put("state","RELEASED");assertDoesNotThrow(()->volumes.releaseEdit(e,64L<<20));
        receipt.put("state","READY");assertThrows(java.io.IOException.class,()->volumes.releaseEdit(e,64L<<20));
        receipt.put("reservedBytes",64L<<20).put("viewState","SEALED").put("viewPath",ImportDiskVolume.editPath(e)+"/view")
                .put("viewHash","b".repeat(64)).put("viewSourceHash","c".repeat(64)).put("viewStateHash","d".repeat(64));
        assertEquals("SEALED",volumes.prepareEditView(e,64L<<20,Path.of("source")).path("viewState").asText());
        for(String field:java.util.List.of("viewHash","viewSourceHash","viewStateHash","viewPath")){
            String old=receipt.path(field).asText();receipt.put(field,"wrong");
            assertThrows(java.io.IOException.class,()->volumes.prepareEditView(e,64L<<20,Path.of("source")));receipt.put(field,old);
        }
        receipt.put("ownerId",7);assertThrows(java.io.IOException.class,()->volumes.prepareEditView(e,64L<<20,Path.of("source")));
    }
}
