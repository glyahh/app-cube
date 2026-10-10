package com.dbts.glyahhaigeneratecode.projectimport;

import com.dbts.glyahhaigeneratecode.service.support.ProjectImportStaging;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.io.*;
import java.nio.file.*;
import java.security.MessageDigest;
import java.util.*;
import java.util.function.BooleanSupplier;

/** 已验证执行产物的受管副本；不作为源码授权，也不由模型选择宿主路径。 */
final class ImportAgentOutputResources {
    private final ImportAgentEditLedger ledger;
    private final ImportDiskVolume volumes;
    private final Path root;
    private final ObjectMapper json=new ObjectMapper();

    ImportAgentOutputResources(ImportAgentEditLedger ledger,ImportDiskVolume volumes,ImportProperties properties)throws IOException{
        this.ledger=ledger;this.volumes=volumes;
        root=properties.getStagingRoot().resolveSibling("project_edit_outputs").toAbsolutePath().normalize();
        new ProjectImportStaging(root,List.of(Path.of("temp/code_output"),Path.of("temp/code_deploy"),properties.getStagingRoot()));
    }

    void plan(ImportAgentEditLedger.Row row,JsonNode proof)throws IOException{
        var files=proof.path("retainedOutputs");
        if(files.isMissingNode()||files.isEmpty())return;
        var inventory=inventory(files);Path path=path(row.identity().id());
        if(Files.exists(path,LinkOption.NOFOLLOW_LINKS))throw new IOException("OUTPUT_RESOURCE_EXISTS");
        long bytes=inventory.files().values().stream().mapToLong(ImportAgentCandidateVerifier.FileStamp::bytes).sum();
        if(Files.getFileStore(root).getUsableSpace()<Math.addExact(bytes,256L<<20))throw new IOException("OUTPUT_SPACE_INSUFFICIENT");
        ledger.planOutputs(row,path.toString(),files);
    }

    void copy(ImportAgentEditLedger.Row row,JsonNode proof,BooleanSupplier eligible)throws IOException{
        var files=proof.path("retainedOutputs");if(files.isMissingNode()||files.isEmpty())return;
        var inventory=inventory(files);var e=row.identity();Path target=path(e.id());
        Files.createDirectory(target);
        long capacity=json.readTree(row.resources()).get(0).path("diskBytes").asLong();
        var access=new ImportAgentCandidateVerifier.FilesAccess(){
            public ImportAgentCandidateVerifier.Inventory inventory(){return inventory;}
            public InputStream open(String name)throws IOException{
                var stamp=inventory.files().get(name);if(stamp==null)throw new IOException("OUTPUT_PATH_INVALID");
                return volumes.openEditFile(e,capacity,proof.path("authorizedStateHash").asText(),proof.path("candidateHash").asText(),
                        proof.path("candidateManifestHash").asText(),"output-file",name,stamp.bytes(),stamp.sha256(),eligible);
            }
        };
        // 1. 复用候选的逐块摘要核验；独立目录只写产物，不复制秘密来源或其他运行。
        ImportAgentPublication.materialize(target,target,inventory,access,eligible);
        verify(target,files);ledger.outputsPhase(e.id(),"READY");
    }

    void finish(ImportAgentEditLedger.Row row,boolean committed)throws IOException{
        for(var resource:ledger.outputs(row.identity().appId()))if(row.identity().id().equals(resource.get("edit_id"))){
            Path target=checked(resource);var files=json.readTree((String)resource.get("files"));
            if(committed){
                if(!"READY".equals(resource.get("phase")))throw new IOException("OUTPUT_RESOURCE_NOT_READY");
                verify(target,files);
            }else{ImportAgentPublication.removeTree(target);ledger.outputsPhase(row.identity().id(),"RELEASED");}
        }
    }

    /** 应用删除持有应用锁；只移除账本匹配的本应用产物，长期保留审计。 */
    void releaseApp(long appId)throws IOException{
        for(var resource:ledger.outputs(appId)){
            Path target=checked(resource);ImportAgentPublication.removeTree(target);
            if(Files.exists(target,LinkOption.NOFOLLOW_LINKS))throw new IOException("OUTPUT_RELEASE_UNCONFIRMED");
            ledger.outputsPhase((UUID)resource.get("edit_id"),"RELEASED");
        }
    }

    private Path checked(Map<String,Object> resource)throws IOException{
        Path expected=path((UUID)resource.get("edit_id"));
        if(!expected.toString().equals(resource.get("resource_path")))throw new IOException("OUTPUT_IDENTITY_INVALID");
        return expected;
    }
    private Path path(UUID id)throws IOException{Path path=root.resolve(id.toString());ProjectImportStaging.guardManagedPath(path);return path;}

    static ImportAgentCandidateVerifier.Inventory inventory(JsonNode files)throws IOException{
        if(!files.isObject()||files.size()>100000)throw new IOException("OUTPUT_MANIFEST_INVALID");
        var stamps=new HashMap<String,ImportAgentCandidateVerifier.FileStamp>();var directories=new HashSet<String>();directories.add(".");
        var fields=files.fields();long total=0;
        while(fields.hasNext()){
            var item=fields.next();String name=item.getKey();var stamp=item.getValue();
            if(name.isEmpty()||name.startsWith("/")||name.contains("\\")||name.contains(":"))throw new IOException("OUTPUT_PATH_INVALID");
            for(String part:name.split("/",-1))if(part.isEmpty()||part.equals(".")||part.equals(".."))throw new IOException("OUTPUT_PATH_INVALID");
            if(!stamp.isArray()||stamp.size()!=3||!stamp.get(0).isTextual()||!stamp.get(1).isIntegralNumber()
                    ||!stamp.get(1).canConvertToLong()||!stamp.get(2).isIntegralNumber()||!stamp.get(2).canConvertToInt())throw new IOException("OUTPUT_MANIFEST_INVALID");
            try{var value=new ImportAgentCandidateVerifier.FileStamp(stamp.get(0).asText(),stamp.get(1).asLong(),stamp.get(2).asInt());
                total=Math.addExact(total,value.bytes());if(total>8L<<30)throw new IOException("OUTPUT_LIMIT_EXCEEDED");stamps.put(name,value);
            }catch(IllegalArgumentException|ArithmeticException error){throw new IOException("OUTPUT_MANIFEST_INVALID");}
            for(int slash=name.lastIndexOf('/');slash>=0;slash=name.lastIndexOf('/',slash-1))directories.add(name.substring(0,slash));
        }
        if(stamps.keySet().stream().anyMatch(directories::contains))throw new IOException("OUTPUT_PATH_CONFLICT");
        return new ImportAgentCandidateVerifier.Inventory(stamps,directories);
    }

    static void verify(Path root,JsonNode files)throws IOException{
        var expected=inventory(files);var foundFiles=new HashSet<String>();var foundDirectories=new HashSet<String>();
        if(!Files.isDirectory(root,LinkOption.NOFOLLOW_LINKS))throw new IOException("OUTPUT_DIRECTORY_MISSING");
        try(var paths=Files.walk(root)){
            for(Path path:paths.toList()){
                ProjectImportStaging.guardManagedPath(path);String name=root.equals(path)?".":root.relativize(path).toString().replace('\\','/');
                if(Files.isDirectory(path,LinkOption.NOFOLLOW_LINKS)){foundDirectories.add(name);continue;}
                var stamp=expected.files().get(name);if(stamp==null||!Files.isRegularFile(path,LinkOption.NOFOLLOW_LINKS))throw new IOException("OUTPUT_CONTENT_CHANGED");
                MessageDigest digest;try{digest=MessageDigest.getInstance("SHA-256");}catch(Exception error){throw new IllegalStateException(error);}
                long count=0;byte[] buffer=new byte[65536];try(var input=Files.newInputStream(path,LinkOption.NOFOLLOW_LINKS)){
                    int length;while((length=input.read(buffer))!=-1){count+=length;if(count>stamp.bytes())throw new IOException("OUTPUT_CONTENT_CHANGED");digest.update(buffer,0,length);}
                }
                if(count!=stamp.bytes()||!HexFormat.of().formatHex(digest.digest()).equals(stamp.sha256()))throw new IOException("OUTPUT_CONTENT_CHANGED");foundFiles.add(name);
            }
        }
        if(!foundFiles.equals(expected.files().keySet())||!foundDirectories.equals(expected.directories()))throw new IOException("OUTPUT_CONTENT_CHANGED");
    }
}
