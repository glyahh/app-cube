package com.dbts.glyahhaigeneratecode.projectimport;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import java.nio.file.*;
import java.nio.charset.StandardCharsets;
import java.io.*;
import java.time.*;
import java.util.*;
import java.security.MessageDigest;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;
import static org.mockito.ArgumentMatchers.*;

class ImportStartupModelExecutorTest {
    @TempDir Path root;
    ObjectMapper json=new ObjectMapper();
    Map<String,String> files=Map.of("backend/app.py","app=FastAPI()");
    ImportStartupScanner.Scope range=new ImportStartupScanner.Scope("backend","ALGORITHM","UNIDENTIFIED","NO_ENTRY");
    ImportStartupScanner.Candidate known=new ImportStartupScanner.Candidate("backend/package.json:dev","backend","backend","npm run dev",null,"STATIC","backend/package.json#scripts.dev");
    ImportStartupService.ModelInput input=new ImportStartupService.ModelInput(files,List.of(range),List.of(known));
    ImportStartupApprovalRepository.Approval approval=new ImportStartupApprovalRepository.Approval(UUID.randomUUID(),UUID.randomUUID(),7,42,UUID.randomUUID(),UUID.randomUUID(),"a".repeat(64),2,3,
        ImportStartupInspectionRepository.RULES_VERSION,List.of("backend"),true,"RUNNING",1,Instant.now().plusSeconds(120),null,null,false);
    ImportStartupModelExecutor executor()throws Exception{var props=new ImportProperties();props.setStagingRoot(Files.createDirectories(root.resolve("staging")).toRealPath());return new ImportStartupModelExecutor(props,new org.springframework.core.env.StandardEnvironment());}
    String payload()throws Exception{
        var service=new LinkedHashMap<String,Object>();service.put("name","后端");service.put("directory","backend");service.put("command","python -m uvicorn app:app");service.put("build",null);
        service.put("evidence",List.of("backend/app.py"));service.put("source","AGENT");service.put("certainty","INFERRED");service.put("evidenceHashes",Map.of("backend/app.py",HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(files.get("backend/app.py").getBytes(StandardCharsets.UTF_8)))));
        return json.writeValueAsString(Map.of("approvalId",approval.id().toString(),"scanId",approval.scanId().toString(),"appId","42","importTaskId",approval.importTaskId().toString(),"contentHash",approval.contentHash(),"configRevision","2","contextEpoch","3","services",List.of(service),"unidentified",List.of()));
    }
    List<Map<String,Object>> frames()throws Exception{
        var result=new ArrayList<Map<String,Object>>();long seq=0;
        for(String event:List.of("run.started","text.message","run.completed","__end__")){var frame=new LinkedHashMap<String,Object>();frame.put("v",1);frame.put("runId",approval.id().toString());frame.put("seq",seq++);frame.put("ts",1);frame.put("event",event);
            if(event.equals("run.started")){frame.put("taskKind","startup");frame.put("appId","42");}
            if(event.equals("text.message")){frame.put("messageId","startup-candidates");frame.put("content",payload());}
            if(event.equals("run.completed"))frame.put("status","succeeded");result.add(frame);}
        return result;
    }
    @SuppressWarnings("unchecked")
    void bind(org.mockito.MockedStatic<ImportProcessRunner> runner,List<Map<String,Object>> frames){
        runner.when(()->ImportProcessRunner.run(anyList(),any(Path.class),anyMap(),any(Duration.class),anyLong(),anyLong(),any(java.util.function.BooleanSupplier.class),any(java.util.function.Consumer.class),any(ImportProcessRunner.StreamHandler.class)))
            .thenAnswer(call->{
                List<String> command=call.getArgument(0);assertEquals("-I",command.get(1));assertTrue(command.get(2).endsWith("import-startup-agent.py"));
                byte[] input=Files.readAllBytes(Path.of(command.get(3)));assertEquals(command.get(4),HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(input)));
                assertFalse(new String(input,StandardCharsets.UTF_8).contains("workspace"));
                // 模型上下文逐条带未识别范围与已有候选，只含批准目录文本。
                var context=json.readTree(json.readTree(input).get("input").get("message").asText());
                assertEquals("NO_ENTRY",context.get("ranges").get(0).get("diagnostic").asText());assertEquals("npm run dev",context.get("known").get(0).get("command").asText());
                assertEquals(List.of("backend/app.py"),new ArrayList<>(json.convertValue(context.get("files"),Map.class).keySet()));
                Map<String,String> env=call.getArgument(2);
                var allowed=Set.of("SystemRoot","SYSTEMROOT","WINDIR","PATH","PATHEXT","SSL_CERT_FILE","REQUESTS_CA_BUNDLE","CURL_CA_BUNDLE",
                    "AGENT_MODEL_ID","AGENT_MODEL_BASE_URL","AGENT_MODEL_API_KEY","AGENT_MODEL_TEMPERATURE","AGENT_MAX_OUTPUT_TOKENS","AGENT_CONTEXT_WINDOW",
                    "AGENT_MODEL_PROXY_ENABLED","AGENT_MODEL_PROXY_URL","DEEPSEEK_API_KEY","AGENT_SUMMARIZER_MODEL_ID","AGENT_SUMMARIZER_BASE_URL","AGENT_SUMMARIZER_API_KEY");
                assertTrue(allowed.containsAll(env.keySet()));
                var journal=(java.util.function.Consumer<ImportProcessRunner.Resource>)call.getArgument(7);journal.accept(new ImportProcessRunner.Resource(123,Instant.now()));
                String output="";for(var frame:frames)output+=json.writeValueAsString(frame)+"\n";
                var handler=(ImportProcessRunner.StreamHandler<?>)call.getArgument(8);Object value=handler.read(new ByteArrayInputStream(output.getBytes(StandardCharsets.UTF_8)));
                return new ImportProcessRunner.Result<>(value,0,output.length(),0);
            });
    }
    @Test void qualifiedFramesBindPayloadAndCleanPrivateInput()throws Exception{
        var executor=executor();var resources=new ArrayList<ImportProcessRunner.Resource>();
        try(var runner=mockStatic(ImportProcessRunner.class,CALLS_REAL_METHODS)){bind(runner,frames());
            var result=executor.execute(approval,input,()->true,resources::add);assertEquals(1,result.validated().candidates().size());assertEquals(1,resources.size());assertTrue(executor.inputAbsent(approval.id()));}
    }
    @ParameterizedTest @ValueSource(strings={"run","sequence","kind","missing-end","extra","status","version"})
    void badProtocolDoesNotReturnCandidates(String change)throws Exception{
        var executor=executor();var frames=frames();switch(change){case "run"->frames.get(1).put("runId",UUID.randomUUID().toString());case "sequence"->frames.get(1).put("seq",0);case "kind"->frames.getFirst().put("taskKind","coding");
            case "missing-end"->frames.removeLast();case "extra"->frames.add(new LinkedHashMap<>(frames.getLast()));case "status"->frames.get(2).put("status","failed");case "version"->frames.getFirst().put("v",2);}
        try(var runner=mockStatic(ImportProcessRunner.class,CALLS_REAL_METHODS)){bind(runner,frames);
            assertThrows(ImportStartupModelExecutor.Failure.class,()->executor.execute(approval,input,()->true,r->{}));assertTrue(executor.inputAbsent(approval.id()));}
    }
    @Test void revokedBeforeSpawnCreatesNoPrivateInput()throws Exception{
        var executor=executor();assertThrows(ImportStartupModelExecutor.Failure.class,()->executor.execute(approval,input,()->false,r->{}));assertTrue(executor.inputAbsent(approval.id()));
    }
    @Test void expiredApprovalIsRejectedEvenIfCallerClaimsEligibility()throws Exception{
        var executor=executor();var expired=new ImportStartupApprovalRepository.Approval(approval.id(),approval.requestId(),approval.ownerId(),approval.appId(),approval.scanId(),approval.importTaskId(),approval.contentHash(),approval.configRevision(),approval.contextEpoch(),approval.rulesVersion(),approval.directories(),true,"RUNNING",1,Instant.now().minusSeconds(1),null,null,false);
        try(var runner=mockStatic(ImportProcessRunner.class)){
            assertEquals("APPROVAL_REVOKED",assertThrows(ImportStartupModelExecutor.Failure.class,()->executor.execute(expired,input,()->true,r->{})).code());
            runner.verifyNoInteractions();assertTrue(executor.inputAbsent(expired.id()));
        }
    }
    @Test void unconfirmedStopPreservesInputForRecovery()throws Exception{
        var executor=executor();
        try(var runner=mockStatic(ImportProcessRunner.class)){
            runner.when(()->ImportProcessRunner.run(anyList(),any(Path.class),anyMap(),any(Duration.class),anyLong(),anyLong(),any(java.util.function.BooleanSupplier.class),any(java.util.function.Consumer.class),any(ImportProcessRunner.StreamHandler.class))).thenThrow(new IllegalStateException("fixture"));
            runner.when(()->ImportProcessRunner.errorCode(any())).thenReturn("STOP_FAILED");
            var failure=assertThrows(ImportStartupModelExecutor.Failure.class,()->executor.execute(approval,input,()->true,r->{}));assertEquals("MODEL_EXECUTOR_STOP_FAILED",failure.code());assertFalse(executor.inputAbsent(approval.id()));
            executor.cleanupStoppedInput(approval.id());assertTrue(executor.inputAbsent(approval.id()));
        }
    }
    @Test void nativeProtocolFixtureUsesProductionParserWithoutClosingOwnedStream()throws Exception{
        var python=new ImportProperties().getCredentialPython().toAbsolutePath();org.junit.jupiter.api.Assumptions.assumeTrue(Files.isRegularFile(python));
        var executor=executor();StringBuilder output=new StringBuilder();for(var frame:frames())output.append(json.writeValueAsString(frame)).append('\n');
        var method=ImportStartupModelExecutor.class.getDeclaredMethod("read",InputStream.class,UUID.class,long.class);method.setAccessible(true);
        var environment=new HashMap<String,String>();if(System.getenv("SystemRoot")!=null)environment.put("SystemRoot",System.getenv("SystemRoot"));var resources=new ArrayList<ImportProcessRunner.Resource>();
        // 平台测试夹具只输出协议，不调用模型，不执行导入源码。
        Path fixture=root.resolve("protocol-fixture.py");
        Files.writeString(fixture,"import sys,base64\nsys.stdout.buffer.write(base64.b64decode('"+
            Base64.getEncoder().encodeToString(output.toString().getBytes(StandardCharsets.UTF_8))+"'))\n",StandardCharsets.UTF_8);
        var result=ImportProcessRunner.run(List.of(python.toString(),"-I",fixture.toString()),
            root,environment,Duration.ofSeconds(10),96*1024,8192,()->true,resources::add,stream->{
                try{return method.invoke(executor,stream,approval.id(),approval.appId());}
                catch(java.lang.reflect.InvocationTargetException failure){throw new AssertionError("平台协议夹具解析失败",failure.getCause());}
            });
        assertEquals(0,result.exitCode());assertNotNull(result.value());assertFalse(resources.isEmpty());
        for(var resource:resources){var handle=ProcessHandle.of(resource.pid());assertTrue(handle.isEmpty()||!handle.get().isAlive()||!handle.get().info().startInstant().orElseThrow().equals(resource.started()));}
    }
    @Test void malformedUtf8IsRejectedRatherThanReplacedInEvidence()throws Exception{
        var executor=executor();var method=ImportStartupModelExecutor.class.getDeclaredMethod("read",InputStream.class,UUID.class,long.class);method.setAccessible(true);
        var failure=assertThrows(java.lang.reflect.InvocationTargetException.class,()->method.invoke(executor,new ByteArrayInputStream(new byte[]{(byte)0xc3,0x28}),approval.id(),approval.appId()));
        assertInstanceOf(java.nio.charset.MalformedInputException.class,failure.getCause());
    }
}
