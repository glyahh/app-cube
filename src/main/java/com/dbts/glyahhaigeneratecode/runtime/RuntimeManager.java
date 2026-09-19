package com.dbts.glyahhaigeneratecode.runtime;

import com.dbts.glyahhaigeneratecode.config.RuntimeProperties;
import com.dbts.glyahhaigeneratecode.exception.ErrorCode;
import com.dbts.glyahhaigeneratecode.exception.MyException;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.net.ServerSocket;
import java.net.Socket;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * 运行时容器的生命周期。
 *
 * <p>与生成沙箱（Python 侧 DockerSandboxManager）的分工：那个跑一次生成就回收，
 * 这个要长期常驻直到用户停止或空闲超时。所以容器名按 appId 固定、端口要映射出来、
 * 日志要落到平台目录。
 *
 * <p>受管进程由一个生成出来的 runner 脚本统一拉起：容器主进程就是它，
 * 任何一个进程退出就让容器退出 —— 这样「进程死了」这件事通过容器状态就能观察到，
 * 不需要额外的心跳探针。
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class RuntimeManager {

    private final DockerCli docker;
    private final RuntimeProperties props;

    /** 容器内的工作区挂载点，与生成沙箱保持一致。 */
    private static final String CONTAINER_WORKSPACE = "/workspace";

    /** 容器内日志目录，由平台挂进去。 */
    private static final String CONTAINER_LOG_DIR = "/var/log/glyahh";

    /** 容器名前缀，与生成沙箱的 glyahh-sbx- 区分开。 */
    private static final String CONTAINER_PREFIX = "glyahh-rt-";

    /** runner 脚本在容器内的路径。 */
    private static final String RUNNER_PATH = CONTAINER_WORKSPACE + "/.glyahh/runner.sh";

    /** 容器名：一个应用同一时刻只允许一个运行容器。 */
    public String containerName(Long appId) {
        return CONTAINER_PREFIX + appId;
    }

    /**
     * 启动一个运行容器。
     *
     * @param appId        应用 id
     * @param workspace    宿主侧项目目录
     * @param logDir       宿主侧本次运行的日志目录
     * @param spec         运行规格
     * @param containerPort 需要映射的容器内端口（来自 spec 的主端口），可为 null
     * @return 启动结果（容器 id 与宿主端口）
     */
    public StartResult start(Long appId, Path workspace, Path logDir, RuntimeSpec spec, Integer containerPort) {
        if (!docker.available()) {
            throw new MyException(ErrorCode.OPERATION_ERROR,
                    "运行环境不可用：未检测到 Docker，请先启动 Docker 服务");
        }
        if (!docker.imageExists(props.getImage())) {
            throw new MyException(ErrorCode.OPERATION_ERROR,
                    "运行镜像 " + props.getImage() + " 不存在，请先执行 deploy/sandbox/build.ps1");
        }

        String name = containerName(appId);
        // 残留容器先清掉：同一应用不允许两个运行容器并存
        removeContainer(name);

        int hostPort = 0;
        if (containerPort != null) {
            hostPort = allocateHostPort();
        }

        writeRunnerScript(workspace, logDir, spec);

        List<String> args = new ArrayList<>(List.of(
                "create", "--name", name,
                "--workdir", CONTAINER_WORKSPACE,
                "--label", "glyahh.runtime.app_id=" + appId,
                // 只绑回环：运行中的应用不该暴露到局域网
                "--pids-limit", "512",
                "--cpus", props.getCpus(),
                "--memory", props.getMemory(),
                "--cap-drop", "ALL",
                "--security-opt", "no-new-privileges",
                "-v", docker.toEnginePath(workspace) + ":" + CONTAINER_WORKSPACE + ":rw",
                "-v", docker.toEnginePath(logDir) + ":" + CONTAINER_LOG_DIR + ":rw"));

        if (hostPort > 0) {
            args.add("-p");
            args.add("127.0.0.1:" + hostPort + ":" + containerPort);
        }
        args.add("--network");
        args.add(props.getNetwork());

        args.add(props.getImage());
        args.add("bash");
        args.add(RUNNER_PATH);

        DockerCli.Result created = docker.run(args, 120);
        if (!created.ok() || created.stdout().isBlank()) {
            throw new MyException(ErrorCode.OPERATION_ERROR, "创建运行容器失败：" + created.failureDetail());
        }
        String containerId = created.stdout().strip();

        DockerCli.Result started = docker.run(List.of("start", containerId), 120);
        if (!started.ok()) {
            removeContainer(name);
            throw new MyException(ErrorCode.OPERATION_ERROR, "启动运行容器失败：" + started.failureDetail());
        }

        return new StartResult(containerId, hostPort > 0 ? hostPort : null);
    }

    /**
     * 生成 runner 脚本。
     *
     * <p>脚本的职责：按顺序拉起所有受管进程，输出统一加上 {@code [进程名]} 前缀
     * 并同时写进挂载出来的日志目录；任何一个进程退出就收掉其余进程并让容器退出。
     *
     * <p>写成文件而不是拼进命令行：进程可能不止一个，命令里还可能有引号，
     * 拼字符串既难读又容易在转义上出错。
     */
    private void writeRunnerScript(Path workspace, Path logDir, RuntimeSpec spec) {
        Path scriptDir = workspace.resolve(".glyahh");
        try {
            Files.createDirectories(scriptDir);
        } catch (IOException e) {
            throw new MyException(ErrorCode.SYSTEM_ERROR, "准备运行目录失败：" + e.getMessage());
        }

        StringBuilder sb = new StringBuilder();
        sb.append("#!/bin/bash\n");
        sb.append("# 由平台生成，勿手改 —— 每次启动都会覆盖\n");
        sb.append("set -u\n");
        // pipefail 必须开：每个进程都是「命令 | sed | tee」管道，
        // 不开的话 wait -n 拿到的是 tee 的退出码，进程崩了也会显示成 0，
        // 平台就检测不到异常退出。
        sb.append("set -o pipefail\n");
        sb.append("LOG_DIR=").append(CONTAINER_LOG_DIR).append("\n");
        sb.append("mkdir -p \"$LOG_DIR\"\n");
        sb.append("pids=()\n");
        sb.append("names=()\n");
        sb.append("\n");
        sb.append("run_one() {\n");
        sb.append("  local name=\"$1\" workdir=\"$2\" cmd=\"$3\"\n");
        sb.append("  local dir=\"").append(CONTAINER_WORKSPACE).append("/$workdir\"\n");
        sb.append("  ( cd \"$dir\" || exit 1\n");
        sb.append("    exec bash -lc \"$cmd\" 2>&1 | sed -u \"s/^/[$name] /\" | tee -a \"$LOG_DIR/console.log\" ) &\n");
        sb.append("  pids+=($!)\n");
        sb.append("  names+=(\"$name\")\n");
        sb.append("}\n");
        sb.append("\n");

        if (spec.getBuildCommand() != null && !spec.getBuildCommand().isBlank()
                && !spec.getBuildCommand().contains("&")) {
            sb.append("# 构建：退出即结束，不作为受管进程；失败则容器直接失败\n");
            sb.append("echo \"[build] ").append(escapeForDoubleQuotes(spec.getBuildCommand())).append("\"\n");
            sb.append("bash -lc '").append(escapeForSingleQuotes(spec.getBuildCommand()))
                    .append("' 2>&1 | sed -u \"s/^/[build] /\" | tee -a \"$LOG_DIR/console.log\"\n");
            sb.append("build_status=${PIPESTATUS[0]}\n");
            sb.append("if [ \"$build_status\" -ne 0 ]; then\n");
            sb.append("  echo \"[build] 构建失败，退出码 $build_status\" | tee -a \"$LOG_DIR/console.log\"\n");
            sb.append("  exit 1\n");
            sb.append("fi\n\n");
        }

        for (RuntimeSpec.ManagedProcess process : spec.getProcesses()) {
            sb.append("run_one \"")
                    .append(escapeForDoubleQuotes(safeName(process.getName())))
                    .append("\" \"")
                    .append(escapeForDoubleQuotes(process.getWorkDir() == null ? "" : process.getWorkDir()))
                    .append("\" \"")
                    .append(escapeForDoubleQuotes(process.getCommand()))
                    .append("\"\n");
        }

        sb.append("\n");
        sb.append("echo \"[supervisor] 已启动 ${#pids[@]} 个进程\" | tee -a \"$LOG_DIR/console.log\"\n");
        sb.append("\n");
        sb.append("# 任何一个进程退出即认为本次运行结束\n");
        sb.append("wait -n\n");
        sb.append("exit_code=$?\n");
        sb.append("echo \"[supervisor] 有进程退出（退出码 $exit_code），正在停止其余进程\" | tee -a \"$LOG_DIR/console.log\"\n");
        sb.append("for pid in \"${pids[@]}\"; do\n");
        sb.append("  pkill -P \"$pid\" 2>/dev/null || true\n");
        sb.append("  kill \"$pid\" 2>/dev/null || true\n");
        sb.append("done\n");
        sb.append("wait 2>/dev/null || true\n");
        sb.append("exit \"$exit_code\"\n");

        try {
            Files.writeString(scriptDir.resolve("runner.sh"), sb.toString());
        } catch (IOException e) {
            throw new MyException(ErrorCode.SYSTEM_ERROR, "写入运行脚本失败：" + e.getMessage());
        }
    }

    /** 进程名会进 shell 双引号，去掉可能破坏引号或换行的字符。 */
    private static String safeName(String name) {
        if (name == null || name.isBlank()) {
            return "app";
        }
        return name.replaceAll("[^A-Za-z0-9_-]", "_");
    }

    private static String escapeForDoubleQuotes(String raw) {
        return raw == null ? "" : raw.replace("\\", "\\\\").replace("\"", "\\\"").replace("$", "\\$").replace("`", "\\`");
    }

    private static String escapeForSingleQuotes(String raw) {
        // 单引号串里出现单引号要写成 '\''
        return raw == null ? "" : raw.replace("'", "'\\''");
    }

    /**
     * 在端口区间内找一个空闲宿主端口。
     *
     * <p>先做一次探测再返回，但不保证绝对不被抢占 —— 真正的绑定由 docker 完成，
     * 失败会在启动时报错。这里只是把冲突概率降到很低。
     */
    private int allocateHostPort() {
        for (int port = props.getGatewayPortStart(); port <= props.getGatewayPortEnd(); port++) {
            if (isPortFree(port)) {
                return port;
            }
        }
        throw new MyException(ErrorCode.OPERATION_ERROR, "没有可用的运行端口，请先停止其他应用");
    }

    private static boolean isPortFree(int port) {
        try (ServerSocket socket = new ServerSocket()) {
            socket.setReuseAddress(true);
            socket.bind(new InetSocketAddress("127.0.0.1", port));
            return true;
        } catch (IOException e) {
            return false;
        }
    }

    /** 容器当前状态：{@code running} / {@code exited} / {@code missing} 等。 */
    public String containerStatus(String containerName) {
        DockerCli.Result result = docker.run(
                List.of("inspect", "-f", "{{.State.Status}}", containerName), 20);
        if (!result.ok()) {
            return "missing";
        }
        return result.stdout().strip().toLowerCase(Locale.ROOT);
    }

    /** 容器退出码；容器不存在或未退出时返回 null。 */
    public Integer containerExitCode(String containerName) {
        DockerCli.Result result = docker.run(
                List.of("inspect", "-f", "{{.State.ExitCode}}", containerName), 20);
        if (!result.ok() || result.stdout().isBlank()) {
            return null;
        }
        try {
            return Integer.parseInt(result.stdout().strip());
        } catch (NumberFormatException e) {
            return null;
        }
    }

    /** 容器启动时间（用于算运行时长）；容器不存在返回 null。 */
    public Instant containerStartedAt(String containerName) {
        DockerCli.Result result = docker.run(
                List.of("inspect", "-f", "{{.State.StartedAt}}", containerName), 20);
        if (!result.ok() || result.stdout().isBlank()) {
            return null;
        }
        try {
            return Instant.parse(result.stdout().strip());
        } catch (RuntimeException e) {
            return null;
        }
    }

    /**
     * 停止并删除容器；不存在时静默返回。
     *
     * <p>先 {@code stop} 再 {@code rm}，不能用 {@code rm -f -t}：{@code docker rm} 没有
     * {@code -t} 选项，那样写会直接报 unknown shorthand flag，容器根本删不掉
     * （实测过 —— 停止后状态还显示运行中）。
     */
    public void removeContainer(String containerName) {
        docker.run(List.of("stop", "-t", String.valueOf(props.getStopGraceSeconds()), containerName), 90);
        docker.run(List.of("rm", "-f", containerName), 60);
    }

    /**
     * 等待容器里的端口在宿主侧可连。
     *
     * @return 就绪返回 true；超时或期间容器已退出返回 false
     */
    public boolean awaitReady(String containerName, Integer hostPort, Duration timeout) {
        if (hostPort == null) {
            // 没有网络端口的进程：只要求容器还在跑
            return "running".equals(containerStatus(containerName));
        }
        Instant deadline = Instant.now().plus(timeout);
        while (Instant.now().isBefore(deadline)) {
            if (canConnect(hostPort)) {
                return true;
            }
            String status = containerStatus(containerName);
            if (!"running".equals(status) && !"created".equals(status)) {
                // 已退出，不必再等
                return false;
            }
            try {
                Thread.sleep(500);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                return false;
            }
        }
        return false;
    }

    /** 一次性检查当前端口，供状态轮询使用，不阻塞等待启动。 */
    public boolean isReady(String containerName, Integer hostPort) {
        return "running".equals(containerStatus(containerName))
                && (hostPort == null || canConnect(hostPort));
    }

    private static boolean canConnect(int port) {
        try (Socket socket = new Socket()) {
            socket.connect(new InetSocketAddress("127.0.0.1", port), 800);
            return true;
        } catch (IOException e) {
            return false;
        }
    }

    /** 读取容器统计（一次性快照）。 */
    public String readStatsJson(String containerName) {
        DockerCli.Result result = docker.run(
                List.of("stats", "--no-stream", "--format", "{{json .}}", containerName), 30);
        return result.ok() ? result.stdout().strip() : null;
    }

    /** 启动结果。 */
    public record StartResult(String containerId, Integer hostPort) {
    }
}
