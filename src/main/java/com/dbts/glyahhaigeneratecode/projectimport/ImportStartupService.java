package com.dbts.glyahhaigeneratecode.projectimport;

import com.dbts.glyahhaigeneratecode.exception.ErrorCode;
import com.dbts.glyahhaigeneratecode.exception.MyException;
import com.dbts.glyahhaigeneratecode.model.Entity.User;
import com.dbts.glyahhaigeneratecode.service.AppService;
import com.dbts.glyahhaigeneratecode.service.support.ProjectImportManifest;
import com.dbts.glyahhaigeneratecode.service.support.ProjectImportStaging;
import org.springframework.stereotype.Service;
import java.io.IOException;
import java.nio.file.*;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/** 当前仅只读检查，不创建可执行计划；临时输入在返回前清理。补充候选只在全内容复核后发布。 */
@Service
public class ImportStartupService {
    private static final org.slf4j.Logger log=org.slf4j.LoggerFactory.getLogger(ImportStartupService.class);
    public record Inspection(UUID scanId,String appId,UUID importTaskId,String contentHash,String scope,String configRevision,String contextEpoch,ImportStartupScanner.Result result){ }
    public record InspectionRecord(UUID scanId,String appId,UUID importTaskId,String status,String diagnostic,Inspection inspection){ }
    /** 模型只收到批准目录内的合法文本、逐条未识别范围和已有候选；不含路径、凭据或历史。 */
    public record ModelInput(Map<String,String> files,List<ImportStartupScanner.Scope> ranges,List<ImportStartupScanner.Candidate> known){
        public ModelInput{files=Map.copyOf(files);ranges=List.copyOf(ranges);known=List.copyOf(known);}
    }
    /** 计划生成的输入：已复核识别（含已发布补充）、同一封存副本的解析文本和当前手动配置。 */
    public record PlanInput(Inspection inspection,Map<String,String> files,List<ImportStartupConfiguration.Manual> manual){
        public PlanInput{files=Map.copyOf(files);manual=List.copyOf(manual);}
    }
    /** 回包不能成为当前候选的确定原因；调用者据此作废结果，不重试成功。 */
    public static final class SupplementRejected extends RuntimeException{
        private final String code;
        public SupplementRejected(String code){super("启动补充回包未发布（"+code+"）。");this.code=code;}
        public String code(){return code;}
    }
    private final AppService apps;
    private final ImportTaskRepository repository;
    private final ImportTaskService tasks;
    private final ImportWorkspaceCoordinator coordinator;
    private final ImportStartupSnapshotStore snapshots;
    private final ImportStartupScanner scanner=new ImportStartupScanner();
    private final ImportPythonStartupReader python;
    private final ImportStartupConfigurationRepository configurations;
    private final ImportStartupInspectionRepository inspections;
    private final ImportStartupApprovalRepository approvals;

    public ImportStartupService(AppService apps,ImportTaskRepository repository,ImportTaskService tasks,
                                ImportWorkspaceCoordinator coordinator,ImportProperties properties,ImportPythonStartupReader python,ImportStartupConfigurationRepository configurations,ImportStartupInspectionRepository inspections,
                                ImportStartupApprovalRepository approvals)throws IOException{
        this.apps=apps;this.repository=repository;this.tasks=tasks;this.coordinator=coordinator;
        this.python=python;
        this.configurations=configurations;
        this.inspections=inspections;
        this.approvals=approvals;
        snapshots=new ImportStartupSnapshotStore(properties.getStagingRoot().resolveSibling("project_startup_private"),
            List.of(Path.of("temp/code_output"),Path.of("temp/code_deploy"),properties.getStagingRoot()),properties.getMaxFiles(),properties.getMaxSourceBytes());
    }

    public Inspection inspect(User user,long appId,String scope){
        return inspect(user,appId,scope,UUID.randomUUID(),null,()->!Thread.currentThread().isInterrupted(),resource->{},result->inspections.save(user.getId(),result));
    }
    public Inspection inspectManaged(User user,ImportStartupScan lease,java.util.function.BooleanSupplier eligible,java.util.function.Consumer<ImportProcessRunner.Resource> journal,java.util.function.Consumer<Inspection> finish){
        return inspect(user,lease.appId(),lease.scope(),lease.scanId(),lease,eligible,journal,finish);
    }
    public boolean inputsAbsent(UUID id){return snapshots.inputAbsent(id)&&python.inputAbsent(id);}
    public boolean helperInputAbsent(UUID id){return python.inputAbsent(id);}
    /** 仅在扫描执行者及其资源已确认停止后调用。 */
    public void cleanupStoppedInputs(UUID id)throws IOException{snapshots.discard(id);python.cleanupStoppedInput(id);}
    private Inspection inspect(User user,long appId,String scope,UUID id,ImportStartupScan lease,java.util.function.BooleanSupplier eligible,java.util.function.Consumer<ImportProcessRunner.Resource> journal,java.util.function.Consumer<Inspection> finish){
        var initial=qualified(user,appId);boolean[] captured={false};
        ImportStartupConfiguration.State[] context={null};
        try {
            // 1. 在共用写入锁中建立全内容副本，解析期间不锁住用户编辑。
            var snapshot=coordinator.withLock(appId,()->{
                requireSame(initial,qualified(user,appId));Path workspace=apps.resolveWorkspaceDir(user,appId);
                context[0]=configurations.current(appId,user.getId(),initial.taskId());
                if(lease!=null&&(!eligible.getAsBoolean()||!initial.taskId().equals(lease.importTaskId())||context[0].configRevision()!=lease.configRevision()||context[0].contextEpoch()!=lease.contextEpoch()))
                    throw new MyException(ErrorCode.CONFLICT_ERROR,"扫描任务或上下文已变化。");
                validateScope(workspace,scope);
                try{var capturedInput=snapshots.capture(id,workspace,eligible);captured[0]=true;return capturedInput;}
                catch(IOException error){throw new MyException(ErrorCode.OPERATION_ERROR,"启动输入建立失败，未返回部分结果。");}
            });
            Map<String,String> input=snapshots.parserInput(snapshot);
            boolean covered=scope.equals(".")||input.keySet().stream().anyMatch(path->path.startsWith(scope+"/"));
            var result=covered?scanner.scan(input,scope):new ImportStartupScanner.Result(null,List.of(),
                List.of(new ImportStartupScanner.Scope(scope,"DISCOVERY","INCOMPLETE","PARSER_SCOPE_NOT_COVERED")));
            if(!eligible.getAsBoolean())throw new MyException(ErrorCode.CONFLICT_ERROR,"扫描任务已失效。");
            var completed=python.supplement(result,input,eligible,id,journal);
            // 2. 重新核对业务资格与当前完整内容，迟到或混合版本不能作为当前结果。
            return coordinator.withLock(appId,()->{
                requireSame(initial,qualified(user,appId));
                if(!eligible.getAsBoolean())throw new MyException(ErrorCode.CONFLICT_ERROR,"扫描任务已失效。");
                var current=configurations.read(appId,user.getId(),initial.taskId());
                if(current.configRevision()!=context[0].configRevision()||current.contextEpoch()!=context[0].contextEpoch())
                    throw new MyException(ErrorCode.CONFLICT_ERROR,"启动配置或上下文已变化，请重新识别。");
                try{snapshots.verify(snapshot,apps.resolveWorkspaceDir(user,appId),eligible);}
                catch(IOException error){throw new MyException(ErrorCode.CONFLICT_ERROR,"工作区已变化或无法复核，请重新识别。");}
                var candidates=new java.util.ArrayList<ImportStartupScanner.Candidate>();var diagnostics=new java.util.ArrayList<>(completed.scopes());
                Path workspace=apps.resolveWorkspaceDir(user,appId);
                for(var manual:current.services())if(scope.equals(".")||manual.directory().equals(scope)||manual.directory().startsWith(scope+"/")){
                    try {
                        ImportStartupConfiguration.validateDirectory(workspace,manual.directory());
                        candidates.add(new ImportStartupScanner.Candidate("manual:"+manual.id(),manual.name(),manual.directory(),manual.command(),manual.build(),"MANUAL","startup configuration revision "+current.configRevision()));
                    }catch(IOException|IllegalArgumentException error){diagnostics.add(new ImportStartupScanner.Scope(manual.directory(),"FIXED","FAILED","MANUAL_DIRECTORY_INVALID"));}
                }
                candidates.addAll(completed.candidates());
                var merged=new ImportStartupScanner.Result(completed.inputHash(),candidates,diagnostics);
                var inspection=new Inspection(id,Long.toString(appId),initial.taskId(),snapshot.contentHash(),scope,Long.toString(current.configRevision()),Long.toString(current.contextEpoch()),merged);
                // 私有输入清理确认后才保存成功证据；不把清理失败记作完成。
                try{snapshots.discard(id);captured[0]=false;}catch(IOException error){throw new MyException(ErrorCode.OPERATION_ERROR,"启动输入清理失败，请检查任务状态。");}
                finish.accept(inspection);
                return inspection;
            });
        }catch(ImportPythonStartupReader.Failure error){
            throw new MyException(ErrorCode.OPERATION_ERROR,error.code().endsWith("STOP_FAILED")?"Python 识别资源停止未确认，操作未完成。":error.getMessage());
        }catch(IOException error){throw new MyException(ErrorCode.OPERATION_ERROR,"启动配置读取失败，未返回部分结果。");}
        finally{
            if(captured[0])try{snapshots.discard(id);}catch(IOException error){throw new MyException(ErrorCode.OPERATION_ERROR,"启动输入清理失败，请检查任务状态。");}
        }
    }

    public InspectionRecord inspection(User user,long appId,UUID scanId){
        return withInspection(user,appId,scanId,(record,snapshot)->record);
    }

    /** 回调在同一工作区写入锁内执行，批准必须绑定已复核的当前证据。 */
    public <T> T withCurrentInspection(User user,long appId,UUID scanId,java.util.function.Function<Inspection,T> action){
        java.util.Objects.requireNonNull(action);
        return withInspection(user,appId,scanId,(record,snapshot)->{
            if(!"SUCCEEDED".equals(record.status())||record.inspection()==null)
                throw new MyException(ErrorCode.CONFLICT_ERROR,"识别结果已过期，请重新识别。");
            return action.apply(record.inspection());
        });
    }

    /** 回调在同一写入锁内执行；识别已过期时传入 null，由调用者作废旧计划。不读取凭据或执行工程代码。 */
    public <T> T withPlanInput(User user,long appId,UUID scanId,java.util.function.Function<PlanInput,T> action){
        java.util.Objects.requireNonNull(action);
        return withInspection(user,appId,scanId,(record,snapshot)->{
            if(!"SUCCEEDED".equals(record.status())||record.inspection()==null||snapshot==null)return action.apply(null);
            Map<String,String> files;
            try{files=snapshots.parserInput(snapshot);}catch(IOException error){throw new MyException(ErrorCode.OPERATION_ERROR,"当前内容无法读取，未生成计划。");}
            var config=configurations.read(appId,user.getId(),record.inspection().importTaskId());
            return action.apply(new PlanInput(record.inspection(),files,config.services()));
        });
    }

    /** 计划资格只读检查：导入任务已替换返回 IMPORT_TASK_CHANGED，应用不可用或归属变化返回 PERMISSION_CHANGED，仍可复核返回 null。 */
    public String planRevoked(User user,long appId,UUID importTaskId){
        try{return qualified(user,appId).taskId().equals(importTaskId)?null:"IMPORT_TASK_CHANGED";}
        catch(MyException error){
            // 只把资格结论当作失效；系统错误照常抛出，不误判为权限变化。
            if(error.getCode()!=ErrorCode.NOT_FOUND_ERROR.getCode()&&error.getCode()!=ErrorCode.CONFLICT_ERROR.getCode())throw error;
            return "PERMISSION_CHANGED";
        }
    }

    /** 返回批准目录内的封存文本，不向模型提供工作区路径；执行租约仍由调用者负责。 */
    public ModelInput approvedModelInput(User user,ImportStartupApprovalRepository.Approval approval,java.util.function.BooleanSupplier eligible){
        if(user==null||user.getId()==null||user.getId()!=approval.ownerId()||!approval.approved()||!"RUNNING".equals(approval.state())
            ||approval.cancelRequested()||!approval.expiresAt().isAfter(java.time.Instant.now())||!eligible.getAsBoolean())
            throw new MyException(ErrorCode.CONFLICT_ERROR,"识别批准已失效。");
        return withCurrentInspection(user,approval.appId(),approval.scanId(),inspection->{
            if(!inspection.importTaskId().equals(approval.importTaskId())||!inspection.contentHash().equals(approval.contentHash())
                ||!inspection.configRevision().equals(Long.toString(approval.configRevision()))||!inspection.contextEpoch().equals(Long.toString(approval.contextEpoch()))
                ||!ImportStartupInspectionRepository.RULES_VERSION.equals(approval.rulesVersion())||!eligible.getAsBoolean())
                throw new MyException(ErrorCode.CONFLICT_ERROR,"识别内容或上下文已变化。");
            // 已有其他批准发布同目录结果时，本批准范围不再可补充，不再调用模型。
            List<ImportStartupScanner.Scope> ranges;
            try{ranges=ImportStartupAgentPolicy.unresolvedRanges(inspection,approval.directories());}
            catch(IllegalArgumentException error){throw new MyException(ErrorCode.CONFLICT_ERROR,"识别范围已变化。");}
            var known=ImportStartupAgentPolicy.knownCandidates(inspection,approval.directories());
            UUID inputId=UUID.randomUUID();boolean captured=false;
            try{
                var snapshot=snapshots.capture(inputId,apps.resolveWorkspaceDir(user,approval.appId()),eligible);captured=true;
                if(!snapshot.contentHash().equals(approval.contentHash()))throw new MyException(ErrorCode.CONFLICT_ERROR,"识别内容已变化。");
                var input=snapshots.agentInput(snapshot,approval.directories());
                if(!approval.expiresAt().isAfter(java.time.Instant.now())||!eligible.getAsBoolean())throw new MyException(ErrorCode.CONFLICT_ERROR,"识别批准已失效。");
                return new ModelInput(input,ranges,known);
            }catch(IOException failure){throw new MyException(ErrorCode.OPERATION_ERROR,"识别文本投影无法完整建立。");}
            finally{if(captured)try{snapshots.discard(inputId);}catch(IOException failure){throw new MyException(ErrorCode.OPERATION_ERROR,"识别输入清理失败。");}}
        });
    }

    /**
     * 在执行者退出后发布补充候选：同一写入锁内重新复核识别证据与完整工作区内容，再由 commit 写入 SUCCEEDED。
     * 过期、取消、内容或上下文变化的回包抛出 SupplementRejected，不能进入当前候选。
     */
    public ImportStartupApprovalRepository.Approval publishSupplement(User user,ImportStartupApprovalRepository.Approval approval,String output,
            java.util.function.BooleanSupplier eligible,java.util.function.UnaryOperator<ImportStartupApprovalRepository.Approval> commit){
        java.util.Objects.requireNonNull(commit);
        if(user==null||user.getId()==null||approval==null||user.getId()!=approval.ownerId()||output==null||eligible==null
            ||!approval.approved()||!"RUNNING".equals(approval.state())||approval.cancelRequested())throw new SupplementRejected("MODEL_RESULT_STALE");
        if(!approval.expiresAt().isAfter(java.time.Instant.now()))throw new SupplementRejected("APPROVAL_EXPIRED");
        return withInspection(user,approval.appId(),approval.scanId(),(record,snapshot)->{
            // 1. 识别证据已过期（内容、配置、代次或规则变化）时，回包作废。
            if(!"SUCCEEDED".equals(record.status())||record.inspection()==null||snapshot==null||!sameIdentity(approval,record.inspection())
                ||!snapshot.contentHash().equals(approval.contentHash()))throw new SupplementRejected("MODEL_RESULT_STALE");
            if(!approval.expiresAt().isAfter(java.time.Instant.now()))throw new SupplementRejected("APPROVAL_EXPIRED");
            // 2. 依据按锁内刚复核的完整副本重新校验；范围须仍可补充，不能重复已有或已发布候选。
            try{supplement(approval,record.inspection(),snapshots.agentInput(snapshot,approval.directories()),output);}
            catch(IOException error){throw new MyException(ErrorCode.OPERATION_ERROR,"当前内容无法复核，候选未发布。");}
            catch(IllegalArgumentException error){throw new SupplementRejected("MODEL_RESULT_CONFLICT");}
            if(!eligible.getAsBoolean())throw new SupplementRejected("MODEL_RESULT_UNVERIFIED");
            // 3. 仍在同一锁内提交；提交 SQL 再核对批准过期、取消与数据库资格。
            return commit.apply(approval);
        });
    }

    private <T> T withInspection(User user,long appId,UUID scanId,java.util.function.BiFunction<InspectionRecord,ImportStartupSnapshotStore.Snapshot,T> action){
        if(scanId==null)throw new MyException(ErrorCode.PARAMS_ERROR,"识别记录身份无效。");
        var initial=qualified(user,appId);
        return coordinator.withLock(appId,()->{
            // 1. 历史证据仅对当前真实 owner 和原导入任务可见。
            requireSame(initial,qualified(user,appId));
            var stored=inspections.find(appId,user.getId(),initial.taskId(),scanId);var result=stored.inspection();
            if("STALE".equals(stored.state())){revokePublished(user.getId(),appId,scanId);return action.apply(staleRecord(appId,initial.taskId(),scanId),null);}
            var current=configurations.current(appId,user.getId(),initial.taskId());
            boolean valid=ImportStartupInspectionRepository.RULES_VERSION.equals(stored.rulesVersion())
                &&Long.toString(current.configRevision()).equals(result.configRevision())
                &&Long.toString(current.contextEpoch()).equals(result.contextEpoch());
            if(valid){
                // 2. 全内容复核也覆盖未经过平台保存的磁盘改动，不凭版本计数宣称当前。
                UUID verification=UUID.randomUUID();boolean captured=false;
                try{
                    var snapshot=ImportInstallDiagnostics.call("COPY_PROJECT",()->snapshots.capture(verification,apps.resolveWorkspaceDir(user,appId),()->!Thread.currentThread().isInterrupted()&&!ImportInstallControl.cancelled()));captured=true;
                    // 3. 内容一致才叠加已发布补充；回调与发布共用这份已复核副本。
                    if(snapshot.contentHash().equals(result.contentHash()))
                        return action.apply(new InspectionRecord(scanId,Long.toString(appId),initial.taskId(),"SUCCEEDED",null,withPublished(user.getId(),result,snapshot)),snapshot);
                }catch(IOException error){throw new MyException(ErrorCode.OPERATION_ERROR,"当前内容无法复核，未恢复识别结果。");}
                finally{if(captured)try{snapshots.discard(verification);}catch(IOException error){throw new MyException(ErrorCode.OPERATION_ERROR,"复核输入清理失败，未恢复识别结果。");}}
            }
            inspections.stale(appId,user.getId(),scanId);revokePublished(user.getId(),appId,scanId);
            return action.apply(staleRecord(appId,initial.taskId(),scanId),null);
        });
    }

    /** 逐条复核已发布补充；不再符合当前内容或范围的记录转 STALE，坏记录只跳过不阻断其他结果。 */
    private Inspection withPublished(long owner,Inspection inspection,ImportStartupSnapshotStore.Snapshot snapshot)throws IOException{
        var merged=inspection;
        for(UUID id:approvals.published(owner,Long.parseLong(inspection.appId()),inspection.scanId())){
            ImportStartupApprovalRepository.Approval approval;
            try{approval=approvals.find(owner,id);}
            catch(RuntimeException error){log.warn("补充结果记录无法复核 approvalId={} errorType={}",id,error.getClass().getSimpleName());continue;}
            if(approval==null||!"SUCCEEDED".equals(approval.state()))continue;
            try{
                if(!sameIdentity(approval,inspection))throw new IllegalArgumentException("补充身份不一致。");
                merged=merge(merged,supplement(approval,merged,snapshots.agentInput(snapshot,approval.directories()),null),approval.directories());
            }catch(IllegalArgumentException error){revoke(approval);}
        }
        return merged;
    }
    /** 回包对当前证据的完整校验：范围仍可补充、依据对应当前文本、不重复已有候选。output 为空时校验已发布记录。 */
    private static ImportStartupSupplementResult.Validated supplement(ImportStartupApprovalRepository.Approval approval,Inspection inspection,Map<String,String> input,String output){
        ImportStartupAgentPolicy.requireSelection(inspection,approval.directories());
        var validated=output==null?ImportStartupSupplementResult.published(approval,input):ImportStartupSupplementResult.validate(approval,input,output);
        var existing=inspection.result().candidates();
        for(var candidate:validated.candidates())
            if(existing.stream().anyMatch(other->other.directory().equals(candidate.directory())&&java.util.Objects.equals(other.command(),candidate.command())))
                throw new IllegalArgumentException("补充候选与已有候选重复。");
        return validated;
    }
    /** 每个补充候选独立列出；原诊断保留，补充结果按目录另记 AGENT 阶段，不合并同目录其他范围。 */
    private static Inspection merge(Inspection inspection,ImportStartupSupplementResult.Validated validated,List<String> directories){
        var candidates=new java.util.ArrayList<>(inspection.result().candidates());candidates.addAll(validated.candidates());
        var scopes=new java.util.ArrayList<>(inspection.result().scopes());
        for(String directory:directories){
            boolean found=validated.candidates().stream().anyMatch(candidate->candidate.directory().equals(directory));
            scopes.add(new ImportStartupScanner.Scope(directory,ImportStartupAgentPolicy.AGENT_PHASE,found?"IDENTIFIED":"UNIDENTIFIED",found?null:"AGENT_NO_ENTRY"));
        }
        return new Inspection(inspection.scanId(),inspection.appId(),inspection.importTaskId(),inspection.contentHash(),inspection.scope(),inspection.configRevision(),inspection.contextEpoch(),
            new ImportStartupScanner.Result(inspection.result().inputHash(),candidates,scopes));
    }
    private static boolean sameIdentity(ImportStartupApprovalRepository.Approval approval,Inspection inspection){
        return Long.toString(approval.appId()).equals(inspection.appId())&&approval.scanId().equals(inspection.scanId())&&approval.importTaskId().equals(inspection.importTaskId())
            &&approval.contentHash().equals(inspection.contentHash())&&Long.toString(approval.configRevision()).equals(inspection.configRevision())
            &&Long.toString(approval.contextEpoch()).equals(inspection.contextEpoch())&&ImportStartupInspectionRepository.RULES_VERSION.equals(approval.rulesVersion());
    }
    private void revokePublished(long owner,long appId,UUID scanId){
        for(UUID id:approvals.published(owner,appId,scanId)){
            try{var approval=approvals.find(owner,id);if(approval!=null&&"SUCCEEDED".equals(approval.state()))revoke(approval);}
            catch(RuntimeException error){log.warn("补充结果记录无法复核 approvalId={} errorType={}",id,error.getClass().getSimpleName());}
        }
    }
    private void revoke(ImportStartupApprovalRepository.Approval approval){
        try{approvals.transition(approval,"STALE",null,"MODEL_RESULT_STALE");}
        catch(MyException error){log.warn("补充结果过期未能记录 approvalId={}",approval.id());}
    }

    private static InspectionRecord staleRecord(long appId,UUID taskId,UUID scanId){
        return new InspectionRecord(scanId,Long.toString(appId),taskId,"STALE","重新识别后再使用。",null);
    }

    public ImportStartupConfiguration.View configuration(User user,long appId){
        var initial=qualified(user,appId);
        return coordinator.withLock(appId,()->{
            requireSame(initial,qualified(user,appId));return configurations.current(appId,user.getId(),initial.taskId()).view();
        });
    }
    public ImportStartupConfiguration.View validateScan(User user,long appId,String scope){
        var initial=qualified(user,appId);
        return coordinator.withLock(appId,()->{
            requireSame(initial,qualified(user,appId));validateScope(apps.resolveWorkspaceDir(user,appId),scope);
            return configurations.current(appId,user.getId(),initial.taskId()).view();
        });
    }

    public ImportStartupConfiguration.View saveConfiguration(User user,long appId,long revision,long epoch,String scope,List<ImportStartupConfiguration.Manual> services){
        var initial=qualified(user,appId);
        return coordinator.withLock(appId,()->{
            requireSame(initial,qualified(user,appId));var previous=configurations.current(appId,user.getId(),initial.taskId());
            if(previous.configRevision()!=revision||previous.contextEpoch()!=epoch)throw new MyException(ErrorCode.CONFLICT_ERROR,"启动配置或上下文已变化，请重新读取。");
            try {
                Path workspace=apps.resolveWorkspaceDir(user,appId);ImportStartupConfiguration.validateDirectory(workspace,scope);
                var validated=ImportStartupConfiguration.validate(workspace,services,previous.services());
                return configurations.save(previous,scope,validated).view();
            }catch(IOException|IllegalArgumentException error){throw new MyException(ErrorCode.PARAMS_ERROR,"启动配置、命令或目录无效。");}
        });
    }

    private ImportTaskRepository.Publication qualified(User user,long appId){
        if(user==null||user.getId()==null||appId<=0)throw new MyException(ErrorCode.PARAMS_ERROR,"应用或登录状态无效。");
        var publication=repository.publicationByApp(appId);
        if(publication==null||publication.ownerId()!=user.getId())throw new MyException(ErrorCode.NOT_FOUND_ERROR,"导入应用不存在。");
        if(!"READY".equals(publication.state()))throw new MyException(ErrorCode.CONFLICT_ERROR,"导入内容尚不可用。");
        var app=apps.getById(appId);
        if(app==null||app.getUserId()==null||app.getUserId()!=publication.ownerId())throw new MyException(ErrorCode.NOT_FOUND_ERROR,"导入应用不存在。");
        var result=tasks.result(user.getId(),publication.taskId());
        if(!Long.toString(appId).equals(result.appId())||!publication.taskId().equals(result.taskId())||!publication.contentHash().equals(result.contentHash()))
            throw new MyException(ErrorCode.CONFLICT_ERROR,"导入资格已变化。");
        apps.resolveWorkspaceDir(user,appId);
        return publication;
    }

    private static void requireSame(ImportTaskRepository.Publication first,ImportTaskRepository.Publication current){
        if(!first.taskId().equals(current.taskId())||!first.contentHash().equals(current.contentHash()))throw new MyException(ErrorCode.CONFLICT_ERROR,"导入资格已变化。");
    }

    private static void validateScope(Path workspace,String scope){
        try {
            if(scope==null)throw new IllegalArgumentException();
            if(!scope.equals("."))ProjectImportManifest.validate(List.of(new ProjectImportManifest.FileEntry(scope+"/scope-check",0)),
                new ProjectImportManifest.Options(false,false,true),new ProjectImportManifest.Limits(1,0));
            Path selected=scope.equals(".")?workspace:workspace.resolve(scope).normalize();
            if(!selected.startsWith(workspace)||!Files.isDirectory(selected,LinkOption.NOFOLLOW_LINKS))throw new IllegalArgumentException();
            ProjectImportStaging.guardManagedPath(selected);
        }catch(IOException|IllegalArgumentException error){throw new MyException(ErrorCode.PARAMS_ERROR,"请选择工程内有效目录。");}
    }
}
