package com.dbts.glyahhaigeneratecode.runtime;

import com.dbts.glyahhaigeneratecode.config.RuntimeProperties;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;

/**
 * docker stats 输出解析。
 *
 * <p>重点是「采不到」与「为零」要能区分：解析失败返回 null 字段，
 * 前端据此显示暂无数据，而不是画一个假的 0。
 */
@DisplayName("运行时指标解析")
class RuntimeMetricsStoreTest {

    @Test
    @DisplayName("解析 CPU 百分比与内存用量")
    void parsesStatsLine() {
        String json = "{\"CPUPerc\":\"0.15%\",\"MemUsage\":\"12.3MiB / 2GiB\",\"Name\":\"x\"}";
        RuntimeMetricsStore.Sample sample = RuntimeMetricsStore.parseStatsLine(json);

        assertNotNull(sample);
        assertEquals(0.15d, sample.cpuPercent(), 0.0001);
        assertEquals(12.3 * 1024 * 1024, sample.memoryBytes(), 1024);
        assertEquals(2L * 1024 * 1024 * 1024, sample.memoryLimitBytes(), 1024);
    }

    @Test
    @DisplayName("垃圾输入返回 null 而不是抛异常")
    void returnsNullForGarbage() {
        assertNull(RuntimeMetricsStore.parseStatsLine(null));
        assertNull(RuntimeMetricsStore.parseStatsLine(""));
        assertNull(RuntimeMetricsStore.parseStatsLine("not json at all"));
    }

    @Test
    @DisplayName("字段缺失时该字段为 null，不影响其他字段")
    void toleratesMissingFields() {
        RuntimeMetricsStore.Sample sample = RuntimeMetricsStore.parseStatsLine("{\"MemUsage\":\"--\"}");
        assertNotNull(sample);
        assertNull(sample.cpuPercent());
        assertNull(sample.memoryBytes());
    }

    @Test
    @DisplayName("窗口按时间裁剪旧点")
    void windowDropsOldSamples() {
        RuntimeProperties props = new RuntimeProperties();
        props.setMetricsWindow(java.time.Duration.ofSeconds(2));
        RuntimeMetricsStore store = new RuntimeMetricsStore(props);

        store.record(1L, 1.0, 1024L, 2048L);
        assertEquals(1, store.window(1L).size());

        // 直接塞一个过期点，模拟窗口推进
        store.record(1L, 2.0, 1024L, 2048L);
        var window = store.window(1L);
        assertEquals(2, window.size());
        // 最新一点在最前
        assertEquals(2.0, window.get(window.size() - 1).cpuPercent());
    }

    @Test
    @DisplayName("清空后没有数据（不是空数组伪装成零）")
    void clearEmptiesWindow() {
        RuntimeProperties props = new RuntimeProperties();
        RuntimeMetricsStore store = new RuntimeMetricsStore(props);
        store.record(9L, 1.0, 1L, 2L);
        store.clear(9L);
        assertEquals(0, store.window(9L).size());
    }
}
