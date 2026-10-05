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

    private JsonNode invoke(String action,long appId,String hash,long bytes,List<String> extra)throws IOException{
        if(appId<=0||hash==null||!hash.matches("[0-9a-f]{64}")||bytes<16L<<20||bytes>8L<<30||bytes%4096!=0)throw new IOException("DISK_VOLUME_IDENTITY_INVALID");
        var args=new ArrayList<>(List.of("python3",engine.path(helper),action,"--app-id",Long.toString(appId),"--content-hash",hash,"--bytes",Long.toString(bytes)));
        args.addAll(extra);
        var result=engine.platform(args,180);
        if(result==null||!result.ok()){
            String reason="";
            if(result!=null)try{reason=json.readTree(result.stdout()).path("reason").asText();}catch(IOException|RuntimeException ignored){ }
            throw new IOException(reason.matches("[A-Z][A-Z0-9_:]{0,100}")?"DISK_VOLUME_"+reason:"DISK_VOLUME_"+action.toUpperCase(java.util.Locale.ROOT)+"_FAILED");
        }
        var record=json.readTree(result.stdout());
        if(record==null||!Long.toString(appId).equals(record.path("appId").asText())||!hash.equals(record.path("contentHash").asText())
            ||record.path("diskBytes").asLong()!=bytes||record.path("formatVersion").asInt()!=2)throw new IOException("DISK_VOLUME_IDENTITY_CONFLICT");
        return record;
    }
}
