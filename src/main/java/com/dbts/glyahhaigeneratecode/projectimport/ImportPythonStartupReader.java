package com.dbts.glyahhaigeneratecode.projectimport;

import com.dbts.glyahhaigeneratecode.service.support.ProjectImportStaging;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import org.springframework.stereotype.Component;
import java.io.IOException;
import java.nio.file.*;
import java.time.Duration;
import java.security.MessageDigest;
import java.util.*;
import java.util.function.BooleanSupplier;

/** 受管调用平台标准库解析器，不 import 工程，不加载工程环境或插件。 */
@Component
public class ImportPythonStartupReader {
    public record Parsed(List<ImportStartupScanner.Candidate> candidates,List<ImportStartupScanner.Scope> scopes){ }
    public record Response(String inputHash,Map<String,Parsed> results){ }
    public static final class Failure extends IOException {
        private final String code;
        public Failure(String code){super("Python 识别失败（"+code+"）。");this.code=code;}
        public String code(){return code;}
    }
    private final Path python,helper,root;
    private final ProjectImportStaging privateJobs;
    private final ObjectMapper json=new ObjectMapper().registerModule(new JavaTimeModule()).disable(SerializationFeature.WRITE_DATES_AS_TIMESTAMPS);
    public ImportPythonStartupReader(ImportProperties properties)throws IOException{
        python=properties.getCredentialPython().toAbsolutePath().normalize();
        helper=Path.of("scripts/import-startup-python.py").toAbsolutePath().normalize();
        root=properties.getStagingRoot().resolveSibling("project_startup_python").toAbsolutePath().normalize();
        privateJobs=new ProjectImportStaging(root,List.of(Path.of("temp/code_output"),Path.of("temp/code_deploy"),properties.getStagingRoot()));
    }

    public ImportStartupScanner.Result supplement(ImportStartupScanner.Result original,Map<String,String> files,BooleanSupplier eligible)throws IOException{
        return supplement(original,files,eligible,UUID.randomUUID(),resource->{});
    }
    public ImportStartupScanner.Result supplement(ImportStartupScanner.Result original,Map<String,String> files,BooleanSupplier eligible,UUID id,java.util.function.Consumer<ImportProcessRunner.Resource> journal)throws IOException{
        var directories=original.scopes().stream().filter(scope->"PYTHON_RULES_NOT_IMPLEMENTED".equals(scope.diagnostic()))
            .map(ImportStartupScanner.Scope::directory).distinct().sorted().toList();
        if(directories.isEmpty())return original;
        if(directories.size()>20)throw new IOException("Python 工程数量超限，未返回部分结果。");
        ProjectImportStaging.guardManagedPath(python);ProjectImportStaging.guardManagedPath(helper);
        if(!Files.isRegularFile(python,LinkOption.NOFOLLOW_LINKS)||!Files.isRegularFile(helper,LinkOption.NOFOLLOW_LINKS))throw new IOException("Python 解析环境不可用。");
        privateJobs.create(id);Path job=root.resolve(id.toString());boolean stopped=true;
        try {
            // 1. 只写平台私有输入，工程路径只是 JSON 键，不作为加载或执行目标。
            byte[] input=json.writeValueAsBytes(Map.of("files",files,"directories",directories));
            String inputHash;
            try {
                if(input.length>4*1024*1024)throw new IOException("Python 解析输入超限。");
                inputHash=HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(input));
                Files.write(job.resolve("input.json"),input,StandardOpenOption.CREATE_NEW,StandardOpenOption.WRITE);
            }catch(java.security.NoSuchAlgorithmException error){throw new IOException("输入摘要不可用。");
            }finally{Arrays.fill(input,(byte)0);}
            var environment=new HashMap<String,String>();
            if(System.getenv("SystemRoot")!=null)environment.put("SystemRoot",System.getenv("SystemRoot"));
            var resources=new ArrayList<ImportProcessRunner.Resource>();
            // 2. -I/-S 隔离项目路径、用户站点与 site 初始化；登记真实 PID/启动时间，超时与取消受管停止。
            var response=ImportProcessRunner.run(List.of(python.toString(),"-I","-S",helper.toString(),job.resolve("input.json").toString()),
                helper.getParent(),environment,Duration.ofSeconds(30),1024*1024,8192,eligible,resource->{
                    journal.accept(resource);
                    synchronized(resources){resources.add(resource);try{json.writeValue(job.resolve("resources.json").toFile(),resources);}catch(IOException error){throw new IllegalStateException("资源登记失败。");}}
                },stream->json.readValue(stream.readAllBytes(),Response.class)).value();
            if(response==null||!inputHash.equals(response.inputHash())||response.results()==null)throw new IOException("Python 输入版本不一致。");
            var parsed=response.results();
            if(!parsed.keySet().equals(new HashSet<>(directories)))throw new IOException("Python 解析范围不一致。");
            var candidates=new ArrayList<>(original.candidates());var scopes=new ArrayList<ImportStartupScanner.Scope>();
            for(var scope:original.scopes())if(!directories.contains(scope.directory())||!"PYTHON_RULES_NOT_IMPLEMENTED".equals(scope.diagnostic()))scopes.add(scope);
            for(String dir:directories){
                Parsed result=parsed.get(dir);
                if(result==null||result.candidates()==null||result.scopes()==null||result.candidates().size()>100||result.scopes().isEmpty())throw new IOException("Python 解析结果无效。");
                for(var candidate:result.candidates())if(!dir.equals(candidate.directory())||!Set.of("DECLARED","HEURISTIC").contains(candidate.source())
                    ||candidate.command()==null||candidate.command().length()>1024||candidate.command().chars().anyMatch(Character::isISOControl))throw new IOException("Python 候选无效。");
                for(var diagnostic:result.scopes())if(!dir.equals(diagnostic.directory())||!Set.of("FIXED","ALGORITHM").contains(diagnostic.phase())
                    ||!Set.of("IDENTIFIED","UNIDENTIFIED","AMBIGUOUS","INCOMPLETE","FAILED").contains(diagnostic.status()))throw new IOException("Python 诊断无效。");
                candidates.addAll(result.candidates());scopes.addAll(result.scopes());
            }
            return new ImportStartupScanner.Result(original.inputHash(),candidates,scopes);
        }catch(RuntimeException error){
            stopped=!"STOP_FAILED".equals(ImportProcessRunner.errorCode(error));
            throw new Failure("PYTHON_READER_"+ImportProcessRunner.errorCode(error));
        }finally{
            if(stopped)privateJobs.cleanup(id);
        }
    }
    public boolean inputAbsent(UUID id){return !Files.exists(root.resolve(id.toString()),LinkOption.NOFOLLOW_LINKS);}
    public void cleanupStoppedInput(UUID id)throws IOException{privateJobs.cleanup(id);}
}
