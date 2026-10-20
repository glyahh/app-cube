package com.dbts.glyahhaigeneratecode.projectimport;

import com.dbts.glyahhaigeneratecode.agent.AgentProperties;
import com.dbts.glyahhaigeneratecode.agent.ModelConfigChangedEvent;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;

import java.io.*;
import java.time.Duration;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.*;

class ImportAgentReviewPoolTest {
    private final ObjectMapper json = new ObjectMapper();

    private class EchoProcess extends Process {
        final LinkedBlockingQueue<Integer> bytes = new LinkedBlockingQueue<>();
        final InputStream response = new InputStream() {
            @Override public int read() throws IOException {
                try { return bytes.take(); }
                catch (InterruptedException error) { Thread.currentThread().interrupt(); throw new IOException(error); }
            }
            @Override public int available() { return bytes.size(); }
        };
        final OutputStream responseWriter = new OutputStream() {
            @Override public void write(int value) { bytes.add(value); }
            @Override public void close() { bytes.add(-1); }
        };
        final ByteArrayOutputStream request = new ByteArrayOutputStream();
        volatile boolean alive = true;
        boolean wrongIdentity;
        volatile boolean refuseStop;
        EchoProcess() throws IOException {
            responseWriter.write("{\"type\":\"ready\",\"protocol\":1}\n".getBytes());
        }
        @Override public OutputStream getOutputStream() {
            return new OutputStream() {
                @Override public void write(int value) throws IOException {
                    if (value != '\n') { request.write(value); return; }
                    var input = json.readTree(request.toByteArray());
                    request.reset();
                    var out = json.createObjectNode().put("requestId", wrongIdentity ? "wrong" : input.path("requestId").asText());
                    out.set("result", input.path("input"));
                    out.set("timing", json.createObjectNode().put("prepareMs", 0).put("invokeMs", 0));
                    responseWriter.write(json.writeValueAsBytes(out));
                    responseWriter.write('\n'); responseWriter.flush();
                }
            };
        }
        @Override public InputStream getInputStream() { return response; }
        @Override public InputStream getErrorStream() { return InputStream.nullInputStream(); }
        @Override public synchronized int waitFor() throws InterruptedException { while (alive) wait(); return 0; }
        @Override public int exitValue() { if (alive) throw new IllegalThreadStateException(); return 0; }
        @Override public synchronized void destroy() { if (refuseStop) return; alive = false; notifyAll(); try { responseWriter.close(); } catch (IOException ignored) { } }
        @Override public Process destroyForcibly() { destroy(); return this; }
        @Override public boolean isAlive() { return alive; }
        @Override public java.util.stream.Stream<ProcessHandle> descendants() { return java.util.stream.Stream.empty(); }
    }

    @Test void reusesProcessButNeverPreviousRequestOrResult() throws Exception {
        AtomicInteger starts = new AtomicInteger();
        var pool = new ImportAgentReviewPool(new AgentProperties(), json) {
            @Override protected Process startProcess() throws IOException { starts.incrementAndGet(); return new EchoProcess(); }
        };
        try {
            assertEquals("first", pool.review(json.createObjectNode().put("hash", "first")).path("hash").asText());
            assertEquals("second", pool.review(json.createObjectNode().put("hash", "second")).path("hash").asText());
            assertEquals(1, starts.get());
        } finally { pool.close(); }
    }

    @Test void rejectsWrongIdentityWithoutReplay() {
        AtomicInteger calls = new AtomicInteger();
        var pool = new ImportAgentReviewPool(new AgentProperties(), json) {
            @Override protected Process startProcess() throws IOException {
                calls.incrementAndGet(); var process = new EchoProcess(); process.wrongIdentity = true; return process;
            }
        };
        try {
            assertThrows(RuntimeException.class, () -> pool.review(json.createObjectNode()));
            assertEquals(1, calls.get());
        } finally { pool.close(); }
    }

    @Test void startupTimeoutAndClosePreventFurtherRequests() {
        AgentProperties props = new AgentProperties(); props.setStartupTimeout(Duration.ofMillis(50));
        var pool = new ImportAgentReviewPool(props, json) {
            @Override protected Process startProcess() throws IOException {
                var process = new EchoProcess();
                process.response.readNBytes(process.response.available());
                return process;
            }
        };
        assertThrows(RuntimeException.class, () -> pool.review(json.createObjectNode()));
        pool.close();
        assertThrows(RuntimeException.class, () -> pool.review(json.createObjectNode()));
    }

    @Test void boundsTwoProcessesEvenWhenStartupIsBlocked() throws Exception {
        AgentProperties props = new AgentProperties(); props.setStartupTimeout(Duration.ofMillis(200));
        CountDownLatch started = new CountDownLatch(2);
        AtomicInteger processes = new AtomicInteger();
        var pool = new ImportAgentReviewPool(props, json) {
            @Override protected Process startProcess() throws IOException {
                processes.incrementAndGet(); var process = new EchoProcess();
                process.response.readNBytes(process.response.available()); started.countDown(); return process;
            }
        };
        try (var executor = Executors.newVirtualThreadPerTaskExecutor()) {
            executor.submit(() -> assertThrows(RuntimeException.class, () -> pool.review(json.createObjectNode())));
            executor.submit(() -> assertThrows(RuntimeException.class, () -> pool.review(json.createObjectNode())));
            assertTrue(started.await(2, TimeUnit.SECONDS));
            assertThrows(RuntimeException.class, () -> pool.review(json.createObjectNode()));
            assertEquals(2, processes.get());
        } finally { pool.close(); }
    }

    @Test void unconfirmedStopRetainsBothSlots() throws Exception {
        AgentProperties props = new AgentProperties(); props.setStartupTimeout(Duration.ofMillis(30));
        var processes = new java.util.ArrayList<EchoProcess>();
        var pool = new ImportAgentReviewPool(props, json) {
            @Override protected Process startProcess() throws IOException {
                var process = new EchoProcess(); process.refuseStop = true;
                process.response.readNBytes(process.response.available()); processes.add(process); return process;
            }
        };
        try {
            assertThrows(RuntimeException.class, () -> pool.review(json.createObjectNode()));
            assertThrows(RuntimeException.class, () -> pool.review(json.createObjectNode()));
            assertThrows(RuntimeException.class, () -> pool.review(json.createObjectNode()));
            assertEquals(2, processes.size());
            assertTrue(processes.stream().allMatch(Process::isAlive));
        } finally {
            processes.forEach(p -> { p.refuseStop = false; p.destroy(); });
            pool.close();
        }
    }

    @Test void reloadRetiresOldConfigurationAndNewRequestUsesNewProcess() throws Exception {
        var processes = new java.util.ArrayList<EchoProcess>();
        var pool = new ImportAgentReviewPool(new AgentProperties(), json) {
            @Override protected Process startProcess() throws IOException {
                var process = new EchoProcess(); synchronized (processes) { processes.add(process); } return process;
            }
        };
        try {
            pool.review(json.createObjectNode().put("version", "old"));
            pool.reload(new ModelConfigChangedEvent());
            assertTimeoutPreemptively(Duration.ofSeconds(2), () -> {
                while (processes.getFirst().isAlive()) Thread.sleep(5);
            });
            var result = pool.review(json.createObjectNode().put("version", "new"));
            assertEquals("new", result.path("version").asText());
            assertFalse(processes.getFirst().isAlive());
        } finally { pool.close(); }
    }
}
