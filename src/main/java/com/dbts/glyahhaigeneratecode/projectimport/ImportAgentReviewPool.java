package com.dbts.glyahhaigeneratecode.projectimport;

import com.dbts.glyahhaigeneratecode.agent.AgentProperties;
import com.dbts.glyahhaigeneratecode.agent.ModelConfigChangedEvent;
import com.dbts.glyahhaigeneratecode.exception.ErrorCode;
import com.dbts.glyahhaigeneratecode.exception.MyException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.annotation.PostConstruct;
import jakarta.annotation.PreDestroy;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.context.event.EventListener;
import org.springframework.stereotype.Component;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.UUID;
import java.util.concurrent.FutureTask;
import java.util.concurrent.TimeUnit;

/** 两个独立审查进程；只复用依赖，每个请求重新构建无工具模型调用。 */
@Slf4j
@Component
@RequiredArgsConstructor
public class ImportAgentReviewPool {
    private final AgentProperties properties;
    private final ObjectMapper json;
    private final Worker[] workers = new Worker[2];
    private long generation;
    private boolean closed;

    private static final class Worker {
        private Process process;
        private boolean busy = true;
        private boolean ready;
        private boolean retiring;
        private final long generation;
        private Worker(long generation) { this.generation = generation; }
    }

    @PostConstruct
    void warmUp() {
        Thread.ofVirtual().name("review-pool-warmup").start(() -> {
            for (int i = 0; i < workers.length; i++) {
                Worker worker;
                synchronized (workers) {
                    if (closed) return;
                    if (workers[i] != null) continue;
                    worker = new Worker(generation);
                    workers[i] = worker;
                }
                try {
                    initialize(worker);
                    release(worker, true);
                } catch (Exception error) {
                    release(worker, false);
                    log.warn("独立审查进程预热失败，审查请求仍按失败转人工处理");
                }
            }
        });
    }

    public JsonNode review(JsonNode input) {
        String requestId = UUID.randomUUID().toString();
        byte[] frame;
        try {
            if (json.writeValueAsBytes(input).length > 256 * 1024) throw failure();
            frame = json.writeValueAsBytes(json.createObjectNode().put("requestId", requestId).set("input", input));
        } catch (IOException error) { throw failure(); }
        if (frame.length + 1 > 256 * 1024 + 256) throw failure();
        Worker worker = acquire();
        boolean succeeded = false;
        long started = System.nanoTime();
        try {
            if (!worker.ready) initialize(worker);
            long prepared = System.nanoTime();
            long remaining = TimeUnit.SECONDS.toNanos(90) - (prepared - started);
            JsonNode response = exchange(worker.process, frame, Math.max(1, remaining), 16 * 1024 + 256);
            if (response.size() != 3 || !requestId.equals(response.path("requestId").asText())
                    || !response.path("result").isObject() || response.has("error")
                    || !validTiming(response.path("timing"))) throw failure();
            succeeded = true;
            log.info("review timing requestId={} prepareMs={} invocationMs={}", requestId,
                    TimeUnit.NANOSECONDS.toMillis(prepared - started) + response.path("timing").path("prepareMs").asLong(),
                    response.path("timing").path("invokeMs").asLong());
            return response.path("result");
        } catch (InterruptedException error) {
            Thread.currentThread().interrupt();
            throw failure();
        } catch (Exception error) { throw failure(); }
        finally { release(worker, succeeded); }
    }

    private Worker acquire() {
        synchronized (workers) {
            if (closed) throw failure();
            for (Worker worker : workers) {
                if (worker != null && !worker.busy && worker.generation == generation) {
                    worker.busy = true;
                    return worker;
                }
            }
            for (int i = 0; i < workers.length; i++) {
                if (workers[i] == null) {
                    workers[i] = new Worker(generation);
                    return workers[i];
                }
            }
        }
        throw new MyException(ErrorCode.TOO_MANY_REQUEST, "独立审查繁忙，请稍后重试。");
    }

    private void initialize(Worker worker) throws Exception {
        // 1. 创建过程也属于占用期，关闭或换配置不能提前释放进程名额。
        synchronized (workers) {
            if (closed || worker.generation != generation) throw failure();
            worker.process = startProcess();
        }
        JsonNode ready = exchange(worker.process, null, properties.getStartupTimeout().toNanos(), 1024);
        if (ready.size() != 2 || !"ready".equals(ready.path("type").asText())
                || !ready.path("protocol").isIntegralNumber() || ready.path("protocol").asInt() != 1) throw failure();
        worker.ready = true;
        log.info("review worker ready generation={} protocol=1", worker.generation);
    }

    protected Process startProcess() throws IOException {
        Path root = Path.of(System.getProperty("user.dir")).toAbsolutePath();
        Path agent = properties.getScriptPath().isBlank() ? root.resolve("agent")
                : root.resolve(properties.getScriptPath()).toAbsolutePath().getParent();
        String executable = properties.getPythonExecutable();
        if (executable.isBlank()) executable = agent.resolve(".venv").resolve(
                System.getProperty("os.name", "").toLowerCase().contains("win") ? "Scripts/python.exe" : "bin/python").toString();
        Path interpreter = root.resolve(executable).toAbsolutePath();
        if (!Files.isRegularFile(interpreter) || !Files.isDirectory(agent)) throw failure();
        return new ProcessBuilder(interpreter.toString(), "-u", "-m", "core.permission_review_worker")
                .directory(agent.toFile()).redirectError(ProcessBuilder.Redirect.DISCARD).start();
    }

    private JsonNode exchange(Process process, byte[] frame, long timeout, int limit) throws Exception {
        var task = new FutureTask<JsonNode>(() -> {
            if (frame != null) {
                process.getOutputStream().write(frame);
                process.getOutputStream().write('\n');
                process.getOutputStream().flush();
            }
            var output = new ByteArrayOutputStream();
            for (int value; (value = process.getInputStream().read()) != -1;) {
                if (value == '\n') {
                    try (var parser = json.getFactory().createParser(output.toByteArray())) {
                        JsonNode result = json.readTree(parser);
                        if (result == null || !result.isObject() || parser.nextToken() != null) throw failure();
                        return result;
                    }
                }
                if (output.size() >= limit) throw failure();
                output.write(value);
            }
            throw failure();
        });
        Thread.ofVirtual().start(task);
        try { return task.get(timeout, TimeUnit.NANOSECONDS); }
        finally { task.cancel(true); }
    }

    private boolean validTiming(JsonNode timing) {
        if (!timing.isObject() || timing.size() != 2) return false;
        for (String field : java.util.List.of("prepareMs", "invokeMs")) {
            JsonNode value = timing.path(field);
            if (!value.isNumber() || !Double.isFinite(value.asDouble()) || value.asDouble() < 0 || value.asDouble() > 90_000) return false;
        }
        return true;
    }

    private void release(Worker worker, boolean reusable) {
        synchronized (workers) {
            if (reusable && !closed && worker.generation == generation && worker.process != null && worker.process.isAlive()) {
                worker.busy = false;
                return;
            }
        }
        retire(worker);
    }

    private void retire(Worker worker) {
        synchronized (workers) {
            if (worker.retiring) return;
            worker.retiring = true;
        }
        Process process = worker.process;
        var descendants = process == null ? java.util.List.<ProcessHandle>of() : process.descendants().toList();
        if (process != null) {
            descendants.forEach(ProcessHandle::destroyForcibly);
            process.destroyForcibly();
        }
        // 停止未确认仍保留名额；等待进程树退出后再清账。
        Thread.ofVirtual().name("review-pool-reaper").start(() -> {
            boolean interrupted = false;
            if (process != null) {
                while (process.isAlive()) {
                    try { process.waitFor(); }
                    catch (InterruptedException error) { interrupted = true; }
                }
            }
            for (ProcessHandle child : descendants) child.onExit().join();
            boolean replenish;
            synchronized (workers) {
                for (int i = 0; i < workers.length; i++) if (workers[i] == worker) workers[i] = null;
                replenish = !closed && worker.generation != generation;
            }
            if (replenish) warmUp();
            if (interrupted) Thread.currentThread().interrupt();
        });
    }

    @EventListener
    public void reload(ModelConfigChangedEvent event) {
        synchronized (workers) {
            generation++;
            for (Worker worker : workers) {
                if (worker != null && !worker.busy) { worker.busy = true; retire(worker); }
            }
        }
        // 空闲旧进程确认退出后重新预热；在途请求完成后再换用新配置。
    }

    @PreDestroy
    public void close() {
        synchronized (workers) {
            closed = true;
            for (Worker worker : workers) if (worker != null) { worker.busy = true; retire(worker); }
        }
    }

    private static MyException failure() {
        return new MyException(ErrorCode.OPERATION_ERROR, "独立审查失败，本次未批准。");
    }
}
