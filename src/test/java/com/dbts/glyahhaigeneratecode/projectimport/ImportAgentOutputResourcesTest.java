package com.dbts.glyahhaigeneratecode.projectimport;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import java.nio.file.*;
import java.security.MessageDigest;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;

class ImportAgentOutputResourcesTest {
    @TempDir Path root;
    @Test void retainedBytesAreSeparateAndTamperingOrExtraNodesAreRejected()throws Exception{
        byte[] raw={0,1,-1};String hash=HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(raw));
        var files=new ObjectMapper().createObjectNode();files.putArray("检查 结果/out.bin").add(hash).add(raw.length).add(0644);
        Files.createDirectory(root.resolve("检查 结果"));Path output=Files.write(root.resolve("检查 结果/out.bin"),raw);
        ImportAgentOutputResources.verify(root,files);
        Files.write(output,new byte[]{0,1,4});assertThrows(java.io.IOException.class,()->ImportAgentOutputResources.verify(root,files));
        Files.write(output,raw);Files.createDirectory(root.resolve("unregistered"));
        assertThrows(java.io.IOException.class,()->ImportAgentOutputResources.verify(root,files));
    }
    @Test void outputManifestRejectsEscapeAndFileDirectoryCollision()throws Exception{
        var json=new ObjectMapper();var escape=json.createObjectNode();escape.putArray("../source.py").add("a".repeat(64)).add(0).add(0644);
        assertThrows(java.io.IOException.class,()->ImportAgentOutputResources.inventory(escape));
        var conflict=json.createObjectNode();conflict.putArray("a").add("a".repeat(64)).add(0).add(0644);conflict.putArray("a/b").add("b".repeat(64)).add(0).add(0644);
        assertThrows(java.io.IOException.class,()->ImportAgentOutputResources.inventory(conflict));
    }
    @Test void unchangedCandidateCannotBeReportedAsSuccessfulRepair()throws Exception{
        var original=new ImportAgentCandidateVerifier.Inventory(Map.of(),Set.of("."));
        var frozen=new ImportAgentCandidateVerifier.Frozen("a".repeat(64),"b".repeat(64),original,Map.of(),Map.of(),Map.of());
        var unchanged=new ImportAgentCandidateVerifier.Verified(ImportAgentCandidateVerifier.inventoryHash(original),"c".repeat(64),0,0);
        assertThrows(java.io.IOException.class,()->ImportAgentCandidateVerification.requireChanged(frozen,unchanged));
        ImportAgentCandidateVerification.requireChanged(frozen,new ImportAgentCandidateVerifier.Verified("d".repeat(64),"c".repeat(64),1,1));
    }
}
