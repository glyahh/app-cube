package com.dbts.glyahhaigeneratecode.runtime;

import com.dbts.glyahhaigeneratecode.model.Entity.AppRuntime;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.time.Instant;
import java.util.List;

/**
 * 运行时后台任务：指标采集、空闲回收、启动对账。
 *
 * <p>三件事放在一起，是因为它们共享同一个前提 —— 「以容器实际状态为准」。
 * 分散到不同组件容易各自维护一份「谁在运行」的内存视图，最后对不上。
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class RuntimeScheduler {

    private final RuntimeOrchestrator orchestrator;
    private final RuntimeManager runtimeManager;
    private final RuntimeMetricsStore metricsStore;

    /**
     * 指标采集。
     *
     * <p>固定间隔拉一次 {@code docker stats}。采集失败只记不抛 ——
     * 指标缺失应该表现为「暂无数据」，不该让调度线程挂掉或刷错误日志。
     */
    @Scheduled(fixedDelayString = "${runtime.metrics-interval-ms:2000}")
    public void collectMetrics() {
        List<AppRuntime> actives;
        try {
            actives = orchestrator.activeRuns();
        } catch (RuntimeException e) {
            log.warn("读取运行中的应用失败: {}", e.getMessage());
            return;
        }

        // 并发采集。每个容器一次 docker stats 是纯 IO 等待，串行跑的话一轮耗时
        // 随应用数线性增长 —— 应用一多，名义 2 秒的间隔会被拉到十几秒，
        // 曲线的时间轴就不再是它标称的那个尺度了。
        // RuntimeMetricsStore.record 内部对窗口加了锁，并发写是安全的。
        actives.parallelStream().forEach(runtime -> {
            try {
                String json = runtimeManager.readStatsJson(runtime.getContainerName());
                RuntimeMetricsStore.Sample sample = RuntimeMetricsStore.parseStatsLine(json);
                if (sample == null) {
                    // 采不到就记一个空点：前端显示「暂无数据」，不是断线也不是 0
                    metricsStore.record(runtime.getAppId(), null, null, null);
                    return;
                }
                metricsStore.record(runtime.getAppId(), sample.cpuPercent(),
                        sample.memoryBytes(), sample.memoryLimitBytes());
            } catch (RuntimeException e) {
                log.debug("采集应用 {} 指标失败: {}", runtime.getAppId(), e.getMessage());
            }
        });
    }

    /**
     * 空闲回收：最后一个页面离开后按配置保留一段时间再停。
     *
     * <p>每分钟扫一次即可 —— 停止时机不需要秒级精度，
     * 而每次扫描都要回查容器状态，频率太高是浪费。
     */
    @Scheduled(fixedDelayString = "${runtime.reap-interval-ms:60000}")
    public void reapIdle() {
        try {
            int stopped = orchestrator.reapIdle(Instant.now());
            if (stopped > 0) {
                log.info("空闲回收：停止了 {} 个长时间无访问的应用", stopped);
            }
        } catch (RuntimeException e) {
            log.warn("空闲回收执行失败: {}", e.getMessage());
        }
    }

    /**
     * 启动对账。
     *
     * <p>应用就绪后跑一次：把记录里「活着」但容器已消失的运行标记为结束。
     * 不做这一步，重启后界面会一直显示虚假的「运行中」。
     */
    @EventListener(ApplicationReadyEvent.class)
    public void reconcileOnStartup() {
        try {
            int fixed = orchestrator.reconcileOnStartup();
            if (fixed > 0) {
                log.info("启动对账完成：修正了 {} 条运行记录", fixed);
            }
        } catch (RuntimeException e) {
            // 对账失败不该影响启动：下一次状态查询会就地校正
            log.warn("启动对账失败: {}", e.getMessage());
        }
    }
}
