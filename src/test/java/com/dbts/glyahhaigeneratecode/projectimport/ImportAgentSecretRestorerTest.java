package com.dbts.glyahhaigeneratecode.projectimport;

import org.junit.jupiter.api.Test;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;

class ImportAgentSecretRestorerTest {
    final String marker="__GLYAHH_SECRET_"+"a".repeat(32)+"__";
    final byte[] original=bytes("API_TOKEN=\"fixture-private-456\"\r\nPORT=8080\r\n");
    final byte[] baseline=bytes("API_TOKEN=\""+marker+"\"\r\nPORT=8080\r\n");
    byte[] bytes(String text){return text.getBytes(StandardCharsets.UTF_8);}
    ImportAgentCandidateVerifier.FileStamp stamp(byte[] raw)throws Exception{
        return new ImportAgentCandidateVerifier.FileStamp(HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(raw)),raw.length,0644);
    }
    List<ImportAgentSecretRestorer.Binding> bindings(){return List.of(new ImportAgentSecretRestorer.Binding(marker,bytes("fixture-private-456"),1));}
    ImportAgentCandidateVerifier.FileStamp restore(byte[] edited,List<ImportAgentSecretRestorer.Binding> bindings)throws Exception{
        return new ImportAgentSecretRestorer().restore(original,baseline,edited,stamp(original),stamp(baseline),stamp(edited),bindings);
    }
    @Test void independentOriginalAndAuthorizedBytesRestoreWithCrLfAndUnicode()throws Exception{
        byte[] edited=bytes(new String(baseline,StandardCharsets.UTF_8).replace("8080","9090")+"# 中文\r\n");
        assertEquals(stamp(bytes("API_TOKEN=\"fixture-private-456\"\r\nPORT=9090\r\n# 中文\r\n")),restore(edited,bindings()));
    }
    @Test void identicalMarkerCountCannotRenameSecretFieldOrMoveItToAnotherExpression()throws Exception{
        for(String replacement:List.of("OTHER_TOKEN", "print", "# API_TOKEN")){
            byte[] edited=bytes(new String(baseline,StandardCharsets.UTF_8).replace("API_TOKEN",replacement));
            assertThrows(IOException.class,()->restore(edited,bindings()));
        }
    }
    @Test void badMappingDuplicateOrRemovedMarkerRefused()throws Exception{
        assertThrows(IOException.class,()->restore(baseline,List.of(new ImportAgentSecretRestorer.Binding(marker,bytes("wrong"),1))));
        assertThrows(IOException.class,()->restore(baseline,List.of(bindings().getFirst(),bindings().getFirst())));
        assertThrows(IOException.class,()->restore(bytes("PORT=9090\n"),bindings()));
    }
    @Test void unverifiedOriginalOrAuthorizedBytesRefused()throws Exception{
        var restorer=new ImportAgentSecretRestorer();
        assertThrows(IOException.class,()->restorer.restore(bytes("wrong"),baseline,baseline,stamp(original),stamp(baseline),stamp(baseline),bindings()));
        assertThrows(IOException.class,()->restorer.restore(original,baseline,bytes("wrong"),stamp(original),stamp(baseline),stamp(baseline),bindings()));
    }
    @Test void privateBindingsCloneBytesAndDoNotExposeValueInText(){
        byte[] raw=bytes("fixture-private-456");var binding=new ImportAgentSecretRestorer.Binding(marker,raw,1);raw[0]=0;
        assertEquals('f',binding.value()[0]);byte[] returned=binding.value();returned[0]=0;assertEquals('f',binding.value()[0]);
        assertFalse(binding.toString().contains("fixture-private"));
    }
}
