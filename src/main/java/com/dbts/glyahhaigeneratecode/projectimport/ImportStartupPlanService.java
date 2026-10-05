package com.dbts.glyahhaigeneratecode.projectimport;

import com.dbts.glyahhaigeneratecode.exception.ErrorCode;
import com.dbts.glyahhaigeneratecode.exception.MyException;
import com.dbts.glyahhaigeneratecode.model.Entity.User;
import org.springframework.stereotype.Service;
import java.time.Instant;
import java.util.List;
import java.util.UUID;

/**
 * 冻结运行计划：按当前已复核识别生成不可变 planRevision，并单独记录本次确认。
 * 识别批准、手动配置和以往运行都不能代替确认；计划或权限变化后旧确认失效并保存差异。本步不安装、不构建、不启动。
 */
@Service
public class ImportStartupPlanService {
    private static final org.slf4j.Logger log=org.slf4j.LoggerFactory.getLogger(ImportStartupPlanService.class);
    /** executable 恒为 false：隔离副本与安装/运行授权接入前，确认的计划也不能执行。 */
    public record View(UUID planId,String appId,UUID scanId,String planRevision,String state,String revision,boolean aggregateAcknowledged,Instant confirmedAt,
        boolean executable,ImportStartupPlan.Content plan,String staleReason,List<ImportStartupPlan.Change> diff,UUID diffBase){ }
    private final ImportStartupService startup;
    private final ImportStartupPlanRepository plans;
    public ImportStartupPlanService(ImportStartupService startup,ImportStartupPlanRepository plans){this.startup=startup;this.plans=plans;}

    /** 生成草稿计划；同内容重复请求返回原计划。差异基准是最近一次确认过的其他计划。 */
    public View create(User user,long appId,UUID scanId,List<String> serviceIds){
        if(scanId==null||serviceIds==null)throw new MyException(ErrorCode.PARAMS_ERROR,"缺少识别记录或所选服务。");
        return startup.withPlanInput(user,appId,scanId,input->{
            // 1. 只用同一锁内复核过的当前识别生成；过期识别不能产生计划。
            if(input==null)throw new MyException(ErrorCode.CONFLICT_ERROR,"识别结果已过期，请重新识别。");
            ImportStartupPlan.Content content;
            try{content=ImportStartupPlan.build(input.inspection(),input.files(),input.manual(),serviceIds);}
            catch(IllegalArgumentException error){throw new MyException(ErrorCode.PARAMS_ERROR,"所选服务无效或已变化，请重新选择。");}
            // 2. 与上次确认的计划比较，展示本次需要重新确认的差异。
            return draft(plans.create(user.getId(),content));
        });
    }

    /** 读取时重新生成并比较；内容、识别、配置或可选服务变化都会使计划失效。 */
    public View get(User user,long appId,UUID planId){
        var plan=require(user,appId,planId);
        return draft(refresh(user,plan));
    }
    public View current(User user,long appId){
        if(user==null||user.getId()==null)throw new MyException(ErrorCode.PARAMS_ERROR,"登录状态无效。");
        var plan=plans.latest(user.getId(),appId);
        if(plan==null)return null;
        return draft(refresh(user,plan));
    }

    /** 确认绑定请求中的 planRevision；同一锁内重新生成，变化则旧计划失效并返回冲突。聚合脚本须显式确认展开范围。 */
    public View confirm(User user,long appId,UUID planId,String planRevision,boolean aggregateAcknowledged){
        if(planRevision==null||!planRevision.matches("[0-9a-f]{64}"))throw new MyException(ErrorCode.PARAMS_ERROR,"缺少有效计划版本。");
        var initial=revoked(user,require(user,appId,planId));
        if(!List.of("DRAFT","CONFIRMED").contains(initial.state()))throw new MyException(ErrorCode.CONFLICT_ERROR,"计划已失效或已被替代，请重新生成。");
        return startup.withPlanInput(user,appId,initial.content().scanId(),input->{
            // 1. 锁内重读，旧请求不能确认已经失效或被替代的计划。
            var plan=require(user,appId,planId);
            if(!List.of("DRAFT","CONFIRMED").contains(plan.state()))throw new MyException(ErrorCode.CONFLICT_ERROR,"计划已失效或已被替代，请重新生成。");
            if(!plan.planRevision().equals(planRevision))throw new MyException(ErrorCode.CONFLICT_ERROR,"计划版本不一致，请刷新后确认。");
            // 2. 当前内容重新生成计划；任何差异都使本计划失效，不偷换命令继续。
            var stale=staleness(plan,input);
            if(stale!=null){plans.stale(plan,stale);throw new MyException(ErrorCode.CONFLICT_ERROR,"计划已变化，原确认失效，请查看差异后重新确认。");}
            if("CONFIRMED".equals(plan.state()))return view(plan,null,null);
            if(!plan.content().aggregates().isEmpty()&&!aggregateAcknowledged)
                throw new MyException(ErrorCode.PARAMS_ERROR,"计划包含聚合脚本或会带上未选服务，请查看展开内容后重新确认。");
            // 3. 只记录本次确认；安装与运行授权在隔离副本接入后另行处理。
            return view(plans.confirm(plan,aggregateAcknowledged),null,null);
        });
    }

    /** 草稿附带相对最近一次确认计划的差异；其他状态按自身记录展示。 */
    private View draft(ImportStartupPlanRepository.Plan plan){
        if(!"DRAFT".equals(plan.state()))return view(plan,null,null);
        ImportStartupPlanRepository.Plan base=null;
        try{base=plans.lastConfirmed(plan.ownerId(),plan.appId(),plan.id());}
        catch(MyException error){log.warn("上次确认计划无法复核 appId={}",plan.appId());}
        boolean differs=base!=null&&!base.planRevision().equals(plan.planRevision());
        return view(plan,differs?ImportStartupPlan.diff(base.content(),plan.content()):List.of(),differs?base.id():null);
    }
    private ImportStartupPlanRepository.Plan refresh(User user,ImportStartupPlanRepository.Plan stored){
        var plan=revoked(user,stored);
        if(!List.of("DRAFT","CONFIRMED").contains(plan.state()))return plan;
        return startup.withPlanInput(user,plan.appId(),plan.content().scanId(),input->{
            var current=require(user,plan.appId(),plan.id());
            if(!List.of("DRAFT","CONFIRMED").contains(current.state()))return current;
            var stale=staleness(current,input);
            return stale==null?current:plans.stale(current,stale);
        });
    }
    /** 返回失效原因与差异；仍有效时返回 null。 */
    private static ImportStartupPlanRepository.Stale staleness(ImportStartupPlanRepository.Plan plan,ImportStartupService.PlanInput input){
        if(input==null)return new ImportStartupPlanRepository.Stale("INSPECTION_STALE",List.of());
        ImportStartupPlan.Content regenerated;
        try{regenerated=ImportStartupPlan.build(input.inspection(),input.files(),input.manual(),plan.content().services().stream().map(ImportStartupPlan.Service::id).toList());}
        catch(IllegalArgumentException error){
            // 所选服务已不在当前识别中（如补充结果作废或手动项删除），差异列出消失的服务。
            var present=input.inspection().result().candidates().stream().map(ImportStartupScanner.Candidate::id).collect(java.util.stream.Collectors.toSet());
            return new ImportStartupPlanRepository.Stale("SELECTION_UNAVAILABLE",plan.content().services().stream().filter(service->!present.contains(service.id()))
                .map(service->new ImportStartupPlan.Change("services["+service.id()+"]",service.name(),null)).toList());
        }
        if(ImportStartupPlan.revision(regenerated).equals(plan.planRevision()))return null;
        return new ImportStartupPlanRepository.Stale("PLAN_CHANGED",ImportStartupPlan.diff(plan.content(),regenerated));
    }
    /** 导入任务替换、应用不可用或归属变化时，旧计划与确认一并失效。 */
    private ImportStartupPlanRepository.Plan revoked(User user,ImportStartupPlanRepository.Plan plan){
        if(!List.of("DRAFT","CONFIRMED").contains(plan.state()))return plan;
        String reason=startup.planRevoked(user,plan.appId(),plan.content().importTaskId());
        return reason==null?plan:plans.stale(plan,new ImportStartupPlanRepository.Stale(reason,List.of()));
    }
    private ImportStartupPlanRepository.Plan require(User user,long appId,UUID planId){
        if(user==null||user.getId()==null||appId<=0||planId==null)throw new MyException(ErrorCode.PARAMS_ERROR,"计划身份无效。");
        var plan=plans.find(user.getId(),appId,planId);
        if(plan==null)throw new MyException(ErrorCode.NOT_FOUND_ERROR,"运行计划不存在。");
        return plan;
    }
    private static View view(ImportStartupPlanRepository.Plan plan,List<ImportStartupPlan.Change> diff,UUID base){
        var changes=diff!=null?diff:plan.stale()==null?List.<ImportStartupPlan.Change>of():plan.stale().diff();
        return new View(plan.id(),Long.toString(plan.appId()),plan.content().scanId(),plan.planRevision(),plan.state(),Long.toString(plan.revision()),plan.acknowledged(),plan.confirmedAt(),
            false,plan.content(),plan.stale()==null?null:plan.stale().reason(),changes,base);
    }
}
