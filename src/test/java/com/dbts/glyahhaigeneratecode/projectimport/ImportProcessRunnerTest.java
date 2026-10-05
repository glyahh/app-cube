package com.dbts.glyahhaigeneratecode.projectimport;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicReference;
import static org.junit.jupiter.api.Assertions.*;

class ImportProcessRunnerTest {
    @TempDir Path directory;
    List<String> python(String code) {
        Path executable=Path.of("agent", ".venv",System.getProperty("os.name").toLowerCase().contains("win")?"Scripts/python.exe":"bin/python").toAbsolutePath();
        assertTrue(Files.isRegularFile(executable),"测试使用已有 Python，不自动安装。");
        return List.of(executable.toString(),"-c",code);
    }
    @Test void separateBoundedOutputAndStreamHandlerProduceActualResult() throws Exception {
        var result=ImportProcessRunner.run(python("import sys;sys.stdout.buffer.write(b'abc');sys.stderr.write('diagnostic')"),directory,Map.of(),Duration.ofSeconds(5),32,32,()->true,resource->{},input->new String(input.readAllBytes(),java.nio.charset.StandardCharsets.UTF_8));
        assertEquals("abc",result.value()); assertEquals(0,result.exitCode()); assertEquals(10,result.stderrBytes());
    }
    @Test void privateInputUsesPipeWithoutArgumentsAndProducesOnlyDigest() throws Exception {
        byte[] secret="FAKE_PRIVATE_PIPE_ONLY".getBytes(java.nio.charset.StandardCharsets.UTF_8);
        var command=python("import sys,hashlib;print(hashlib.sha256(sys.stdin.buffer.read()).hexdigest())");
        var result=ImportProcessRunner.run(command,directory,Map.of(),Duration.ofSeconds(5),128,32,()->true,r->{},secret,input->new String(input.readAllBytes(),java.nio.charset.StandardCharsets.US_ASCII).strip());
        assertEquals(java.util.HexFormat.of().formatHex(java.security.MessageDigest.getInstance("SHA-256").digest(secret)),result.value());
        assertFalse(command.toString().contains("FAKE_PRIVATE_PIPE_ONLY"));
    }
    @Test void boundedStdinAndBlockedWriterCannotBypassTimeout() {
        assertThrows(RuntimeException.class,()->ImportProcessRunner.run(python("print('unexpected')"),directory,Map.of(),Duration.ofSeconds(5),32,32,()->true,r->{},new byte[16385],input->input.readAllBytes()));
        var resources=new java.util.concurrent.CopyOnWriteArrayList<ImportProcessRunner.Resource>();
        var error=assertThrows(RuntimeException.class,()->ImportProcessRunner.run(python("import time;time.sleep(60)"),directory,Map.of(),Duration.ofMillis(500),32,32,()->true,resources::add,new byte[16384],input->input.readAllBytes()));
        assertEquals("TIMEOUT",ImportProcessRunner.errorCode(error));
        resources.forEach(resource->assertFalse(ProcessHandle.of(resource.pid()).map(ProcessHandle::isAlive).orElse(false)));
    }
    @Test void revokedBeforeStartDoesNotCreateProcess() {
        var resource=new AtomicReference<ImportProcessRunner.Resource>();
        assertThrows(RuntimeException.class,()->ImportProcessRunner.run(python("print('unexpected')"),directory,Map.of(),Duration.ofSeconds(5),32,32,()->false,resource::set,input->input.readAllBytes()));
        assertNull(resource.get());
    }
    @Test void timeoutStopsActualProcessAndDoesNotReturnPartialSuccess() {
        var resource=new AtomicReference<ImportProcessRunner.Resource>();
        var error=assertThrows(RuntimeException.class,()->ImportProcessRunner.run(python("import time;print('partial',flush=True);time.sleep(60)"),directory,Map.of(),Duration.ofMillis(400),32,32,()->true,resource::set,input->input.readAllBytes()));
        assertTrue(error.getMessage().contains("TIMEOUT"));
        assertNotNull(resource.get()); assertFalse(ProcessHandle.of(resource.get().pid()).map(ProcessHandle::isAlive).orElse(false));
    }
    @Test void outputOverflowStopsActualProducerAndNeverTruncatesAsSuccess() {
        var resource=new AtomicReference<ImportProcessRunner.Resource>();
        var error=assertThrows(RuntimeException.class,()->ImportProcessRunner.run(python("import sys,time;sys.stdout.write('x'*10000);sys.stdout.flush();time.sleep(60)"),directory,Map.of(),Duration.ofSeconds(5),32,32,()->true,resource::set,input->input.readAllBytes()));
        assertTrue(error.getMessage().contains("OUTPUT_LIMIT"));
        assertFalse(ProcessHandle.of(resource.get().pid()).map(ProcessHandle::isAlive).orElse(false));
    }
    @Test void journalFailureStillStopsOwnedProcessAndStderrSecretsAreNotInException() {
        var resource=new AtomicReference<ImportProcessRunner.Resource>();
        assertThrows(RuntimeException.class,()->ImportProcessRunner.run(python("import time;time.sleep(60)"),directory,Map.of(),Duration.ofSeconds(5),32,32,()->true,r->{resource.set(r);throw new IllegalStateException("journal failed");},input->input.readAllBytes()));
        assertFalse(ProcessHandle.of(resource.get().pid()).map(ProcessHandle::isAlive).orElse(false));
        var error=assertThrows(RuntimeException.class,()->ImportProcessRunner.run(python("import sys;sys.stderr.write('FAKE_SECRET_ONLY');sys.exit(2)"),directory,Map.of(),Duration.ofSeconds(5),32,32,()->true,r->{},input->input.readAllBytes()));
        assertFalse(error.getMessage().contains("FAKE_SECRET_ONLY"));
    }
    @Test void observedChildIsJournaledAndActuallyStoppedWithParent() {
        var resources=new java.util.concurrent.CopyOnWriteArrayList<ImportProcessRunner.Resource>();
        var error=assertThrows(RuntimeException.class,()->ImportProcessRunner.run(python("import subprocess,sys,time;subprocess.Popen([sys.executable,'-c','import time;time.sleep(60)']);time.sleep(60)"),directory,Map.of(),Duration.ofSeconds(2),32,32,()->true,resources::add,input->input.readAllBytes()));
        assertTrue(error.getMessage().contains("TIMEOUT"));
        assertTrue(resources.size()>=2,"实际子进程必须登记，不只记录根 PID。");
        resources.forEach(resource->assertFalse(ProcessHandle.of(resource.pid()).map(ProcessHandle::isAlive).orElse(false)));
    }
    @Test void parentSuccessWaitsForObservedChildNaturalExit() {
        var resources=new java.util.concurrent.CopyOnWriteArrayList<ImportProcessRunner.Resource>();
        var result=ImportProcessRunner.run(python("import subprocess,sys,time;subprocess.Popen([sys.executable,'-c','import time;time.sleep(0.8)'],stdout=subprocess.DEVNULL,stderr=subprocess.DEVNULL);time.sleep(0.3);print('done')"),directory,Map.of(),Duration.ofSeconds(5),32,32,()->true,resources::add,input->input.readAllBytes());
        assertEquals(0,result.exitCode());
        assertTrue(resources.size()>=2,"必须验证实际观察到的子进程。");
        resources.forEach(resource->assertFalse(ProcessHandle.of(resource.pid()).map(ProcessHandle::isAlive).orElse(false)));
    }
    @Test void parentSuccessDoesNotHideChildTimeout() {
        var resources=new java.util.concurrent.CopyOnWriteArrayList<ImportProcessRunner.Resource>();
        var error=assertThrows(RuntimeException.class,()->ImportProcessRunner.run(python("import subprocess,sys,time;subprocess.Popen([sys.executable,'-c','import time;time.sleep(60)'],stdout=subprocess.DEVNULL,stderr=subprocess.DEVNULL);time.sleep(0.3)"),directory,Map.of(),Duration.ofSeconds(2),32,32,()->true,resources::add,input->input.readAllBytes()));
        assertEquals("TIMEOUT",ImportProcessRunner.errorCode(error));
        assertTrue(resources.size()>=2);
        resources.forEach(resource->assertFalse(ProcessHandle.of(resource.pid()).map(ProcessHandle::isAlive).orElse(false)));
    }
    @Test void stderrOverflowAndPartiallyConsumedStdoutCannotSucceed() {
        var error=assertThrows(RuntimeException.class,()->ImportProcessRunner.run(python("import sys,time;sys.stderr.write('x'*10000);sys.stderr.flush();time.sleep(60)"),directory,Map.of(),Duration.ofSeconds(5),32,32,()->true,r->{},input->input.readAllBytes()));
        assertTrue(error.getMessage().contains("OUTPUT_LIMIT"));
        error=assertThrows(RuntimeException.class,()->ImportProcessRunner.run(python("print('unconsumed')"),directory,Map.of(),Duration.ofSeconds(5),32,32,()->true,r->{},input->null));
        assertTrue(error.getMessage().contains("UNCONSUMED_OUTPUT"));
    }
    @Test void revocationAfterStartStopsProcessWithoutReturningOutput() {
        var started=new java.util.concurrent.atomic.AtomicBoolean();
        var resource=new AtomicReference<ImportProcessRunner.Resource>();
        var error=assertThrows(RuntimeException.class,()->ImportProcessRunner.run(python("import time;time.sleep(60)"),directory,Map.of(),Duration.ofSeconds(5),32,32,()->!started.get(),r->{resource.set(r);started.set(true);},input->input.readAllBytes()));
        assertTrue(error.getMessage().contains("REVOKED"));
        assertFalse(ProcessHandle.of(resource.get().pid()).map(ProcessHandle::isAlive).orElse(false));
    }
}
