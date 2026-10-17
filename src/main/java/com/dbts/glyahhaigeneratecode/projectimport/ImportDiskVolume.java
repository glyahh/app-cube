package com.dbts.glyahhaigeneratecode.projectimport;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.io.IOException;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

/** 受限卷平台客户端；失败明确阻止执行，不改用无限额目录。 */
public final class ImportDiskVolume {
    private final ImportExecutionSandbox.Engine engine;
    private final Path helper;
    private final ObjectMapper json=new ObjectMapper();
    public ImportDiskVolume(ImportExecutionSandbox.Engine engine,Path helper){this.engine=engine;this.helper=helper.toAbsolutePath().normalize();}

    public String prepare(long appId,String hash,long bytes,Path source,Path manifest)throws IOException{
        // 1. 创建或核对同身份卷，再从已封存的独立副本初始化，复用时不覆盖安装产物。
        try{invoke("create",appId,hash,bytes,List.of());}
        catch(IOException error){
            if(!"DISK_VOLUME_VOLUME_NOT_MOUNTED".equals(error.getMessage())&&!"DISK_VOLUME_VOLUME_UNRESOLVED".equals(error.getMessage()))throw error;
            invoke("recover",appId,hash,bytes,List.of());
        }
        var result=invoke("seed",appId,hash,bytes,List.of("--source",engine.path(source),"--manifest",engine.path(manifest)));
        String path="/var/lib/glyahh/import-disks/"+appId+"/work";
        if(!"READY".equals(result.path("state").asText())||!"SEALED".equals(result.path("seedState").asText())
            ||!path.equals(result.path("mountPath").asText())||result.path("actualBytes").asLong()!=bytes
            ||!result.path("journal").asBoolean()||result.path("reservedBytes").asLong()<bytes)throw new IOException("DISK_VOLUME_NOT_VERIFIED");
        return path;
    }

    public boolean release(long appId,String hash,long bytes){
        try{return "RELEASED".equals(invoke("release",appId,hash,bytes,List.of()).path("state").asText());}
        catch(IOException|RuntimeException error){return false;}
    }

    public void inspect(long appId,String hash,long bytes)throws IOException{
        var result=invoke("inspect",appId,hash,bytes,List.of());
        if(!"SEALED".equals(result.path("seedState").asText()))throw new IOException("DISK_VOLUME_SEED_NOT_SEALED");
    }

    public JsonNode createEdit(ImportAgentEditLedger.Entry entry,long bytes)throws IOException{
        var result=invokeEdit("create",entry,bytes);
        String path=editPath(entry);
        if(!"READY".equals(result.path("state").asText())||!path.equals(result.path("mountPath").asText())
                ||!result.path("actualBytes").isIntegralNumber()||result.path("actualBytes").asLong()!=bytes
                ||!result.path("journal").isBoolean()||!result.path("journal").asBoolean()
                ||!result.path("reservedBytes").isIntegralNumber()||result.path("reservedBytes").asLong()<bytes
                ||!result.path("loopDevice").asText().matches("/dev/loop[0-9]+"))throw new IOException("DISK_VOLUME_NOT_VERIFIED");
        return result;
    }

    public void releaseEdit(ImportAgentEditLedger.Entry entry,long bytes)throws IOException{
        if(!"RELEASED".equals(invokeEdit("release",entry,bytes).path("state").asText()))throw new IOException("DISK_VOLUME_RELEASE_UNCONFIRMED");
    }

    /** 平台只启动已登记容器，并独立读回CPU/内存/进程/swap硬限额。 */
    public JsonNode startEditContainer(ImportAgentEditLedger.Entry entry,long bytes,String containerId)throws IOException{
        if(containerId==null||!containerId.matches("[0-9a-f]{64}"))throw new IOException("DISK_VOLUME_IDENTITY_INVALID");
        var result=invokeEdit("edit-container-start",entry,bytes,List.of("--container-id",containerId));
        if(!"CONTAINER_RUNNING".equals(result.path("state").asText())||!containerId.equals(result.path("containerId").asText()))
            throw new IOException("DISK_VOLUME_CONTAINER_START_UNCONFIRMED");
        var limits=result.path("limits");
        for(String key:List.of("memoryBytes","swapBytes","pids","cpuQuota","cpuPeriod"))
            if(!limits.path(key).isIntegralNumber()||!limits.path(key).canConvertToLong())throw new IOException("DISK_VOLUME_LIMITS_UNCONFIRMED");
        if(limits.path("memoryBytes").asLong()<=0||limits.path("memoryBytes").asLong()>1L<<30||limits.path("swapBytes").asLong()!=0
                ||limits.path("pids").asLong()<=0||limits.path("pids").asLong()>128||limits.path("cpuQuota").asLong()<=0
                ||limits.path("cpuQuota").asLong()>limits.path("cpuPeriod").asLong())throw new IOException("DISK_VOLUME_LIMITS_UNCONFIRMED");
        return result;
    }

    /** 仅停止已持久登记且身份/挂载匹配的编辑容器，未知资源保留待恢复。 */
    public JsonNode stopEditContainers(ImportAgentEditLedger.Entry entry,long bytes)throws IOException{
        var result=invokeEdit("edit-container-stop",entry,bytes);
        if(!"STOP_CONFIRMED".equals(result.path("state").asText())
                ||!("glyahh-edit-"+entry.id()).equals(result.path("containerName").asText())
                ||!result.has("containerId")||!result.path("containerId").isNull()
                    &&(!result.path("containerId").isTextual()||!result.path("containerId").asText().matches("[0-9a-f]{64}")))
            throw new IOException("EDIT_CONTAINER_STOP_UNCONFIRMED");
        return result;
    }

    public JsonNode createEditContainer(ImportAgentEditLedger.Entry entry,long bytes)throws IOException{
        var result=invokeEdit("edit-container-create",entry,bytes);
        if(!"CONTAINER_CREATED".equals(result.path("state").asText())||!result.path("containerId").isTextual()
                ||!result.path("containerId").asText().matches("[0-9a-f]{64}"))throw new IOException("EDIT_CONTAINER_CREATE_UNCONFIRMED");
        return result;
    }

    public JsonNode prepareEditView(ImportAgentEditLedger.Entry entry,long bytes,Path source)throws IOException{
        var result=invokeEdit("edit-view",entry,bytes,List.of("--source",engine.path(source)));
        if(!"READY".equals(result.path("state").asText())||!"SEALED".equals(result.path("viewState").asText())
                ||!editPath(entry).equals(result.path("mountPath").asText())
                ||!(editPath(entry)+"/view").equals(result.path("viewPath").asText())
                ||!result.path("actualBytes").isIntegralNumber()||result.path("actualBytes").asLong()!=bytes
                ||!result.path("reservedBytes").isIntegralNumber()||result.path("reservedBytes").asLong()<bytes
                ||!result.path("journal").isBoolean()||!result.path("journal").asBoolean()
                ||!result.path("loopDevice").asText().matches("/dev/loop[0-9]+"))throw new IOException("DISK_VOLUME_VIEW_NOT_VERIFIED");
        for(String key:List.of("viewHash","viewSourceHash","viewStateHash"))
            if(!result.path(key).isTextual()||!result.path(key).asText().matches("[0-9a-f]{64}"))throw new IOException("DISK_VOLUME_VIEW_NOT_VERIFIED");
        return result;
    }

    public static String editPath(ImportAgentEditLedger.Entry entry){
        return "/var/lib/glyahh/import-disks/edit-"+entry.id()+"/work";
    }

    String platformPath(Path source){return engine.path(source);}

    /** 私有正文只留在Java平台内存；不返回给模型、公共控制器或日志。 */
    public byte[] readEditDocument(ImportAgentEditLedger.Entry entry,long bytes,String stateHash,String candidateHash,
            String manifestHash,String part,java.util.function.BooleanSupplier eligible)throws IOException{
        if(!List.of("manifest","private-state","inventory","source-inventory","view-inventory").contains(part))throw new IOException("DISK_VOLUME_READ_INVALID");
        var first=readEditChunk(entry,bytes,stateHash,candidateHash,manifestHash,part,null,0,eligible);
        String expectedHash=part.equals("manifest")?manifestHash:part.equals("inventory")?candidateHash:
                part.equals("private-state")?stateHash:first.hash();
        if(first.size()>32L<<20||!expectedHash.equals(first.hash()))throw new IOException("DISK_VOLUME_READ_INVALID");
        var output=new java.io.ByteArrayOutputStream();output.write(first.data());long offset=first.data().length;
        while(offset<first.size()){
            var chunk=readEditChunk(entry,bytes,stateHash,candidateHash,manifestHash,part,null,offset,eligible);
            if(chunk.size()!=first.size()||!chunk.hash().equals(first.hash()))throw new IOException("DISK_VOLUME_READ_CHANGED");
            output.write(chunk.data());offset+=chunk.data().length;
        }
        byte[] result=output.toByteArray();
        if(!sha256(result).equals(expectedHash))throw new IOException("DISK_VOLUME_READ_CHANGED");
        return result;
    }

    /** 实际目录和文件流接入独立校验器；不能用manifest文件集合代替真实目录。 */
    public ImportAgentCandidateVerifier.FilesAccess candidateAccess(ImportAgentEditLedger.Entry entry,long bytes,
            String stateHash,String candidateHash,String manifestHash,ImportAgentCandidateVerifier verifier,
            java.util.function.BooleanSupplier eligible){
        return privateAccess(entry,bytes,stateHash,candidateHash,manifestHash,verifier,eligible,"inventory","file");
    }

    /** 读取平台冻结的原来源，原秘密只在Java平台侧用于独立恢复。 */
    public ImportAgentCandidateVerifier.FilesAccess sourceAccess(ImportAgentEditLedger.Entry entry,long bytes,
            String stateHash,String candidateHash,String manifestHash,ImportAgentCandidateVerifier verifier,
            java.util.function.BooleanSupplier eligible){
        return privateAccess(entry,bytes,stateHash,candidateHash,manifestHash,verifier,eligible,"source-inventory","source-file");
    }

    /** 完整视图用于复核授权及独立产物范围；文件读取仍限定已授权文件。 */
    public ImportAgentCandidateVerifier.FilesAccess viewAccess(ImportAgentEditLedger.Entry entry,long bytes,
            String stateHash,String candidateHash,String manifestHash,ImportAgentCandidateVerifier verifier,
            java.util.function.BooleanSupplier eligible){
        return privateAccess(entry,bytes,stateHash,candidateHash,manifestHash,verifier,eligible,"view-inventory","view-file");
    }

    private ImportAgentCandidateVerifier.FilesAccess privateAccess(ImportAgentEditLedger.Entry entry,long bytes,
            String stateHash,String candidateHash,String manifestHash,ImportAgentCandidateVerifier verifier,
            java.util.function.BooleanSupplier eligible,String inventoryPart,String filePart){
        return new ImportAgentCandidateVerifier.FilesAccess(){
            private ImportAgentCandidateVerifier.Inventory actual;
            @Override public ImportAgentCandidateVerifier.Inventory inventory()throws IOException{
                requireReadEligibility(eligible);
                byte[] body=readEditDocument(entry,bytes,stateHash,candidateHash,manifestHash,inventoryPart,eligible);
                actual=verifier.readInventory(body,inventoryPart.equals("inventory")?candidateHash:sha256(body));
                requireReadEligibility(eligible);
                return actual;
            }
            @Override public java.io.InputStream open(String relative)throws IOException{
                requireReadEligibility(eligible);
                var stamp=actual==null?null:actual.files().get(relative);
                if(stamp==null)throw new IOException("DISK_VOLUME_READ_INVALID");
                return openEditFile(entry,bytes,stateHash,candidateHash,manifestHash,filePart,relative,stamp.bytes(),stamp.sha256(),eligible);
            }
        };
    }

    /** 候选及授权视图文件逐块读取，资格每块复核；最终完整摘要由独立校验器计算。 */
    public java.io.InputStream openEditFile(ImportAgentEditLedger.Entry entry,long bytes,String stateHash,String candidateHash,
            String manifestHash,String part,String name,long size,String hash,java.util.function.BooleanSupplier eligible)throws IOException{
        if(!List.of("file","view-file","source-file","output-file").contains(part)||name==null||name.isEmpty()||name.startsWith("/")||name.contains("\\")
                ||java.util.Arrays.stream(name.split("/",-1)).anyMatch(p->p.isEmpty()||p.equals(".")||p.equals(".."))
                ||size<0||size>8L<<30||hash==null||!hash.matches("[0-9a-f]{64}"))throw new IOException("DISK_VOLUME_READ_INVALID");
        return new java.io.InputStream(){
            private long offset;
            private byte[] buffer=new byte[0];private int at;private boolean closed,inspected;
            @Override public int read()throws IOException{byte[] one=new byte[1];return read(one,0,1)<0?-1:one[0]&255;}
            @Override public int read(byte[] target,int start,int length)throws IOException{
                java.util.Objects.checkFromIndexSize(start,length,target.length);
                if(closed)throw new IOException("DISK_VOLUME_READ_CLOSED");
                if(length==0)return 0;
                requireReadEligibility(eligible);
                if(at==buffer.length){
                    if(offset==size&&inspected)return -1;
                    var chunk=readEditChunk(entry,bytes,stateHash,candidateHash,manifestHash,part,name,offset,eligible);
                    if(chunk.size()!=size||!chunk.hash().equals(hash))throw new IOException("DISK_VOLUME_READ_CHANGED");
                    buffer=chunk.data();at=0;offset+=buffer.length;inspected=true;
                    if(buffer.length==0)return -1;
                }
                int count=Math.min(length,buffer.length-at);System.arraycopy(buffer,at,target,start,count);at+=count;return count;
            }
            @Override public void close(){closed=true;buffer=new byte[0];}
        };
    }

    private record PrivateChunk(long size,String hash,byte[] data){ }
    private PrivateChunk readEditChunk(ImportAgentEditLedger.Entry entry,long bytes,String stateHash,String candidateHash,
            String manifestHash,String part,String name,long offset,java.util.function.BooleanSupplier eligible)throws IOException{
        requireReadEligibility(eligible);
        for(String value:new String[]{stateHash,candidateHash,manifestHash})
            if(value==null||!value.matches("[0-9a-f]{64}"))throw new IOException("DISK_VOLUME_READ_INVALID");
        var extra=new ArrayList<>(List.of("--state-hash",stateHash,"--candidate-hash",candidateHash,"--manifest-hash",manifestHash,
                "--part",part,"--offset",Long.toString(offset),"--length","262144"));
        if(name!=null)extra.addAll(List.of("--file-path",name));
        var record=invokeEdit("candidate-read",entry,bytes,extra);
        if(!"PRIVATE_CHUNK".equals(record.path("state").asText())||!part.equals(record.path("part").asText())
                ||(name==null?!record.path("path").isNull():!record.path("path").isTextual()||!name.equals(record.path("path").asText()))
                ||!record.path("offset").isIntegralNumber()||!record.path("offset").canConvertToLong()||record.path("offset").asLong()!=offset
                ||!record.path("size").isIntegralNumber()||!record.path("size").canConvertToLong()
                ||record.path("size").asLong()<offset||record.path("size").asLong()>8L<<30
                ||!record.path("sha256").isTextual()||!record.path("sha256").asText().matches("[0-9a-f]{64}")
                ||!record.path("authorizedStateHash").isTextual()||!stateHash.equals(record.path("authorizedStateHash").asText())
                ||!record.path("candidateHash").isTextual()||!candidateHash.equals(record.path("candidateHash").asText())
                ||!record.path("candidateManifestHash").isTextual()||!manifestHash.equals(record.path("candidateManifestHash").asText())||!record.path("data").isTextual()
                ||record.path("data").asText().length()>349528)throw new IOException("DISK_VOLUME_READ_INVALID");
        byte[] data;
        try{data=java.util.Base64.getDecoder().decode(record.path("data").asText());}
        catch(IllegalArgumentException error){throw new IOException("DISK_VOLUME_READ_INVALID");}
        if(data.length!=Math.min(262144L,record.path("size").asLong()-offset))throw new IOException("DISK_VOLUME_READ_CHANGED");
        requireReadEligibility(eligible);
        return new PrivateChunk(record.path("size").asLong(),record.path("sha256").asText(),data);
    }
    private static void requireReadEligibility(java.util.function.BooleanSupplier eligible)throws IOException{
        if(eligible==null||!eligible.getAsBoolean())throw new IOException("DISK_VOLUME_READ_REVOKED");
    }
    private static String sha256(byte[] bytes){
        try{return java.util.HexFormat.of().formatHex(java.security.MessageDigest.getInstance("SHA-256").digest(bytes));}
        catch(java.security.NoSuchAlgorithmException error){throw new IllegalStateException("摘要不可用。");}
    }

    /** 获取平台封存回执；业务资格、清单内容和最终发布仍由Java独立验证。 */
    public JsonNode sealEditCandidate(ImportAgentEditLedger.Entry entry,long bytes,String stateHash)throws IOException{
        if(stateHash==null||!stateHash.matches("[0-9a-f]{64}"))throw new IOException("DISK_VOLUME_STATE_HASH_INVALID");
        var result=invokeEdit("edit-candidate",entry,bytes,List.of("--state-hash",stateHash));
        if(!"CANDIDATE_SEALED".equals(result.path("state").asText())
                ||!(editPath(entry)+"/candidate").equals(result.path("candidatePath").asText())
                ||!(editPath(entry)+"/candidate-manifest.json").equals(result.path("candidateManifestPath").asText())
                ||!result.path("authorizedStateHash").isTextual()
                ||!stateHash.equals(result.path("authorizedStateHash").asText()))throw new IOException("DISK_VOLUME_CANDIDATE_NOT_VERIFIED");
        for(String key:List.of("candidateHash","candidateManifestHash"))
            if(!result.path(key).isTextual()||!result.path(key).asText().matches("[0-9a-f]{64}"))throw new IOException("DISK_VOLUME_CANDIDATE_NOT_VERIFIED");
        return result;
    }

    private JsonNode invokeEdit(String action,ImportAgentEditLedger.Entry entry,long bytes)throws IOException{
        return invokeEdit(action,entry,bytes,List.of());
    }

    public JsonNode inspectAction(ImportAgentEditLedger.Entry entry, long bytes, JsonNode action) throws IOException {
        Path directory = Path.of("temp", "agent-permission-private").toAbsolutePath();
        java.nio.file.Files.createDirectories(directory);
        Path request = java.nio.file.Files.createTempFile(directory, "action-", ".json");
        try {
            byte[] raw = ImportAgentCanonicalJson.bytes(action);
            if (raw.length > 1 << 20) throw new IOException("IMPORT_ACTION_LIMIT");
            java.nio.file.Files.write(request, raw);
            return invokeEdit("action-inspect", entry, bytes, List.of("--request-file", engine.path(request)));
        } finally {
            java.nio.file.Files.deleteIfExists(request);
        }
    }

    public JsonNode reviewCandidate(ImportAgentEditLedger.Row row, JsonNode proof) throws IOException {
        long bytes = json.readTree(row.resources()).get(0).path("diskBytes").asLong();
        return invokeEdit("candidate-review", row.identity(), bytes, List.of("--state-hash", proof.path("authorizedStateHash").asText(),
                "--candidate-hash", proof.path("candidateHash").asText(), "--manifest-hash", proof.path("candidateManifestHash").asText()));
    }

    private JsonNode invokeEdit(String action,ImportAgentEditLedger.Entry entry,long bytes,List<String> extra)throws IOException{
        if(entry==null||bytes<16L<<20||bytes>8L<<30||bytes%4096!=0)throw new IOException("DISK_VOLUME_IDENTITY_INVALID");
        var args=new ArrayList<>(List.of("python3",engine.path(helper),action,"--app-id",Long.toString(entry.appId()),
                "--content-hash",entry.sourceHash(),"--bytes",Long.toString(bytes),"--owner-id",Long.toString(entry.ownerId()),
                "--run-id",entry.runId(),"--edit-id",entry.id().toString()));
        args.addAll(extra);
        var record=execute(args,action);
        for(String key:List.of("appId","ownerId","runId","editId","contentHash"))
            if(record==null||!record.path(key).isTextual())throw new IOException("DISK_VOLUME_IDENTITY_CONFLICT");
        if(record==null||!Long.toString(entry.appId()).equals(record.path("appId").asText())
                ||!Long.toString(entry.ownerId()).equals(record.path("ownerId").asText())
                ||!entry.runId().equals(record.path("runId").asText())||!entry.id().toString().equals(record.path("editId").asText())
                ||!entry.sourceHash().equals(record.path("contentHash").asText())||!record.path("diskBytes").isIntegralNumber()
                ||!record.path("diskBytes").canConvertToLong()||record.path("diskBytes").asLong()!=bytes||!record.path("formatVersion").isIntegralNumber()
                ||!record.path("formatVersion").canConvertToInt()
                ||record.path("formatVersion").asInt()!=3)throw new IOException("DISK_VOLUME_IDENTITY_CONFLICT");
        return record;
    }

    private JsonNode invoke(String action,long appId,String hash,long bytes,List<String> extra)throws IOException{
        if(appId<=0||hash==null||!hash.matches("[0-9a-f]{64}")||bytes<16L<<20||bytes>8L<<30||bytes%4096!=0)throw new IOException("DISK_VOLUME_IDENTITY_INVALID");
        var args=new ArrayList<>(List.of("python3",engine.path(helper),action,"--app-id",Long.toString(appId),"--content-hash",hash,"--bytes",Long.toString(bytes)));
        args.addAll(extra);
        var record=execute(args,action);
        if(record==null||!Long.toString(appId).equals(record.path("appId").asText())||!hash.equals(record.path("contentHash").asText())
            ||record.path("diskBytes").asLong()!=bytes||record.path("formatVersion").asInt()!=2)throw new IOException("DISK_VOLUME_IDENTITY_CONFLICT");
        return record;
    }

    private JsonNode execute(List<String> args,String action)throws IOException{
        var result=ImportInstallDiagnostics.call("VOLUME_"+action.toUpperCase(java.util.Locale.ROOT).replace("-","_"),()->engine.platform(args,180));
        if(result==null||!result.ok()){
            String reason="";
            if(result!=null)try{reason=json.readTree(result.stdout()).path("reason").asText();}catch(IOException|RuntimeException ignored){ }
            throw new IOException(reason.matches("[A-Z][A-Z0-9_:]{0,100}")?"DISK_VOLUME_"+reason:"DISK_VOLUME_"+action.toUpperCase(java.util.Locale.ROOT)+"_FAILED");
        }
        return json.readTree(result.stdout());
    }
}
