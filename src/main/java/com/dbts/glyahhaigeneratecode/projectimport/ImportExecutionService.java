package com.dbts.glyahhaigeneratecode.projectimport;

import com.dbts.glyahhaigeneratecode.config.RuntimeProperties;
import com.dbts.glyahhaigeneratecode.exception.ErrorCode;
import com.dbts.glyahhaigeneratecode.exception.MyException;
import com.dbts.glyahhaigeneratecode.model.Entity.User;
import com.dbts.glyahhaigeneratecode.runtime.DockerCli;
import com.dbts.glyahhaigeneratecode.service.AppService;
import org.springframework.stereotype.Service;
import java.io.IOException;
import java.nio.file.Path;
import java.time.Duration;
import java.util.HashSet;
import java.util.List;
import java.util.UUID;

/**
 * 隔离执行的业务接入：只接受仍有效的已确认计划及其列出的目录，内存必须硬上限。
 * 安装与运行经 ImportStartupPreparationService 调用；内存硬上限无法保证时只返回拒绝，不建容器。
 */
@Service
public class ImportExecutionService implements ImportExecutionActions {
    private static final org.slf4j.Logger log=org.slf4j.LoggerFactory.getLogger(ImportExecutionService.class);
    private final ImportStartupPlanService plans;
    private final AppService apps;
    private final ImportExecutionRunner runner;

    public ImportExecutionService(ImportStartupPlanService plans,AppService apps,ImportExecutionLedger ledger,ImportWorkspaceCoordinator coordinator,
                                  ImportStartupResourceGuard guard,DockerCli docker,RuntimeProperties runtime,ImportProperties properties)throws IOException{
        this.plans=plans;this.apps=apps;
        Path root=properties.getStagingRoot().resolveSibling("project_import_exec");
        var copies=new ImportStartupSnapshotStore(root,List.of(Path.of("temp/code_output"),Path.of("temp/code_deploy"),properties.getStagingRoot(),
            properties.getStagingRoot().resolveSibling("project_startup_private")),properties.getMaxFiles(),properties.getMaxSourceBytes());
        var engine=ImportExecutionSandbox.docker(docker);
        runner=new ImportExecutionRunner(ledger,new ImportExecutionSandbox(engine,runtime.getImage()),copies,guard,coordinator::withLock,root,
            new ImportDiskVolume(engine,Path.of("scripts/import_disk_volume.py")));
    }

    /** 只做限额准入。无法保证时返回原因，调用方不得因此启动服务。 */
    @Override public List<String> admit(long appId,ImportExecutionSandbox.Limits limits){
        if(limits==null||limits.memory()!=ImportExecutionSandbox.Memory.REQUIRE_HARD)throw new MyException(ErrorCode.PARAMS_ERROR,"执行必须保证内存硬上限。");
        return runner.admit(appId,limits);
    }

    /** 内部入口：计划须在本次读取时重新复核为 CONFIRMED，目录须是计划列出的服务、依赖或构建目录。 */
    @Override public ImportExecutionRunner.Result execute(User user,long appId,UUID planId,String directory,String command,ImportExecutionSandbox.Limits limits){
        if(limits==null||limits.memory()!=ImportExecutionSandbox.Memory.REQUIRE_HARD)throw new MyException(ErrorCode.PARAMS_ERROR,"执行必须保证内存硬上限。");
        var view=plans.get(user,appId,planId);
        if(!"CONFIRMED".equals(view.state()))throw new MyException(ErrorCode.CONFLICT_ERROR,"运行计划未确认或已失效。");
        var directories=new HashSet<String>();
        view.plan().services().forEach(service->directories.add(service.directory()));
        view.plan().dependencies().forEach(dependency->directories.add(dependency.directory()));
        view.plan().builds().forEach(step->directories.add(step.directory()));
        if(!directories.contains(directory))throw new MyException(ErrorCode.PARAMS_ERROR,"执行目录不在已确认计划中。");
        return execute(user,appId,planId,directory,command,limits,"task",ImportExecutionSandbox.Network.NONE,ImportExecutionSandbox.Lifetime.FINISH);
    }

    @Override public ImportExecutionRunner.Result execute(User user,long appId,UUID planId,String directory,String command,ImportExecutionSandbox.Limits limits,
                                                         String slot,ImportExecutionSandbox.Network network,ImportExecutionSandbox.Lifetime lifetime){
        if(limits==null||limits.memory()!=ImportExecutionSandbox.Memory.REQUIRE_HARD)throw new MyException(ErrorCode.PARAMS_ERROR,"执行必须保证内存硬上限。");
        var view=plans.get(user,appId,planId);
        if(!"CONFIRMED".equals(view.state()))throw new MyException(ErrorCode.CONFLICT_ERROR,"运行计划未确认或已失效。");
        var directories=new HashSet<String>();
        view.plan().services().forEach(service->directories.add(service.directory()));
        view.plan().dependencies().forEach(dependency->directories.add(dependency.directory()));
        view.plan().builds().forEach(step->directories.add(step.directory()));
        if(!directories.contains(directory))throw new MyException(ErrorCode.PARAMS_ERROR,"执行目录不在已确认计划中。");
        return execute(user,appId,planId,directory,command,limits,slot,network,lifetime,null);
    }

    @Override public ImportExecutionRunner.Result execute(User user,long appId,UUID planId,String directory,String command,ImportExecutionSandbox.Limits limits,
                                                         String slot,ImportExecutionSandbox.Network network,ImportExecutionSandbox.Lifetime lifetime,Integer port){
        if(limits==null||limits.memory()!=ImportExecutionSandbox.Memory.REQUIRE_HARD)throw new MyException(ErrorCode.PARAMS_ERROR,"执行必须保证内存硬上限。");
        var view=plans.get(user,appId,planId);
        if(!"CONFIRMED".equals(view.state()))throw new MyException(ErrorCode.CONFLICT_ERROR,"运行计划未确认或已失效。");
        var directories=new HashSet<String>();
        view.plan().services().forEach(service->directories.add(service.directory()));
        view.plan().dependencies().forEach(dependency->directories.add(dependency.directory()));
        view.plan().builds().forEach(step->directories.add(step.directory()));
        if(!directories.contains(directory))throw new MyException(ErrorCode.PARAMS_ERROR,"执行目录不在已确认计划中。");
        if(port!=null&&view.plan().services().stream().noneMatch(service->directory.equals(service.directory())&&port.equals(service.port())))
            throw new MyException(ErrorCode.PARAMS_ERROR,"端口不在已确认计划中。");
        String healthPath="/";
        if(lifetime==ImportExecutionSandbox.Lifetime.SERVE){
            var service=view.plan().services().stream().filter(item->slot.equals("service:"+ImportExecutionRunner.sha(item.id()).substring(0,32)))
                .findFirst().orElseThrow(()->new MyException(ErrorCode.PARAMS_ERROR,"服务不在已确认计划中。"));
            if(!directory.equals(service.directory())||!command.equals(service.command())||!java.util.Objects.equals(port,service.port()))
                throw new MyException(ErrorCode.PARAMS_ERROR,"服务与已确认计划不一致。");
            if(port!=null)healthPath=service.readiness().path();
        }
        return runner.execute(new ImportExecutionRunner.Target(user.getId(),appId,planId,view.planRevision(),view.plan().contentHash(),
            apps.resolveWorkspaceDir(user,appId),directory,command,slot,network,lifetime,port,healthPath),limits);
    }

    /** 删除应用前释放导入容器。返回 false 时应用和持久副本都必须保留。 */
    public boolean releaseApp(long appId){return runner.releaseApp(appId);}

    @Override public boolean stopOtherPlans(long appId,String planRevision){return runner.stopOtherPlans(appId,planRevision);}
    @Override public String stopExecution(UUID id){var row=runner.stop(id,Duration.ofSeconds(60));return row==null?"STOPPED":row.state();}
    @Override public String executionState(User user,long appId,UUID planId,UUID id){return runner.executionState(user.getId(),appId,planId,id);}
    @Override public String executionReason(User user,long appId,UUID planId,UUID id){return runner.executionReason(user.getId(),appId,planId,id);}

    public ImportExecutionLedger.Row stop(UUID executionId){return runner.stop(executionId,Duration.ofSeconds(60));}

    /** 启动后对账：上一实例遗留的执行容器与副本按实际资源核对清理，不自动重跑。 */
    @org.springframework.context.event.EventListener(org.springframework.boot.context.event.ApplicationReadyEvent.class)
    public void reconcileOnStartup(){
        var thread=new Thread(()->{
            try{
                var result=runner.reconcile();
                log.info("导入执行对账完成 rows={} containers={} files={} unresolved={}",result.rows(),result.containers(),result.files(),result.unresolved());
            }catch(RuntimeException error){log.warn("导入执行对账失败：{}",error.getClass().getSimpleName());}
        },"import-execution-reconcile");
        thread.setDaemon(true);thread.start();
    }
}
