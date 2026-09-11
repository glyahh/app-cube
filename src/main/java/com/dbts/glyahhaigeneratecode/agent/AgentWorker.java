package com.dbts.glyahhaigeneratecode.agent;

import lombok.extern.slf4j.Slf4j;

import java.io.BufferedReader;
import java.io.BufferedWriter;
import java.io.IOException;
import java.io.InputStreamReader;
import java.io.OutputStreamWriter;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.List;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * 一个常驻的 Python Agent 进程。
 *
 * <p>协议：往 stdin 写一行 JSON 请求，从 stdout 读多行 NDJSON 事件，最后一行是 终止帧。
 *
 * <p><b>stdout 用独立线程抽干。</b>不能用「调用方直接 readLine」的写法 ——
 * readLine 没有超时，Python 卡死时会把进程池的槽位永久占住。抽到队列里之后，
 * {@link #readEvent} 可以带超时轮询，且 EOF 可检测。
 */
@Slf4j
public final class AgentWorker implements AutoCloseable {

    private final long pid;
    private final Process process;
    private final BufferedWriter stdin;
    private final BlockingQueue<String> lines = new LinkedBlockingQueue<>();
    private final AtomicBoolean eof = new AtomicBoolean(false);
    private final Thread pump;

    private AgentWorker(Process process) {
        this.process = process;
        this.pid = process.pid();
        this.stdin = new BufferedWriter(new OutputStreamWriter(process.getOutputStream(), StandardCharsets.UTF_8));
        // 1. 起一个虚拟线程把 stdout 抽干，避免调用方阻塞在 readLine 上
        this.pump = Thread.ofVirtual().name("agent-stdout-" + pid).start(this::pumpStdout);
    }

    /**
     * 拉起一个 Python 进程。
     *
     * <p>stderr 走 {@code Redirect.INHERIT}：一来 Python 的日志会直接出现在 Java 日志里，
     * 二来避免「没人读 stderr 导致管道缓冲填满、子进程写日志时阻塞」这个经典死锁。
     */
    static AgentWorker start(String executable, String scriptPath, Duration startupTimeout) throws IOException {
        ProcessBuilder builder = new ProcessBuilder(executable, scriptPath);
        builder.redirectError(ProcessBuilder.Redirect.INHERIT);
        // agent/ 作为工作目录：app.py 里的相对导入依赖它
        builder.directory(new java.io.File(scriptPath).getParentFile());
        // 强制 UTF-8。Windows 默认编码会让中文乱码。
        builder.environment().put("PYTHONIOENCODING", "utf-8");
        builder.environment().put("PYTHONUNBUFFERED", "1");

        Process process = builder.start();
        AgentWorker worker = new AgentWorker(process);
        worker.awaitStartup(startupTimeout);
        return worker;
    }

    /**
     * 等进程稳定下来。
     *
     * <p>刻意不做就绪握手 —— Python 在收到请求前不输出任何东西，stdin 由操作系统缓冲，
     * 早写不会丢。这里只区分「起来了」和「当场就死了」（比如解释器路径写错、
     * venv 不存在）。真死了的话 stderr 已经把原因打到 Java 日志里了。
     */
    private void awaitStartup(Duration timeout) throws IOException {
        long deadline = System.nanoTime() + timeout.toNanos();
        while (System.nanoTime() < deadline) {
            if (!process.isAlive()) {
                throw new IOException("Python 进程启动后立即退出，退出码=" + process.exitValue()
                        + "，pid=" + pid + "。原因见上方 stderr。");
            }
            // 进程活着且已跑过一小段 —— 认为就绪
            if (System.nanoTime() + Duration.ofMillis(200).toNanos() < deadline) {
                try {
                    Thread.sleep(200);
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    throw new IOException("等待 Python 启动时被中断", e);
                }
                if (process.isAlive()) {
                    return;
                }
            }
        }
        log.warn("Python 进程 {} 启动等待超时，按就绪处理", pid);
    }

    private void pumpStdout() {
        try (BufferedReader reader = new BufferedReader(
                new InputStreamReader(process.getInputStream(), StandardCharsets.UTF_8))) {
            String line;
            while ((line = reader.readLine()) != null) {
                lines.put(line);
            }
        } catch (IOException e) {
            if (process.isAlive()) {
                log.warn("读 Python 进程 {} 的 stdout 失败", pid, e);
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        } finally {
            eof.set(true);
        }
    }

    /** 发一行请求。 */
    public void send(String jsonLine) throws IOException {
        stdin.write(jsonLine);
        stdin.write('\n');
        stdin.flush();
    }

    /**
     * 读一帧事件。
     *
     * @return 一行 NDJSON；返回 {@code null} 表示流已结束（Python 退出或崩了）
     * @throws TimeoutException 等待超时 —— 说明 Python 卡住，按故障处理
     */
    public String readEvent(Duration timeout) throws TimeoutException, InterruptedException {
        String line = lines.poll(timeout.toMillis(), TimeUnit.MILLISECONDS);
        if (line != null) {
            return line;
        }
        // 队列空且已 EOF = 真的结束了；否则是超时
        return eof.get() ? null : throwTimeout();
    }

    private static String throwTimeout() throws TimeoutException {
        throw new TimeoutException("等待 Python 事件超时");
    }

    public boolean isEof() {
        return eof.get();
    }

    public boolean isAlive() {
        return process.isAlive();
    }

    public long pid() {
        return pid;
    }

    /**
     * 杀掉整棵进程树。
     *
     * <p>必须杀进程树：Python 起的 npm / node 不会随父进程退出，
     * {@code destroyForcibly()} 只杀直接子进程。
     *
     * <p>先取快照再动手 —— {@code descendants()} 是惰性流，父进程一死它就空了。
     */
    public void destroyTree() {
        List<ProcessHandle> descendants = process.toHandle().descendants().toList();
        for (ProcessHandle handle : descendants) {
            if (handle.isAlive()) {
                handle.destroyForcibly();
            }
        }
        if (process.isAlive()) {
            process.destroyForcibly();
        }
        if (!descendants.isEmpty()) {
            log.info("已终止 Python 进程 {} 及其 {} 个子进程", pid, descendants.size());
        }
    }

    @Override
    public void close() {
        destroyTree();
    }
}
