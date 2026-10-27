package com.dbts.glyahhaigeneratecode.projectimport;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.dbts.glyahhaigeneratecode.runtime.DockerCli;
import com.dbts.glyahhaigeneratecode.config.RuntimeProperties;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import java.nio.file.*;
import java.time.Instant;
import java.util.UUID;
import static org.junit.jupiter.api.Assertions.*;

/** 由真实卷脚本创建并清理夹具；本类实际调用生产WSL平台引擎，不使用协议替身。 */
@com.dbts.glyahhaigeneratecode.ExternalTest
class ImportAgentCandidatePlatformTest {
    @Test void productionReaderBuildsFrozenAndVerifiesActualSealedCandidate()throws Exception{
        var node=new ObjectMapper().readTree(Files.readAllBytes(Path.of(System.getenv("AGENT_TEST_CANDIDATE_FIXTURE"))));
        var now=Instant.now();var entry=new ImportAgentEditLedger.Entry(UUID.fromString(node.path("editId").asText()),
                Long.parseLong(node.path("ownerId").asText()),Long.parseLong(node.path("appId").asText()),UUID.randomUUID(),
                node.path("runId").asText(),node.path("contentHash").asText(),0,0,UUID.randomUUID(),ProcessHandle.current().pid(),now,now.plusSeconds(300));
        var client=new ImportDiskVolume(ImportExecutionSandbox.docker(new DockerCli(new RuntimeProperties())),Path.of("scripts/import_disk_volume.py"));
        long bytes=node.path("diskBytes").asLong();String stateHash=node.path("authorizedStateHash").asText(),candidateHash=node.path("candidateHash").asText(),manifestHash=node.path("candidateManifestHash").asText();
        var verifier=new ImportAgentCandidateVerifier(10000,bytes);
        byte[] state=client.readEditDocument(entry,bytes,stateHash,candidateHash,manifestHash,"private-state",()->true);
        var frozen=verifier.freeze(state,stateHash,entry,node.path("sourcePath").asText(),node.path("viewSourceHash").asText(),
                client.sourceAccess(entry,bytes,stateHash,candidateHash,manifestHash,verifier,()->true),
                client.viewAccess(entry,bytes,stateHash,candidateHash,manifestHash,verifier,()->true),()->true);
        assertEquals(1,frozen.authorized().size());assertTrue(frozen.original().files().containsKey(".env"));
        byte[] manifest=client.readEditDocument(entry,bytes,stateHash,candidateHash,manifestHash,"manifest",()->true);
        var verified=verifier.verify(manifest,manifestHash,candidateHash,entry,bytes,frozen,
                client.candidateAccess(entry,bytes,stateHash,candidateHash,manifestHash,verifier,()->true),()->true);
        assertEquals(candidateHash,verified.candidateHash());assertEquals(4,verified.files());
        assertFalse(frozen.toString().contains("fixture-private"));
    }
}
