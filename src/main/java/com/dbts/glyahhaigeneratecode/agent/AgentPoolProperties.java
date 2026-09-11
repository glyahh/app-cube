package com.dbts.glyahhaigeneratecode.agent;

import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.context.annotation.Configuration;

import java.time.Duration;

/**
 * Python 进程池的伸缩策略。
 *
 * <p>带滞回 + 冷却的池：扩容阈值高（coreSize 占满就扩）、缩容阈值低（活跃数低于
 * shrinkBelow 才缩）、中间是死区防抖动、缩容后再加一段冷却。
 *
 * <p>冷却加在「缩容之后」而不是「扩容之后」是有意的取舍：刚缩容说明负载低，
 * 短期内暴涨就先排队，避免反复拉起销毁。
 */
@Data
@Configuration
@ConfigurationProperties(prefix = "agent.pool")
public class AgentPoolProperties {

    /** 常驻进程数。Java 启动时拉起这么多。 */
    private int coreSize = 3;

    /** 扩容上限。超出后新请求排队等待。 */
    private int maxSize = 5;

    /** 活跃进程数低于此值时触发缩容。 */
    private int shrinkBelow = 2;

    /** 缩容之后多久内禁止扩容。 */
    private Duration scaleCooldown = Duration.ofMinutes(5);

    /** 池满时的等待上限。超时返回 WORKSPACE_BUSY。 */
    private Duration acquireTimeout = Duration.ofSeconds(30);
}
