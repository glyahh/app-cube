package com.dbts.glyahhaigeneratecode.projectimport;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.UUID;

/**
 * 安装与运行的纯判断：目录存在、锁文件相同都不等于依赖就绪。
 * 只使用计划里写明的安装命令，不自行补 npm install 或启动命令。
 */
public final class ImportStartupPreparation {
    public record DependencyView(String id,String directory,String status,String reason){ }
    public record ServiceView(String serviceId,String phase,String state,String reason,UUID executionId,String address){
        public ServiceView(String serviceId,String phase,String state,String reason,UUID executionId){this(serviceId,phase,state,reason,executionId,null);}
    }
    public record Assessment(List<DependencyView> dependencies,List<String> installCommands,boolean installRequired){ }

    private ImportStartupPreparation(){ }

    /** 计划里的依赖状态保持未检查。没有依赖时才不需要安装。 */
    public static Assessment assess(ImportStartupPlan.Content plan){
        var dependencies=new ArrayList<DependencyView>();
        var commands=new LinkedHashSet<String>();
        for(var dependency:plan.dependencies()){
            // 1. 不把 UNKNOWN 改成 READY，也不因为目录里有文件就当成已安装。
            dependencies.add(new DependencyView(dependency.id(),dependency.directory(),dependency.status(),"UNCHECKED"));
            commands.addAll(dependency.installs());
        }
        boolean required=!dependencies.isEmpty();
        return new Assessment(List.copyOf(dependencies),List.copyOf(commands),required);
    }

    /** 准入失败时每个服务都停在未启动，不留下执行编号。 */
    public static List<ServiceView> notStarted(ImportStartupPlan.Content plan,String reason){
        return plan.services().stream().map(service->new ServiceView(service.id(),"NOT_STARTED","REFUSED",reason,null)).toList();
    }

    /** 重试只保留已经就绪的服务，其余重新执行。 */
    public static List<ServiceView> keepReady(List<ServiceView> previous,ImportStartupPlan.Content plan){
        var ready=new ArrayList<ServiceView>();
        for(var service:plan.services()){
            ServiceView kept=null;
            if(previous!=null)for(var item:previous)if(service.id().equals(item.serviceId())&&"READY".equals(item.state()))kept=item;
            if(kept!=null)ready.add(kept);
        }
        return List.copyOf(ready);
    }

    public static String aggregate(List<ServiceView> services){
        if(services.isEmpty())return "READY";
        long ready=services.stream().filter(service->"READY".equals(service.state())).count();
        if(ready==services.size())return "READY";
        if(ready==0)return services.stream().allMatch(service->"REFUSED".equals(service.state()))?"NONE_READY":"FAILED";
        return "PARTIAL";
    }

    public static boolean started(List<ServiceView> services){
        return services.stream().anyMatch(service->service.executionId()!=null&&List.of("READY","RUNNING","STOPPING","STOP_FAILED").contains(service.state()));
    }
}
