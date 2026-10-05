package com.dbts.glyahhaigeneratecode.projectimport;

import com.dbts.glyahhaigeneratecode.service.support.ProjectImportManifest;
import com.dbts.glyahhaigeneratecode.service.support.ProjectImportStaging;
import com.dbts.glyahhaigeneratecode.service.support.ProjectImportStaging.StoredFile;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.charset.CodingErrorAction;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.nio.file.attribute.BasicFileAttributes;
import java.nio.file.attribute.DosFileAttributeView;
import java.nio.file.attribute.PosixFileAttributeView;
import java.nio.file.attribute.AclFileAttributeView;
import java.security.MessageDigest;
import java.time.Duration;
import java.util.*;
import java.util.function.BooleanSupplier;

/** 完整内容私有副本。调用者须在建立副本期间协调受管写入；本类不授予业务或执行资格。 */
public final class ImportStartupSnapshotStore {
    public record Snapshot(UUID id,String contentHash,List<StoredFile> files,Map<String,String> attributes) {
        public Snapshot {files=List.copyOf(files);attributes=Map.copyOf(attributes);}
    }
    private final Path root;
    private final ProjectImportStaging staging;
    private final int maxFiles;
    private final long maxBytes;
    private static final Duration TIMEOUT=Duration.ofSeconds(30);

    public ImportStartupSnapshotStore(Path root,List<Path> forbiddenRoots,int maxFiles,long maxBytes) throws IOException {
        if(maxFiles<1||maxBytes<1)throw new IllegalArgumentException("快照额度无效。");
        this.root=root.toAbsolutePath().normalize();this.staging=new ProjectImportStaging(root,forbiddenRoots);
        this.maxFiles=maxFiles;this.maxBytes=maxBytes;
    }

    public Snapshot capture(UUID id,Path workspace,BooleanSupplier eligible) throws IOException {
        if(id==null||eligible==null)throw new IllegalArgumentException("快照身份无效。");
        Path source=workspace.toAbsolutePath().normalize();
        if(source.startsWith(root)||root.startsWith(source))throw new IOException("快照与工作区必须隔离。");
        long deadline=System.nanoTime()+TIMEOUT.toNanos();
        BooleanSupplier qualified=()->System.nanoTime()<deadline&&eligible.getAsBoolean();
        boolean created=false;
        try {
            // 1. 全量列举，包括依赖、二进制和敏感文件；这些字节不等于模型可读权限。
            var attributes=new TreeMap<String,String>();var entries=inventory(source,attributes,qualified);
            staging.create(id);created=true;var stored=new ArrayList<StoredFile>();
            for(var entry:entries){
                Path file=source.resolve(entry.path());ProjectImportStaging.guardManagedPath(file);
                stored.add(staging.receive(id,stored.size(),entry,Files.newInputStream(file,LinkOption.NOFOLLOW_LINKS),qualified,TIMEOUT));
            }
            String content=staging.seal(id,stored,qualified,TIMEOUT);
            var snapshot=new Snapshot(id,identity(content,attributes),stored,attributes);
            // 2. 原目录完整复核，拒绝变化和截断；受管写入协调仍由业务接入层承担。
            verify(snapshot,source,qualified);
            return snapshot;
        } catch(IOException|RuntimeException error) {
            if(created)try{staging.cleanup(id);}catch(IOException cleanup){throw new IOException("快照失败，私有副本清理未完成。");}
            throw new IOException("快照未建立，请检查来源、额度或任务资格。");
        }
    }

    public void verify(Snapshot snapshot,Path workspace,BooleanSupplier eligible) throws IOException {
        var attributes=new TreeMap<String,String>();inventory(workspace.toAbsolutePath().normalize(),attributes,eligible);
        String content=staging.verifyWorkspace(workspace,snapshot.files(),eligible,TIMEOUT);
        if(!snapshot.contentHash().equals(identity(content,attributes)))throw new IOException("工作区内容或属性已变化。");
    }

    public Map<String,String> parserInput(Snapshot snapshot) throws IOException {
        return textInput(snapshot,null);
    }
    /** 只从已封存数字副本提取批准目录内的合法源码；限额失败不截断或返回部分输入。 */
    public Map<String,String> agentInput(Snapshot snapshot,List<String> directories) throws IOException {
        if(directories==null||directories.isEmpty()||directories.stream().anyMatch(Objects::isNull))throw new IllegalArgumentException("模型读取范围无效。");
        return textInput(snapshot,List.copyOf(directories));
    }
    private Map<String,String> textInput(Snapshot snapshot,List<String> directories) throws IOException {
        boolean agent=directories!=null;
        // 私有数字命名副本重新封存，防止被修改后继续充当原内容依据。
        staging.seal(snapshot.id(),snapshot.files(),()->true,TIMEOUT);
        var texts=new TreeMap<String,String>();long total=0;
        for(var file:snapshot.files()){
            String path=file.path();String name=path.substring(path.lastIndexOf('/')+1);
            // 模型只读批准目录内的文件；同级或上级目录不是本次授权的相关上下文。
            if(ProjectImportManifest.isSensitivePath(path)||!(agent?agentFile(path,name)&&directories.stream().anyMatch(dir->within(path,dir)):parserFile(path,name)))continue;
            long single=agent?16*1024:256*1024,limit=agent?48*1024:4*1024*1024;
            if(file.size()>single||file.size()>limit-total||(agent&&texts.size()>=256))throw new IOException("文本解析超限，未返回部分输入。");
            Path payload=root.resolve(snapshot.id().toString()).resolve(file.index()+".bin");ProjectImportStaging.guardManagedPath(payload);
            try(var input=Files.newInputStream(payload,LinkOption.NOFOLLOW_LINKS)) {
                byte[] bytes=input.readNBytes((int)file.size()+1);
                if(bytes.length!=file.size()||!HexFormat.of().formatHex(digest(bytes)).equals(file.sha256()))throw new IOException("私有副本内容已变化。");
                var decoder=StandardCharsets.UTF_8.newDecoder().onMalformedInput(CodingErrorAction.REPORT).onUnmappableCharacter(CodingErrorAction.REPORT);
                String text=decoder.decode(ByteBuffer.wrap(bytes)).toString();
                if(agent&&text.indexOf('\0')>=0)throw new IOException("模型文本包含二进制控制字符。");
                texts.put(path,text);total+=file.size();
            }catch(java.nio.charset.CharacterCodingException error){throw new IOException("配置不是有效 UTF-8，未返回部分输入。");}
        }
        return Map.copyOf(texts);
    }

    /** 由已封存数字副本建立独立目录副本并逐字节复核；只用于隔离执行，不回写工作区。 */
    public Path materialize(Snapshot snapshot,BooleanSupplier eligible) throws IOException {
        if(snapshot==null||eligible==null)throw new IllegalArgumentException("快照身份无效。");
        return staging.prepareWorkspace(snapshot.id(),snapshot.files(),eligible,TIMEOUT);
    }
    public Path materialized(UUID id){return staging.preparedWorkspace(id);}
    public void discardMaterialized(UUID id) throws IOException {staging.cleanupPreparedWorkspace(id);}
    public void discard(UUID id) throws IOException {staging.cleanup(id);}
    public boolean inputAbsent(UUID id){return !Files.exists(root.resolve(id.toString()),LinkOption.NOFOLLOW_LINKS);}

    private List<ProjectImportManifest.FileEntry> inventory(Path source,Map<String,String> attributes,BooleanSupplier eligible) throws IOException {
        ProjectImportStaging.guardManagedPath(source);
        if(!Files.isDirectory(source,LinkOption.NOFOLLOW_LINKS))throw new IOException("工作区不存在。");
        var entries=new ArrayList<ProjectImportManifest.FileEntry>();long[] bytes={0};int[] directories={0};
        Files.walkFileTree(source,new SimpleFileVisitor<>(){
            private void guard(Path file) throws IOException {
                if(!eligible.getAsBoolean())throw new IOException("快照任务失效或超时。");
                ProjectImportStaging.guardManagedPath(file);
                String relative=source.equals(file)?".":source.relativize(file).toString().replace('\\','/');
                String mode=Files.isExecutable(file)?"x":"-";
                var posix=Files.getFileAttributeView(file,PosixFileAttributeView.class,LinkOption.NOFOLLOW_LINKS);
                if(posix!=null)mode+="|"+new TreeSet<>(posix.readAttributes().permissions());
                var dos=Files.getFileAttributeView(file,DosFileAttributeView.class,LinkOption.NOFOLLOW_LINKS);
                if(dos!=null){var value=dos.readAttributes();mode+="|"+value.isReadOnly()+"|"+value.isHidden()+"|"+value.isSystem();}
                var acl=Files.getFileAttributeView(file,AclFileAttributeView.class,LinkOption.NOFOLLOW_LINKS);
                if(acl!=null)mode+="|"+acl.getOwner().getName()+"|"+acl.getAcl();
                attributes.put(relative,mode);
            }
            @Override public FileVisitResult preVisitDirectory(Path dir,BasicFileAttributes attrs) throws IOException {
                if(++directories[0]>maxFiles+1)throw new IOException("目录数量超限。");guard(dir);return FileVisitResult.CONTINUE;
            }
            @Override public FileVisitResult visitFile(Path file,BasicFileAttributes attrs) throws IOException {
                guard(file);if(!attrs.isRegularFile()||entries.size()>=maxFiles||attrs.size()>maxBytes-bytes[0])throw new IOException("文件类型或快照额度无效。");
                bytes[0]+=attrs.size();entries.add(new ProjectImportManifest.FileEntry(source.relativize(file).toString().replace('\\','/'),attrs.size()));
                return FileVisitResult.CONTINUE;
            }
        });
        try {ProjectImportManifest.validate(entries,new ProjectImportManifest.Options(false,false,true),new ProjectImportManifest.Limits(maxFiles,maxBytes));}
        catch(IllegalArgumentException error){throw new IOException("快照清单无效。");}
        entries.sort(Comparator.comparing(ProjectImportManifest.FileEntry::path));return entries;
    }

    private static boolean within(String path,String directory){return ".".equals(directory)||path.startsWith(directory+"/");}
    private static boolean parserFile(String path,String name){
        if(path.equals(".glyahh/run.json"))return true;
        for(String part:path.split("/"))if(Set.of("node_modules",".venv","venv","vendor","dist","target","build",".git",".glyahh","examples","tests","test").contains(part.toLowerCase(Locale.ROOT)))return false;
        return Set.of("package.json","package-lock.json","npm-shrinkwrap.json","pnpm-lock.yaml","yarn.lock","bun.lock","pyproject.toml","requirements.txt","pom.xml","index.html","mvnw").contains(name)
            ||name.endsWith(".java")||name.endsWith(".py");
    }
    private static boolean agentFile(String path,String name){
        for(String part:path.split("/"))if(Set.of("node_modules","vendor",".venv","venv","env","dist","target","build","out",".next",".nuxt",".output",
            "__pycache__",".pytest_cache",".mypy_cache",".ruff_cache",".cache","coverage",".nyc_output","logs","log","tmp","temp",".git",".glyahh",".idea",".vscode",".gradle",".mvn",
            "keys","secrets","certs","ssl",".ssh","examples","example","tests","test","fixtures").contains(part.toLowerCase(Locale.ROOT)))return false;
        String lower=name.toLowerCase(Locale.ROOT);
        if(lower.startsWith(".env")||Set.of("credentials","credentials.json","secrets.json","id_rsa","id_dsa",".netrc",".npmrc",".pypirc").contains(lower))return false;
        int dot=lower.lastIndexOf('.');
        return dot>=0&&Set.of("java","kt","py","js","jsx","ts","tsx","vue","html","json","toml","xml","md","txt","yaml","yml","gradle","go","rs","c","h","cpp","cs","php","rb").contains(lower.substring(dot+1));
    }

    private static String identity(String content,Map<String,String> attributes){
        try {
            var digest=MessageDigest.getInstance("SHA-256");digest.update(content.getBytes(StandardCharsets.UTF_8));
            for(var item:new TreeMap<>(attributes).entrySet())for(String value:List.of(item.getKey(),item.getValue())){
                byte[] bytes=value.getBytes(StandardCharsets.UTF_8);digest.update(ByteBuffer.allocate(4).putInt(bytes.length).array());digest.update(bytes);
            }
            return HexFormat.of().formatHex(digest.digest());
        }catch(java.security.NoSuchAlgorithmException error){throw new IllegalStateException("快照摘要不可用。");}
    }

    private static byte[] digest(byte[] bytes){
        try{return MessageDigest.getInstance("SHA-256").digest(bytes);}
        catch(java.security.NoSuchAlgorithmException error){throw new IllegalStateException("快照摘要不可用。");}
    }
}
