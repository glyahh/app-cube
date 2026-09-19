package com.dbts.glyahhaigeneratecode.runtime;

import com.dbts.glyahhaigeneratecode.config.RuntimeProperties;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.TimeUnit;

/**
 * Docker CLI 调用的唯一出口。
 *
 * <p>为什么是 CLI 而不是 SDK：Python 侧沙箱已经证明这台机器上 docker 可能只存在于
 * WSL 里（Windows 没有 docker.exe），探测逻辑必须能走 {@code wsl.exe -d <distro> -- docker}。
 * 加 Java SDK 依赖还得再解一遍同样的问题，不如直接复用已验证的探测方式。
 *
 * <p>容器只绑回环地址：运行中的应用不该暴露到局域网。
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class DockerCli {

    private final RuntimeProperties props;

    /** 探测结果缓存：一台机器上 docker 的入口不会中途变化，每次子进程探测代价太高。 */
    private volatile List<String> cachedCommand;

    /** 单次 CLI 调用的默认超时。docker create/start 正常在秒级。 */
    private static final int DEFAULT_TIMEOUT_SECONDS = 60;

    /**
     * 探测 docker 入口，返回命令前缀（如 {@code ["docker"]} 或 {@code ["wsl.exe","-d","Ubuntu","--","docker"]}）。
     *
     * @return 命令前缀；都不可用时返回 null
     */
    public List<String> commandPrefix() {
        List<String> cached = cachedCommand;
        if (cached != null) {
            return cached;
        }
        synchronized (this) {
            if (cachedCommand != null) {
                return cachedCommand;
            }
            List<String> resolved = probeCommand();
            cachedCommand = resolved;
            return resolved;
        }
    }

    private List<String> probeCommand() {
        if (isExecutable("docker")) {
            return List.of("docker");
        }
        if (isExecutable("wsl.exe")) {
            String distro = System.getenv().getOrDefault("AGENT_WSL_DISTRO", "Ubuntu");
            List<String> wslDocker = List.of("wsl.exe", "-d", distro, "--", "docker");
            if (run(wslDocker, List.of("info", "--format", "{{.ServerVersion}}"), 15).ok()) {
                return wslDocker;
            }
        }
        return null;
    }

    /**
     * 在 PATH 里找一个可执行文件。
     *
     * <p>这里刻意不用 {@code Path.of}：PATH 里可能存在当前进程编码解释不了的目录名
     * （实测有中文路径被转成 {@code ?}），{@code Path.of} 会抛 {@code InvalidPathException}
     * 直接把请求打成 500。逐项容错，坏的那条跳过就好。
     */
    private static boolean isExecutable(String name) {
        String pathEnv = System.getenv("PATH");
        if (pathEnv == null) {
            return false;
        }
        for (String dir : pathEnv.split(java.io.File.pathSeparator)) {
            if (dir.isBlank()) {
                continue;
            }
            for (String ext : new String[]{"", ".exe", ".cmd", ".bat"}) {
                try {
                    if (new java.io.File(dir, name + ext).canExecute()) {
                        return true;
                    }
                } catch (RuntimeException ignored) {
                    // 这个 PATH 项本身有问题，跳过
                }
            }
        }
        return false;
    }

    /** 容器是否可用（daemon 在线）。 */
    public boolean available() {
        List<String> prefix = commandPrefix();
        if (prefix == null) {
            return false;
        }
        return run(prefix, List.of("info", "--format", "{{.ServerVersion}}"), 15).ok();
    }

    /** 镜像是否已存在于本地。 */
    public boolean imageExists(String image) {
        List<String> prefix = commandPrefix();
        if (prefix == null) {
            return false;
        }
        return run(prefix, List.of("image", "inspect", image), 20).ok();
    }

    /**
     * 把宿主路径转换成容器引擎能识别的形式。
     *
     * <p>WSL 里的 docker 看不到 {@code D:\...}，必须换成 {@code /mnt/d/...}。
     */
    public String toEnginePath(Path path) {
        String text = path.toAbsolutePath().normalize().toString().replace('\\', '/');
        List<String> prefix = commandPrefix();
        boolean viaWsl = prefix != null && !prefix.isEmpty() && prefix.get(0).startsWith("wsl");
        if (viaWsl && text.length() >= 2 && text.charAt(1) == ':') {
            return "/mnt/" + Character.toLowerCase(text.charAt(0)) + text.substring(2);
        }
        return text;
    }

    /** 命令执行结果。 */
    public record Result(boolean ok, int exitCode, String stdout, String stderr) {

        /** 失败时用于日志与错误信息的最简描述。 */
        public String failureDetail() {
            String detail = stderr == null || stderr.isBlank() ? stdout : stderr;
            if (detail == null) {
                return "exit=" + exitCode;
            }
            String trimmed = detail.strip();
            return trimmed.length() > 400 ? trimmed.substring(0, 400) : trimmed;
        }
    }

    /**
     * 执行一条 docker 子命令。
     *
     * @param args    子命令与参数，如 {@code create --name x image}
     * @param timeout 超时秒数
     * @param stdin   需要写入标准输入的内容（如 tar 流），可为 null
     */
    public Result run(List<String> args, int timeout, byte[] stdin) {
        List<String> prefix = commandPrefix();
        if (prefix == null) {
            return new Result(false, -1, "", "docker CLI unavailable");
        }
        return run(prefix, args, timeout, stdin);
    }

    public Result run(List<String> args, int timeout) {
        return run(args, timeout, null);
    }

    private Result run(List<String> prefix, List<String> args, int timeout) {
        return run(prefix, args, timeout, null);
    }

    private Result run(List<String> prefix, List<String> args, int timeout, byte[] stdin) {
        List<String> command = new ArrayList<>(prefix);
        command.addAll(args);

        ProcessBuilder builder = new ProcessBuilder(command);
        builder.redirectErrorStream(false);
        Process process = null;
        try {
            process = builder.start();
            if (stdin != null) {
                try (var out = process.getOutputStream()) {
                    out.write(stdin);
                }
            } else {
                process.getOutputStream().close();
            }
            // 必须并发读走输出，否则管道写满会让子进程阻塞，waitFor 直接超时
            byte[][] buffers = new byte[2][];
            Thread outReader = readAsync(process.getInputStream(), buffers, 0);
            Thread errReader = readAsync(process.getErrorStream(), buffers, 1);

            boolean finished = process.waitFor(timeout, TimeUnit.SECONDS);
            if (!finished) {
                process.destroyForcibly();
                process.waitFor(5, TimeUnit.SECONDS);
                return new Result(false, 124, "", "docker command timeout after " + timeout + "s");
            }
            outReader.join(2000);
            errReader.join(2000);
            return new Result(process.exitValue() == 0, process.exitValue(),
                    decode(buffers[0]), decode(buffers[1]));
        } catch (IOException e) {
            log.warn("docker 调用失败: {}", e.getMessage());
            return new Result(false, -1, "", e.getMessage());
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return new Result(false, -1, "", "interrupted");
        } finally {
            if (process != null && process.isAlive()) {
                process.destroyForcibly();
            }
        }
    }

    private static Thread readAsync(java.io.InputStream stream, byte[][] sink, int index) {
        Thread thread = new Thread(() -> {
            try (var in = stream; var buffer = new java.io.ByteArrayOutputStream()) {
                in.transferTo(buffer);
                sink[index] = buffer.toByteArray();
            } catch (IOException ignored) {
                sink[index] = new byte[0];
            }
        });
        thread.setDaemon(true);
        thread.start();
        return thread;
    }

    /**
     * 解码子进程输出。
     *
     * <p>顺序与 Python 侧一致：UTF-8 优先，再试 UTF-16（Windows 上 wsl.exe 偶尔给这个），
     * 最后 GBK 兜底 —— 中文 Windows 的错误信息常见 GBK。
     */
    private static String decode(byte[] raw) {
        if (raw == null || raw.length == 0) {
            return "";
        }
        for (String charset : new String[]{"UTF-8", "UTF-16", "GBK"}) {
            try {
                String text = new String(raw, charset).replace("\u0000", "");
                if (!text.isEmpty()) {
                    return text;
                }
            } catch (Exception ignored) {
                // 换下一个编码
            }
        }
        return new String(raw, StandardCharsets.UTF_8);
    }
}
