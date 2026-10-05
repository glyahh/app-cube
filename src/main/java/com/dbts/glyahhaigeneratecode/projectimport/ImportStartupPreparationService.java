package com.dbts.glyahhaigeneratecode.projectimport;

import com.dbts.glyahhaigeneratecode.exception.ErrorCode;
import com.dbts.glyahhaigeneratecode.exception.MyException;
import com.dbts.glyahhaigeneratecode.model.Entity.User;
import org.springframework.stereotype.Service;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.UUID;

/**
 * 安装确认与按计划运行。单独安装不启动服务。
 * 依赖未核对、用户拒绝、或内存硬上限无法保证时，不调用执行，也不把拒绝记成已启动。
 * 启动时只读取已有记录，不自动重跑安装或运行。
 */
@Service
public class ImportStartupPreparationService {
    public record View(UUID preparationId,String appId,UUID planId,String planRevision,String kind,String decision,String state,String reason,
                       boolean runPermitted,boolean servicesStarted,List<ImportStartupPreparation.DependencyView> dependencies,List<ImportStartupPreparation.ServiceView> services){ }
    private final ImportStartupPlanService plans;
    private final ImportStartupPreparationRepository preparations;
    private final ImportExecutionActions execution;
    public ImportStartupPreparationService(ImportStartupPlanService plans,ImportStartupPreparationRepository preparations,ImportExecutionActions execution){
        this.plans=plans;this.preparations=preparations;this.execution=execution;
    }

    public View install(User user,long appId,UUID planId,String planRevision,boolean approved){
        var plan=confirmed(user,appId,planId,planRevision);
        var assessment=ImportStartupPreparation.assess(plan.plan());
        if(!approved)return view(plan,save(user,plan,"INSTALL","REJECTED","REJECTED","INSTALL_DECLINED",List.of()),assessment);
        var existing=preparations.find(user.getId(),appId,planId,planRevision,"INSTALL");
        if(existing!=null&&"SUCCEEDED".equals(existing.state()))return view(plan,existing,assessment);
        if(!assessment.installRequired())return view(plan,save(user,plan,"INSTALL","APPROVED","NOT_REQUIRED",null,List.of()),assessment);
        if(assessment.installCommands().isEmpty())
            return view(plan,save(user,plan,"INSTALL","APPROVED","BLOCKED","DEPENDENCY_UNKNOWN",List.of()),assessment);
        // 1. 先准入。内存硬上限无法保证时不执行安装命令，也不启动服务。
        var refused=execution.admit(appId,ImportExecutionSandbox.Limits.defaults());
        if(!refused.isEmpty())return view(plan,save(user,plan,"INSTALL","APPROVED","REFUSED",String.join(",",refused),List.of()),assessment);
        // 2. 只执行计划写明的安装命令，使用仅出站网络。失败不继续启动。产物留在执行副本。
        String failure=null;
        for(var dependency:plan.plan().dependencies())for(String command:dependency.installs()){
            var result=execution.execute(user,appId,planId,dependency.directory(),command,ImportExecutionSandbox.Limits.defaults(),"install",ImportExecutionSandbox.Network.EGRESS,ImportExecutionSandbox.Lifetime.FINISH);
            if(!"SUCCEEDED".equals(result.state())){failure=result.reason()==null?result.state():result.reason();break;}
        }
        String state=failure==null?"SUCCEEDED":"REFUSED".equals(failure)||failure.contains("MEMORY")?"REFUSED":"FAILED";
        return view(plan,save(user,plan,"INSTALL","APPROVED",state,failure,List.of()),assessment);
    }

    public View run(User user,long appId,UUID planId,String planRevision){
        var plan=confirmed(user,appId,planId,planRevision);
        var assessment=ImportStartupPreparation.assess(plan.plan());
        var install=preparations.find(user.getId(),appId,planId,planRevision,"INSTALL");
        // 1. 缺依赖或用户拒绝时停在询问，不启动任何服务，也不停掉已经在运行的服务。
        if(assessment.installRequired()&&(install==null||"REJECTED".equals(install.decision())))
            return view(plan,save(user,plan,"RUN","REJECTED","WAITING_INSTALL","INSTALL_REQUIRED",List.of()),assessment);
        if(install!=null&&List.of("BLOCKED","REFUSED","FAILED").contains(install.state()))
            return view(plan,save(user,plan,"RUN","APPROVED",install.state(),install.reason(),List.of()),assessment);
        if(!execution.stopOtherPlans(appId,planRevision))
            return view(plan,save(user,plan,"RUN","APPROVED","FAILED","STOP_FAILED",List.of()),assessment);
        var current=preparations.find(user.getId(),appId,planId,planRevision,"RUN");
        current=refresh(user,plan,current);
        // 2. 停止失败不放开重试。同一计划里已经就绪的服务直接保留，重复点击不会再启动一次。
        if(current!=null&&"STOP_FAILED".equals(current.reason()))return view(plan,current,assessment);
        var kept=current==null?List.<ImportStartupPreparation.ServiceView>of():ImportStartupPreparation.keepReady(current.services(),plan.plan());
        if(current!=null&&"READY".equals(current.state())&&kept.size()==plan.plan().services().size())return view(plan,current,assessment);
        return executeRun(user,plan,assessment,kept);
    }

    /** 只重试未就绪的服务。已就绪的执行编号保持不变，不重复启动。 */
    public View retry(User user,long appId,UUID planId,String planRevision){
        var plan=confirmed(user,appId,planId,planRevision);
        var assessment=ImportStartupPreparation.assess(plan.plan());
        var current=preparations.find(user.getId(),appId,planId,planRevision,"RUN");
        current=refresh(user,plan,current);
        if(current==null||!List.of("PARTIAL","FAILED","NONE_READY","REFUSED").contains(current.state()))
            throw new MyException(ErrorCode.CONFLICT_ERROR,"当前运行记录不能重试。");
        if("STOP_FAILED".equals(current.reason()))return view(plan,current,assessment);
        var install=preparations.find(user.getId(),appId,planId,planRevision,"INSTALL");
        if(assessment.installRequired()&&(install==null||!"SUCCEEDED".equals(install.state())&&!"NOT_REQUIRED".equals(install.state())))
            return view(plan,current,assessment);
        if(!execution.stopOtherPlans(appId,planRevision))
            return view(plan,save(user,plan,"RUN","APPROVED","FAILED","STOP_FAILED",current.services()),assessment);
        var kept=ImportStartupPreparation.keepReady(current.services(),plan.plan());
        return executeRun(user,plan,assessment,kept);
    }

    /** 停止本次运行里已登记的服务。容器没有消失时保持停止失败，不允许立刻重试。 */
    public View stop(User user,long appId,UUID planId,String planRevision){
        var plan=confirmed(user,appId,planId,planRevision);
        var assessment=ImportStartupPreparation.assess(plan.plan());
        var current=preparations.find(user.getId(),appId,planId,planRevision,"RUN");
        if(current==null)return view(plan,save(user,plan,"RUN","APPROVED","FAILED","NOT_RUNNING",List.of()),assessment);
        boolean failed=false;
        var services=new ArrayList<ImportStartupPreparation.ServiceView>();
        for(var service:current.services()){
            // 1. 只有已确认终态才算停下；仍在停止或结果不明时继续保留资源边界。
            String state=service.executionId()==null?"STOPPED":execution.stopExecution(service.executionId());
            boolean stopped=List.of("STOPPED","SUCCEEDED","FAILED","REFUSED","INTERRUPTED").contains(state==null?"UNKNOWN":state);
            failed|=!stopped;
            services.add(new ImportStartupPreparation.ServiceView(service.serviceId(),"STOP",stopped?"STOPPED":"STOP_FAILED",
                stopped?"STOP_REQUESTED":"CONTAINER_NOT_CONFIRMED_GONE",service.executionId(),null));
        }
        // 2. 原执行编号仅用于审计，停止的服务不能被后续运行当作 READY 复用。
        return view(plan,saveObserved(current,"FAILED",failed?"STOP_FAILED":"STOPPED",services),assessment);
    }

    public View current(User user,long appId,UUID planId){
        var plan=plans.get(user,appId,planId);
        var assessment=ImportStartupPreparation.assess(plan.plan());
        var run=preparations.find(user.getId(),appId,plan.planId(),plan.planRevision(),"RUN");
        if(run!=null)return view(plan,refresh(user,plan,run),assessment);
        var install=preparations.find(user.getId(),appId,plan.planId(),plan.planRevision(),"INSTALL");
        return install==null?null:view(plan,install,assessment);
    }

    private ImportStartupPreparationRepository.Row refresh(User user,ImportStartupPlanService.View plan,ImportStartupPreparationRepository.Row row){
        if(row==null)return null;
        var services=new ArrayList<ImportStartupPreparation.ServiceView>();
        boolean changed=false;
        for(var item:row.services()){
            if(!"READY".equals(item.state())||item.executionId()==null){services.add(item);continue;}
            String actual=execution.executionState(user,Long.parseLong(plan.appId()),plan.planId(),item.executionId());
            if("RUNNING".equals(actual)){services.add(item);continue;}
            changed=true;
            boolean uncertain=actual==null||List.of("UNKNOWN","STOP_FAILED","STOPPING","PREPARING").contains(actual);
            String reason=uncertain?"RUNTIME_UNCONFIRMED":execution.executionReason(user,Long.parseLong(plan.appId()),plan.planId(),item.executionId());
            services.add(new ImportStartupPreparation.ServiceView(item.serviceId(),"RUNTIME",uncertain?"STOP_FAILED":"FAILED",
                reason==null?"SERVICE_EXITED":reason,item.executionId(),null));
        }
        if(!changed)return row;
        String state=ImportStartupPreparation.aggregate(services);
        String reason=services.stream().anyMatch(item->"STOP_FAILED".equals(item.state()))?"STOP_FAILED":"SERVICE_EXITED";
        return saveObserved(row,state,reason,services);
    }

    /** 使用操作开始时读取的版本，拒绝把迟到的停止或状态查询写到新结果上。 */
    private ImportStartupPreparationRepository.Row saveObserved(ImportStartupPreparationRepository.Row row,String state,String reason,List<ImportStartupPreparation.ServiceView> services){
        return preparations.save(new ImportStartupPreparationRepository.Row(row.id(),row.ownerId(),row.appId(),row.planId(),row.planRevision(),
            row.contentHash(),row.kind(),row.decision(),state,reason,services,row.revision()+1));
    }

    private View executeRun(User user,ImportStartupPlanService.View plan,ImportStartupPreparation.Assessment assessment,List<ImportStartupPreparation.ServiceView> kept){
        var refused=execution.admit(Long.parseLong(plan.appId()),ImportExecutionSandbox.Limits.defaults());
        if(!refused.isEmpty()){
            var blocked=new java.util.HashMap<String,ImportStartupPreparation.ServiceView>();
            for(var item:kept)blocked.put(item.serviceId(),item);
            var services=new ArrayList<ImportStartupPreparation.ServiceView>();
            String reason=String.join(",",refused);
            for(var service:plan.plan().services())services.add(blocked.getOrDefault(service.id(),new ImportStartupPreparation.ServiceView(service.id(),"NOT_STARTED","REFUSED",reason,null)));
            String state=ImportStartupPreparation.aggregate(services);
            return view(plan,save(user,plan,"RUN","APPROVED",state,"NONE_READY".equals(state)?reason:reason,services),assessment);
        }
        var ready=new HashMap<String,ImportStartupPreparation.ServiceView>();
        for(var item:kept)ready.put(item.serviceId(),item);
        var builds=new HashMap<String,ImportExecutionRunner.Result>();
        var services=new ArrayList<ImportStartupPreparation.ServiceView>();
        // 2. 共享构建只执行一次。失败只挡住依赖它的服务，已就绪的服务不重新启动。
        for(var service:plan.plan().services()){
            var keptReady=ready.get(service.id());
            if(keptReady!=null){services.add(keptReady);continue;}
            if(service.build()!=null){
                var step=plan.plan().builds().stream().filter(item->item.id().equals(service.build())).findFirst().orElse(null);
                if(step==null){services.add(new ImportStartupPreparation.ServiceView(service.id(),"BUILD","FAILED","BUILD_MISSING",null));continue;}
                var build=builds.computeIfAbsent(step.id(),id->execution.execute(user,Long.parseLong(plan.appId()),plan.planId(),step.directory(),step.command(),ImportExecutionSandbox.Limits.defaults(),slot("build",step.id()),ImportExecutionSandbox.Network.EGRESS,ImportExecutionSandbox.Lifetime.FINISH));
                if(!"SUCCEEDED".equals(build.state())){services.add(new ImportStartupPreparation.ServiceView(service.id(),"BUILD","FAILED",build.reason(),build.executionId()));continue;}
            }
            if(service.command()==null){services.add(new ImportStartupPreparation.ServiceView(service.id(),"READY","READY","STATIC_ENTRY",null));continue;}
            var network=service.port()==null?ImportExecutionSandbox.Network.NONE:ImportExecutionSandbox.Network.LOOPBACK;
            var result=execution.execute(user,Long.parseLong(plan.appId()),plan.planId(),service.directory(),service.command(),ImportExecutionSandbox.Limits.defaults(),slot("service",service.id()),network,ImportExecutionSandbox.Lifetime.SERVE,service.port());
            // 3. 只有容器仍在运行才算就绪。写明端口时还必须拿到本次执行记下的本机地址。进程正常退出不算已启动。
            boolean staying="RUNNING".equals(result.state())&&(service.port()==null||result.address()!=null);
            String failure=staying?null:"SUCCEEDED".equals(result.state())?"NOT_READY":result.reason();
            services.add(new ImportStartupPreparation.ServiceView(service.id(),staying?"READY":"START",staying?"READY":"FAILED",failure,result.executionId(),result.address()));
        }
        String state=ImportStartupPreparation.aggregate(services);
        String reason="READY".equals(state)?null:"PARTIAL".equals(state)?"PARTIAL_FAILURE":services.stream().filter(item->item.reason()!=null).map(ImportStartupPreparation.ServiceView::reason).findFirst().orElse(state);
        return view(plan,save(user,plan,"RUN","APPROVED",state,reason,services),assessment);
    }

    private static String slot(String kind,String id){return kind+":"+ImportExecutionRunner.sha(id).substring(0,32);}

    private ImportStartupPlanService.View confirmed(User user,long appId,UUID planId,String planRevision){
        if(planRevision==null||!planRevision.matches("[0-9a-f]{64}"))throw new MyException(ErrorCode.PARAMS_ERROR,"缺少有效计划版本。");
        var plan=plans.get(user,appId,planId);
        if(!"CONFIRMED".equals(plan.state())||!plan.planRevision().equals(planRevision))
            throw new MyException(ErrorCode.CONFLICT_ERROR,"计划已变化，原确认失效，请查看差异后重新确认。");
        return plan;
    }
    private ImportStartupPreparationRepository.Row save(User user,ImportStartupPlanService.View plan,String kind,String decision,String state,String reason,List<ImportStartupPreparation.ServiceView> services){
        var existing=preparations.find(user.getId(),Long.parseLong(plan.appId()),plan.planId(),plan.planRevision(),kind);
        var row=new ImportStartupPreparationRepository.Row(existing==null?UUID.randomUUID():existing.id(),user.getId(),Long.parseLong(plan.appId()),plan.planId(),plan.planRevision(),
            plan.plan().contentHash(),kind,decision,state,reason,services,existing==null?0:existing.revision()+1);
        return preparations.save(row);
    }
    private static View view(ImportStartupPlanService.View plan,ImportStartupPreparationRepository.Row row,ImportStartupPreparation.Assessment assessment){
        boolean permitted="READY".equals(row.state());
        return new View(row.id(),plan.appId(),plan.planId(),plan.planRevision(),row.kind(),row.decision(),row.state(),row.reason(),permitted,
            ImportStartupPreparation.started(row.services()),assessment.dependencies(),row.services());
    }
}
