package com.dbts.glyahhaigeneratecode.projectimport;

import com.dbts.glyahhaigeneratecode.service.support.ProjectImportManifest;
import com.fasterxml.jackson.core.JsonFactory;
import com.fasterxml.jackson.core.StreamReadConstraints;
import com.fasterxml.jackson.core.StreamReadFeature;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.DeserializationFeature;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.*;
import java.util.function.BooleanSupplier;

/** 独立核对平台候选清单和实际字节；调用方仍须取得冻结证据、业务资格及发布写锁。 */
public final class ImportAgentCandidateVerifier {
    public record FileStamp(String sha256,long bytes,int mode) {
        public FileStamp {
            if(sha256==null||!sha256.matches("[0-9a-f]{64}")||bytes<0||mode<0||mode>07777)
                throw new IllegalArgumentException("候选文件摘要无效。");
        }
    }
    public record Inventory(Map<String,FileStamp> files,Set<String> directories) {
        public Inventory {files=Map.copyOf(files);directories=Set.copyOf(directories);}
    }
    /** 必须由平台私有状态和冻结源独立构建，不能从候选manifest反向填充。 */
    public record Frozen(String viewSourceHash,String authorizedStateHash,Inventory original,
                         Map<String,FileStamp> authorized,Map<String,FileStamp> restored,Map<String,FileStamp> outputs) {
        public Frozen {
            authorized=Collections.unmodifiableMap(new HashMap<>(authorized));
            restored=Map.copyOf(restored);outputs=Map.copyOf(outputs);
        }
    }
    public interface FilesAccess {
        Inventory inventory()throws IOException;
        InputStream open(String relative)throws IOException;
    }
    public record Verified(String candidateHash,String manifestHash,int files,long bytes) { }
    private final int maxFiles;
    private final long maxBytes;
    private final ObjectMapper json=new ObjectMapper(JsonFactory.builder()
            .enable(StreamReadFeature.STRICT_DUPLICATE_DETECTION)
            .streamReadConstraints(StreamReadConstraints.builder().maxNestingDepth(32).maxStringLength(1<<20).build()).build())
            .enable(DeserializationFeature.FAIL_ON_TRAILING_TOKENS);
    private static final Set<String> FIELDS=Set.of("appId","ownerId","runId","editId","contentHash","diskBytes","formatVersion",
            "schemaVersion","authorizedStateHash","viewSourceHash","candidateHash","files","directories","changes","authorized","retainedOutputs");
    private static final Comparator<String> PYTHON_ORDER=(left,right)->Arrays.compare(left.codePoints().toArray(),right.codePoints().toArray());

    public ImportAgentCandidateVerifier(int maxFiles,long maxBytes){
        if(maxFiles<1||maxBytes<1)throw new IllegalArgumentException("候选校验额度无效。");
        this.maxFiles=maxFiles;this.maxBytes=maxBytes;
    }

    /** 平台真实遍历结果仍须严格解析并独立计算完整摘要。 */
    public Inventory readInventory(byte[] body,String expectedHash)throws IOException{
        if(body==null||body.length>32<<20)throw rejected();
        try{
            JsonNode node=json.readTree(body);
            if(node==null||!node.isObject()||!keys(node).equals(Set.of("files","directories")))throw rejected();
            var actual=new Inventory(stamps(node.path("files"),false),directories(node.path("directories")));
            bounded(actual);
            if(!inventoryHash(actual).equals(expectedHash))throw rejected();
            return actual;
        }catch(IOException|RuntimeException error){throw rejected();}
    }

    /** 私有状态必须来自平台通道；来源和视图必须独立实际读取，不能以manifest作输入。 */
    public Frozen freeze(byte[] state,String stateHash,ImportAgentEditLedger.Entry entry,String sourcePath,
            String viewSourceHash,FilesAccess source,FilesAccess view,BooleanSupplier eligible)throws IOException{
        qualified(eligible);
        if(state==null||state.length>32<<20||!hash(state).equals(stateHash))throw rejected();
        try{
            JsonNode node=json.readTree(state);
            if(node==null||!node.isObject()||!keys(node).equals(Set.of("formatVersion","appId","ownerId","runId","source","root",
                    "sourceHash","viewHash","maxFiles","maxBytes","original","baseline","secrets","markers","bindings","authorized")))throw rejected();
            for(var pair:Map.of("appId",Long.toString(entry.appId()),"ownerId",Long.toString(entry.ownerId()),"runId",entry.runId(),
                    "source",sourcePath,"root",ImportDiskVolume.editPath(entry)+"/view","sourceHash",viewSourceHash).entrySet())
                if(!node.path(pair.getKey()).isTextual()||!pair.getValue().equals(node.path(pair.getKey()).asText()))throw rejected();
            if(number(node.path("formatVersion"))!=1||number(node.path("maxFiles"))<1||number(node.path("maxFiles"))>100000
                    ||number(node.path("maxBytes"))<1||number(node.path("maxBytes"))>8L<<30)throw rejected();
            var original=privateInventory(node.path("original"),viewSourceHash);
            if(!node.path("viewHash").isTextual())throw rejected();
            var baseline=privateInventory(node.path("baseline"),node.path("viewHash").asText());
            var authorized=stamps(node.path("authorized"),true);
            var actualSource=actualInventory(source,eligible);var actualView=actualInventory(view,eligible);
            bounded(actualSource);bounded(actualView);
            if(!original.equals(actualSource))throw rejected();
            var bindingsNode=node.path("bindings");
            if(!bindingsNode.isObject()||bindingsNode.size()>maxFiles||!node.path("secrets").isArray()||!node.path("markers").isObject())throw rejected();
            var restored=new HashMap<String,FileStamp>();var outputs=new HashMap<String,FileStamp>();
            var restorer=new ImportAgentSecretRestorer();
            // 1. 验证每项授权，不允许敏感路径、未知删除或未授权原文件变化。
            for(var item:authorized.entrySet()){
                String path=item.getKey();if(protectedPath(path))throw rejected();
                if(!Objects.equals(item.getValue(),actualView.files().get(path)))throw rejected();
                if(item.getValue()==null){
                    if(bindingsNode.path(path).size()!=0)throw rejected();
                    // 文件工具创建后再删除是空修改；隐藏的原文件仍不能借空修改删除。
                    if(!baseline.files().containsKey(path)&&original.files().containsKey(path))throw rejected();
                    continue;
                }
                byte[] edited=readVerified(view,path,item.getValue(),eligible);
                var mapping=bindings(bindingsNode.path(path));
                var before=original.files().get(path);
                byte[] raw=before==null?new byte[0]:readVerified(source,path,before,eligible);
                byte[] safe=restorer.baseline(raw,path,mapping);
                var empty=new FileStamp(hash(new byte[0]),0,0644);
                var base=baseline.files().get(path);
                if(before==null&&(base!=null||!mapping.isEmpty()))throw rejected();
                restored.put(path,restorer.restore(raw,safe,edited,before==null?empty:before,base==null?empty:base,item.getValue(),mapping));
            }
            // 2. 执行产物单独登记，不能转成源码授权；原基线源码不得被Shell偷改或删除。
            for(var item:actualView.files().entrySet())if(!authorized.containsKey(item.getKey())){
                var before=baseline.files().get(item.getKey());
                if(!Objects.equals(before,item.getValue())){
                    if(before!=null&&!derivedPath(item.getKey()))throw rejected();
                    outputs.put(item.getKey(),item.getValue());
                }
            }
            for(String path:baseline.files().keySet())if(!actualView.files().containsKey(path)&&!authorized.containsKey(path)&&!derivedPath(path))throw rejected();
            if(!original.equals(actualInventory(source,eligible))||!actualView.equals(actualInventory(view,eligible)))throw rejected();
            qualified(eligible);
            return new Frozen(viewSourceHash,stateHash,original,authorized,restored,outputs);
        }catch(IOException|RuntimeException error){qualified(eligible);throw rejected();}
    }
    private Inventory privateInventory(JsonNode node,String expectedHash)throws IOException{
        if(!node.isArray()||node.size()!=2)throw rejected();
        var actual=new Inventory(stamps(node.get(0),false),directories(node.get(1)));bounded(actual);
        if(!inventoryHash(actual).equals(expectedHash))throw rejected();return actual;
    }
    private List<ImportAgentSecretRestorer.Binding> bindings(JsonNode node)throws IOException{
        if(node.isMissingNode())return List.of();
        if(!node.isObject()||node.size()>maxFiles)throw rejected();
        var result=new ArrayList<ImportAgentSecretRestorer.Binding>();var fields=node.fields();
        while(fields.hasNext()){
            var item=fields.next();var value=item.getValue();
            if(!value.isArray()||value.size()!=2||!value.get(0).isTextual()||number(value.get(1))<1||number(value.get(1))>Integer.MAX_VALUE)throw rejected();
            try{result.add(new ImportAgentSecretRestorer.Binding(item.getKey(),Base64.getDecoder().decode(value.get(0).asText()),(int)number(value.get(1))));}
            catch(IllegalArgumentException error){throw rejected();}
        }
        return result;
    }
    private byte[] readVerified(FilesAccess files,String path,FileStamp stamp,BooleanSupplier eligible)throws IOException{
        if(stamp.bytes()>Integer.MAX_VALUE-8)throw rejected();
        var output=new java.io.ByteArrayOutputStream();byte[] buffer=new byte[64*1024];long count=0;
        try(var input=files.open(path)){
            int length;while((length=input.read(buffer))!=-1){qualified(eligible);if(length>stamp.bytes()-count)throw rejected();output.write(buffer,0,length);count+=length;}
        }
        byte[] raw=output.toByteArray();if(count!=stamp.bytes()||!hash(raw).equals(stamp.sha256()))throw rejected();return raw;
    }
    private static boolean derivedPath(String path){
        return Arrays.stream(path.split("/")).anyMatch(part->Set.of(".git","__pycache__","node_modules",".venv","venv",".pytest_cache",
                ".mypy_cache",".ruff_cache",".cache","dist","target","build","coverage").contains(part));
    }

    public Verified verify(byte[] manifest,String manifestHash,String candidateHash,ImportAgentEditLedger.Entry entry,
                           long diskBytes,Frozen frozen,FilesAccess files,BooleanSupplier eligible)throws IOException{
        // 1. 只读取完整且已冻结的清单；正文摘要和身份必须先通过，禁止截断或重复JSON键。
        qualified(eligible);
        if(manifest==null||manifest.length>32<<20||!hash(manifest).equals(manifestHash))throw rejected();
        JsonNode node;
        try{node=json.readTree(manifest);}
        catch(IOException|RuntimeException error){throw rejected();}
        if(node==null||!node.isObject()||!keys(node).equals(FIELDS))throw rejected();
        for(var pair:Map.of("appId",Long.toString(entry.appId()),"ownerId",Long.toString(entry.ownerId()),"runId",entry.runId(),
                "editId",entry.id().toString(),"contentHash",entry.sourceHash(),"viewSourceHash",frozen.viewSourceHash(),
                "authorizedStateHash",frozen.authorizedStateHash(),"candidateHash",candidateHash).entrySet())
            if(!node.path(pair.getKey()).isTextual()||!pair.getValue().equals(node.path(pair.getKey()).asText()))throw rejected();
        if(number(node.path("diskBytes"))!=diskBytes||number(node.path("schemaVersion"))!=1||number(node.path("formatVersion"))!=3)throw rejected();
        var claimed=new Inventory(stamps(node.path("files"),false),directories(node.path("directories")));
        bounded(claimed);
        if(!inventoryHash(claimed).equals(candidateHash))throw rejected();
        if(!stamps(node.path("authorized"),true).equals(frozen.authorized())
                ||!stamps(node.path("retainedOutputs"),false).equals(frozen.outputs()))throw rejected();

        // 2. 按独立授权构造唯一允许的结果；保留原文件，不能靠manifest自己增加授权。
        var expected=new HashMap<>(frozen.original().files());
        bounded(frozen.original());
        var expectedDirs=new HashSet<>(frozen.original().directories());
        for(var change:frozen.authorized().entrySet()){
            String path=change.getKey();
            if(protectedPath(path))throw rejected();
            if(change.getValue()==null){
                if(frozen.restored().containsKey(path))throw rejected();
                expected.remove(path);
            }else{
                var restored=frozen.restored().get(path);
                if(restored==null)throw rejected();
                expected.put(path,restored);
                for(int slash=path.lastIndexOf('/');slash>=0;slash=path.lastIndexOf('/',slash-1))expectedDirs.add(path.substring(0,slash));
            }
        }
        if(!frozen.authorized().keySet().containsAll(frozen.restored().keySet())
                ||!expected.equals(claimed.files())||!expectedDirs.equals(claimed.directories()))throw rejected();
        var changed=new HashSet<String>();
        var union=new HashSet<>(frozen.original().files().keySet());union.addAll(expected.keySet());
        for(String path:union)if(!Objects.equals(frozen.original().files().get(path),expected.get(path)))changed.add(path);
        JsonNode changes=node.path("changes");
        if(!changes.isArray()||changes.size()!=changed.size())throw rejected();
        for(JsonNode item:changes){
            if(!item.isObject()||!keys(item).equals(Set.of("path","before","after"))||!item.path("path").isTextual())throw rejected();
            String path=item.path("path").asText();
            if(!changed.remove(path)||!Objects.equals(stamp(item.path("before"),true),frozen.original().files().get(path))
                    ||!Objects.equals(stamp(item.path("after"),true),expected.get(path)))throw rejected();
        }

        // 3. 清单不能替代真实目录和字节；读取前后复核全范围，每块核对资格，拒绝增长和短读。
        if(!claimed.equals(actualInventory(files,eligible)))throw rejected();
        long total=0;byte[] buffer=new byte[64*1024];
        for(var item:claimed.files().entrySet()){
            qualified(eligible);long count=0;var digest=digest();
            try(InputStream stream=files.open(item.getKey())){
                int length;
                while((length=stream.read(buffer))!=-1){
                    qualified(eligible);
                    if(length>item.getValue().bytes()-count)throw rejected();
                    digest.update(buffer,0,length);count+=length;
                }
            }catch(IOException error){
                if("CANDIDATE_REVOKED".equals(error.getMessage()))throw error;
                throw rejected();
            }
            if(count!=item.getValue().bytes()||!HexFormat.of().formatHex(digest.digest()).equals(item.getValue().sha256()))throw rejected();
            total+=count;
        }
        qualified(eligible);
        if(!claimed.equals(actualInventory(files,eligible)))throw rejected();
        return new Verified(candidateHash,manifestHash,claimed.files().size(),total);
    }

    private Inventory actualInventory(FilesAccess files,BooleanSupplier eligible)throws IOException{
        qualified(eligible);
        try{var actual=files.inventory();qualified(eligible);return actual;}
        catch(IOException|RuntimeException error){qualified(eligible);throw rejected();}
    }

    private void bounded(Inventory inventory)throws IOException{
        if(inventory.files().size()+inventory.directories().size()>maxFiles)throw rejected();
        long total=0;
        for(var file:inventory.files().values()){
            if(file.bytes()>maxBytes-total)throw rejected();
            total+=file.bytes();
        }
        try{
            if(!inventory.files().isEmpty())ProjectImportManifest.validate(inventory.files().entrySet().stream()
                    .map(e->new ProjectImportManifest.FileEntry(e.getKey(),e.getValue().bytes())).toList(),
                    new ProjectImportManifest.Options(false,false,true),new ProjectImportManifest.Limits(maxFiles,maxBytes));
            for(String directory:inventory.directories())if(!directory.equals("."))
                ProjectImportManifest.validate(List.of(new ProjectImportManifest.FileEntry(directory+"/__dir_check__",0)),
                        new ProjectImportManifest.Options(false,false,true),new ProjectImportManifest.Limits(maxFiles,maxBytes));
        }catch(IllegalArgumentException error){throw rejected();}
        if(!inventory.directories().contains("."))throw rejected();
        var dirNames=new HashSet<String>();
        for(String directory:inventory.directories())if(!dirNames.add(directory.toLowerCase(Locale.ROOT)))throw rejected();
        for(String path:inventory.files().keySet()){
            if(dirNames.contains(path.toLowerCase(Locale.ROOT)))throw rejected();
            for(int slash=path.lastIndexOf('/');slash>=0;slash=path.lastIndexOf('/',slash-1))
                if(!inventory.directories().contains(path.substring(0,slash)))throw rejected();
        }
    }
    private Map<String,FileStamp> stamps(JsonNode node,boolean nullable)throws IOException{
        if(!node.isObject()||node.size()>maxFiles)throw rejected();
        var result=new HashMap<String,FileStamp>();
        var fields=node.fields();
        while(fields.hasNext()){var item=fields.next();result.put(item.getKey(),stamp(item.getValue(),nullable));}
        return result;
    }
    private FileStamp stamp(JsonNode node,boolean nullable)throws IOException{
        if(node.isNull()&&nullable)return null;
        if(!node.isArray()||node.size()!=3||!node.get(0).isTextual())throw rejected();
        long mode=number(node.get(2));
        if(mode<0||mode>07777)throw rejected();
        try{return new FileStamp(node.get(0).asText(),number(node.get(1)),(int)mode);}
        catch(IllegalArgumentException error){throw rejected();}
    }
    private Set<String> directories(JsonNode node)throws IOException{
        if(!node.isArray()||node.size()>maxFiles)throw rejected();
        var paths=new HashSet<String>();
        for(JsonNode item:node)if(!item.isTextual()||!paths.add(item.asText()))throw rejected();
        return paths;
    }
    private static long number(JsonNode node)throws IOException{
        if(!node.isIntegralNumber()||!node.canConvertToLong())throw rejected();return node.longValue();
    }
    private static Set<String> keys(JsonNode node){var result=new HashSet<String>();node.fieldNames().forEachRemaining(result::add);return result;}
    private static void qualified(BooleanSupplier eligible)throws IOException{if(eligible==null||!eligible.getAsBoolean())throw new IOException("CANDIDATE_REVOKED");}
    private static IOException rejected(){return new IOException("CANDIDATE_NOT_VERIFIED");}
    private static boolean protectedPath(String path){
        var parts=Arrays.asList(path.toLowerCase(Locale.ROOT).split("/"));
        String name=parts.getLast();
        return ProjectImportManifest.isSensitivePath(path)
                ||parts.stream().anyMatch(part->Set.of(".git","keys","secrets","certs","ssl",".ssh","node_modules",".venv","venv",
                        "__pycache__",".pytest_cache",".mypy_cache",".ruff_cache",".cache","dist","target","build","coverage").contains(part))
                ||Set.of("secrets.json","id_dsa").contains(name)||name.endsWith(".secret")
                ||(name.startsWith(".env")&&!Set.of(".env.example",".env.sample",".env.template").contains(name));
    }
    private static MessageDigest digest(){try{return MessageDigest.getInstance("SHA-256");}catch(java.security.NoSuchAlgorithmException error){throw new IllegalStateException("摘要不可用。");}}
    private static String hash(byte[] bytes){return HexFormat.of().formatHex(digest().digest(bytes));}

    /** 与隔离视图的Python完整清单摘要一致，包括中文、补充平面字符和文件属性。 */
    static String inventoryHash(Inventory inventory){
        var text=new StringBuilder("{\"directories\":[");boolean comma=false;
        for(String directory:inventory.directories().stream().sorted(PYTHON_ORDER).toList()){
            if(comma)text.append(',');quote(text,directory);comma=true;
        }
        text.append("],\"files\":{");comma=false;
        for(String path:inventory.files().keySet().stream().sorted(PYTHON_ORDER).toList()){
            if(comma)text.append(',');quote(text,path);text.append(":[");var stamp=inventory.files().get(path);
            quote(text,stamp.sha256());text.append(',').append(stamp.bytes()).append(',').append(stamp.mode()).append(']');comma=true;
        }
        return hash(text.append("}}").toString().getBytes(StandardCharsets.UTF_8));
    }
    private static void quote(StringBuilder target,String text){
        target.append('"');
        for(char value:text.toCharArray())switch(value){
            case '"'->target.append("\\\"");case '\\'->target.append("\\\\");case '\b'->target.append("\\b");
            case '\f'->target.append("\\f");case '\n'->target.append("\\n");case '\r'->target.append("\\r");case '\t'->target.append("\\t");
            default->{if(value<32||value>=127)target.append(String.format(Locale.ROOT,"\\u%04x",(int)value));else target.append(value);}
        }
        target.append('"');
    }
}
