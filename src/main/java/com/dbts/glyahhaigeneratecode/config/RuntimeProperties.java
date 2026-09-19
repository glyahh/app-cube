package com.dbts.glyahhaigeneratecode.config;

import com.dbts.glyahhaigeneratecode.constant.AppConstant;
import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.context.annotation.Configuration;

import java.nio.file.Path;
import java.time.Duration;

/**
 * 运行沙箱配置。
 *
 * <p>与 {@link WorkspaceProperties} 同一角色：路径与限额的**唯一可覆盖入口**，
 * 默认值直接引用 {@link AppConstant} 的常量表达式，避免两套默认值各自演化。
 */
@Data
@Configuration
@ConfigurationProperties(prefix = "runtime")
public class RuntimeProperties {

    /** 运行时容器镜像。由 {@code deploy/sandbox/build.ps1} 构建。 */
    private String image = "glyahh-sandbox-runtime:24.04";

    /** 运行日志根目录，如 {@code temp/runtime_logs/{appId}/{runNo}/}。 */
    private Path logRoot = Path.of(AppConstant.RUNTIME_LOG_ROOT_DIR);

    /** 宿主侧网关端口的分配区间，避免与平台自身及常用端口冲突。 */
    private int gatewayPortStart = 22000;

    private int gatewayPortEnd = 22999;

    /**
     * 最后一个页面离开后保留运行的时长。
     *
     * <p>Plan 明确 10 分钟；按服务端心跳判断，不依赖浏览器关闭事件
     * （刷新、多标签、断网都不会误停）。
     */
    private Duration idleStopAfter = Duration.ofMinutes(10);

    /** 心跳超时上限：超过即视为「页面都已离开」，进入保留倒计时。 */
    private Duration heartbeatTimeout = Duration.ofSeconds(90);

    /** 指标采集间隔。Plan 明确 2 秒。 */
    private Duration metricsInterval = Duration.ofSeconds(2);

    /** 指标内存窗口长度。Plan 明确最近 30 分钟，不承诺跨重启历史。 */
    private Duration metricsWindow = Duration.ofMinutes(30);

    /** 单次运行的日志上限，超出即轮转。Plan 明确 20 MiB。 */
    private long logMaxBytes = 20L * 1024 * 1024;

    /** 日志保留份数。Plan 明确最近 5 次。 */
    private int logRetainRuns = 5;

    /** 单个项目的受管进程数上限，防止一个项目拉起过多进程。 */
    private int maxProcesses = 5;

    /** 就绪探测的整体超时。超时按启动失败处理。 */
    private Duration readinessTimeout = Duration.ofSeconds(120);

    /** 容器停止的宽限时间（秒），到期未退出则强杀。 */
    private int stopGraceSeconds = 5;

    /** 容器 CPU / 内存上限。 */
    private String cpus = "2";

    private String memory = "2g";

    /**
     * 运行容器使用的 Docker 网络。
     *
     * <p>必须是 bridge（或自定义 bridge）—— {@code none} 会让容器没有网络栈，
     * {@code -p} 端口映射随之失效，应用根本访问不到。
     *
     * <p>隔离靠的是「端口只绑 127.0.0.1」这条：容器出网没有被挡住，
     * 首期接受这一点，需要真出网隔离时再引入独立 internal 网络 + 出网代理。
     */
    private String network = "bridge";
}
