package com.dbts.glyahhaigeneratecode.knowledge;

import com.dbts.glyahhaigeneratecode.agent.AgentRunFinishedEvent;
import com.dbts.glyahhaigeneratecode.model.Entity.App;
import com.dbts.glyahhaigeneratecode.model.enums.CodeGenTypeEnum;
import com.dbts.glyahhaigeneratecode.service.AppService;
import com.dbts.glyahhaigeneratecode.service.support.WorkspacePathResolver;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.context.event.EventListener;
import org.springframework.stereotype.Component;
import reactor.core.scheduler.Schedulers;

import java.nio.file.Path;

/**
 * 知识生成的自动触发。
 *
 * <p>两个入口（Plan 明确要求）：
 * <ol>
 *   <li><b>生成完成后</b>：启用了知识图谱的项目，在代码生成与验证完成后自动生成第一版知识。</li>
 *   <li><b>首次启用时</b>：存量项目打开知识图谱开关时，针对最近一次完成的源码版本补一份知识。</li>
 * </ol>
 *
 * <p>两条都异步执行：快照要遍历整个工作区，同步跑会把生成流的收尾或设置保存卡住。
 * 失败只记日志 —— 知识生成失败不该影响生成结果本身。
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class KnowledgeAutoTrigger {

    private final KnowledgeTaskService taskService;
    private final AppService appService;
    private final WorkspacePathResolver workspacePathResolver;

    /** 一轮生成结束后：成功、且项目启用了知识图谱，才跟进生成知识。 */
    @EventListener
    public void onRunFinished(AgentRunFinishedEvent event) {
        if (event == null || event.appId() == null || !event.succeeded()) {
            return;
        }
        Long appId = event.appId();
        Long userId = event.userId();
        runAsync("生成完成后", appId, () -> {
            if (!knowledgeEnabled(appId) || !taskService.latestSuccessful(appId).isEmpty()) {
                return;
            }
            Path workspace = workspaceOf(appId);
            if (workspace == null) {
                return;
            }
            taskService.submit(appId, userId, workspace);
        });
    }

    /** 首次启用知识图谱：给存量项目补一份知识。 */
    @EventListener
    public void onDashboardFeaturesChanged(DashboardFeaturesChangedEvent event) {
        if (event == null || event.appId() == null || !event.knowledgeNewlyEnabled()) {
            return;
        }
        Long appId = event.appId();
        Long userId = event.userId();
        runAsync("首次启用", appId, () -> {
            // 已有知识保留旧版本，由用户手动更新，重新开关也不触发重建。
            if (!taskService.latestSuccessful(appId).isEmpty()) {
                return;
            }
            Path workspace = workspaceOf(appId);
            if (workspace == null) {
                return;
            }
            taskService.submit(appId, userId, workspace);
        });
    }

    private void runAsync(String reason, Long appId, Runnable action) {
        Schedulers.boundedElastic().schedule(() -> {
            try {
                action.run();
                log.info("知识自动生成已触发（{}）appId={}", reason, appId);
            } catch (Exception e) {
                // 自动触发失败不打扰用户：他们还能在看板里手动点「生成知识」
                log.warn("知识自动生成触发失败（{}）appId={}: {}", reason, appId, e.getMessage());
            }
        });
    }

    private boolean knowledgeEnabled(Long appId) {
        App app = appService.getById(appId);
        return app != null && app.getDashboardFeatures() != null
                && app.getDashboardFeatures().contains("knowledge");
    }

    /** 内部触发用：按 appId 解析工作区，不涉及用户输入，因此不走带鉴权的重载。 */
    private Path workspaceOf(Long appId) {
        App app = appService.getById(appId);
        if (app == null) {
            return null;
        }
        CodeGenTypeEnum type = CodeGenTypeEnum.getEnumByValue(app.getCodeGenType());
        if (type == null) {
            log.warn("应用 {} 的 codeGenType 无法识别，跳过知识自动生成", appId);
            return null;
        }
        return workspacePathResolver.outputDir(appId, type);
    }
}
