package com.dbts.glyahhaigeneratecode.projectimport;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.junit.jupiter.api.Test;
import java.io.*;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.Instant;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;

class ImportAgentFrozenTest {
    final ObjectMapper json=new ObjectMapper();
    final ImportAgentCandidateVerifier verifier=new ImportAgentCandidateVerifier(100,1<<20);
    final String marker="__GLYAHH_SECRET_"+"a".repeat(32)+"__";
    byte[] bytes(String value){return value.getBytes(StandardCharsets.UTF_8);}
    String hash(byte[] raw)throws Exception{return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(raw));}
    ImportAgentCandidateVerifier.FileStamp stamp(byte[] raw)throws Exception{return new ImportAgentCandidateVerifier.FileStamp(hash(raw),raw.length,0644);}
    record Access(ImportAgentCandidateVerifier.Inventory inventory,Map<String,byte[]> files) implements ImportAgentCandidateVerifier.FilesAccess {
        public InputStream open(String path){return new ByteArrayInputStream(files.get(path));}
    }
    Access access(Map<String,byte[]> files)throws Exception{
        var stamps=new HashMap<String,ImportAgentCandidateVerifier.FileStamp>();for(var entry:files.entrySet())stamps.put(entry.getKey(),stamp(entry.getValue()));
        return new Access(new ImportAgentCandidateVerifier.Inventory(stamps,Set.of(".")),files);
    }
    ObjectNode stamps(Map<String,ImportAgentCandidateVerifier.FileStamp> files){
        var node=json.createObjectNode();files.forEach((path,value)->{if(value==null)node.putNull(path);else node.set(path,json.valueToTree(List.of(value.sha256(),value.bytes(),value.mode())));});return node;
    }
    record Fixture(ImportAgentEditLedger.Entry entry,ObjectNode state,Access source,Access view){ }
    Fixture fixture()throws Exception{
        var now=Instant.now();var entry=new ImportAgentEditLedger.Entry(UUID.randomUUID(),7,101,UUID.randomUUID(),"201","b".repeat(64),0,0,UUID.randomUUID(),1,now,now.plusSeconds(60));
        var source=access(Map.of("config.py",bytes("API_TOKEN=\"fixture-private-456\"\nPORT=8080\n"),".env",bytes("private-original")));
        var baseline=access(Map.of("config.py",bytes("API_TOKEN=\""+marker+"\"\nPORT=8080\n")));
        var view=access(Map.of("config.py",bytes("API_TOKEN=\""+marker+"\"\nPORT=9090\n"),"test-output.json",bytes("result")));
        var node=json.createObjectNode().put("formatVersion",1).put("appId","101").put("ownerId","7").put("runId","201")
                .put("source","/trusted/source").put("root",ImportDiskVolume.editPath(entry)+"/view")
                .put("sourceHash",ImportAgentCandidateVerifier.inventoryHash(source.inventory())).put("viewHash",ImportAgentCandidateVerifier.inventoryHash(baseline.inventory()))
                .put("maxFiles",100).put("maxBytes",1<<20);
        node.putArray("original").add(stamps(source.inventory().files())).add(json.valueToTree(List.of(".")));
        node.putArray("baseline").add(stamps(baseline.inventory().files())).add(json.valueToTree(List.of(".")));
        node.putArray("secrets").add("fixture-private-456");node.putObject("markers").put("fixture-private-456",marker);
        node.putObject("bindings").putObject("config.py").set(marker,json.valueToTree(List.of(Base64.getEncoder().encodeToString(bytes("fixture-private-456")),1)));
        node.set("authorized",stamps(Map.of("config.py",view.inventory().files().get("config.py"))));
        return new Fixture(entry,node,source,view);
    }
    ImportAgentCandidateVerifier.Frozen freeze(Fixture f,ImportAgentCandidateVerifier.FilesAccess source,ImportAgentCandidateVerifier.FilesAccess view)throws Exception{
        byte[] raw=json.writeValueAsBytes(f.state());return verifier.freeze(raw,hash(raw),f.entry(),"/trusted/source",f.state().path("sourceHash").asText(),source,view,()->true);
    }
    @Test void actualSourceAuthorizedViewAndOriginalSecretsProduceIndependentFrozen()throws Exception{
        var f=fixture();var result=freeze(f,f.source(),f.view());
        assertEquals(stamp(bytes("API_TOKEN=\"fixture-private-456\"\nPORT=9090\n")),result.restored().get("config.py"));
        assertEquals(f.source().inventory(),result.original());assertEquals(Set.of("test-output.json"),result.outputs().keySet());
        assertFalse(result.authorized().containsKey("test-output.json"));assertFalse(result.toString().contains("fixture-private"));
        f.state().put("maxFiles",100000);
        assertEquals(result.restored(),freeze(f,f.source(),f.view()).restored());
    }
    @Test void changedSourceOrShellChangesCannotBecomeFrozen()throws Exception{
        var f=fixture();assertThrows(IOException.class,()->freeze(f,access(Map.of("config.py",bytes("other"))),f.view()));
        var altered=access(Map.of("config.py",bytes("shell")));assertThrows(IOException.class,()->freeze(f,f.source(),altered));
    }
    @Test void mappedValueOrUnchangedCountRenamedFieldIsRefused()throws Exception{
        var f=fixture();((ObjectNode)f.state().path("bindings").path("config.py")).set(marker,json.valueToTree(List.of(Base64.getEncoder().encodeToString(bytes("wrong")),1)));
        assertThrows(IOException.class,()->freeze(f,f.source(),f.view()));
        var clean=fixture();var altered=access(Map.of("config.py",bytes("OTHER_TOKEN=\""+marker+"\"\nPORT=9090\n")));
        clean.state().set("authorized",stamps(altered.inventory().files()));assertThrows(IOException.class,()->freeze(clean,clean.source(),altered));
    }
    @Test void identityExtraFieldsDuplicateKeysAndCancelRefused()throws Exception{
        var f=fixture();f.state().put("ownerId","8");assertThrows(IOException.class,()->freeze(f,f.source(),f.view()));
        var other=fixture();other.state().put("candidateHash","a".repeat(64));assertThrows(IOException.class,()->freeze(other,other.source(),other.view()));
        var valid=fixture();byte[] raw=json.writeValueAsBytes(valid.state());
        assertThrows(IOException.class,()->verifier.freeze(raw,hash(raw),valid.entry(),"/trusted/source",valid.state().path("sourceHash").asText(),valid.source(),valid.view(),()->false));
        byte[] duplicate=bytes(new String(raw,StandardCharsets.UTF_8).replace("{\"formatVersion\":","{\"formatVersion\":1,\"formatVersion\":"));
        assertThrows(IOException.class,()->verifier.freeze(duplicate,hash(duplicate),valid.entry(),"/trusted/source",valid.state().path("sourceHash").asText(),valid.source(),valid.view(),()->true));
    }
    @Test void sourceChangeAtFinalReadRefused()throws Exception{
        var f=fixture();var calls=new java.util.concurrent.atomic.AtomicInteger();
        var source=new ImportAgentCandidateVerifier.FilesAccess(){
            public ImportAgentCandidateVerifier.Inventory inventory(){return calls.incrementAndGet()==1?f.source().inventory():new ImportAgentCandidateVerifier.Inventory(Map.of(),Set.of("."));}
            public InputStream open(String path){return f.source().open(path);}
        };
        assertThrows(IOException.class,()->freeze(f,source,f.view()));
    }
    @Test void authorizedCreateThenDeleteIsNoOpAndDoesNotAuthorizeSourceDeletion()throws Exception{
        var f=fixture();((ObjectNode)f.state().path("authorized")).putNull("temporary-check.py");
        var frozen=freeze(f,f.source(),f.view());assertTrue(frozen.authorized().containsKey("temporary-check.py"));
        assertNull(frozen.authorized().get("temporary-check.py"));assertEquals(f.source().inventory(),frozen.original());
        ((ObjectNode)f.state().path("authorized")).putNull(".env");
        assertThrows(IOException.class,()->freeze(f,f.source(),f.view()));
    }
}
