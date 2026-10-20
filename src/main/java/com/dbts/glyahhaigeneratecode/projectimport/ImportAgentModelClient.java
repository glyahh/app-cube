package com.dbts.glyahhaigeneratecode.projectimport;

import com.dbts.glyahhaigeneratecode.agent.AgentProperties;
import com.dbts.glyahhaigeneratecode.exception.ErrorCode;
import com.dbts.glyahhaigeneratecode.exception.MyException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.concurrent.FutureTask;
import java.util.concurrent.Semaphore;
import java.util.concurrent.TimeUnit;

/** 独立有界模型调用，不占用正在等待许可的编辑 Worker。 */
@Service
@RequiredArgsConstructor
public class ImportAgentModelClient {
    private final AgentProperties properties;
    private final ObjectMapper json;
    private final ImportAgentReviewPool reviewPool;
    private final Semaphore slots = new Semaphore(2);

    public JsonNode catalog() {
        return call("core.model_profiles", new byte[0], Duration.ofSeconds(15), 32 * 1024);
    }

    public JsonNode review(String kind, String objectHash, JsonNode payload) {
        if (!java.util.Set.of("ACTION", "CANDIDATE").contains(kind) || objectHash == null
                || !objectHash.matches("[0-9a-f]{64}") || payload == null || !payload.isObject()) {
            throw new MyException(ErrorCode.PARAMS_ERROR, "独立审查对象无效。");
        }
        var input = json.createObjectNode().put("kind", kind).put("objectHash", objectHash);
        input.set("payload", payload);
        var result = reviewPool.review(input);
        if (!result.isObject() || result.size() != 3 || !objectHash.equals(result.path("objectHash").asText())
                || !java.util.Set.of("ORDINARY", "HIGH").contains(result.path("risk").asText())
                || !result.path("reason").isTextual() || result.path("reason").asText().isBlank()
                || result.path("reason").asText().length() > 1000) throw failure();
        return result;
    }

    private JsonNode call(String module, byte[] input, Duration timeout, int outputLimit) {
        if (input.length > 256 * 1024) throw new MyException(ErrorCode.PARAMS_ERROR, "审查输入超过限制，本次未批准。");
        if (!slots.tryAcquire()) throw new MyException(ErrorCode.TOO_MANY_REQUEST, "独立审查繁忙，请稍后重试。");
        Process process = null;
        try {
            // 1. 只运行平台固定模块，不接受客户端指定命令、路径或模型凭据。
            Path root = Path.of(System.getProperty("user.dir")).toAbsolutePath();
            Path agent = properties.getScriptPath().isBlank() ? root.resolve("agent")
                    : root.resolve(properties.getScriptPath()).toAbsolutePath().getParent();
            String executable = properties.getPythonExecutable();
            if (executable.isBlank()) executable = agent.resolve(".venv").resolve(
                    System.getProperty("os.name", "").toLowerCase().contains("win") ? "Scripts/python.exe" : "bin/python").toString();
            Path interpreter = root.resolve(executable).toAbsolutePath();
            if (!Files.isRegularFile(interpreter) || !Files.isDirectory(agent)) throw failure();
            process = new ProcessBuilder(interpreter.toString(), "-m", module).directory(agent.toFile())
                    .redirectError(ProcessBuilder.Redirect.DISCARD).start();
            final Process child = process;
            var reader = new FutureTask<byte[]>(() -> {
                try (var stream = child.getInputStream()) {
                    byte[] bytes = stream.readNBytes(outputLimit + 1);
                    if (bytes.length > outputLimit) throw new IOException("MODEL_OUTPUT_LIMIT");
                    return bytes;
                }
            });
            var writer = new FutureTask<Void>(() -> {
                try (var stream = child.getOutputStream()) { stream.write(input); }
                return null;
            });
            Thread.ofVirtual().start(reader);
            Thread.ofVirtual().start(writer);
            // 2. 输入写入和输出读取也受同一总时限保护；超时后不保留后台调用。
            long deadline = System.nanoTime() + timeout.toNanos();
            if (!process.waitFor(timeout.toMillis(), TimeUnit.MILLISECONDS) || process.exitValue() != 0) throw failure();
            writer.get(Math.max(1, deadline - System.nanoTime()), TimeUnit.NANOSECONDS);
            byte[] output = reader.get(Math.max(1, deadline - System.nanoTime()), TimeUnit.NANOSECONDS);
            var parser = json.getFactory().createParser(new String(output, StandardCharsets.UTF_8));
            try (parser) {
                JsonNode result = json.readTree(parser);
                if (result == null || parser.nextToken() != null) throw failure();
                return result;
            }
        } catch (InterruptedException error) {
            Thread.currentThread().interrupt();
            throw failure();
        } catch (IOException | java.util.concurrent.ExecutionException | java.util.concurrent.TimeoutException error) {
            throw failure();
        } finally {
            if (process != null && process.isAlive()) {
                process.descendants().forEach(ProcessHandle::destroyForcibly);
                process.destroyForcibly();
                boolean interrupted = Thread.interrupted();
                try { process.waitFor(5, TimeUnit.SECONDS); }
                catch (InterruptedException error) { interrupted = true; }
                finally { if (interrupted) Thread.currentThread().interrupt(); }
            }
            if (process == null || !process.isAlive()) slots.release();
            else {
                // 停止未确认仍占槽位，直到实际退出，不允许累积悬挂调用。
                final Process pending = process;
                Thread.ofVirtual().start(() -> {
                    boolean interrupted = false;
                    while (pending.isAlive()) {
                        try { pending.waitFor(); }
                        catch (InterruptedException error) { interrupted = true; }
                    }
                    slots.release();
                    if (interrupted) Thread.currentThread().interrupt();
                });
            }
        }
    }

    private static MyException failure() {
        return new MyException(ErrorCode.OPERATION_ERROR, "模型配置读取或独立审查失败，本次未批准。");
    }
}
