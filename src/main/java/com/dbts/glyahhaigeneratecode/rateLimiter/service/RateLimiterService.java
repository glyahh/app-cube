package com.dbts.glyahhaigeneratecode.rateLimiter.service;

import com.dbts.glyahhaigeneratecode.exception.ErrorCode;
import com.dbts.glyahhaigeneratecode.exception.MyException;
import jakarta.annotation.Resource;
import lombok.extern.slf4j.Slf4j;
import org.redisson.api.RRateLimiter;
import org.redisson.api.RateIntervalUnit;
import org.redisson.api.RateType;
import org.redisson.api.RedissonClient;
import org.springframework.stereotype.Service;

import java.time.Duration;

/**
 * 限流核心。
 *
 * <p>从 {@code rateLimiterAspect} 里抽出来，是为了让**不经过 Spring MVC 切面**的路径
 * 也能用同一套限流。WebSocket handler 不是 controller，{@code @RateLimit} 注解
 * 切不到它 —— 如果 WS 路径不做限流，那就是一个绕过限流的口子。
 *
 * <p>两条路共用这里的实现与 key 命名规则，避免各写一套后慢慢漂移。
 */
@Slf4j
@Service
public class RateLimiterService {

    /** 与切面保持一致的 key 前缀。 */
    private static final String KEY_PREFIX = "rate_limit:";

    @Resource
    private RedissonClient redissonClient;

    /** 用户级限流 key。SSE 与 WS 两条路都用这个，保证配额是共享的而不是各算各的。 */
    public String userKey(Long userId) {
        return KEY_PREFIX + "user:" + userId;
    }

    /**
     * 取一个令牌，取不到抛 {@link ErrorCode#TOO_MANY_REQUEST}。
     *
     * @param key             限流 key，同一 key 共享配额
     * @param rate            时间窗口内允许的请求数
     * @param intervalSeconds 时间窗口（秒）
     * @param message         超限时给用户的提示
     */
    public void tryAcquire(String key, int rate, int intervalSeconds, String message) {
        RRateLimiter limiter = redissonClient.getRateLimiter(key);
        limiter.expire(Duration.ofHours(1));

        // trySetRate 只在首次初始化时成功，后续调用返回 false（保留已有配置）。
        // 记下返回值，避免出现「注解参数改了但实际没生效」的误判。
        boolean initialized = limiter.trySetRate(RateType.OVERALL, rate, intervalSeconds, RateIntervalUnit.SECONDS);
        if (!initialized) {
            log.debug("RateLimiter 已存在配置，复用旧配置: key={}", key);
        }

        if (!limiter.tryAcquire(1)) {
            throw new MyException(ErrorCode.TOO_MANY_REQUEST, message);
        }
    }
}
