package com.dbts.glyahhaigeneratecode.projectimport;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.junit.jupiter.api.Test;
import java.io.*;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.Instant;
import java.util.*;
import java.util.concurrent.atomic.AtomicInteger;
import static org.junit.jupiter.api.Assertions.*;

class ImportAgentCandidateVerifierTest {
    final ObjectMapper json=new ObjectMapper();
    final ImportAgentCandidateVerifier verifier=new ImportAgentCandidateVerifier(100,1<<20);
    final byte[] original="before".getBytes(StandardCharsets.UTF_8), edited="after".getBytes(StandardCharsets.UTF_8);
    static String hash(byte[] b)throws Exception{return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(b));}
    ImportAgentCandidateVerifier.FileStamp stamp(byte[] b)throws Exception{return new ImportAgentCandidateVerifier.FileStamp(hash(b),b.length,0644);}
    final String path="中文 空格/😀.py";
    ImportAgentEditLedger.Entry entry(){var now=Instant.now();return new ImportAgentEditLedger.Entry(UUID.randomUUID(),7,101,UUID.randomUUID(),"201","a".repeat(64),0,0,UUID.randomUUID(),1,now,now.plusSeconds(60));}
    record Fixture(ImportAgentEditLedger.Entry entry,ImportAgentCandidateVerifier.Frozen frozen,
                   ImportAgentCandidateVerifier.Inventory inventory,ObjectNode manifest,ImportAgentCandidateVerifier.FilesAccess access){ }
    Fixture fixture()throws Exception{
        var entry=entry();var originalStamp=stamp(original);var editedStamp=stamp(edited);
        var dirs=Set.of(".","中文 空格");
        var inventory=new ImportAgentCandidateVerifier.Inventory(Map.of(path,editedStamp,"asset.bin",stamp(new byte[]{0,-1}),"empty.txt",stamp(new byte[0])),dirs);
        var frozen=new ImportAgentCandidateVerifier.Frozen("b".repeat(64),"c".repeat(64),
                new ImportAgentCandidateVerifier.Inventory(Map.of(path,originalStamp,"asset.bin",stamp(new byte[]{0,-1}),"empty.txt",stamp(new byte[0])),dirs),
                Map.of(path,editedStamp),Map.of(path,editedStamp),Map.of());
        var node=json.createObjectNode().put("appId","101").put("ownerId","7").put("runId","201").put("editId",entry.id().toString())
                .put("contentHash",entry.sourceHash()).put("diskBytes",64L<<20).put("schemaVersion",1).put("formatVersion",3)
                .put("authorizedStateHash",frozen.authorizedStateHash()).put("viewSourceHash",frozen.viewSourceHash())
                .put("candidateHash",ImportAgentCandidateVerifier.inventoryHash(inventory));
        node.set("directories",json.valueToTree(dirs));node.set("files",stamps(inventory.files()));
        node.set("authorized",stamps(frozen.authorized()));node.set("retainedOutputs",json.createObjectNode());
        node.putArray("changes").addObject().put("path",path).set("before",json.valueToTree(List.of(originalStamp.sha256(),originalStamp.bytes(),originalStamp.mode())));
        ((ObjectNode)node.path("changes").get(0)).set("after",json.valueToTree(List.of(editedStamp.sha256(),editedStamp.bytes(),editedStamp.mode())));
        var access=new ImportAgentCandidateVerifier.FilesAccess(){
            public ImportAgentCandidateVerifier.Inventory inventory(){return inventory;}
            public InputStream open(String p){return new ByteArrayInputStream(p.equals(path)?edited:p.equals("asset.bin")?new byte[]{0,-1}:new byte[0]);}
        };
        return new Fixture(entry,frozen,inventory,node,access);
    }
    ObjectNode stamps(Map<String,ImportAgentCandidateVerifier.FileStamp> files){
        var node=json.createObjectNode();files.forEach((p,s)->{
            if(s==null)node.putNull(p);else node.set(p,json.valueToTree(List.of(s.sha256(),s.bytes(),s.mode())));
        });return node;
    }
    ImportAgentCandidateVerifier.Verified verify(Fixture f,ImportAgentCandidateVerifier.FilesAccess access)throws Exception{
        byte[] raw=json.writeValueAsBytes(f.manifest());
        return verifier.verify(raw,hash(raw),f.manifest().path("candidateHash").asText(),f.entry(),64L<<20,f.frozen(),access,()->true);
    }
    @Test void validActualBytesBinaryEmptyFileAndUnicodePass()throws Exception{
        var f=fixture();var result=verify(f,f.access());assertEquals(3,result.files());assertEquals(edited.length+2,result.bytes());
    }
    @Test void pythonCanonicalHashMatchesUnicodeSupplementaryPlaneFixture(){
        var inventory=new ImportAgentCandidateVerifier.Inventory(Map.of("中文 空格/😀.bin",new ImportAgentCandidateVerifier.FileStamp("a".repeat(64),2,420),
                "\ue000.txt",new ImportAgentCandidateVerifier.FileStamp("b".repeat(64),0,493)),Set.of(".","中文 空格"));
        assertEquals("b10e22ee4e16b72cbecf8b3e444d42356bd9af9a9030cb9bd55658e5aa77e9de",ImportAgentCandidateVerifier.inventoryHash(inventory));
    }
    @Test void wrongIdentityAndExtraFieldsCannotPass()throws Exception{
        for(String key:List.of("appId","ownerId","runId","editId","contentHash","authorizedStateHash","viewSourceHash","candidateHash")){
            var f=fixture();f.manifest().put(key,"wrong");assertThrows(IOException.class,()->verify(f,f.access()));
        }
        var f=fixture();f.manifest().put("success",true);assertThrows(IOException.class,()->verify(f,f.access()));
    }
    @Test void floatsBooleansAndOverflowNotCoerced()throws Exception{
        for(String key:List.of("schemaVersion","diskBytes","formatVersion")){
            var f=fixture();f.manifest().put(key,1.0);assertThrows(IOException.class,()->verify(f,f.access()));
            f.manifest().put(key,true);assertThrows(IOException.class,()->verify(f,f.access()));
        }
    }
    @Test void duplicateKeysTrailingJsonAndWrongRawHashRefused()throws Exception{
        var f=fixture();String raw=json.writeValueAsString(f.manifest());
        for(String bad:List.of(raw.replace("{\"appId\":", "{\"appId\":\"101\",\"appId\":"),raw+"{}")){
            byte[] bytes=bad.getBytes(StandardCharsets.UTF_8);
            assertThrows(IOException.class,()->verifier.verify(bytes,hash(bytes),f.manifest().path("candidateHash").asText(),f.entry(),64L<<20,f.frozen(),f.access(),()->true));
        }
        assertThrows(IOException.class,()->verifier.verify(raw.getBytes(StandardCharsets.UTF_8),"0".repeat(64),f.manifest().path("candidateHash").asText(),f.entry(),64L<<20,f.frozen(),f.access(),()->true));
    }
    @Test void candidateCannotCreateItsOwnAuthorization()throws Exception{
        var f=fixture();((ObjectNode)f.manifest().path("authorized")).set("injected.py",json.valueToTree(List.of("d".repeat(64),1,420)));
        assertThrows(IOException.class,()->verify(f,f.access()));
        var modified=fixture();((ObjectNode)modified.manifest().path("changes").get(0)).put("path","other.py");
        assertThrows(IOException.class,()->verify(modified,modified.access()));
    }
    @Test void shortGrowingOrChangedBytesRefused()throws Exception{
        var f=fixture();
        for(byte[] bytes:List.of(new byte[0],"after-extra".getBytes(StandardCharsets.UTF_8),"wrong".getBytes(StandardCharsets.UTF_8))){
            var access=new ImportAgentCandidateVerifier.FilesAccess(){
                public ImportAgentCandidateVerifier.Inventory inventory(){return f.inventory();}
                public InputStream open(String p)throws IOException{return p.equals(path)?new ByteArrayInputStream(bytes):f.access().open(p);}
            };
            assertThrows(IOException.class,()->verify(f,access));
        }
    }
    @Test void actualDirectoryChangesAndRevocationRefused()throws Exception{
        var f=fixture();var calls=new AtomicInteger();
        var access=new ImportAgentCandidateVerifier.FilesAccess(){
            public ImportAgentCandidateVerifier.Inventory inventory(){return calls.incrementAndGet()==1?f.inventory():new ImportAgentCandidateVerifier.Inventory(f.inventory().files(),Set.of("."));}
            public InputStream open(String p)throws IOException{return f.access().open(p);}
        };
        assertThrows(IOException.class,()->verify(f,access));
        byte[] raw=json.writeValueAsBytes(f.manifest());
        assertThrows(IOException.class,()->verifier.verify(raw,hash(raw),f.manifest().path("candidateHash").asText(),f.entry(),64L<<20,f.frozen(),f.access(),()->false));
    }
}
