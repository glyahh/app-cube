package com.dbts.glyahhaigeneratecode.projectimport;

import com.dbts.glyahhaigeneratecode.exception.ErrorCode;
import com.dbts.glyahhaigeneratecode.exception.MyException;
import com.dbts.glyahhaigeneratecode.service.support.ProjectImportStaging;
import java.io.ByteArrayOutputStream;
import java.io.FilterInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.time.Instant;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.BooleanSupplier;
import java.util.function.Consumer;

/** 仅供受管 Git/读取辅助命令；输出分离、有界，失败不回显秘密。 */
public final class ImportProcessRunner {
    private ImportProcessRunner() { }
    public record Resource(long pid,Instant started) { }
    public record Result<T>(T value,int exitCode,long stdoutBytes,long stderrBytes) { }
    @FunctionalInterface public interface StreamHandler<T> {T read(InputStream input) throws Exception;}
    public static String errorCode(Throwable error){return error instanceof ProcessFailure known?known.reason:"SOURCE_READ_FAILED";}

    public static <T> Result<T> run(List<String> command,Path directory,Map<String,String> environment,Duration timeout,
                                  long maxOutput,long maxError,BooleanSupplier eligible,Consumer<Resource> journal,StreamHandler<T> handler) {
        return run(command,directory,environment,timeout,maxOutput,maxError,eligible,journal,null,handler);
    }

    /** 私有配置通过有界 stdin 交付，调用者负责清除输入缓冲区。 */
    public static <T> Result<T> run(List<String> command,Path directory,Map<String,String> environment,Duration timeout,
                                  long maxOutput,long maxError,BooleanSupplier eligible,Consumer<Resource> journal,byte[] privateInput,StreamHandler<T> handler) {
        if(command==null || command.isEmpty() || environment==null || timeout==null || timeout.isNegative() || timeout.isZero()
                || maxOutput<0 || maxError<0 || maxError>65536 || (privateInput!=null && privateInput.length>16384)) throw failure("INVALID_PROCESS_LIMIT");
        if(!eligible.getAsBoolean()) throw failure("REVOKED_BEFORE_START");
        Context context=null; Thread errorReader=null; Thread inputWriter=null;
        var timer=Executors.newSingleThreadScheduledExecutor(runnable->{Thread thread=new Thread(runnable,"import-process-guard");thread.setDaemon(true);return thread;});
        var diagnostic=new ByteArrayOutputStream(); var errorBytes=new AtomicLong(); var outputBytes=new AtomicLong();
        try {
            // 1. 只接收服务端结构化参数；不继承模型密钥或宿主代理/凭据环境。
            ProjectImportStaging.guardManagedPath(directory.toAbsolutePath().normalize());
            if(!Files.isDirectory(directory)) throw failure("INVALID_PROCESS_DIRECTORY");
            ProcessBuilder builder=new ProcessBuilder(command).directory(directory.toFile());
            builder.environment().clear(); builder.environment().putAll(environment); builder.redirectErrorStream(false);
            Process process=builder.start(); context=new Context(process,journal); Context active=context;
            long started=System.nanoTime();
            // 根进程先登记，子进程发现后追加；持久记录失败时不继续读取。
            journal.accept(new Resource(process.pid(),process.info().startInstant().orElse(null)));
            timer.scheduleWithFixedDelay(()->{
                try{active.observeChildren();}
                catch(RuntimeException error){active.reject(error instanceof IllegalArgumentException?"RESOURCE_IDENTITY_UNCONFIRMED":"RESOURCE_JOURNAL_FAILED");return;}
                try {
                    if(System.nanoTime()-started>timeout.toNanos()) active.reject("TIMEOUT");
                    else if(!eligible.getAsBoolean())active.reject("REVOKED");
                } catch(RuntimeException error){active.reject("QUALIFICATION_FAILED");}
            },0,50,TimeUnit.MILLISECONDS);
            if(privateInput==null)process.getOutputStream().close();
            else {
                inputWriter=new Thread(()->{
                    try(var input=process.getOutputStream()) {
                        if(active.error.get()!=null || !eligible.getAsBoolean()){active.reject("REVOKED");return;}
                        input.write(privateInput);input.flush();
                    }catch(Exception error){if(active.error.get()==null)active.reject("STDIN_WRITE_FAILED");}
                },"import-process-stdin");
                inputWriter.setDaemon(true);inputWriter.start();
            }
            // 2. stderr 独立有界读取，不能与 blob 或 askpass 秘密协议合并。
            errorReader=new Thread(()->{
                try(var input=process.getErrorStream()) {
                    byte[] buffer=new byte[4096]; int count;
                    while((count=input.read(buffer))!=-1) {
                        long size=errorBytes.addAndGet(count);
                        if(size>maxError){active.reject("OUTPUT_LIMIT");return;}
                        diagnostic.write(buffer,0,count);
                    }
                } catch(IOException error){if(active.error.get()==null)active.reject("STDERR_READ_FAILED");}
            },"import-process-stderr");
            errorReader.setDaemon(true); errorReader.start();
            InputStream output=new FilterInputStream(process.getInputStream()) {
                @Override public int read() throws IOException {
                    int value=in.read(); if(value>=0)check(1); return value;
                }
                @Override public int read(byte[] buffer,int offset,int length) throws IOException {
                    int count=in.read(buffer,offset,length); if(count>0)check(count); return count;
                }
                private void check(int count) throws IOException {
                    if(outputBytes.addAndGet(count)>maxOutput){active.reject("OUTPUT_LIMIT");throw new IOException("bounded output");}
                    if(active.error.get()!=null)throw new IOException("process no longer eligible");
                }
            };
            // 3. 消费真实流，未读完或中途失败不视作命令完成。
            T value;
            try(output) {
                value=handler.read(output);
                if(output.read()!=-1){active.reject("UNCONSUMED_OUTPUT");throw failure("UNCONSUMED_OUTPUT");}
            }
            process.waitFor(); errorReader.join(5000);
            if(errorReader.isAlive())active.reject("STOP_FAILED");
            if(inputWriter!=null){inputWriter.join(5000);if(inputWriter.isAlive())active.reject("STOP_FAILED");}
            // 辅助进程可能比根进程稍晚结束；等待期间保留超时与取消门禁。
            long childDeadline=System.nanoTime()+TimeUnit.SECONDS.toNanos(5);
            while(active.error.get()==null && active.children.values().stream().anyMatch(child->child.handle().isAlive())
                    && System.nanoTime()<childDeadline)Thread.sleep(20);
            if(active.error.get()==null && active.children.values().stream().anyMatch(child->child.handle().isAlive()))active.reject("CHILD_STILL_RUNNING");
            if(active.error.get()!=null)throw failure(active.error.get());
            if(!eligible.getAsBoolean())throw failure("REVOKED");
            if(process.exitValue()!=0)throw failure(classify(diagnostic.toByteArray(),process.exitValue()));
            return new Result<>(value,process.exitValue(),outputBytes.get(),errorBytes.get());
        } catch(Exception error) {
            String code=context!=null && context.error.get()!=null?context.error.get():error instanceof ProcessFailure known?known.reason:"PROCESS_READ_FAILED";
            throw failure(code);
        } finally {
            timer.shutdownNow();
            boolean stopped=context==null || context.stop();
            try {
                if(errorReader!=null) {errorReader.join(5000);if(errorReader.isAlive())stopped=false;}
                if(inputWriter!=null) {inputWriter.join(5000);if(inputWriter.isAlive())stopped=false;}
                if(!timer.awaitTermination(5,TimeUnit.SECONDS))stopped=false;
            } catch(InterruptedException error){Thread.currentThread().interrupt();stopped=false;}
            byte[] bytes=diagnostic.toByteArray();Arrays.fill(bytes,(byte)0);diagnostic.reset();
            if(!stopped)throw failure("STOP_FAILED");
        }
    }

    private static String classify(byte[] diagnostic,int exit) {
        String message=new String(diagnostic,StandardCharsets.UTF_8);
        if(message.contains("SSL/TLS") || message.contains("certificate"))return "TLS_READ_FAILED";
        if(message.contains("Authentication failed") || message.contains("could not read Username"))return "AUTH_READ_FAILED";
        if(message.contains("repository not found"))return "SOURCE_NOT_ACCESSIBLE";
        return "PROCESS_EXIT_"+exit;
    }

    private static final class Context {
        private record Child(ProcessHandle handle,Instant started) { }
        final Process process;
        final AtomicReference<String> error=new AtomicReference<>();
        final Map<Long,Child> children=new ConcurrentHashMap<>();
        final Consumer<Resource> journal;
        Context(Process process,Consumer<Resource> journal){this.process=process;this.journal=journal;}
        synchronized void observeChildren() {
            if(process.isAlive())process.toHandle().descendants().forEach(handle->{
                Child child=new Child(handle,handle.info().startInstant().orElse(null));
                if(child.started()==null && !handle.isAlive())return;
                if(children.putIfAbsent(handle.pid(),child)==null)journal.accept(new Resource(handle.pid(),child.started()));
            });
        }
        void reject(String reason){error.compareAndSet(null,reason);stop();}
        synchronized boolean stop() {
            try {observeChildren();}catch(RuntimeException failure){error.compareAndSet(null,"RESOURCE_JOURNAL_FAILED");}
            if(process.isAlive())process.destroyForcibly();
            boolean owned=true;
            for(Child child:children.values()) {
                if(!child.handle().isAlive())continue;
                var actual=child.handle().info().startInstant();
                if(child.started()==null || actual.isEmpty() || !actual.get().equals(child.started())){owned=false;continue;}
                child.handle().destroyForcibly();
            }
            long deadline=System.nanoTime()+TimeUnit.SECONDS.toNanos(5);
            while(System.nanoTime()<deadline && (process.isAlive() || children.values().stream().anyMatch(child->child.handle().isAlive()))) {
                try{Thread.sleep(20);}catch(InterruptedException error){Thread.currentThread().interrupt();return false;}
            }
            return owned && !process.isAlive() && children.values().stream().noneMatch(child->child.handle().isAlive());
        }
    }
    private static final class ProcessFailure extends MyException {
        private final String reason;
        private ProcessFailure(String reason){super(ErrorCode.OPERATION_ERROR,"Git 读取失败："+reason+"。");this.reason=reason;}
    }
    private static MyException failure(String code){return new ProcessFailure(code);}
}
