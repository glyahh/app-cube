package com.dbts.glyahhaigeneratecode.runtime;

import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

import java.time.Instant;
import java.util.ArrayDeque;
import java.util.Deque;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 项目访问入口的流量统计（QPS 与错误率）。
 *
 * <p>只统计经过平台网关的请求：那是唯一能可靠归因到「这个项目」的口子。
 * 平台自身的健康检查、状态轮询不算用户流量，单独排除 —— 否则空闲的应用
 * 也会显示有 QPS，用户会以为有人在访问。
 *
 * <p>与指标一样只保留内存窗口，重启即清零：Plan 明确不承诺跨重启的历史监控。
 */
@Component
@RequiredArgsConstructor
public class RuntimeTrafficStats {

    /** 统计窗口，与指标窗口一致。 */
    private static final long WINDOW_SECONDS = 1800;

    /** 每桶一秒，用桶数算 QPS，避免每次都遍历原始请求。 */
    private static final int MAX_SECONDS = (int) WINDOW_SECONDS;

    /** 一个应用一份计数。 */
    private static final class Counter {
        /** 每秒的请求数，下标是秒偏移。 */
        final int[] requestBuckets = new int[MAX_SECONDS];
        final int[] errorBuckets = new int[MAX_SECONDS];
        /** 桶对应的秒时间戳，用来判断桶是否过期。 */
        final long[] bucketStamp = new long[MAX_SECONDS];
        long totalRequests;
        long totalErrors;
    }

    private final Map<Long, Counter> counters = new ConcurrentHashMap<>();

    /** 记一次请求。 */
    public void record(Long appId, int statusCode, boolean healthCheck) {
        if (healthCheck) {
            return;
        }
        Counter counter = counters.computeIfAbsent(appId, k -> new Counter());
        long now = Instant.now().getEpochSecond();
        int index = (int) (now % MAX_SECONDS);
        synchronized (counter) {
            if (counter.bucketStamp[index] != now) {
                counter.bucketStamp[index] = now;
                counter.requestBuckets[index] = 0;
                counter.errorBuckets[index] = 0;
            }
            counter.requestBuckets[index]++;
            counter.totalRequests++;
            if (statusCode >= 500) {
                counter.errorBuckets[index]++;
                counter.totalErrors++;
            }
        }
    }

    /**
     * 计算最近一段时间的 QPS 与错误率。
     *
     * @param appId   应用 id
     * @param seconds 统计窗口（秒）
     * @return 没有数据时返回 null —— 由调用方呈现「暂无数据」，不伪造成 0
     */
    public Snapshot snapshot(Long appId, int seconds) {
        Counter counter = counters.get(appId);
        if (counter == null) {
            return null;
        }
        int window = Math.max(1, Math.min(seconds, MAX_SECONDS));
        long now = Instant.now().getEpochSecond();
        long requests = 0;
        long errors = 0;
        synchronized (counter) {
            for (int i = 0; i < window; i++) {
                int index = (int) ((now - i) % MAX_SECONDS);
                if (index < 0) {
                    index += MAX_SECONDS;
                }
                if (counter.bucketStamp[index] != now - i) {
                    continue;
                }
                requests += counter.requestBuckets[index];
                errors += counter.errorBuckets[index];
            }
        }
        if (requests == 0) {
            return null;
        }
        return new Snapshot(requests / (double) window, errors / (double) requests * 100d, requests);
    }

    /** 清掉某应用的统计（停止运行或删除应用时）。 */
    public void clear(Long appId) {
        counters.remove(appId);
    }

    /**
     * 统计快照。
     *
     * @param qps            每秒请求数
     * @param errorRatePercent 错误率（5xx 占比）
     * @param sampleRequests 窗口内样本请求数，样本太小时前端应提示仅供参考
     */
    public record Snapshot(double qps, double errorRatePercent, long sampleRequests) {
    }
}
