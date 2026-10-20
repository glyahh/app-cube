package com.dbts.glyahhaigeneratecode.agent;

import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.context.annotation.Configuration;

import java.time.Duration;

/**
 * Python Agent 运行时的接入配置。
 *
 * <p>Python 侧以常驻子进程方式运行，通过 stdin/stdout 的 NDJSON 协议通信。
 * 协议定义见 docs/contracts/agent.md —— 两边改任何一处都要同时改那里。
 */
@Data
@Configuration
@ConfigurationProperties(prefix = "agent.runtime")
public class AgentProperties {

    /**
     * Python 解释器路径。留空则按 .venv 的默认位置探测（Windows 是 Scripts/python.exe，
     * 其他平台是 bin/python）。
     *
     * <p>刻意不走 {@code uv run}：它每次都会校验锁文件，比直接调解释器慢。
     */
    private String pythonExecutable = "";

    /** agent/app.py 的路径。留空则按项目根下的 agent/app.py 探测。 */
    private String scriptPath = "";

    /**
     * 单次 run 的硬截止。Python 侧有软截止，这里是兜底 ——
     * Python 卡死时不能让进程池的槽位被永久占住。
     */
    private Duration runDeadline = Duration.ofMinutes(30);

    /**
     * 读一帧事件的等待上限。超时说明 Python 卡住，按故障处理。
     *
     * <p>Python 侧每 15 秒补一条心跳，这里给三倍余量：心跳线程被 GIL 或
     * 事件循环短暂挡住时不该误判成故障。原来是 60 秒，推理模型想得久一点
     * 就会撞上，把正常生成判成卡死。
     */
    private Duration readTimeout = Duration.ofSeconds(180);

    /** 启动一个 Python 进程后，等待它就绪的上限。冷启动实测约 7 秒，留足余量。 */
    private Duration startupTimeout = Duration.ofSeconds(60);

    /**
     * 心跳间隔。
     *
     * <p>不是为了保活 Python —— 那是子进程，死了 stdout 立刻 EOF，不需要心跳去发现。
     * 心跳是为了中间代理：Nginx 默认会缓冲，空闲连接也可能被掐。
     */
    private Duration heartbeatInterval = Duration.ofSeconds(15);
}
