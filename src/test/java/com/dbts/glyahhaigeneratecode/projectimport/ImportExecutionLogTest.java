package com.dbts.glyahhaigeneratecode.projectimport;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.*;

/** 对实际落盘出口验证，不以停止后的文件重写代替运行中保护。 */
class ImportExecutionLogTest {
    @TempDir Path directory;

    private OutputStream log(Path path) throws Exception {
        var type = Class.forName(ImportExecutionSandbox.class.getName() + "$DeferredLog");
        var constructor = type.getDeclaredConstructor(Path.class, OutputStream.class);
        constructor.setAccessible(true);
        return (OutputStream) constructor.newInstance(path, Files.newOutputStream(path));
    }

    @Test void 运行中完整日志行落盘前已经遮盖() throws Exception {
        Path path = directory.resolve("live.log");
        try (var output = log(path)) {
            output.write("API_TOKEN=FAKE_LIVE_SECRET_12345\n正常诊断：exit 3\n".getBytes(StandardCharsets.UTF_8));
            String actual = Files.readString(path);
            assertFalse(actual.contains("FAKE_LIVE_SECRET_12345"));
            assertTrue(actual.contains("正常诊断：exit 3"));
        }
    }

    @Test void 非UTF8字节不能绕过整份日志遮盖() throws Exception {
        Path path = directory.resolve("binary.log");
        try (var output = log(path)) {
            output.write(new byte[]{(byte) 0xff, (byte) 0xfe, '\n'});
            output.write("API_TOKEN=FAKE_BINARY_SECRET_12345\n".getBytes(StandardCharsets.UTF_8));
        }
        assertFalse(Files.readString(path, StandardCharsets.ISO_8859_1).contains("FAKE_BINARY_SECRET_12345"));
    }

    @Test void 秘密与中文跨任意字节分块仍遮盖且保留诊断() throws Exception {
        Path path = directory.resolve("chunks.log");
        byte[] bytes = "中文 API_TOKEN=FAKE_CHUNK_SECRET_12345\n恢复成功\n".getBytes(StandardCharsets.UTF_8);
        try (var output = log(path)) {
            for (byte value : bytes) output.write(value);
        }
        assertEquals("中文 API_TOKEN=***\n恢复成功\n", Files.readString(path));
    }

    @Test void 未结束行不落原文关闭后保留脱敏尾行() throws Exception {
        Path path = directory.resolve("tail.log");
        try (var output = log(path)) {
            output.write("API_TOKEN=FAKE_TAIL_SECRET_12345".getBytes(StandardCharsets.UTF_8));
            assertFalse(Files.readString(path).contains("FAKE_TAIL_SECRET_12345"));
        }
        assertEquals("API_TOKEN=***", Files.readString(path));
    }

    @Test void 两条输出流交错不破坏秘密识别() throws Exception {
        Path path = directory.resolve("mixed.log");
        try (var output = log(path)) {
            var method = output.getClass().getDeclaredMethod("stream");
            method.setAccessible(true);
            var stdout = (OutputStream) method.invoke(output);
            var stderr = (OutputStream) method.invoke(output);
            stdout.write("API_TO".getBytes(StandardCharsets.UTF_8));
            stderr.write("stderr diagnostic\n".getBytes(StandardCharsets.UTF_8));
            stdout.write("KEN=FAKE_MIXED_SECRET_12345\n".getBytes(StandardCharsets.UTF_8));
            assertEquals("stderr diagnostic\nAPI_TOKEN=***\n", Files.readString(path));
        }
    }

    @Test void 非法字节替换膨胀不能突破实际日志限额() throws Exception {
        Path path = directory.resolve("limit.log");
        var type = Class.forName(ImportExecutionSandbox.class.getName() + "$DeferredLog");
        var constructor = type.getDeclaredConstructor(Path.class, OutputStream.class, long.class, java.util.concurrent.atomic.AtomicBoolean.class);
        constructor.setAccessible(true);
        var exceeded = new java.util.concurrent.atomic.AtomicBoolean();
        try (var output = (OutputStream) constructor.newInstance(path, Files.newOutputStream(path), 10L, exceeded)) {
            output.write(new byte[]{(byte) 0xff, (byte) 0xff, (byte) 0xff, (byte) 0xff, '\n'});
        }
        assertTrue(exceeded.get());
        assertTrue(Files.size(path) <= 10);
        assertEquals("���", Files.readString(path));
    }
}
