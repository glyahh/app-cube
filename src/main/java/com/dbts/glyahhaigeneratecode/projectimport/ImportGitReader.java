package com.dbts.glyahhaigeneratecode.projectimport;

import com.dbts.glyahhaigeneratecode.service.support.ProjectImportStaging;
import java.io.IOException;
import java.io.ByteArrayInputStream;
import java.io.SequenceInputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.function.BooleanSupplier;
import java.util.function.Consumer;

/** 受管只读 Git；仓库配置、凭据 helper、模板及工程脚本不参与读取。 */
public final class ImportGitReader {
    public record Snapshot(String branch,String commit,List<ImportGitMetadata.Blob> blobs){
        public Snapshot{blobs=List.copyOf(blobs);}
    }
    private final Path executable;
    private final Path directory;
    private final ImportGitNetworkPolicy network;
    private final Map<String,String> environment;
    private final Map<String,String> networkEnvironment;
    private final BooleanSupplier eligible;
    private final Consumer<ImportProcessRunner.Resource> journal;
    private final Path home;

    public ImportGitReader(Path executable,Path directory,ImportGitNetworkPolicy network,
                           BooleanSupplier eligible,Consumer<ImportProcessRunner.Resource> journal) throws IOException {
        this(executable,directory,network,eligible,journal,Map.of());
    }

    public ImportGitReader(Path executable,Path directory,ImportGitNetworkPolicy network,
                           BooleanSupplier eligible,Consumer<ImportProcessRunner.Resource> journal,Map<String,String> askpass) throws IOException {
        this(executable,directory,network,eligible,journal,askpass,false);
    }

    /** 重建范围只读本任务已拉取的对象，不重新访问远端分支。 */
    public static ImportGitReader reopen(Path executable,Path directory,
                           BooleanSupplier eligible,Consumer<ImportProcessRunner.Resource> journal)throws IOException {
        return new ImportGitReader(executable,directory,ImportGitNetworkPolicy.offline(),eligible,journal,Map.of(),true);
    }

    private ImportGitReader(Path executable,Path directory,ImportGitNetworkPolicy network,
                           BooleanSupplier eligible,Consumer<ImportProcessRunner.Resource> journal,Map<String,String> askpass,boolean reopen) throws IOException {
        // 1. 来源准备服务提供已归属的私有目录；不读取用户工程或宿主 Git 配置。
        this.executable=executable.toAbsolutePath().normalize();
        this.directory=directory.toAbsolutePath().normalize();
        ProjectImportStaging.guardManagedPath(this.executable);
        ProjectImportStaging.guardManagedPath(this.directory);
        if(!Files.isRegularFile(this.executable) || !Files.isDirectory(this.directory))throw new IOException("Git 读取环境无效。");
        this.network=network;this.eligible=eligible;this.journal=journal;
        home=reopen?Files.createTempDirectory(this.directory,"read-home-"):Files.createDirectory(this.directory.resolve("home"));
        Path config=Files.createFile(home.resolve("empty.gitconfig"));
        Files.createDirectory(home.resolve("empty-template"));
        var env=new HashMap<String,String>();
        env.put("HOME",home.toString());env.put("USERPROFILE",home.toString());
        env.put("GIT_CONFIG_NOSYSTEM","1");env.put("GIT_CONFIG_GLOBAL",config.toString());
        env.put("XDG_CONFIG_HOME",home.toString());env.put("GIT_CEILING_DIRECTORIES",this.directory.toString());
        env.put("GIT_TERMINAL_PROMPT","0");env.put("GCM_INTERACTIVE","Never");
        // Windows Git 的系统工具需要这两个非秘密路径，不继承整个宿主环境。
        String systemRoot=System.getenv("SystemRoot");
        if(systemRoot!=null){env.put("SystemRoot",systemRoot);env.put("WINDIR",systemRoot);}
        env.put("PATH",this.executable.getParent().toString());
        environment=Map.copyOf(env);
        if(!askpass.isEmpty() && !askpass.keySet().equals(java.util.Set.of("GIT_ASKPASS","IMPORT_ASKPASS_ENDPOINT","IMPORT_ASKPASS_CAPABILITY")))
            throw new IOException("凭据通道环境无效。");
        var networkEnv=new HashMap<>(env);networkEnv.putAll(askpass);networkEnvironment=Map.copyOf(networkEnv);
        // 2. 显式 bare 元数据目录阻止向上发现当前开发仓库的 .git/config。
        if(reopen) {
            Path metadata=this.directory.resolve("metadata.git");ProjectImportStaging.guardManagedPath(metadata);
            if(!Files.isDirectory(metadata.resolve("objects")))throw new IOException("固定 Git 对象不存在。");
            try(var paths=Files.walk(metadata)){for(Path path:paths.toList())ProjectImportStaging.guardManagedPath(path);}
        } else {
            var initialize=command();initialize.addAll(List.of("init","--bare","--template="+home.resolve("empty-template")));
            ImportProcessRunner.run(initialize,this.directory,environment,Duration.ofSeconds(10),65536,65536,
                    eligible,journal,input->input.readAllBytes());
        }
    }

    public Snapshot fixedSnapshot(String branch,String commit,int maxFiles,long maxSourceBytes) {
        branch=ImportRepositorySource.branch(branch);
        if(commit==null || !commit.matches("[0-9a-f]{40}"))throw new IllegalArgumentException("固定版本无效。");
        var tree=command();tree.addAll(List.of("ls-tree","-r","-z","-l",commit,"--"));
        var blobs=ImportProcessRunner.run(tree,directory,environment,Duration.ofSeconds(30),8L*1024*1024,65536,
                eligible,journal,input->ImportGitMetadata.tree(input.readAllBytes(),maxFiles,maxSourceBytes)).value();
        return new Snapshot(branch,commit,blobs);
    }

    public List<ImportGitMetadata.Branch> branches(ImportRepositorySource source) {
        // 服务端再次规范化来源，不能把可构造的 record 当已校验输入。
        source=ImportRepositorySource.parse(source.provider(),source.cloneUrl());
        network.requireSource(source);
        var command=command();command.addAll(List.of("ls-remote","--heads","--",source.cloneUrl()));
        var result=ImportProcessRunner.run(command,directory,networkEnvironment,Duration.ofSeconds(60),8L*1024*1024,65536,
                eligible,journal,input->ImportGitMetadata.branches(input.readAllBytes()));
        return result.value();
    }

    public Snapshot fetch(ImportRepositorySource source,ImportGitMetadata.Branch selected,int maxFiles,long maxSourceBytes,
                          long maxGitBytes,long minFreeBytes) {
        // 1. 拉取用户选中的固定对象，而不是随后可能移动的分支。
        source=ImportRepositorySource.parse(source.provider(),source.cloneUrl());
        network.requireSource(source);
        String branch=ImportRepositorySource.branch(selected.name());String commit=selected.commit();
        if(!commit.matches("[0-9a-f]{40}") || maxGitBytes<=0 || minFreeBytes<0)throw new IllegalArgumentException("Git 内容版本或额度无效。");
        // 拉取前先确认剩余空间。不足直接停止，不开始传输，也不把读到一半的内容当成成功。
        try{ImportDiskAdmission.requireUsable(Files.getFileStore(directory).getUsableSpace(),minFreeBytes);}
        catch(java.io.IOException error){throw new IllegalStateException("无法确认磁盘剩余空间，已停止读取。");}
        BooleanSupplier qualified=()->eligible.getAsBoolean() && withinDiskBudget(maxGitBytes,minFreeBytes);
        var fetch=command();fetch.addAll(List.of("fetch","--depth=1","--no-tags","--no-recurse-submodules","--",
                source.cloneUrl(),commit+":refs/import/selected"));
        ImportProcessRunner.run(fetch,directory,networkEnvironment,Duration.ofMinutes(5),65536,65536,qualified,journal,input->input.readAllBytes());
        var resolve=command();resolve.addAll(List.of("rev-parse","--verify","refs/import/selected^{commit}"));
        String actual=ImportProcessRunner.run(resolve,directory,environment,Duration.ofSeconds(10),128,65536,
                qualified,journal,input->new String(input.readAllBytes(),StandardCharsets.US_ASCII).strip()).value();
        if(!actual.equals(commit))throw new IllegalStateException("Git 实际内容版本不一致。");
        // 2. 在同一对象上取完整文件树，链接、子模块及越限不能部分接入。
        var tree=command();tree.addAll(List.of("ls-tree","-r","-z","-l",commit,"--"));
        var blobs=ImportProcessRunner.run(tree,directory,environment,Duration.ofSeconds(30),8L*1024*1024,65536,
                qualified,journal,input->ImportGitMetadata.tree(input.readAllBytes(),maxFiles,maxSourceBytes)).value();
        return new Snapshot(branch,commit,blobs);
    }

    public <T> T blob(ImportGitMetadata.Blob blob,ImportProcessRunner.StreamHandler<T> consumer) {
        if(blob==null || !blob.objectId().matches("[0-9a-f]{40}") || blob.size()<0)throw new IllegalArgumentException("Git 对象无效。");
        var command=command();command.addAll(List.of("cat-file","blob",blob.objectId()));
        return ImportProcessRunner.run(command,directory,environment,Duration.ofMinutes(5),blob.size(),65536,eligible,journal,input->{
            // 真实对象字节校验独立于清单，流式交付不运行任何 filter。
            var digest=java.security.MessageDigest.getInstance("SHA-1");
            digest.update(("blob "+blob.size()+"\0").getBytes(StandardCharsets.US_ASCII));
            var count=new java.util.concurrent.atomic.AtomicLong();
            var checked=new java.io.FilterInputStream(new java.security.DigestInputStream(input,digest)){
                @Override public int read()throws IOException{int value=in.read();if(value>=0)count.incrementAndGet();return value;}
                @Override public int read(byte[] bytes,int offset,int length)throws IOException{int size=in.read(bytes,offset,length);if(size>0)count.addAndGet(size);return size;}
                @Override public void close(){/* 原始输出流由受管进程统一关闭。 */}
            };
            byte[] prefix=checked.readNBytes(64);ImportGitMetadata.requireMaterializedContent(prefix);
            var combined=new SequenceInputStream(new ByteArrayInputStream(prefix),checked);
            T result=consumer.read(combined);
            if(combined.read()!=-1 || count.get()!=blob.size() || !java.util.HexFormat.of().formatHex(digest.digest()).equals(blob.objectId()))
                throw new IOException("Git 对象内容不一致或未读完。");
            return result;
        }).value();
    }

    private boolean withinDiskBudget(long maxBytes,long minFreeBytes) {
        try(var entries=Files.walk(directory)) {
            long total=0;
            for(var iterator=entries.iterator();iterator.hasNext();) {
                Path path=iterator.next();ProjectImportStaging.guardManagedPath(path);
                if(Files.isRegularFile(path)){total=Math.addExact(total,Files.size(path));if(total>maxBytes)return false;}
            }
            return Files.getFileStore(directory).getUsableSpace()>=minFreeBytes;
        } catch(Exception error){return false;}
    }

    public static Map<String,String> askpass(Path python,Path helper,ImportCredentialBroker broker,Path ownedDirectory)throws IOException {
        python=python.toAbsolutePath().normalize();helper=helper.toAbsolutePath().normalize();
        ProjectImportStaging.guardManagedPath(python);ProjectImportStaging.guardManagedPath(helper);
        if(!Files.isRegularFile(python) || !Files.isRegularFile(helper))throw new IOException("凭据辅助程序未配置。");
        ownedDirectory=ownedDirectory.toAbsolutePath().normalize();ProjectImportStaging.guardManagedPath(ownedDirectory);
        boolean windows=System.getProperty("os.name").toLowerCase().contains("win");
        Path wrapper=ownedDirectory.resolve(windows?"askpass.cmd":"askpass.sh");ProjectImportStaging.guardManagedPath(wrapper);
        // GIT_ASKPASS 接收程序路径而非多参数命令；包装器只含可信路径，不含秘密或能力值。
        String script=windows?"@echo off\r\n"+quotedPath(python)+" "+quotedPath(helper)+" \"%~1\"\r\n":
                "#!/bin/sh\nexec "+quotedPath(python)+" "+quotedPath(helper)+" \"$@\"\n";
        Files.writeString(wrapper,script,StandardCharsets.UTF_8,java.nio.file.StandardOpenOption.CREATE_NEW,java.nio.file.StandardOpenOption.WRITE);
        if(!windows)Files.setPosixFilePermissions(wrapper,java.util.Set.of(java.nio.file.attribute.PosixFilePermission.OWNER_READ,java.nio.file.attribute.PosixFilePermission.OWNER_WRITE,java.nio.file.attribute.PosixFilePermission.OWNER_EXECUTE));
        return Map.of("GIT_ASKPASS",wrapper.toString(),"IMPORT_ASKPASS_ENDPOINT",broker.endpoint(),"IMPORT_ASKPASS_CAPABILITY",broker.capability());
    }
    private static String quotedPath(Path path)throws IOException {
        String value=path.toString().replace('\\','/');
        if(value.chars().anyMatch(c->c<32 || "\"`$!%".indexOf(c)>=0))throw new IOException("凭据辅助路径无效。");
        return "\""+value+"\"";
    }

    private ArrayList<String> command() {
        var command=new ArrayList<String>();command.add(executable.toString());
        command.add("--git-dir="+directory.resolve("metadata.git"));
        var config=new ArrayList<>(network.gitConfig());
        config.addAll(List.of("gc.auto=0","maintenance.auto=false","core.hooksPath="+home.resolve("empty-template"),
                "init.templateDir="+home.resolve("empty-template"),"core.askPass=","credential.interactive=false"));
        for(String value:config){command.add("-c");command.add(value);}
        return command;
    }
}
