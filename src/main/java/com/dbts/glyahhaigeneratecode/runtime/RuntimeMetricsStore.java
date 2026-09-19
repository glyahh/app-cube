package com.dbts.glyahhaigeneratecode.runtime;

import cn.hutool.json.JSONObject;
import cn.hutool.json.JSONUtil;
import com.dbts.glyahhaigeneratecode.config.RuntimeProperties;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.time.Instant;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 运行时指标的内存窗口。
 *
 * <p>首期只保留内存：每个运行中的容器一条采样序列，定时从 {@code docker stats} 取
 * CPU 与内存占用。窗口长度由配置决定（默认 30 分钟，每 2 秒一点，约 900 点）。
 *
 * <p>刻意不落库：Plan 明确「不承诺跨平台重启的历史监控」，为此建表只会带来
 * 写放大和一张永远没人清理的表。
 *
 * <p>采集失败时记 null 而不是 0 —— 「没采到」和「占用为零」是两件事，
 * 后者会让用户以为程序没在干活。
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class RuntimeMetricsStore {

    private final RuntimeProperties props;

    /** 一个采样点。CPU 与内存都可能为 null（采集失败）。 */
    public record Sample(Instant at, Double cpuPercent, Long memoryBytes, Long memoryLimitBytes) {
    }

    private final Map<Long, Deque<Sample>> windows = new ConcurrentHashMap<>();

    /** 记录一个采样点，并丢弃窗口之外的旧点。 */
    public void record(Long appId, Double cpuPercent, Long memoryBytes, Long memoryLimitBytes) {
        Deque<Sample> window = windows.computeIfAbsent(appId, k -> new ArrayDeque<>());
        Instant now = Instant.now();
        synchronized (window) {
            window.addLast(new Sample(now, cpuPercent, memoryBytes, memoryLimitBytes));
            Instant cutoff = now.minus(props.getMetricsWindow());
            while (!window.isEmpty() && window.peekFirst().at().isBefore(cutoff)) {
                window.pollFirst();
            }
        }
    }

    /** 取窗口内的全部采样点。 */
    public List<Sample> window(Long appId) {
        Deque<Sample> window = windows.get(appId);
        if (window == null) {
            return List.of();
        }
        synchronized (window) {
            return new ArrayList<>(window);
        }
    }

    /** 运行结束或应用删除时清掉窗口。 */
    public void clear(Long appId) {
        windows.remove(appId);
    }

    /**
     * 解析一条 {@code docker stats --format '{{json .}}'} 输出。
     *
     * <p>格式形如 {@code {"CPUPerc":"0.15%","MemUsage":"12.3MiB / 2GiB",...}}。
     * 解析不出来时返回 null 字段，由调用方决定呈现「暂无数据」。
     */
    public static Sample parseStatsLine(String json) {
        if (json == null || json.isBlank()) {
            return null;
        }
        try {
            JSONObject obj = JSONUtil.parseObj(json);
            Double cpu = parsePercent(obj.getStr("CPUPerc"));
            long[] mem = parseMemUsage(obj.getStr("MemUsage"));

            // 内存占用为 0 判成「采不到」，不是「零负载」。
            //
            // 已退出的容器 docker stats 照样返回成功、退出码也是 0，只是各项全零；
            // 而真实运行的容器不可能内存为 0 —— 光进程本身就有基础占用。
            // 不这样区分的话，一次崩溃会在曲线上留下一个贴着底部的假谷底，
            // 看起来像「服务还活着只是没流量」，实际是它已经死了。
            if (mem != null && mem[0] == 0L) {
                return null;
            }

            return new Sample(Instant.now(), cpu,
                    mem == null ? null : mem[0],
                    mem == null ? null : mem[1]);
        } catch (RuntimeException e) {
            return null;
        }
    }

    private static Double parsePercent(String raw) {
        if (raw == null || raw.isBlank()) {
            return null;
        }
        try {
            return Double.parseDouble(raw.replace("%", "").trim());
        } catch (NumberFormatException e) {
            return null;
        }
    }

    /** 解析 {@code "12.3MiB / 2GiB"} 形式的用量，返回 {used, limit}。 */
    private static long[] parseMemUsage(String raw) {
        if (raw == null || !raw.contains("/")) {
            return null;
        }
        String[] parts = raw.split("/");
        Long used = parseSize(parts[0].trim());
        Long limit = parts.length > 1 ? parseSize(parts[1].trim()) : null;
        if (used == null) {
            return null;
        }
        return new long[]{used, limit == null ? 0L : limit};
    }

    private static Long parseSize(String raw) {
        if (raw == null || raw.isBlank()) {
            return null;
        }
        String text = raw.trim();
        int split = 0;
        while (split < text.length() && (Character.isDigit(text.charAt(split)) || text.charAt(split) == '.')) {
            split++;
        }
        try {
            double value = Double.parseDouble(text.substring(0, split));
            String unit = text.substring(split).trim().toLowerCase();
            double multiplier = switch (unit) {
                case "b" -> 1d;
                case "kib", "kb" -> 1024d;
                case "mib", "mb" -> 1024d * 1024;
                case "gib", "gb" -> 1024d * 1024 * 1024;
                case "tib", "tb" -> 1024d * 1024 * 1024 * 1024;
                default -> 1d;
            };
            return (long) (value * multiplier);
        } catch (RuntimeException e) {
            return null;
        }
    }
}
