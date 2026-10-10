package com.dbts.glyahhaigeneratecode.projectimport;

import com.dbts.glyahhaigeneratecode.exception.ErrorCode;
import com.dbts.glyahhaigeneratecode.exception.MyException;
import com.dbts.glyahhaigeneratecode.service.support.ProjectImportStaging;
import com.dbts.glyahhaigeneratecode.service.support.ProjectImportManifest.Options;
import jakarta.annotation.PreDestroy;
import org.springframework.stereotype.Service;
import lombok.extern.slf4j.Slf4j;
import java.io.IOException;
import java.net.InetAddress;
import java.net.URI;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import static com.dbts.glyahhaigeneratecode.projectimport.ImportSourceTask.Status.*;

/** 来源准备；持久登记先于网络，取消必须核实实际退出。 */
@Service
@Slf4j
public class ImportSourceService implements AutoCloseable {
    private final ImportSourceRepository repository;
    private final ImportProperties properties;
    private final ImportTaskService tasks;
    private final ImportPreferenceService preferences;
    private final ImportCredentialService credentials;
    private final UUID executor=UUID.randomUUID();
    private volatile boolean closing;
    private final Map<UUID,CountDownLatch> active=new ConcurrentHashMap<>();
    private final Map<UUID,ImportCredentialBroker> brokers=new ConcurrentHashMap<>();
    private final java.util.concurrent.ExecutorService workers=Executors.newCachedThreadPool(r->{var thread=new Thread(r,"import-source-reader");thread.setDaemon(true);return thread;});
    private final java.util.concurrent.ScheduledExecutorService expiry=Executors.newSingleThreadScheduledExecutor(r->{var thread=new Thread(r,"import-source-expiry");thread.setDaemon(true);return thread;});

    public ImportSourceService(ImportSourceRepository repository,ImportProperties properties,ImportTaskService tasks,ImportPreferenceService preferences,ImportCredentialService credentials) {
        this.repository=repository;this.properties=properties;this.tasks=tasks;this.preferences=preferences;
        this.credentials=credentials;
        expiry.scheduleWithFixedDelay(this::expire,60,60,TimeUnit.SECONDS);
    }

    public ImportSourceTask.View preparePublic(long owner,String appName,String provider,String url,Options options) {
        return prepare(owner,appName,provider,url,options,null,null);
    }

    public ImportSourceTask.View prepare(long owner,String appName,String provider,String url,Options options,UUID credentialId,Long credentialVersion) {
        return prepare(owner,appName,provider,url,options,credentialId,credentialVersion,null);
    }

    public ImportSourceTask.View prepare(long owner,String appName,String provider,String url,Options options,UUID credentialId,Long credentialVersion,UUID requestId) {
        ImportProviderPolicy.requireSupported(provider);
        if(owner<=0 || appName==null || appName.isBlank() || appName.strip().length()>80 || appName.chars().anyMatch(Character::isISOControl))
            throw new MyException(ErrorCode.PARAMS_ERROR,"应用名称无效，最多 80 个字符。");
        if((credentialId==null)!=(credentialVersion==null) || (credentialVersion!=null && credentialVersion<=0))
            throw new MyException(ErrorCode.PARAMS_ERROR,"凭据版本不完整。");
        if(requestId!=null) {
            ImportRepositorySource source=ImportRepositorySource.parse(provider,url);
            Options frozen=options==null?preferences.get(owner):options;
            return tasks.sourceRequest(owner,()->{
                var previous=repository.findByRequest(owner,requestId);
                if(previous==null)return null;
                if(appName==null || !repository.requestMatches(owner,requestId,new ImportSourceTask.Definition(appName.strip(),source,frozen),credentialId,credentialVersion))throw conflict();
                return get(owner,previous.taskId());
            },()->createSource(owner,appName,provider,url,frozen,credentialId,credentialVersion,requestId));
        }
        return createSource(owner,appName,provider,url,options,credentialId,credentialVersion,null);
    }

    private ImportSourceTask.View createSource(long owner,String appName,String provider,String url,Options options,UUID credentialId,Long credentialVersion,UUID requestId) {
        if(closing)throw new MyException(ErrorCode.OPERATION_ERROR,"来源执行器正在停止。");
        if(owner<=0 || appName==null || appName.isBlank() || appName.strip().length()>80 || appName.chars().anyMatch(Character::isISOControl))
            throw new MyException(ErrorCode.PARAMS_ERROR,"应用名称无效，最多 80 个字符。");
        ImportRepositorySource source=ImportRepositorySource.parse(provider,url);
        if((credentialId==null)!=(credentialVersion==null) || (credentialVersion!=null && credentialVersion<=0))throw new MyException(ErrorCode.PARAMS_ERROR,"凭据版本不完整。");
        if(credentialId!=null)for(Path tool:java.util.Arrays.asList(properties.getCredentialCurl(),properties.getCredentialPython(),properties.getCredentialHelper())) {
            if(tool==null)throw new MyException(ErrorCode.OPERATION_ERROR,"凭据读取工具未配置。");
            try{ProjectImportStaging.guardManagedPath(tool.toAbsolutePath().normalize());}
            catch(IOException error){throw new MyException(ErrorCode.OPERATION_ERROR,"凭据读取工具不可用。");}
            if(!Files.isRegularFile(tool))throw new MyException(ErrorCode.OPERATION_ERROR,"凭据读取工具不可用。");
        }
        if(properties.getGitExecutable()==null || !Files.isRegularFile(properties.getGitExecutable()))
            throw new MyException(ErrorCode.OPERATION_ERROR,"Git 读取工具未配置。");
        if(properties.getSourceTimeout()==null || properties.getSourceTimeout().isNegative() || properties.getSourceTimeout().isZero())
            throw new MyException(ErrorCode.OPERATION_ERROR,"Git 读取时限配置无效。");
        Options frozen=options==null?preferences.get(owner):options;
        var process=ProcessHandle.current();
        var task=new ImportSourceTask(UUID.randomUUID(),owner,new ImportSourceTask.Definition(appName.strip(),source,frozen),
                RESOLVING_SOURCE,0,credentialId,credentialVersion,null,null,List.of(),List.of(),false,null,executor,process.pid(),
                process.info().startInstant().orElseThrow(),Instant.now().plus(properties.getSourceTimeout()),null,"NONE");
        // 1. 来源与文件夹共用准入；网络操作在退出短锁后进行。
        tasks.admitSource(owner,()->{
            try {
                Path root=properties.getStagingRoot().toAbsolutePath().normalize();ProjectImportStaging.guardManagedPath(root);
                if(Files.getFileStore(root).getUsableSpace()<properties.getMinFreeBytes())throw new IOException("space");
                if(requestId==null)repository.insert(task);else repository.insert(task,requestId);
                Files.createDirectory(directory(task));
            }catch(IOException error){repository.transition(task,FAILED,"SOURCE_DIRECTORY_FAILED","UNVERIFIED");throw new MyException(ErrorCode.OPERATION_ERROR,"无法建立来源暂存目录。");}
            return null;
        });
        CountDownLatch exited=new CountDownLatch(1);active.put(task.taskId(),exited);
        try {workers.submit(()->readBranches(task,exited));}
        catch(RuntimeException error){active.remove(task.taskId());exited.countDown();repository.transition(task,FAILED,"SOURCE_EXECUTOR_UNAVAILABLE","UNVERIFIED");throw new MyException(ErrorCode.OPERATION_ERROR,"来源读取未启动。");}
        return task.view();
    }

    public ImportSourceTask.View getByRequest(long owner,UUID requestId) {
        if(owner<=0 || requestId==null)throw new MyException(ErrorCode.PARAMS_ERROR,"来源请求无效。");
        var task=repository.findByRequest(owner,requestId);
        if(task==null)throw new MyException(ErrorCode.NOT_FOUND_ERROR,"来源请求尚无已确认任务。");
        return get(owner,task.taskId());
    }

    private void readBranches(ImportSourceTask lease,CountDownLatch exited) {
        try {
            ImportProviderPolicy.requireSupported(lease.definition().source().provider());
            // 2. 显式代理与直连证明分开；不失败后换线路或匿名身份。
            ImportGitNetworkPolicy network=properties.getGitProxy()==null || properties.getGitProxy().isBlank()
                    ?ImportGitNetworkPolicy.direct(URI.create(lease.definition().source().cloneUrl()).getHost(),
                        List.of(InetAddress.getAllByName(URI.create(lease.definition().source().cloneUrl()).getHost())))
                    :ImportGitNetworkPolicy.proxy(properties.getGitProxy());
            List<ImportGitMetadata.Branch> branches;
            try(var broker=credentialBroker(lease,network)) {
                var reader=new ImportGitReader(properties.getGitExecutable(),directory(lease),network,()->!closing && repository.qualified(lease),resource->repository.resource(lease,resource),
                        broker==null?Map.of():ImportGitReader.askpass(properties.getCredentialPython(),properties.getCredentialHelper(),broker,directory(lease)));
                branches=reader.branches(lease.definition().source());
            }
            if(!releaseStoppedBroker(lease.taskId()))throw new IOException("凭据通道停止未确认。");
            repository.branchesReady(lease,branches);
        }catch(RuntimeException | IOException error){
            // 只有本轮仍有资格才记录失败，迟到错误不能覆盖取消状态。
            var current=repository.find(lease.ownerId(),lease.taskId());
            if(current!=null && current.status()==lease.status() && current.revision()==lease.revision()) {
                String cleanup="UNVERIFIED";
                String code=ImportPlatformIdentity.errorCode(error);
                if(!code.equals("STOP_FAILED") && resourcesStopped(current))try{cleanup(current);cleanup="DONE";}catch(IOException ignored){cleanup="FAILED";}
                repository.transition(lease,cleanup.equals("UNVERIFIED")?STOP_FAILED:FAILED,code,cleanup);
            }
        }finally{releaseStoppedBroker(lease.taskId());active.remove(lease.taskId(),exited);exited.countDown();}
    }

    public ImportSourceTask.View selectBranch(long owner,UUID id,long revision,String branch) {
        var previous=owned(owner,id);
        ImportProviderPolicy.requireSupported(previous.definition().source().provider());
        if(previous.status()!=WAITING_BRANCH || previous.revision()!=revision || closing)throw conflict();
        String name=ImportRepositorySource.branch(branch);
        var selected=previous.branches().stream().filter(value->value.name().equals(name)).findFirst()
                .orElseThrow(()->new MyException(ErrorCode.PARAMS_ERROR,"请选择当前仓库的一个分支。"));
        try {
            if(properties.getMaxGitBytes()<=0 || Files.getFileStore(directory(previous)).getUsableSpace()<properties.getMaxGitBytes()+properties.getMinFreeBytes())
                throw new IOException("space");
        }catch(IOException error){throw new MyException(ErrorCode.OPERATION_ERROR,"来源暂存空间不足或无法核实。");}
        var process=ProcessHandle.current();
        var lease=repository.select(previous,name,selected.commit(),executor,process.pid(),process.info().startInstant().orElseThrow(),properties.getMaxGitBytes());
        var exited=new CountDownLatch(1);active.put(id,exited);
        try{workers.submit(()->readContent(lease,selected,exited));}
        catch(RuntimeException error){active.remove(id);exited.countDown();repository.transition(lease,FAILED,"SOURCE_EXECUTOR_UNAVAILABLE","UNVERIFIED");throw new MyException(ErrorCode.OPERATION_ERROR,"来源读取未启动。");}
        return lease.view();
    }

    private void readContent(ImportSourceTask lease,ImportGitMetadata.Branch selected,CountDownLatch exited) {
        try {
            var source=lease.definition().source();
            var network=properties.getGitProxy()==null || properties.getGitProxy().isBlank()
                    ?ImportGitNetworkPolicy.direct(URI.create(source.cloneUrl()).getHost(),List.of(InetAddress.getAllByName(URI.create(source.cloneUrl()).getHost())))
                    :ImportGitNetworkPolicy.proxy(properties.getGitProxy());
            // 分支阶段的独立目录已建立，不复用读进程或其输入流。
            Path content=Files.createDirectory(directory(lease).resolve("content"));
            String contentHash;
            try(var broker=credentialBroker(lease,network)) {
                var reader=new ImportGitReader(properties.getGitExecutable(),content,network,()->!closing && repository.qualified(lease),resource->repository.resource(lease,resource),
                        broker==null?Map.of():ImportGitReader.askpass(properties.getCredentialPython(),properties.getCredentialHelper(),broker,content));
                var snapshot=reader.fetch(source,selected,properties.getMaxFiles(),properties.getMaxSourceBytes(),properties.getMaxGitBytes(),properties.getMinFreeBytes());
                var task=tasks.createFromSource(lease,snapshot);
                var blobs=snapshot.blobs().stream().collect(java.util.stream.Collectors.toMap(ImportGitMetadata.Blob::path,value->value));
                for(int index=0;index<task.manifest().included().size();index++) {
                    var file=task.manifest().included().get(index);var blob=blobs.get(file.path());
                    final int currentIndex=index;final var current=task;
                    task=reader.blob(blob,input->tasks.upload(lease.ownerId(),lease.taskId(),current.revision(),currentIndex,input));
                }
                contentHash=task.contentHash();
            }
            // 私有通道实际停止后才发布确认资格，不能让界面或提交抢在停止证明之前。
            if(!releaseStoppedBroker(lease.taskId()))throw new IOException("凭据通道停止未确认。");
            repository.contentReady(lease,contentHash);
        }catch(RuntimeException | IOException error){
            var current=repository.find(lease.ownerId(),lease.taskId());
            if(current!=null && current.status()==lease.status() && current.revision()==lease.revision()) {
                repository.transition(lease,ImportPlatformIdentity.errorCode(error).equals("STOP_FAILED")?STOP_FAILED:FAILED,
                        ImportPlatformIdentity.errorCode(error),"UNVERIFIED");
            }
        }finally{releaseStoppedBroker(lease.taskId());active.remove(lease.taskId(),exited);exited.countDown();}
    }

    public ImportSourceTask.View get(long owner,UUID id) {
        var task=owned(owner,id);
        if((task.status()==RESOLVING_SOURCE || task.status()==READING_CONTENT) && !active.containsKey(id)) {
            repository.transition(task,RECOVERY_REQUIRED,"SOURCE_EXECUTOR_UNCONFIRMED","UNVERIFIED");task=owned(owner,id);
        }
        return task.view();
    }

    public ImportSourceTask.View rebuild(long owner,UUID id,long revision,String appName,Options options) {
        var previous=owned(owner,id);
        ImportProviderPolicy.requireSupported(previous.definition().source().provider());
        if(closing || previous.status()!=WAITING_CONFIRMATION || previous.revision()!=revision || active.containsKey(id)
                || !resourcesStopped(previous) || !releaseStoppedBroker(id))throw conflict();
        if(appName==null || appName.isBlank() || appName.strip().length()>80 || appName.chars().anyMatch(Character::isISOControl) || options==null)
            throw new MyException(ErrorCode.PARAMS_ERROR,"应用名称或导入范围无效。");
        tasks.checkRebuild(owner,id,options);
        var process=ProcessHandle.current();
        var definition=new ImportSourceTask.Definition(appName.strip(),previous.definition().source(),options);
        var lease=repository.rebuild(previous,definition,executor,process.pid(),process.info().startInstant().orElseThrow());
        var exited=new CountDownLatch(1);active.put(id,exited);
        try{workers.submit(()->rebuildContent(lease,exited));}
        catch(RuntimeException error){active.remove(id,exited);exited.countDown();repository.transition(lease,FAILED,"SOURCE_EXECUTOR_UNAVAILABLE","UNVERIFIED");throw new MyException(ErrorCode.OPERATION_ERROR,"范围重建未启动。");}
        return lease.view();
    }

    private void rebuildContent(ImportSourceTask lease,CountDownLatch exited) {
        try {
            // 1. 核对持久快照与本任务的真实对象树；本轮不创建凭据通道、不访问网络。
            var expected=repository.snapshot(lease);
            var reader=ImportGitReader.reopen(properties.getGitExecutable(),directory(lease).resolve("content"),
                    ()->!closing && repository.qualified(lease),resource->repository.resource(lease,resource));
            var actual=reader.fixedSnapshot(lease.branch(),lease.commit(),properties.getMaxFiles(),properties.getMaxSourceBytes());
            if(!actual.equals(expected))throw new IOException("固定内容清单已变化。");
            // 2. 旧接收字节释放后按新范围重读同一快照，主任务版本单调递增。
            var task=tasks.rebuildFromSource(lease,actual);
            var blobs=actual.blobs().stream().collect(java.util.stream.Collectors.toMap(ImportGitMetadata.Blob::path,value->value));
            for(int index=0;index<task.manifest().included().size();index++) {
                var blob=blobs.get(task.manifest().included().get(index).path());
                final int currentIndex=index;final var current=task;
                task=reader.blob(blob,input->tasks.upload(lease.ownerId(),lease.taskId(),current.revision(),currentIndex,input));
            }
            repository.contentReady(lease,task.contentHash());
        } catch(RuntimeException | IOException error) {
            var current=repository.find(lease.ownerId(),lease.taskId());
            if(current!=null && current.status()==lease.status() && current.revision()==lease.revision())
                repository.transition(lease,ImportPlatformIdentity.errorCode(error).equals("STOP_FAILED")?STOP_FAILED:FAILED,
                        ImportPlatformIdentity.errorCode(error),"UNVERIFIED");
        } finally{active.remove(lease.taskId(),exited);exited.countDown();}
    }

    private ImportCredentialBroker credentialBroker(ImportSourceTask lease,ImportGitNetworkPolicy gitNetwork)throws IOException {
        if(lease.credentialId()==null)return null;
        String host=lease.definition().source().provider().equals("GitHub")?"api.github.com":"gitee.com";
        var network=gitNetwork.evidenceMode().equals("EXPLICIT_PROXY_TLS_HOST")?gitNetwork:
                ImportGitNetworkPolicy.direct(host,List.of(InetAddress.getAllByName(host)));
        java.util.function.BooleanSupplier eligible=()->!closing && repository.qualified(lease);
        String login=ImportPlatformIdentity.authenticate(properties.getCredentialCurl(),directory(lease),lease.definition().source().provider(),
                credentials.readForSource(lease),network,eligible,resource->repository.resource(lease,resource));
        var broker=new ImportCredentialBroker(lease.definition().source(),eligible,()->new ImportCredentialBroker.Secret(login,credentials.readForSource(lease)));
        if(brokers.putIfAbsent(lease.taskId(),broker)!=null){broker.close();throw new IOException("上一个凭据通道停止未确认。");}
        return broker;
    }
    private boolean releaseStoppedBroker(UUID id) {
        var broker=brokers.get(id);
        if(broker==null)return true;
        if(!broker.confirmedStopped())return false;
        brokers.remove(id,broker);return true;
    }

    /** 凭据撤销时优先停止来源读取；无来源记录才由文件夹协调器处理。 */
    public ImportSourceTask.View cancelIfPresent(long owner,UUID id) {
        var current=repository.find(owner,id);
        if(current==null)return null;
        if(current.status()==SUCCEEDED || (current.status()==CANCELLED && "DONE".equals(current.cleanup())))return current.view();
        return cancel(owner,id,current.revision());
    }

    public ImportSourceTask.View cancel(long owner,UUID id,long revision) {
        var previous=owned(owner,id);
        if(previous.status()==CANCELLED && "DONE".equals(previous.cleanup()))return previous.view();
        if(previous.status()==SUCCEEDED || previous.revision()!=revision)throw conflict();
        CountDownLatch worker=active.get(id);
        if(!repository.transition(previous,CANCELLING,null,"PENDING"))throw conflict();
        var cancelling=owned(owner,id);
        boolean exited=true;
        if(worker!=null)try{exited=worker.await(properties.getStopTimeout().toMillis(),TimeUnit.MILLISECONDS);}
        catch(InterruptedException error){Thread.currentThread().interrupt();exited=false;}
        // 重启丢失执行上下文时，不把已观察 PID 全死冒充完整子进程树已停止。
        boolean lost=(previous.status()==RECOVERY_REQUIRED || "SOURCE_EXECUTOR_UNCONFIRMED".equals(previous.error()) || ("STOP_FAILED".equals(previous.error()) && !brokers.containsKey(id))
                || ((previous.status()==RESOLVING_SOURCE || previous.status()==READING_CONTENT) && worker==null));
        cancelling=owned(owner,id);
        if(!exited || lost || !resourcesStopped(cancelling) || !releaseStoppedBroker(id)) {
            repository.transition(cancelling,STOP_FAILED,lost?"SOURCE_EXECUTOR_UNCONFIRMED":"RESOURCE_STOP_UNCONFIRMED","UNVERIFIED");return owned(owner,id).view();
        }
        if(cancelling.mainTaskCreated()) {
            var main=tasks.get(owner,id);
            if(main.status()==ImportTask.Status.SUCCEEDED) {
                repository.transition(cancelling,SUCCEEDED,null,"NONE");return owned(owner,id).view();
            }
            var result=tasks.cancelFromSource(owner,id,main.revision());
            if(result.status()!=ImportTask.Status.CANCELLED || !"DONE".equals(result.cleanup())) {
                repository.transition(cancelling,STOP_FAILED,"MAIN_TASK_STOP_UNCONFIRMED","UNVERIFIED");return owned(owner,id).view();
            }
        }
        String cleanup="DONE";
        try{cleanup(cancelling);}catch(IOException error){cleanup="FAILED";log.warn("来源清理失败，taskId={}，type={}",id,error.getClass().getSimpleName());}
        repository.transition(cancelling,CANCELLED,cleanup.equals("DONE")?null:"SOURCE_CLEANUP_FAILED",cleanup);
        return owned(owner,id).view();
    }

    private static boolean resourcesStopped(ImportSourceTask task) {
        for(var resource:task.resources()) {
            var process=ProcessHandle.of(resource.pid());
            if(process.isPresent() && process.get().isAlive()) {
                var started=process.get().info().startInstant();
                if(started.isEmpty() || resource.started()==null || started.get().equals(resource.started()))return false;
            }
        }
        return true;
    }

    private Path directory(ImportSourceTask task)throws IOException {
        Path root=properties.getStagingRoot().toAbsolutePath().normalize();Path path=root.resolve(task.taskId()+".git");
        if(!path.getParent().equals(root))throw new IOException("来源目录越界。");ProjectImportStaging.guardManagedPath(path);return path;
    }
    private void cleanup(ImportSourceTask task)throws IOException {
        Path directory=directory(task);if(!Files.exists(directory,LinkOption.NOFOLLOW_LINKS))return;
        try(var entries=Files.walk(directory)) {
            List<Path> paths=entries.sorted(java.util.Comparator.reverseOrder()).toList();
            for(Path path:paths){ProjectImportStaging.guardManagedPath(path);if(!path.startsWith(directory))throw new IOException("来源清理越界。");}
            for(Path path:paths) {
                ProjectImportStaging.guardManagedPath(path);
                // Git 在 Windows 创建只读对象；仅解除已停止任务受管副本的删除限制。
                var dos=Files.getFileAttributeView(path,java.nio.file.attribute.DosFileAttributeView.class,LinkOption.NOFOLLOW_LINKS);
                if(dos!=null && dos.readAttributes().isReadOnly())dos.setReadOnly(false);
                Files.delete(path);
            }
        }
    }
    private ImportSourceTask owned(long owner,UUID id){var task=repository.find(owner,id);if(task==null)throw new MyException(ErrorCode.NOT_FOUND_ERROR,"来源任务不存在。");return task;}
    private void expire(){
        try {
            // 1. 下线平台只撤销未完成任务，历史成功应用保持不变。
            for(var task:repository.retired())try{cancelIfPresent(task.ownerId(),task.taskId());}
            catch(RuntimeException error){log.warn("下线来源取消未确认，taskId={}",task.taskId());}
            for(var task:repository.expired())try{cancel(task.ownerId(),task.taskId(),task.revision());}
            catch(RuntimeException error){
                if(error instanceof MyException known && known.getCode()==ErrorCode.CONFLICT_ERROR.getCode())continue;
                log.warn("导入来源过期取消未确认，taskId={}",task.taskId());
            }
        }catch(RuntimeException error){log.warn("导入来源过期检查失败，未确认取消；数据库资格检查仍阻断过期读取。");}
    }
    private static MyException conflict(){return new MyException(ErrorCode.CONFLICT_ERROR,"来源任务已变化，请刷新。");}
    @PreDestroy @Override public void close(){
        closing=true;expiry.shutdownNow();workers.shutdownNow();
        try{if(!workers.awaitTermination(properties.getStopTimeout().toMillis(),TimeUnit.MILLISECONDS))log.warn("导入来源执行器停止未确认；保留持久资源记录。");}
        catch(InterruptedException error){Thread.currentThread().interrupt();}
        // 未退出的来源保留持久资源与未确认状态，重启后不得当作已停止。
    }
}
