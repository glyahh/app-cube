package com.dbts.glyahhaigeneratecode.projectimport;

import com.dbts.glyahhaigeneratecode.exception.MyException;
import com.dbts.glyahhaigeneratecode.service.support.ProjectImportStaging;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.nio.file.attribute.BasicFileAttributes;
import java.security.MessageDigest;
import java.time.Duration;
import java.time.Instant;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.Supplier;

/**
 * 隔离执行核心：在写入锁内建立冻结副本，内容须等于已确认计划；正式执行写入独立受限卷。
 * 原工程不被执行产物改写，失败保留已导入工程及未决资源登记，不把清理失败当成功。
 */
public final class ImportExecutionRunner {
    private static final org.slf4j.Logger log=org.slf4j.LoggerFactory.getLogger(ImportExecutionRunner.class);
    public record Target(long ownerId,long appId,UUID planId,String planRevision,String contentHash,Path workspace,String directory,String command,
                         String slot,ImportExecutionSandbox.Network network,ImportExecutionSandbox.Lifetime lifetime,Integer port,String healthPath){
        public Target(long ownerId,long appId,UUID planId,String planRevision,String contentHash,Path workspace,String directory,String command,
                      String slot,ImportExecutionSandbox.Network network,ImportExecutionSandbox.Lifetime lifetime,Integer port){
            this(ownerId,appId,planId,planRevision,contentHash,workspace,directory,command,slot,network,lifetime,port,"/");
        }
        public Target(long ownerId,long appId,UUID planId,String planRevision,String contentHash,Path workspace,String directory,String command){
            this(ownerId,appId,planId,planRevision,contentHash,workspace,directory,command,"task",ImportExecutionSandbox.Network.NONE,ImportExecutionSandbox.Lifetime.FINISH,null);
        }
        public Target(long ownerId,long appId,UUID planId,String planRevision,String contentHash,Path workspace,String directory,String command,
                      String slot,ImportExecutionSandbox.Network network,ImportExecutionSandbox.Lifetime lifetime){
            this(ownerId,appId,planId,planRevision,contentHash,workspace,directory,command,slot,network,lifetime,null);
        }
        public Target{
            if(slot==null||!slot.matches("[A-Za-z0-9._:@+-]{1,160}")||network==null||lifetime==null)throw new IllegalArgumentException("执行目标无效。");
            if(ownerId<=0||appId<=0||planId==null||planRevision==null||contentHash==null||workspace==null||directory==null||command==null)throw new IllegalArgumentException("执行目标无效。");
            if(network==ImportExecutionSandbox.Network.LOOPBACK&&(port==null||port<1||port>65535))throw new IllegalArgumentException("回环端口无效。");
            if(network!=ImportExecutionSandbox.Network.LOOPBACK&&port!=null)throw new IllegalArgumentException("未声明回环发布时不能带端口。");
        }
    }
    public record Result(UUID executionId,String state,String reason,Integer exitCode,long outputBytes,String containerId,String address){
        public Result(UUID executionId,String state,String reason,Integer exitCode,long outputBytes,String containerId){this(executionId,state,reason,exitCode,outputBytes,containerId,null);}
    }
    public record Reconciled(int rows,int containers,int files,List<String> unresolved){ }
    /** 同一应用的工作区写入锁；生产为 ImportWorkspaceCoordinator。 */
    public interface Locker {<T> T withLock(long appId,Supplier<T> action);}

    private final ImportExecutionLedger ledger;
    private final ImportExecutionSandbox sandbox;
    private final ImportStartupSnapshotStore copies;
    private final ImportStartupResourceGuard guard;
    private final Locker locker;
    private final Path root;
    private final ImportDiskVolume volumes;
    private final long pid;
    private final Instant started;
    private final Map<UUID,AtomicBoolean> active=new ConcurrentHashMap<>();

    public ImportExecutionRunner(ImportExecutionLedger ledger,ImportExecutionSandbox sandbox,ImportStartupSnapshotStore copies,ImportStartupResourceGuard guard,Locker locker,Path root){
        this(ledger,sandbox,copies,guard,locker,root,null);
    }
    public ImportExecutionRunner(ImportExecutionLedger ledger,ImportExecutionSandbox sandbox,ImportStartupSnapshotStore copies,ImportStartupResourceGuard guard,Locker locker,Path root,ImportDiskVolume volumes){
        if(ledger==null||sandbox==null||copies==null||guard==null||locker==null||root==null)throw new IllegalArgumentException("执行组件无效。");
        this.ledger=ledger;this.sandbox=sandbox;this.copies=copies;this.guard=guard;this.locker=locker;this.root=root.toAbsolutePath().normalize();
        this.volumes=volumes;
        var process=ProcessHandle.current();this.pid=process.pid();this.started=process.info().startInstant().orElseThrow();
    }

    /** 只核对限额能否生效，不复制、不建容器。无法保证时返回原因代码。其他服务仍在运行不挡住新的槽位。 */
    public List<String> admit(long appId,ImportExecutionSandbox.Limits limits){
        return List.copyOf(sandbox.preflight(limits));
    }

    /**
     * 删除应用前停掉该应用的导入容器，并确认容器已经消失，再删除持久副本。
     * 任一容器无法确认消失时返回 false，持久副本保留，调用方不能继续删除应用。
     */
    public boolean releaseApp(long appId){
        if(appId<=0)return false;
        // 1. 未结束的执行先请求停止；已经停失败或清理未完成的，再按容器是否消失复核。
        for(var row:List.copyOf(ledger.forApp(appId))){
            if(!row.active()&&"DONE".equals(row.cleanup()))continue;
            var current=row.active()?stop(row.id(),Duration.ofSeconds(60)):row;
            if(current==null||current.active())return false;
            if("STOP_FAILED".equals(current.state())||!"DONE".equals(current.cleanup())){
                if(!containersGone(current))return false;
                if(!"DONE".equals(current.cleanup())){
                    // 容器已经消失后，补掉这次执行的临时文件，再把清理记成完成。
                    if(!cleanup(current.id()))return false;
                    try{current=ledger.cleaned(current);}catch(MyException conflict){return false;}
                    if(current==null||!"DONE".equals(current.cleanup()))return false;
                }
            }
        }
        // 2. 容器都已确认消失后，才删除应用级持久副本和来源标记。
        return removeDurable(appId);
    }

    /** 该应用当前未结束的指定前缀槽位执行；取消安装时按它停掉正在跑的命令。 */
    public List<UUID> activeSlots(long appId,String slotPrefix){
        return ledger.activeSlots(appId,slotPrefix);
    }

    /** 计划版本变化时先停止旧槽位。停不掉则返回 false，调用方不能把新旧结果拼成一次成功。 */
    public boolean stopOtherPlans(long appId,String planRevision){
        boolean stopped=true;
        for(var id:ledger.activeOther(appId,planRevision)){
            var row=stop(id,Duration.ofSeconds(60));
            if(row==null||row.active()||"STOP_FAILED".equals(row.state()))stopped=false;
        }
        return stopped;
    }

    public Result execute(Target target,ImportExecutionSandbox.Limits limits){
        var id=UUID.randomUUID();
        ImportInstallControl.check();
        ImportInstallDiagnostics.execution(null);
        ImportInstallDiagnostics.setCommandStage(target.slot().startsWith("build:")?"BUILD_PREPARE":target.lifetime()==ImportExecutionSandbox.Lifetime.SERVE?"SERVICE_WAIT":ImportInstallDiagnostics.commandType(target.command()));
        var entry=new ImportExecutionLedger.Entry(id,target.ownerId(),target.appId(),target.planId(),target.planRevision(),target.contentHash(),target.directory(),
            sha(target.command()),limits,pid,started,target.slot(),switch(target.network()){case EGRESS->"glyahh-imp-egress";case LOOPBACK->"bridge";case NONE->"none";});
        // 1. 先确认每一项边界都能真实生效；同一槽位未结束时拒绝，不建立副本、不创建容器。
        var refused=new ArrayList<>(ImportInstallDiagnostics.call("ADMISSION",()->sandbox.preflight(limits)));
        if(ledger.blocking(target.appId(),target.slot()))refused.add("PREVIOUS_EXECUTION_UNRESOLVED");
        if(!refused.isEmpty()){
            var row=ledger.open(entry,"REFUSED",String.join(",",refused));
            ImportInstallDiagnostics.execution(id);ImportInstallControl.execution(id);
            return new Result(id,row.state(),row.reason(),null,0,null);
        }
        ledger.open(entry,"PREPARING",null);
        ImportInstallDiagnostics.execution(id);
        var stop=new AtomicBoolean();active.put(id,stop);
        ImportExecutionSandbox.Outcome outcome=null;
        try{
            ImportInstallControl.execution(id);ImportInstallControl.check();
            outcome=run(id,target,limits,stop);
        }catch(RuntimeException error){
            outcome=new ImportExecutionSandbox.Outcome("FAILED","EXECUTION_ERROR",null,0,null);
        }finally{
            // 2. 安装和构建结束后清理本次临时文件。持久副本和仍在运行的服务容器保留。
            boolean running=outcome!=null&&"RUNNING".equals(outcome.state());
            boolean gone=outcome!=null&&!running&&!"STOP_FAILED".equals(outcome.state());
            boolean cleaned=running||(gone&&cleanup(id));
            try{
                var current=ledger.find(id);
                if(!running&&current!=null&&current.active())ledger.finish(current,outcome==null?"FAILED":outcome.state(),outcome==null?"EXECUTION_ERROR":outcome.reason(),
                    outcome==null?null:outcome.exitCode(),outcome==null?0:outcome.outputBytes(),cleaned?"DONE":"FAILED");
            }finally{active.remove(id);}
        }
        var last=ledger.find(id);
        return new Result(id,last.state(),last.reason(),outcome==null?null:outcome.exitCode(),outcome==null?0:outcome.outputBytes(),outcome==null?null:outcome.containerId(),outcome==null?null:outcome.address());
    }

    private ImportExecutionSandbox.Outcome run(UUID id,Target target,ImportExecutionSandbox.Limits limits,AtomicBoolean stop){
        ImportStartupSnapshotStore.Snapshot snapshot;
        try{
            // 3. 锁内全量复制并复核原目录，内容身份必须等于计划确认时的内容；不等即失败，不执行旧计划。
            snapshot=locker.withLock(target.appId(),()->{
                try{return ImportInstallDiagnostics.call("COPY_PROJECT",()->copies.capture(id,target.workspace(),()->!stop.get()&&!ImportInstallControl.cancelled()));}
                catch(IOException error){recordCopyFailure(id,target.appId(),"CAPTURE",error);throw new IllegalStateException("COPY_FAILED");}
            });
        }catch(IllegalStateException|MyException error){
            if(stop.get()||ImportInstallControl.cancelled())return new ImportExecutionSandbox.Outcome("STOPPED","STOP_REQUESTED",null,0,null);
            return new ImportExecutionSandbox.Outcome("FAILED",error instanceof MyException?"WORKSPACE_BUSY":"COPY_FAILED",null,0,null);
        }
        if(!snapshot.contentHash().equals(target.contentHash()))return new ImportExecutionSandbox.Outcome("FAILED","PLAN_CONTENT_CHANGED",null,0,null);
        long bytes=snapshot.files().stream().mapToLong(ProjectImportStaging.StoredFile::size).sum();
        if(bytes>limits.diskBytes())return new ImportExecutionSandbox.Outcome("FAILED","COPY_EXCEEDS_DISK",null,0,null);
        Path copy;
        try{copy=ImportInstallDiagnostics.call("PREPARE_WORKSPACE",()->durable(target,snapshot,limits,()->!stop.get()&&!ImportInstallControl.cancelled()));copies.discard(id);}
        catch(IOException error){recordCopyFailure(id,target.appId(),"MATERIALIZE",error);return new ImportExecutionSandbox.Outcome(stop.get()?"STOPPED":"FAILED",stop.get()?"STOP_REQUESTED":"COPY_FAILED",null,0,null);}
        if(copy==null)return new ImportExecutionSandbox.Outcome("FAILED","PLAN_CONTENT_CHANGED",null,0,null);
        if(stop.get()||ImportInstallControl.cancelled())return new ImportExecutionSandbox.Outcome("STOPPED","STOP_REQUESTED",null,0,null);
        try{if(directoryBytes(copy)>limits.diskBytes()){discardQuotaBlocked(id,target.appId());return new ImportExecutionSandbox.Outcome("FAILED","DISK_QUOTA",null,0,null);}}
        catch(IOException error){return new ImportExecutionSandbox.Outcome("FAILED","COPY_FAILED",null,0,null);}
        String mountedCopy=null;
        if(volumes!=null){
            try{
                mountedCopy=ImportInstallDiagnostics.call("PREPARE_VOLUME",()->prepareVolume(id,target,snapshot,copy,limits));
            }catch(IOException error){return new ImportExecutionSandbox.Outcome("FAILED",error.getMessage()!=null&&error.getMessage().matches("DISK_VOLUME_[A-Z0-9_:]{1,110}")?error.getMessage():"DISK_VOLUME_UNAVAILABLE",null,0,null);}
            if(stop.get()||ImportInstallControl.cancelled())return new ImportExecutionSandbox.Outcome("STOPPED","STOP_REQUESTED",null,0,null);
        }
        var request=new ImportExecutionSandbox.Request(id,target.appId(),copy,target.directory(),target.command(),limits,target.network(),target.lifetime(),true,target.port(),target.healthPath(),mountedCopy);
        var outcome=sandbox.execute(request,file(id,".guard.sh"),file(id,".env"),file(id,".output.log"),new ImportExecutionSandbox.Journal(){
            @Override public void created(String containerId){ledger.container(ledger.find(id),containerId);}
            @Override public void started(String startedAt){
                var current=ledger.find(id);
                if(current!=null&&"PREPARING".equals(current.state()))ledger.running(current,startedAt);
            }
            @Override public void finished(ImportExecutionSandbox.Outcome result){
                var current=ledger.find(id);
                if(current==null||!current.active())return;
                boolean cleaned=!"STOP_FAILED".equals(result.state())&&cleanup(id);
                ledger.finish(current,result.state(),result.reason(),result.exitCode(),result.outputBytes(),cleaned?"DONE":"FAILED");
            }
        },()->stop.get()||ImportInstallControl.cancelled());
        if(outcome!=null&&"SUCCEEDED".equals(outcome.state())){
            try{if(directoryBytes(copy)>limits.diskBytes()){discardQuotaBlocked(id,target.appId());return new ImportExecutionSandbox.Outcome("FAILED","DISK_QUOTA",outcome.exitCode(),outcome.outputBytes(),outcome.containerId());}}
            catch(IOException error){return new ImportExecutionSandbox.Outcome("FAILED","COPY_FAILED",outcome.exitCode(),outcome.outputBytes(),outcome.containerId());}
        }
        return outcome;
    }

    /** 只记录固定原因码与类型，不输出可能含工程秘密的路径、异常消息或堆栈。 */
    private static void recordCopyFailure(UUID id,long appId,String stage,IOException error){
        Throwable root=error;
        while(root.getCause()!=null&&root.getCause()!=root)root=root.getCause();
        String message=root.getMessage();
        String reason=switch(message==null?"":message){
            case "实际文件长度与清单不一致。"->"FILE_LENGTH_MISMATCH";
            case "工作副本内容已变化。","工作区内容或属性已变化。"->"CONTENT_CHANGED";
            case "目录数量超限。"->"DIRECTORY_LIMIT";
            case "快照任务失效或超时。"->"COPY_EXPIRED";
            case "快照清单无效。"->"MANIFEST_INVALID";
            default->"COPY_IO_FAILED";
        };
        String site=Arrays.stream(root.getStackTrace()).filter(frame->frame.getClassName().startsWith("com.dbts.glyahhaigeneratecode."))
            .findFirst().map(frame->frame.getClassName()+"."+frame.getMethodName()+":"+frame.getLineNumber()).orElse("UNKNOWN");
        log.warn("导入执行副本失败 executionId={} appId={} stage={} reason={} type={} site={}",id,appId,stage,reason,root.getClass().getSimpleName(),site);
    }

    /** 按冻结身份检查服务；已退出的资源清理并落终态，不自动重跑。 */
    public String executionReason(long ownerId,long appId,UUID planId,UUID id){
        var row=ledger.find(id);
        return row!=null&&row.ownerId()==ownerId&&row.appId()==appId&&row.planId().equals(planId)?row.reason():null;
    }

    /** 按冻结身份检查服务；已退出的资源清理并落终态，不自动重跑。 */
    public String executionState(long ownerId,long appId,UUID planId,UUID id){
        var row=ledger.find(id);
        if(row==null||row.ownerId()!=ownerId||row.appId()!=appId||!row.planId().equals(planId))return "UNKNOWN";
        if(!"RUNNING".equals(row.state()))return row.state();
        String state=sandbox.executionState(row.containerId());
        if(!"FAILED".equals(state))return state;
        boolean gone=containersGone(row);
        boolean cleaned=gone&&cleanup(id);
        try{return ledger.finish(row,gone?"FAILED":"STOP_FAILED",gone?"SERVICE_EXITED":"CONTAINER_NOT_CONFIRMED_GONE",null,0,cleaned?"DONE":"FAILED").state();}
        catch(MyException conflict){
            var current=ledger.find(id);
            // 监督线程可能已经确认终态；只复用同一冻结身份的权威结果，不把正常竞争记成未知。
            return current!=null&&current.ownerId()==ownerId&&current.appId()==appId&&current.planId().equals(planId)&&!current.active()?current.state():"UNKNOWN";
        }
    }

    /** 按冻结身份读取执行摘要；未知身份统一返回 null，避免泄露其他用户执行记录。 */
    public ImportExecutionLedger.Detail detail(long ownerId,long appId,UUID planId,UUID id){
        var detail=ledger.detail(id);
        return detail!=null&&detail.ownerId()==ownerId&&detail.appId()==appId&&detail.planId().equals(planId)?detail:null;
    }

    /** 读取已脱敏执行日志，限制返回字节数，避免把日志文件直接搬进 JVM。 */
    public String log(long ownerId,long appId,UUID planId,UUID id,int maxBytes){
        if(maxBytes<1||maxBytes>1<<20)throw new IllegalArgumentException("日志长度无效。");
        var detail=detail(ownerId,appId,planId,id);
        if(detail==null)return null;
        Path path=file(id,".output.log");
        if(!Files.isRegularFile(path,LinkOption.NOFOLLOW_LINKS))return "";
        try(var input=Files.newInputStream(path)){
            byte[] bytes=input.readNBytes(maxBytes);
            String value=ImportLogMask.mask(new String(bytes,StandardCharsets.UTF_8));
            return value+(Files.size(path)>bytes.length?"\n[日志已截断]":"");
        }catch(IOException error){throw new MyException(com.dbts.glyahhaigeneratecode.exception.ErrorCode.OPERATION_ERROR,"执行日志暂时不可读。");}
    }

    /** 请求停止：本实例执行中的由执行线程结束容器并确认消失；执行者已不在时直接按账本核对。 */
    public ImportExecutionLedger.Row stop(UUID id,Duration timeout){
        var row=ledger.find(id);
        if(row==null)return null;
        if(!row.active()){
            // 结束执行不等于资源已消失。允许再次停止重试清理，但不重开执行账本。
            if(!"DONE".equals(row.cleanup()))reconcileRow(row);
            return ledger.find(id);
        }
        var flag=active.get(id);
        if(flag!=null){
            try{ledger.stopping(row);}catch(MyException conflict){/* 状态已推进，以执行线程结果为准 */}
            flag.set(true);
            long deadline=System.nanoTime()+timeout.toNanos();
            while(System.nanoTime()<deadline){
                var current=ledger.find(id);
                if(current!=null&&!current.active())return current;
                try{Thread.sleep(100);}catch(InterruptedException error){Thread.currentThread().interrupt();break;}
            }
            return ledger.find(id);
        }
        if(row.active())return finishDetached(row);
        if(guard.parent(row.executorPid(),row.executorStart())==ImportStartupResourceGuard.Parent.GONE){reconcileRow(row);}
        return ledger.find(id);
    }

    /** 启动时对账：执行者已不在的登记逐个结束容器并确认消失；无登记的带标签容器与副本残留一并清理，执行输出日志保留。 */
    public Reconciled reconcile(){
        int rows=0,containers=0,files=0;var unresolved=new ArrayList<String>();
        var keep=new HashSet<String>();var handled=new HashSet<String>();
        for(var row:ledger.outstanding()){
            boolean mine=row.executorPid()==pid&&row.executorStart().equals(started)&&active.containsKey(row.id());
            var owner=mine?ImportStartupResourceGuard.Parent.ALIVE:guard.parent(row.executorPid(),row.executorStart());
            if(row.active()&&owner!=ImportStartupResourceGuard.Parent.GONE){keep.add(row.id().toString());if(owner==ImportStartupResourceGuard.Parent.UNKNOWN)unresolved.add(row.id()+":EXECUTOR_UNKNOWN");continue;}
            if(reconcileRow(row)){rows++;handled.add(row.id().toString());}else{unresolved.add(row.id()+":NOT_CONFIRMED");keep.add(row.id().toString());}
        }
        var managed=sandbox.managed();
        if(managed==null)unresolved.add("ENGINE_UNAVAILABLE");
        else for(var item:managed.entrySet()){
            if(keep.contains(item.getValue())||handled.contains(item.getValue()))continue;
            if(sandbox.removed(item.getKey()))containers++;else unresolved.add(item.getKey()+":CONTAINER_NOT_CONFIRMED_GONE");
        }
        try(var entries=Files.list(root)){
            for(var path:entries.toList()){
                String name=path.getFileName().toString();String key=name.length()>=36?name.substring(0,36):name;
                if(name.endsWith(".output.log")||keep.contains(key)||!isUuid(key))continue;
                if(cleanup(UUID.fromString(key)))files++;else unresolved.add(key+":FILES");
            }
        }catch(IOException error){unresolved.add("ROOT_UNREADABLE");}
        if(volumes!=null){
            Path folder=root.resolve("apps");
            if(Files.exists(folder,LinkOption.NOFOLLOW_LINKS))try(var resources=Files.list(folder)){
                ProjectImportStaging.guardManagedPath(folder);
                for(var resource:resources.filter(path->path.getFileName().toString().matches("[1-9][0-9]*\\.volume\\.json")).toList()){
                    try{
                        ProjectImportStaging.guardManagedPath(resource);
                        var record=new com.fasterxml.jackson.databind.ObjectMapper().readTree(Files.readString(resource));
                        long appId=Long.parseLong(resource.getFileName().toString().split("\\.")[0]);
                        if(!Long.toString(appId).equals(record.path("appId").asText()))throw new IOException("身份冲突");
                        volumes.inspect(appId,record.path("contentHash").asText(),record.path("diskBytes").asLong());
                    }catch(IOException|RuntimeException error){unresolved.add(resource.getFileName()+":DISK_NOT_CONFIRMED");}
                }
            }catch(IOException error){unresolved.add("DISK_RESOURCES_UNREADABLE");}
        }
        return new Reconciled(rows,containers,files,List.copyOf(unresolved));
    }

    /** 用户触发的应用级对账只处理该应用，不扫描或清理其他用户资源。 */
    public Reconciled reconcileApp(long appId){
        int rows=0;var unresolved=new ArrayList<String>();
        for(var row:ledger.forApp(appId)){
            if(!row.active()&&"DONE".equals(row.cleanup()))continue;
            if(reconcileRow(row))rows++;else unresolved.add(row.id()+":NOT_CONFIRMED");
        }
        return new Reconciled(rows,0,0,List.copyOf(unresolved));
    }

    private boolean reconcileRow(ImportExecutionLedger.Row row){
        boolean gone=sandbox.removed(row.containerId()!=null?row.containerId():row.containerName());
        if(row.containerId()!=null&&gone)gone=sandbox.removed(row.containerName());
        boolean cleaned=gone&&cleanup(row.id());
        try{
            if(row.active())ledger.finish(row,gone?"INTERRUPTED":"STOP_FAILED",gone?"EXECUTOR_GONE":"CONTAINER_NOT_CONFIRMED_GONE",null,0,cleaned?"DONE":"FAILED");
            else if(cleaned)ledger.cleaned(row);
        }catch(MyException conflict){return false;}
        return cleaned;
    }

    /** 把来源副本放到应用级持久目录。来源哈希变化时不覆盖，避免把新旧结果拼在一起。 */
    private Path durable(Target target,ImportStartupSnapshotStore.Snapshot snapshot,ImportExecutionSandbox.Limits limits,java.util.function.BooleanSupplier eligible)throws IOException{
        Path folder=root.resolve("apps");Path durable=folder.resolve(Long.toString(target.appId()));Path marker=folder.resolve(target.appId()+".source");
        Files.createDirectories(folder);
        if(Files.exists(marker,LinkOption.NOFOLLOW_LINKS)){
            if(snapshot.contentHash().equals(Files.readString(marker).strip()))return durable;
            if(volumes==null)return null;
            Path resource=folder.resolve(target.appId()+".volume.json");ProjectImportStaging.guardManagedPath(resource);
            if(!Files.exists(resource,LinkOption.NOFOLLOW_LINKS)||new com.fasterxml.jackson.databind.ObjectMapper().readTree(Files.readString(resource)).path("ownerId").asLong()!=target.ownerId())return null;
            // 新确认内容只在旧执行和容器都已退出后重建副本，不覆盖仍使用旧版本的资源。
            if(ledger.forApp(target.appId()).stream().anyMatch(row->!row.id().equals(snapshot.id())&&(row.active()||!"DONE".equals(row.cleanup())))||!removeDurable(target.appId()))return null;
        }
        long size=snapshot.files().stream().mapToLong(ProjectImportStaging.StoredFile::size).sum();
        if(size>limits.diskBytes())throw new IOException("DISK_QUOTA");
        Path staged=copies.materialize(snapshot,eligible);
        // 与导入发布复用同一个有界改名策略，不让 Windows 瞬时占用只在执行副本阶段漏处理。
        ImportTaskService.moveWorkspace(staged,durable);
        Files.writeString(marker,snapshot.contentHash(),StandardOpenOption.CREATE_NEW,StandardOpenOption.WRITE);
        return durable;
    }

    /** 冻结清单放在容器不可写的目录；资源登记先于平台创建，失败保留登记供清理。 */
    private String prepareVolume(UUID executionId,Target target,ImportStartupSnapshotStore.Snapshot snapshot,Path source,ImportExecutionSandbox.Limits limits)throws IOException{
        Path folder=root.resolve("apps");Path manifest=folder.resolve(target.appId()+".manifest.json");Path resource=folder.resolve(target.appId()+".volume.json");
        ProjectImportStaging.guardManagedPath(manifest);ProjectImportStaging.guardManagedPath(resource);
        var json=new com.fasterxml.jackson.databind.ObjectMapper();
        // 1. 绑定所有者、原内容和额度；旧产物不随新版本或新额度被接管。
        if(Files.exists(resource,LinkOption.NOFOLLOW_LINKS)){
            var existing=json.readTree(Files.readString(resource));
            if(existing.path("ownerId").asLong()!=target.ownerId()||!target.contentHash().equals(existing.path("contentHash").asText())||existing.path("diskBytes").asLong()!=limits.diskBytes())throw new IOException("DISK_VOLUME_IDENTITY_CONFLICT");
        }else{
            var files=new ArrayList<Map<String,Object>>();
            for(var file:snapshot.files())if(!file.directory())files.add(Map.of("path",file.path(),"size",file.size(),"sha256",file.sha256(),"executable",snapshot.attributes().getOrDefault(file.path(),"-").startsWith("x")));
            var filePaths=snapshot.files().stream().filter(file->!file.directory()).map(ProjectImportStaging.StoredFile::path).collect(java.util.stream.Collectors.toSet());
            var directories=snapshot.attributes().keySet().stream().filter(path->!".".equals(path)&&!filePaths.contains(path)).sorted().toList();
            Files.writeString(manifest,json.writeValueAsString(Map.of("appId",Long.toString(target.appId()),"contentHash",target.contentHash(),"diskBytes",limits.diskBytes(),"formatVersion",2,"files",files,"directories",directories)),StandardOpenOption.CREATE_NEW);
            Files.writeString(resource,json.writeValueAsString(Map.of("appId",Long.toString(target.appId()),"ownerId",target.ownerId(),"contentHash",target.contentHash(),"diskBytes",limits.diskBytes(),"executionId",executionId.toString(),"planId",target.planId().toString(),"planRevision",target.planRevision(),"state","PREPARING")),StandardOpenOption.CREATE_NEW);
        }
        // 2. 平台逐文件复核后才把受限挂载交给容器，不把 Windows 来源目录作为失败备用。
        String mounted=volumes.prepare(target.appId(),target.contentHash(),limits.diskBytes(),source,manifest);
        var record=json.readTree(Files.readString(resource));
        ((com.fasterxml.jackson.databind.node.ObjectNode)record).put("state","READY").put("mountPath",mounted);
        Path pending=folder.resolve(target.appId()+".volume.pending");ProjectImportStaging.guardManagedPath(pending);
        Files.writeString(pending,json.writeValueAsString(record),StandardOpenOption.CREATE_NEW);
        Files.move(pending,resource,StandardCopyOption.REPLACE_EXISTING,StandardCopyOption.ATOMIC_MOVE);
        return mounted;
    }

    private long directoryBytes(Path directory)throws IOException{
        long total=0;
        try(var walk=Files.walk(directory)){
            for(var path:walk.filter(Files::isRegularFile).toList()){
                total+=Files.size(path);
                if(total<0)return Long.MAX_VALUE;
            }
        }
        return total;
    }

    /** 按容器 id 和容器名分别确认删除。任一还在就视为没停干净。 */
    private boolean containersGone(ImportExecutionLedger.Row row){
        boolean gone=sandbox.removed(row.containerId()!=null?row.containerId():row.containerName());
        if(row.containerId()!=null&&gone)gone=sandbox.removed(row.containerName());
        return gone;
    }

    /**
     * 额度被上一轮残留占满时丢弃应用级副本与受限卷。
     *
     * 持久副本按内容身份复用，失败的写入会留在里面；不清理的话同一应用后续执行会一直 DISK_QUOTA。
     * 仍有其它未结束执行时不动副本，避免破坏在跑的资源。
     */
    private void discardQuotaBlocked(UUID current,long appId){
        boolean busy=ledger.forApp(appId).stream().anyMatch(row->!row.id().equals(current)&&(row.active()||!"DONE".equals(row.cleanup())));
        if(!busy)removeDurable(appId);
    }

    /** 删除应用级持久副本。目录里出现链接时停止，不跟着链接删到目录外面。 */
    private boolean removeDurable(long appId){
        Path folder=root.resolve("apps").normalize();
        Path durable=folder.resolve(Long.toString(appId)).normalize();
        Path marker=folder.resolve(appId+".source").normalize();
        if(!durable.startsWith(folder)||!marker.startsWith(folder))return false;
        try{
            Path resource=folder.resolve(appId+".volume.json");Path manifest=folder.resolve(appId+".manifest.json");
            ProjectImportStaging.guardManagedPath(resource);ProjectImportStaging.guardManagedPath(manifest);
            if(Files.exists(resource,LinkOption.NOFOLLOW_LINKS)){
                var value=new com.fasterxml.jackson.databind.ObjectMapper().readTree(Files.readString(resource));
                if(volumes==null||!Long.toString(appId).equals(value.path("appId").asText())||!volumes.release(appId,value.path("contentHash").asText(),value.path("diskBytes").asLong()))return false;
            }
            deleteBounded(durable);
            if(Files.isSymbolicLink(marker))return false;
            Files.deleteIfExists(marker);
            Files.deleteIfExists(resource);Files.deleteIfExists(manifest);
            return !Files.exists(durable,LinkOption.NOFOLLOW_LINKS)&&!Files.exists(marker,LinkOption.NOFOLLOW_LINKS);
        }catch(IOException error){return false;}
    }

    private static void deleteBounded(Path directory)throws IOException{
        if(!Files.exists(directory,LinkOption.NOFOLLOW_LINKS))return;
        if(Files.isSymbolicLink(directory))throw new IOException("持久副本是链接，已停止删除。");
        Files.walkFileTree(directory,new SimpleFileVisitor<>(){
            @Override public FileVisitResult preVisitDirectory(Path path,BasicFileAttributes attributes)throws IOException{
                if(attributes.isSymbolicLink()||attributes.isOther())throw new IOException("持久副本包含链接，已停止删除。");
                return FileVisitResult.CONTINUE;
            }
            @Override public FileVisitResult visitFile(Path file,BasicFileAttributes attributes)throws IOException{
                if(attributes.isSymbolicLink()||attributes.isOther())throw new IOException("持久副本包含链接，已停止删除。");
                Files.delete(file);
                return FileVisitResult.CONTINUE;
            }
            @Override public FileVisitResult postVisitDirectory(Path path,IOException failure)throws IOException{
                if(failure!=null)throw failure;
                Files.delete(path);
                return FileVisitResult.CONTINUE;
            }
        });
    }

    /** 服务线程已经返回后，停止仍以容器消失为准，不删除持久副本。 */
    private ImportExecutionLedger.Row finishDetached(ImportExecutionLedger.Row row){
        boolean gone=sandbox.removed(row.containerId()!=null?row.containerId():row.containerName());
        if(row.containerId()!=null&&gone)gone=sandbox.removed(row.containerName());
        boolean cleaned=gone&&cleanup(row.id());
        try{return ledger.finish(ledger.find(row.id()),gone?"STOPPED":"STOP_FAILED",gone?"STOP_REQUESTED":"CONTAINER_NOT_CONFIRMED_GONE",null,0,cleaned?"DONE":"FAILED");}
        catch(MyException conflict){return ledger.find(row.id());}
    }

    /** 删除本次执行的封存块与临时文件，均限定在执行根目录内；应用持久副本和输出日志保留。 */
    private boolean cleanup(UUID id){
        return ImportInstallDiagnostics.call("CLEANUP",()->cleanupObserved(id));
    }

    private boolean cleanupObserved(UUID id){
        try{
            copies.discardMaterialized(id);copies.discard(id);
            for(String suffix:List.of(".guard.sh",".env")){var path=file(id,suffix);ProjectImportStaging.guardManagedPath(path);Files.deleteIfExists(path);}
            return copies.inputAbsent(id)&&!Files.exists(copies.materialized(id),LinkOption.NOFOLLOW_LINKS);
        }catch(IOException|RuntimeException error){return false;}
    }

    private Path file(UUID id,String suffix){return root.resolve(id+suffix);}
    private static boolean isUuid(String value){try{return UUID.fromString(value).toString().equals(value);}catch(IllegalArgumentException error){return false;}}
    static String sha(String value){
        try{return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(value.getBytes(StandardCharsets.UTF_8)));}
        catch(java.security.NoSuchAlgorithmException error){throw new IllegalStateException("摘要不可用。");}
    }
}
