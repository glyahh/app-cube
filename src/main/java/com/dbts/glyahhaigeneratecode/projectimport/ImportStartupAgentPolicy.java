package com.dbts.glyahhaigeneratecode.projectimport;

import java.util.*;

/** 仅决定哪些未识别目录可申请补充；不生成运行权限或替代持久用户批准。 */
public final class ImportStartupAgentPolicy {
    /** 已发布补充结果的诊断阶段；同目录不再重复申请。 */
    public static final String AGENT_PHASE="AGENT";
    private ImportStartupAgentPolicy(){ }

    public static List<String> eligibleDirectories(ImportStartupService.Inspection inspection){
        Objects.requireNonNull(inspection,"识别证据缺失。");
        var scopes=inspection.result().scopes();var candidates=inspection.result().candidates();
        var directories=new TreeSet<String>();
        // 1. 部分目录可申请补充，已识别的其他服务不阻断；不把解析失败或歧义当未识别。
        for(var scope:scopes){
            String directory=scope.directory();
            if(!"UNIDENTIFIED".equals(scope.status())||AGENT_PHASE.equals(scope.phase())||!within(directory,inspection.scope()))continue;
            // 2. 同目录其他范围已由规则识别时，本未识别范围保持独立可补充；手动配置和已发布补充优先。
            boolean claimed=candidates.stream().anyMatch(candidate->candidate.directory().equals(directory)&&List.of("MANUAL","AGENT").contains(candidate.source()))
                ||scopes.stream().anyMatch(other->other.directory().equals(directory)&&AGENT_PHASE.equals(other.phase()));
            boolean blocked=scopes.stream().anyMatch(other->within(directory,other.directory())
                &&List.of("FAILED","INCOMPLETE","AMBIGUOUS").contains(other.status()));
            if(!claimed&&!blocked)directories.add(directory);
        }
        return List.copyOf(directories);
    }

    public static List<String> requireSelection(ImportStartupService.Inspection inspection,List<String> selected){
        if(selected==null||selected.isEmpty()||selected.size()>20||selected.stream().anyMatch(Objects::isNull))
            throw new IllegalArgumentException("请选择未识别目录。");
        var allowed=new HashSet<>(eligibleDirectories(inspection));var unique=new HashSet<>(selected);
        if(unique.size()!=selected.size()||!allowed.containsAll(selected))throw new IllegalArgumentException("识别范围已变化，请重新确认。");
        return List.copyOf(selected);
    }

    /** 批准目录内仍未识别的各个范围，逐条交给模型，不合并为目录级一项。 */
    public static List<ImportStartupScanner.Scope> unresolvedRanges(ImportStartupService.Inspection inspection,List<String> directories){
        var selected=Set.copyOf(requireSelection(inspection,directories));
        return inspection.result().scopes().stream().filter(scope->selected.contains(scope.directory())&&"UNIDENTIFIED".equals(scope.status())
            &&!AGENT_PHASE.equals(scope.phase())).distinct().toList();
    }

    /** 批准目录内已有候选，模型只作排除参考，不能重复或替换。 */
    public static List<ImportStartupScanner.Candidate> knownCandidates(ImportStartupService.Inspection inspection,List<String> directories){
        var selected=Set.copyOf(directories);
        return inspection.result().candidates().stream().filter(candidate->selected.contains(candidate.directory())).toList();
    }

    private static boolean within(String directory,String scope){return ".".equals(scope)||directory.equals(scope)||directory.startsWith(scope+"/");}
}
