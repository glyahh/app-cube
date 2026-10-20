package com.dbts.glyahhaigeneratecode.agent;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

/**
 * 启动对账：收口上一次进程退出时留下的在途状态。
 *
 * <p>进程被停止时没有机会走 {@code doFinally}，{@code agent_run} 会停在 RUNNING，
 * 编辑会话停在 EDITING，许可票据停在 REQUESTED。会话的唯一索引会让该应用
 * 之后无法再发起编辑，前端也会一直读到旧票据。
 *
 * <p>本项目单机部署，启动时不存在其他实例的活跃 run，可以直接按状态收口。
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class AgentRunReconciler {
    private final JdbcTemplate jdbc;

    @EventListener(ApplicationReadyEvent.class)
    public void reconcile() {
        // 1. 取消未消费的票据，避免前端继续显示待批准卡片。
        int permissions = jdbc.update("""
                UPDATE biz.agent_tool_permission SET status='CANCELLED',revision=revision+1
                WHERE status IN ('REQUESTED','APPROVED')
                """);
        // 2. 关闭所有未关闭的编辑会话，释放应用级唯一索引。
        int sessions = jdbc.update("""
                UPDATE biz.agent_edit_session SET state='FAILED',closed_at=now()
                WHERE closed_at IS NULL
                """);
        // 3. 把上一进程遗留的 run 标成中断，不再冒充仍在执行。
        int runs = jdbc.update("""
                UPDATE biz.agent_run SET status='FAILED',error_code='PROCESS_RESTARTED',
                    error_message='服务重启，上一轮已中断',finished_at=now()
                WHERE status IN ('RUNNING','PENDING')
                """);
        if (permissions + sessions + runs > 0) {
            log.info("启动对账完成：票据取消 {}，会话关闭 {}，run 收口 {}", permissions, sessions, runs);
        }
    }
}
