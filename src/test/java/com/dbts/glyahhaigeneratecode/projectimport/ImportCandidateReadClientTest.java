package com.dbts.glyahhaigeneratecode.projectimport;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import java.nio.file.Path;
import java.time.Instant;
import java.util.*;
import java.io.IOException;
import java.security.MessageDigest;
import java.util.concurrent.atomic.AtomicBoolean;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;
import static org.mockito.ArgumentMatchers.*;

class ImportCandidateReadClientTest {
    @Test void actualInventoryBridgeUsesPlatformTraversalAndFileBytes()throws Exception{
        byte[] data={0,-1};String fileHash=hash(data);
        byte[] inventory=("{\"directories\":[\".\",\"empty\"],\"files\":{\"asset.bin\":[\""+fileHash+"\",2,420]}}").getBytes(java.nio.charset.StandardCharsets.UTF_8);
        String candidateHash=hash(inventory);var f=fixture(data,"file","asset.bin");String stateHash="a".repeat(64),manifestHash="c".repeat(64);
        when(f.engine().platform(anyList(),eq(180))).thenAnswer(call->{
            List<String> args=call.getArgument(0);String part=args.get(args.indexOf("--part")+1);
            long offset=Long.parseLong(args.get(args.indexOf("--offset")+1));byte[] body=part.equals("inventory")?inventory:data;
            var node=new ObjectMapper().createObjectNode().put("appId","101").put("ownerId","7").put("runId","201").put("editId",f.entry().id().toString())
                    .put("contentHash",f.entry().sourceHash()).put("diskBytes",64L<<20).put("formatVersion",3).put("state","PRIVATE_CHUNK")
                    .put("part",part).put("offset",offset).put("size",body.length).put("sha256",hash(body))
                    .put("authorizedStateHash",stateHash).put("candidateHash",candidateHash).put("candidateManifestHash",manifestHash)
                    .put("data",Base64.getEncoder().encodeToString(Arrays.copyOfRange(body,(int)offset,body.length)));
            if(part.equals("inventory"))node.putNull("path");else node.put("path","asset.bin");
            return new ImportExecutionSandbox.Engine.Result(true,0,node.toString(),"");
        });
        var access=f.client().candidateAccess(f.entry(),64L<<20,stateHash,candidateHash,manifestHash,new ImportAgentCandidateVerifier(20,1024),()->true);
        assertThrows(IOException.class,()->access.open("asset.bin"));
        var actual=access.inventory();assertEquals(Set.of(".","empty"),actual.directories());assertEquals(2,actual.files().get("asset.bin").bytes());
        try(var stream=access.open("asset.bin")){assertArrayEquals(data,stream.readAllBytes());}
        assertEquals(actual,access.inventory());assertThrows(IOException.class,()->access.open("unknown.py"));
    }
    @Test void actualInventoryParserRejectsExtraFieldsDuplicatesAndForeignHash()throws Exception{
        var verifier=new ImportAgentCandidateVerifier(10,100);
        byte[] raw="{\"directories\":[\".\"],\"files\":{}}".getBytes(java.nio.charset.StandardCharsets.UTF_8);
        assertTrue(verifier.readInventory(raw,hash(raw)).files().isEmpty());
        assertThrows(IOException.class,()->verifier.readInventory(raw,"a".repeat(64)));
        assertThrows(IOException.class,()->verifier.readInventory("{\"files\":{},\"files\":{},\"directories\":[\".\"]}".getBytes(),hash(raw)));
        assertThrows(IOException.class,()->verifier.readInventory("{\"files\":{},\"directories\":[\".\"],\"extra\":1}".getBytes(),hash(raw)));
    }

    record Fixture(ImportDiskVolume client,ImportExecutionSandbox.Engine engine,ImportAgentEditLedger.Entry entry){ }
    String hash(byte[] value)throws Exception{return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(value));}
    Fixture fixture(byte[] data,String part,String name)throws Exception{
        var engine=mock(ImportExecutionSandbox.Engine.class);when(engine.path(any())).thenReturn("/helper.py");
        var now=Instant.now();var entry=new ImportAgentEditLedger.Entry(UUID.randomUUID(),7,101,UUID.randomUUID(),"201","a".repeat(64),0,0,UUID.randomUUID(),1,now,now.plusSeconds(60));
        String digest=hash(data);
        when(engine.platform(anyList(),eq(180))).thenAnswer(call->{
            List<String> args=call.getArgument(0);long offset=Long.parseLong(args.get(args.indexOf("--offset")+1));
            var node=new ObjectMapper().createObjectNode().put("appId","101").put("ownerId","7").put("runId","201").put("editId",entry.id().toString())
                    .put("contentHash",entry.sourceHash()).put("diskBytes",64L<<20).put("formatVersion",3).put("state","PRIVATE_CHUNK")
                    .put("part",part).put("offset",offset).put("size",data.length).put("sha256",digest)
                    .put("authorizedStateHash",digest).put("candidateHash","b".repeat(64)).put("candidateManifestHash",digest)
                    .put("data",Base64.getEncoder().encodeToString(Arrays.copyOfRange(data,(int)offset,(int)Math.min(offset+262144,data.length))));
            if(name==null)node.putNull("path");else node.put("path",name);
            return new ImportExecutionSandbox.Engine.Result(true,0,node.toString(),"");
        });
        return new Fixture(new ImportDiskVolume(engine,Path.of("helper.py")),engine,entry);
    }
    @Test void privateDocumentsAreReassembledAndIndependentlyHashed()throws Exception{
        byte[] data=new byte[262144+37];new Random(7).nextBytes(data);var f=fixture(data,"private-state",null);String hash=hash(data);
        assertArrayEquals(data,f.client().readEditDocument(f.entry(),64L<<20,hash,"b".repeat(64),hash,"private-state",()->true));
        verify(f.engine(),times(2)).platform(anyList(),eq(180));
    }
    @Test void streamRevocationClosedAndEmptyFileAreNotSilentSuccess()throws Exception{
        byte[] data=new byte[262145];var f=fixture(data,"file","asset.bin");String hash=hash(data);var allowed=new AtomicBoolean(true);
        var stream=f.client().openEditFile(f.entry(),64L<<20,hash,"b".repeat(64),hash,"file","asset.bin",data.length,hash,allowed::get);
        assertEquals(262144,stream.readNBytes(262144).length);allowed.set(false);assertThrows(IOException.class,stream::read);
        stream.close();assertThrows(IOException.class,stream::read);
        var empty=fixture(new byte[0],"file","empty.bin");String emptyHash=hash(new byte[0]);
        try(var input=empty.client().openEditFile(empty.entry(),64L<<20,emptyHash,"b".repeat(64),emptyHash,"file","empty.bin",0,emptyHash,()->true)){
            assertEquals(-1,input.read());
        }
        verify(empty.engine()).platform(anyList(),eq(180));
    }
    @Test void foreignReceiptOrTruncatedProtocolCannotBeReturned()throws Exception{
        byte[] data={0,-1};var f=fixture(data,"manifest",null);String hash=hash(data);
        when(f.engine().platform(anyList(),eq(180))).thenReturn(new ImportExecutionSandbox.Engine.Result(true,0,"{}",""));
        assertThrows(IOException.class,()->f.client().readEditDocument(f.entry(),64L<<20,hash,"b".repeat(64),hash,"manifest",()->true));
        assertThrows(IOException.class,()->f.client().openEditFile(f.entry(),64L<<20,hash,"b".repeat(64),hash,"file","../private-state.json",2,hash,()->true));
    }
}
