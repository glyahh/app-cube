package com.dbts.glyahhaigeneratecode.projectimport;

import com.fasterxml.jackson.core.*;
import com.fasterxml.jackson.databind.*;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.*;

/** 校验只读模型回包；候选仍是不可信推断，不授予任何命令执行权限。 */
public final class ImportStartupSupplementResult {
    public record Validated(List<ImportStartupScanner.Candidate> candidates,List<String> unidentified){
        public Validated{candidates=List.copyOf(candidates);unidentified=List.copyOf(unidentified);}
    }
    private static final ObjectMapper JSON=new ObjectMapper(JsonFactory.builder().streamReadConstraints(
        StreamReadConstraints.builder().maxNestingDepth(32).maxStringLength(16*1024).build()).build()).enable(JsonParser.Feature.STRICT_DUPLICATE_DETECTION);
    private ImportStartupSupplementResult(){ }
    public static Validated validate(ImportStartupApprovalRepository.Approval approval,Map<String,String> input,String output){
        if(approval==null||!approval.approved()||!"RUNNING".equals(approval.state())||approval.cancelRequested()||!approval.expiresAt().isAfter(java.time.Instant.now()))throw invalid();
        return parse(approval,input,output,32*1024);
    }
    /** 发布后读取：仅 SUCCEEDED 记录，依据须对应当前封存文本；批准时限只约束执行，不约束已发布结果。 */
    public static Validated published(ImportStartupApprovalRepository.Approval approval,Map<String,String> input){
        if(approval==null||!approval.approved()||!"SUCCEEDED".equals(approval.state())||approval.cancelRequested())throw invalid();
        // JSONB 读回会规范化空白，限额放宽到原回包上限两倍；字段与依据规则不变。
        return parse(approval,input,approval.result(),64*1024);
    }
    private static Validated parse(ImportStartupApprovalRepository.Approval approval,Map<String,String> input,String output,int limit){
        if(input==null||input.isEmpty()||output==null||output.getBytes(StandardCharsets.UTF_8).length>limit)throw invalid();
        try{
            JsonNode root=JSON.readTree(output);
            fields(root,Set.of("approvalId","scanId","appId","importTaskId","contentHash","configRevision","contextEpoch","services","unidentified"));
            // 1. 回包只能沿用本批准冻结身份，不能靠模型提供另一应用或旧版本。
            var identity=Map.of("approvalId",approval.id().toString(),"scanId",approval.scanId().toString(),"appId",Long.toString(approval.appId()),
                "importTaskId",approval.importTaskId().toString(),"contentHash",approval.contentHash(),"configRevision",Long.toString(approval.configRevision()),"contextEpoch",Long.toString(approval.contextEpoch()));
            for(var entry:identity.entrySet())if(!entry.getValue().equals(text(root.get(entry.getKey()),128)))throw invalid();
            var services=root.get("services");var unidentified=root.get("unidentified");
            if(!services.isArray()||services.size()>20||!unidentified.isArray()||unidentified.size()>20)throw invalid();
            var directories=new HashSet<String>();var names=new HashSet<String>();var commands=new HashSet<List<String>>();var candidates=new ArrayList<ImportStartupScanner.Candidate>();
            for(var service:services){
                fields(service,Set.of("name","directory","command","build","evidence","source","certainty","evidenceHashes"));
                String directory=text(service.get("directory"),512),name=text(service.get("name"),80),command=text(service.get("command"),1024);
                if(!approval.directories().contains(directory)||!names.add(name)||!commands.add(List.of(directory,command))
                    ||!"AGENT".equals(text(service.get("source"),32))||!"INFERRED".equals(text(service.get("certainty"),32)))throw invalid();
                String build=service.get("build").isNull()?null:text(service.get("build"),1024);
                var evidence=service.get("evidence");var hashes=service.get("evidenceHashes");var paths=new LinkedHashSet<String>();
                if(!evidence.isArray()||evidence.isEmpty()||evidence.size()>10||!hashes.isObject())throw invalid();
                // 2. 依据必须对应本次批准的实际文本；SHA 不代表模型推断已经成为事实。
                for(var entry:evidence){String path=text(entry,512);
                    if(!paths.add(path)||!input.containsKey(path)||!sha(input.get(path)).equals(text(hashes.get(path),64)))throw invalid();}
                fields(hashes,paths);
                directories.add(directory);
                String id="agent:"+approval.id()+":"+sha(directory+"\0"+name+"\0"+command);
                candidates.add(new ImportStartupScanner.Candidate(id,name,directory,command,build,"AGENT",String.join(" + ",paths)));
            }
            var unknown=new ArrayList<String>();
            for(var entry:unidentified){String directory=text(entry,512);if(!approval.directories().contains(directory)||!directories.add(directory))throw invalid();unknown.add(directory);}
            if(!directories.equals(new HashSet<>(approval.directories())))throw invalid();
            return new Validated(candidates,unknown);
        }catch(java.io.IOException|RuntimeException error){throw invalid();}
    }
    private static void fields(JsonNode value,Set<String> expected){if(value==null||!value.isObject())throw invalid();var actual=new HashSet<String>();value.fieldNames().forEachRemaining(actual::add);if(!actual.equals(expected))throw invalid();}
    private static String text(JsonNode value,int limit){if(value==null||!value.isTextual())throw invalid();String text=value.textValue();if(text.isBlank()||text.length()>limit||text.chars().anyMatch(Character::isISOControl))throw invalid();return text;}
    private static String sha(String text){try{return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(text.getBytes(StandardCharsets.UTF_8)));}catch(java.security.NoSuchAlgorithmException error){throw new IllegalStateException(error);}}
    private static IllegalArgumentException invalid(){return new IllegalArgumentException("启动补充回包不符合本次批准，请重新识别。");}
}
