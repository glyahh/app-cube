package com.dbts.glyahhaigeneratecode.projectimport;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import java.time.Instant;
import java.util.*;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import static org.junit.jupiter.api.Assertions.*;

class ImportStartupSupplementResultTest {
    ObjectMapper json=new ObjectMapper();
    ImportStartupApprovalRepository.Approval approval=new ImportStartupApprovalRepository.Approval(UUID.randomUUID(),UUID.randomUUID(),7,42,UUID.randomUUID(),UUID.randomUUID(),"a".repeat(64),2,3,
        ImportStartupInspectionRepository.RULES_VERSION,List.of("backend","frontend"),true,"RUNNING",1,Instant.now().plusSeconds(60),null,null,false);
    Map<String,String> files=Map.of("backend/app.py","app=FastAPI()");
    Map<String,Object> output()throws Exception{
        var service=new LinkedHashMap<String,Object>();service.put("name","后端");service.put("directory","backend");service.put("command","python -m uvicorn app:app");service.put("build",null);
        service.put("evidence",List.of("backend/app.py"));service.put("source","AGENT");service.put("certainty","INFERRED");
        service.put("evidenceHashes",Map.of("backend/app.py",HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(files.get("backend/app.py").getBytes(StandardCharsets.UTF_8)))));
        var output=new LinkedHashMap<String,Object>();output.put("approvalId",approval.id().toString());output.put("scanId",approval.scanId().toString());output.put("appId","42");output.put("importTaskId",approval.importTaskId().toString());
        output.put("contentHash",approval.contentHash());output.put("configRevision","2");output.put("contextEpoch","3");output.put("services",List.of(service));output.put("unidentified",List.of("frontend"));return output;
    }
    @Test void candidateIdentityIsStableAndUnknownPartRemainsExplicit()throws Exception{
        String text=json.writeValueAsString(output());var first=ImportStartupSupplementResult.validate(approval,files,text);var again=ImportStartupSupplementResult.validate(approval,files,text);
        assertEquals(first,again);assertEquals(List.of("frontend"),first.unidentified());assertTrue(first.candidates().getFirst().id().startsWith("agent:"+approval.id()));
    }
    @ParameterizedTest @ValueSource(strings={"approvalId","scanId","appId","importTaskId","contentHash","configRevision","contextEpoch"})
    void oldOrForeignIdentityCannotReturnCandidate(String field)throws Exception{
        var output=output();output.put(field,"wrong");assertThrows(IllegalArgumentException.class,()->ImportStartupSupplementResult.validate(approval,files,json.writeValueAsString(output)));
    }
    @ParameterizedTest @ValueSource(strings={"scope","facts","hash","install","missing","extra","control"})
    void invalidCandidateCannotPublish(String change)throws Exception{
        var output=output();var service=(Map<String,Object>)((List<?>)output.get("services")).getFirst();
        switch(change){case "scope"->service.put("directory","other");case "facts"->service.put("certainty","FACT");case "hash"->service.put("evidenceHashes",Map.of("backend/app.py","b".repeat(64)));
            case "install"->service.put("install","pip install anything");case "missing"->output.put("unidentified",List.of());case "extra"->output.put("userId","other");case "control"->service.put("command","python\nextra");}
        assertThrows(IllegalArgumentException.class,()->ImportStartupSupplementResult.validate(approval,files,json.writeValueAsString(output)));
    }
    @Test void duplicateJsonFieldAndOversizedOutputAreRejected()throws Exception{
        String text=json.writeValueAsString(output());assertThrows(IllegalArgumentException.class,()->ImportStartupSupplementResult.validate(approval,files,text.replace("\"appId\":\"42\"","\"appId\":\"42\",\"appId\":\"42\"")));
        assertThrows(IllegalArgumentException.class,()->ImportStartupSupplementResult.validate(approval,files," ".repeat(32*1024+1)));
    }
    @Test void changedProjectionIsNotEvidenceForOldReply()throws Exception{
        String text=json.writeValueAsString(output());assertThrows(IllegalArgumentException.class,()->ImportStartupSupplementResult.validate(approval,Map.of("backend/app.py","changed"),text));
    }
    @Test void multipleServicesInSameDirectoryHaveDistinctStableIdentities()throws Exception{
        var output=output();var first=(Map<String,Object>)((List<?>)output.get("services")).getFirst();var second=new LinkedHashMap<>(first);
        second.put("name","后台任务");second.put("command","python -m worker");output.put("services",List.of(first,second));
        var result=ImportStartupSupplementResult.validate(approval,files,json.writeValueAsString(output));
        assertEquals(2,result.candidates().size());assertNotEquals(result.candidates().get(0).id(),result.candidates().get(1).id());
    }
    @Test void expiredApprovalCannotValidateEvenAWellFormedReply()throws Exception{
        var expired=new ImportStartupApprovalRepository.Approval(approval.id(),approval.requestId(),approval.ownerId(),approval.appId(),approval.scanId(),approval.importTaskId(),approval.contentHash(),approval.configRevision(),approval.contextEpoch(),
            approval.rulesVersion(),approval.directories(),true,"RUNNING",approval.revision(),Instant.now().minusSeconds(1),null,null,false);
        String text=json.writeValueAsString(output());assertThrows(IllegalArgumentException.class,()->ImportStartupSupplementResult.validate(expired,files,text));
    }
    ImportStartupApprovalRepository.Approval stored(String state,Instant expires,String result){
        return new ImportStartupApprovalRepository.Approval(approval.id(),approval.requestId(),approval.ownerId(),approval.appId(),approval.scanId(),approval.importTaskId(),approval.contentHash(),approval.configRevision(),approval.contextEpoch(),
            approval.rulesVersion(),approval.directories(),true,state,approval.revision()+1,expires,result,null,false);
    }
    @Test void publishedRecordIsRecheckedAgainstCurrentTextAfterJsonbNormalization()throws Exception{
        // 模拟 PostgreSQL JSONB 读回的空白与键序规范化。
        String normalized=new ObjectMapper().writerWithDefaultPrettyPrinter().writeValueAsString(new TreeMap<>(output()));
        var record=stored("SUCCEEDED",Instant.now().minusSeconds(60),normalized);
        assertEquals(1,ImportStartupSupplementResult.published(record,files).candidates().size());
        assertThrows(IllegalArgumentException.class,()->ImportStartupSupplementResult.published(record,Map.of("backend/app.py","changed")));
    }
    @Test void onlySucceededRecordsCanBeReadAsPublished()throws Exception{
        String text=json.writeValueAsString(output());
        for(String state:List.of("RUNNING","FAILED","STALE"))
            assertThrows(IllegalArgumentException.class,()->ImportStartupSupplementResult.published(stored(state,Instant.now().plusSeconds(60),text),files));
    }
}
