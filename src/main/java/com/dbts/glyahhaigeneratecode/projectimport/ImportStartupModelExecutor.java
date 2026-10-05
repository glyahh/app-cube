package com.dbts.glyahhaigeneratecode.projectimport;

import com.dbts.glyahhaigeneratecode.service.support.ProjectImportStaging;
import com.fasterxml.jackson.core.*;
import com.fasterxml.jackson.databind.*;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import org.springframework.stereotype.Component;
import java.io.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.time.Duration;
import java.security.MessageDigest;
import java.util.*;
import java.util.function.*;

/** 专属平台模型进程，不使用生成进程池，不赋予工程路径或脚本执行能力。 */
@Component
public class ImportStartupModelExecutor {
    public record Output(String json,ImportStartupSupplementResult.Validated validated){ }
    private record Frames(String result,String error){ }
    public static final class Failure extends IOException{
        private final String code;
        public Failure(String code){super("启动补充失败（"+code+"）。");this.code=code;}
        public String code(){return code;}
    }
    private static final List<String> HOST_ENV=List.of("SystemRoot","SYSTEMROOT","WINDIR","PATH","PATHEXT","SSL_CERT_FILE","REQUESTS_CA_BUNDLE","CURL_CA_BUNDLE");
    private static final List<String> MODEL_ENV=List.of("AGENT_MODEL_ID","AGENT_MODEL_BASE_URL","AGENT_MODEL_API_KEY","AGENT_MODEL_TEMPERATURE",
        "AGENT_MAX_OUTPUT_TOKENS","AGENT_CONTEXT_WINDOW","AGENT_MODEL_PROXY_ENABLED","AGENT_MODEL_PROXY_URL","DEEPSEEK_API_KEY",
        "AGENT_SUMMARIZER_MODEL_ID","AGENT_SUMMARIZER_BASE_URL","AGENT_SUMMARIZER_API_KEY");
    private final ObjectMapper json=new ObjectMapper(JsonFactory.builder().streamReadConstraints(StreamReadConstraints.builder().maxNestingDepth(32).build()).build())
        .enable(JsonParser.Feature.STRICT_DUPLICATE_DETECTION).registerModule(new JavaTimeModule()).disable(SerializationFeature.WRITE_DATES_AS_TIMESTAMPS);
    private final Path python,helper,root;
    private final ProjectImportStaging privateJobs;
    private final org.springframework.core.env.Environment springEnvironment;
    public ImportStartupModelExecutor(ImportProperties properties,org.springframework.core.env.Environment springEnvironment)throws IOException{
        python=properties.getCredentialPython().toAbsolutePath().normalize();helper=Path.of("scripts/import-startup-agent.py").toAbsolutePath().normalize();
        root=properties.getStagingRoot().resolveSibling("project_startup_model").toAbsolutePath().normalize();
        privateJobs=new ProjectImportStaging(root,List.of(Path.of("temp/code_output"),Path.of("temp/code_deploy"),properties.getStagingRoot()));
        this.springEnvironment=springEnvironment;
    }
    public Output execute(ImportStartupApprovalRepository.Approval approval,ImportStartupService.ModelInput model,BooleanSupplier eligible,Consumer<ImportProcessRunner.Resource> journal)throws IOException{
        var files=model.files();
        BooleanSupplier active=()->approval.expiresAt().isAfter(java.time.Instant.now())&&eligible.getAsBoolean();
        if(!approval.approved()||!"RUNNING".equals(approval.state())||approval.cancelRequested()||!active.getAsBoolean())throw new Failure("APPROVAL_REVOKED");
        ProjectImportStaging.guardManagedPath(python);ProjectImportStaging.guardManagedPath(helper);
        if(!Files.isRegularFile(python,LinkOption.NOFOLLOW_LINKS)||!Files.isRegularFile(helper,LinkOption.NOFOLLOW_LINKS))throw new Failure("MODEL_ENVIRONMENT_UNAVAILABLE");
        var context=new LinkedHashMap<String,Object>();
        context.put("approvalId",approval.id().toString());context.put("scanId",approval.scanId().toString());context.put("appId",Long.toString(approval.appId()));context.put("importTaskId",approval.importTaskId().toString());
        context.put("contentHash",approval.contentHash());context.put("configRevision",Long.toString(approval.configRevision()));context.put("contextEpoch",Long.toString(approval.contextEpoch()));context.put("directories",approval.directories());
        // 同目录多个未识别范围逐条列出，已有候选只供排除，模型不能合并或替换。
        context.put("ranges",model.ranges().stream().map(range->{var item=new LinkedHashMap<String,Object>();item.put("directory",range.directory());item.put("phase",range.phase());item.put("diagnostic",range.diagnostic());return item;}).toList());
        context.put("known",model.known().stream().map(known->{var item=new LinkedHashMap<String,Object>();item.put("name",known.name());item.put("directory",known.directory());item.put("command",known.command());return item;}).toList());
        context.put("files",Map.copyOf(files));
        String message=json.writeValueAsString(context);if(message.getBytes(StandardCharsets.UTF_8).length>64*1024)throw new Failure("MODEL_INPUT_LIMIT");
        byte[] input=json.writeValueAsBytes(Map.of("runId",approval.id().toString(),"appId",Long.toString(approval.appId()),"input",Map.of("taskKind","startup","message",message)));
        if(input.length>96*1024)throw new Failure("MODEL_INPUT_LIMIT");
        privateJobs.create(approval.id());Path job=root.resolve(approval.id().toString());boolean stopped=true;
        var protocolFailure=new java.util.concurrent.atomic.AtomicReference<Failure>();
        try{
            // 1. 平台私有输入封存，模型仅收到文本；不下发原工作区路径或历史会话。
            Files.write(job.resolve("input.json"),input,StandardOpenOption.CREATE_NEW,StandardOpenOption.WRITE);
            String hash;
            try{hash=HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(input));}catch(java.security.NoSuchAlgorithmException error){throw new IOException("输入摘要不可用。");}
            // 1. 子进程环境被清空，只放回证书所需的宿主变量和模型配置。配置来自 Spring 已加载的 .env，不写日志。
            var environment=processEnvironment();
            var resources=new ArrayList<ImportProcessRunner.Resource>();
            // 2. 一次性专属进程有独立 PID/启动时间与输入摘要，不终止共享池中的其他任务。
            var response=ImportProcessRunner.run(List.of(python.toString(),"-I",helper.toString(),job.resolve("input.json").toString(),hash),helper.getParent(),environment,
                Duration.ofSeconds(150),96*1024,8192,active,resource->{
                    journal.accept(resource);
                    synchronized(resources){resources.add(resource);try{json.writeValue(job.resolve("resources.json").toFile(),resources);}catch(IOException error){throw new IllegalStateException("模型资源登记失败。");}}
                },stream->{try{return read(stream,approval.id(),approval.appId());}catch(Failure failure){protocolFailure.set(failure);throw failure;}}).value();
            if(response.error()!=null||response.result()==null)throw new Failure("MODEL_RESPONSE_FAILED");
            if(!active.getAsBoolean())throw new Failure("APPROVAL_REVOKED");
            return new Output(response.result(),ImportStartupSupplementResult.validate(approval,files,response.result()));
        }catch(IllegalArgumentException error){throw new Failure("MODEL_CANDIDATE_INVALID");}
        catch(RuntimeException error){
            String code=ImportProcessRunner.errorCode(error);stopped=!"STOP_FAILED".equals(code);
            if(stopped&&protocolFailure.get()!=null)throw protocolFailure.get();
            throw new Failure("MODEL_EXECUTOR_"+code);
        }finally{Arrays.fill(input,(byte)0);if(stopped)privateJobs.cleanup(approval.id());}
    }
    private Frames read(InputStream stream,UUID run,long appId)throws IOException{
        var events=new ArrayList<String>();String result=null,error=null;long sequence=0;
        // 流由 ImportProcessRunner 管理，解析器不能提前关闭它，外层仍需核对 EOF。
        var reader=new BufferedReader(new InputStreamReader(stream,StandardCharsets.UTF_8.newDecoder()
            .onMalformedInput(java.nio.charset.CodingErrorAction.REPORT)
            .onUnmappableCharacter(java.nio.charset.CodingErrorAction.REPORT)));
            for(String line;(line=reader.readLine())!=null;){
                if(line.length()>64*1024)throw new Failure("MODEL_FRAME_LIMIT");var frame=json.readTree(line);
                if(frame==null||!frame.isObject()||!frame.path("v").isIntegralNumber()||frame.path("v").asInt()!=1
                    ||!run.toString().equals(frame.path("runId").asText())||!frame.path("seq").isIntegralNumber()||frame.path("seq").asLong()!=sequence++
                    ||!frame.path("ts").isIntegralNumber()||frame.path("ts").asLong()<=0)throw new Failure("MODEL_FRAME_IDENTITY");
                String event=frame.path("event").asText();events.add(event);if(events.size()>4)throw new Failure("MODEL_FRAME_SEQUENCE");
                switch(event){
                    case "run.started" -> {if(!"startup".equals(frame.path("taskKind").asText())||!Long.toString(appId).equals(frame.path("appId").asText()))throw new Failure("MODEL_FRAME_KIND");}
                    case "text.message" -> {if(!"startup-candidates".equals(frame.path("messageId").asText())||!frame.path("content").isTextual())throw new Failure("MODEL_FRAME_CONTENT");result=frame.path("content").textValue();}
                    case "run.completed" -> {if(!"succeeded".equals(frame.path("status").asText()))throw new Failure("MODEL_FRAME_STATUS");}
                    case "error" -> error="MODEL_FAILURE";
                    case "__end__" -> { }
                    default -> throw new Failure("MODEL_FRAME_EVENT");
                }
            }
        if(error!=null){if(!events.equals(List.of("error","__end__"))&&!events.equals(List.of("run.started","error","__end__")))throw new Failure("MODEL_FRAME_SEQUENCE");}
        else if(!events.equals(List.of("run.started","text.message","run.completed","__end__")))throw new Failure("MODEL_FRAME_SEQUENCE");
        return new Frames(result,error);
    }

    /** 只传递模型调用和本机证书需要的变量。空值不放行，值本身不进入日志。 */
    private Map<String,String> processEnvironment(){
        var environment=new HashMap<String,String>();
        // 1. 证书和系统路径仍取当前进程，避免清空环境后 HTTPS 找不到信任库。
        for(String key:HOST_ENV){
            String value=System.getenv(key);
            if(value!=null&&!value.isBlank())environment.put(key,value);
        }
        // 2. 模型地址和密钥优先用 Spring 从 .env 装入的配置，进程环境只作补充。
        for(String key:MODEL_ENV){
            String value=springEnvironment==null?null:springEnvironment.getProperty(key);
            if(value==null||value.isBlank())value=System.getenv(key);
            if(value!=null&&!value.isBlank())environment.put(key,value);
        }
        return environment;
    }

    public boolean inputAbsent(UUID id){return !Files.exists(root.resolve(id.toString()),LinkOption.NOFOLLOW_LINKS);}
    /** 调用者须先确认本进程树与执行者已经停止。 */
    public void cleanupStoppedInput(UUID id)throws IOException{privateJobs.cleanup(id);}
}
