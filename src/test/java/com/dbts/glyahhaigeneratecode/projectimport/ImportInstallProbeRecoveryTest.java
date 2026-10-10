package com.dbts.glyahhaigeneratecode.projectimport;
import org.junit.jupiter.api.Test;
import java.time.Instant;
import java.util.*;
import static org.mockito.Mockito.*;
import static org.mockito.ArgumentMatchers.*;
import static org.junit.jupiter.api.Assertions.*;
class ImportInstallProbeRecoveryTest {
    final UUID id=UUID.randomUUID();
    final String name=ImportExecutionSandbox.PREFIX+"pinprobe-"+UUID.randomUUID(),container="a".repeat(64);
    ImportStartupInstallJob job(){
        return new ImportStartupInstallJob(id,UUID.randomUUID(),42,7,UUID.randomUUID(),"b".repeat(64),"c".repeat(64),"d".repeat(64),
            ImportStartupInstallJob.State.PREPARING,1,false,null,null,UUID.randomUUID(),42,Instant.EPOCH,Instant.now(),null,List.of(),List.of(),List.of(),null,"PENDING",
            true,List.of(new ImportStartupInstallJob.Probe(name,null)),Instant.now());
    }
    @Test void missingContainerIsConfirmedOnlyBySuccessfulQuery(){
        var engine=mock(ImportExecutionSandbox.Engine.class);
        when(engine.run(anyList(),anyInt())).thenReturn(new ImportExecutionSandbox.Engine.Result(false,1,"",""));
        assertFalse(new ImportInstallProbeRecovery(engine).confirmed(job(),true));
        when(engine.run(anyList(),anyInt())).thenReturn(new ImportExecutionSandbox.Engine.Result(true,0,"",""));
        assertTrue(new ImportInstallProbeRecovery(engine).confirmed(job(),true));
    }
    @Test void wrongLabelNeverGetsRemoved(){
        var engine=mock(ImportExecutionSandbox.Engine.class);
        when(engine.run(anyList(),anyInt())).thenAnswer(c->{
            var args=c.<List<String>>getArgument(0);
            return new ImportExecutionSandbox.Engine.Result(true,0,args.getFirst().equals("ps")?container:"/"+name+"|{\"glyahh.import.install-job\":\"other-job\",\"glyahh.import.execution\":\"pinprobe\"}","");
        });
        assertFalse(new ImportInstallProbeRecovery(engine).confirmed(job(),true));
        verify(engine,never()).run(argThat(args->args.getFirst().equals("rm")),anyInt());
    }
    @Test void removalRequiresBoundIdentityAndReadback(){
        var engine=mock(ImportExecutionSandbox.Engine.class);
        when(engine.run(anyList(),anyInt())).thenReturn(
            new ImportExecutionSandbox.Engine.Result(true,0,container,""),
            new ImportExecutionSandbox.Engine.Result(true,0,"/"+name+"|{\"glyahh.import.install-job\":\""+id+"\",\"glyahh.import.execution\":\"pinprobe\"}",""),
            new ImportExecutionSandbox.Engine.Result(true,0,container,""),
            new ImportExecutionSandbox.Engine.Result(true,0,"",""));
        assertTrue(new ImportInstallProbeRecovery(engine).confirmed(job(),true));
        verify(engine).run(argThat(args->args.getFirst().equals("inspect")&&args.stream().noneMatch(a->a.contains("\""))),anyInt());
    }
}
