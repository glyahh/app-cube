package com.dbts.glyahhaigeneratecode.projectimport;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.io.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.security.MessageDigest;
import java.time.*;
import java.util.*;
import java.util.concurrent.*;

/** 单次编辑专用原生进程；身份及停止结果由业务账本登记，不借用普通Worker。 */
final class ImportNativeWorker {
    private Process process;
    private BufferedWriter input;
    private final ProcessBuilder builder;
    private boolean stopRequested;
    private final BlockingQueue<String> output=new ArrayBlockingQueue<>(256);
    private final UUID editId,leaseId;
    private final String repository;
    private volatile boolean eof;
    private volatile boolean malformed;
    private static final ObjectMapper JSON=new ObjectMapper();

    ImportNativeWorker(UUID editId,UUID leaseId)throws IOException{
        this.editId=editId;this.leaseId=leaseId;
        Path root=Path.of(System.getProperty("user.dir")).toAbsolutePath().normalize();
        repository=linuxPath(root);
        String hash;
        try{hash=HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(Files.readAllBytes(root.resolve("agent/uv.lock"))));}
        catch(java.security.NoSuchAlgorithmException error){throw new IllegalStateException(error);}
        String python="/var/lib/glyahh/agent-runtimes/item1-"+hash+"/venv/bin/python";
        var command=prefix();command.addAll(List.of(python,"-u",repository+"/agent/native_import_worker.py",
                "--edit-id",editId.toString(),"--lease-id",leaseId.toString()));
        builder=new ProcessBuilder(command).directory(root.toFile());
        builder.redirectError(ProcessBuilder.Redirect.DISCARD);
    }

    synchronized void start()throws IOException{
        // 1. 对象先交给清理链路，再创建进程；取消后的停止墓碑禁止晚到启动。
        if(stopRequested||process!=null)throw new IOException("IMPORT_NATIVE_START_REVOKED");
        process=builder.start();input=new BufferedWriter(new OutputStreamWriter(process.getOutputStream(),StandardCharsets.UTF_8));
        Thread.ofVirtual().name("import-native-stdout-"+process.pid()).start(()->{
            try(var reader=new InputStreamReader(process.getInputStream(),StandardCharsets.UTF_8)){
                StringBuilder line=new StringBuilder();int value;
                while((value=reader.read())!=-1){
                    if(value=='\n'){output.put(line.toString());line.setLength(0);}
                    else if(value!='\r'){if(line.length()>=1<<20){malformed=true;break;}line.append((char)value);}
                }
                if(!line.isEmpty())malformed=true;
            }catch(IOException|InterruptedException error){malformed=true;}
            finally{eof=true;}
        });
    }

    private static ArrayList<String> prefix(){
        return new ArrayList<>(List.of("wsl.exe","-d",System.getenv().getOrDefault("AGENT_WSL_DISTRO","Ubuntu"),"-u","root","--exec"));
    }

    private static String linuxPath(Path path)throws IOException{
        String value=path.toString().replace('\\','/');
        if(value.length()<3||value.charAt(1)!=':'||!Character.isLetter(value.charAt(0)))throw new IOException("IMPORT_NATIVE_PLATFORM_UNAVAILABLE");
        return "/mnt/"+Character.toLowerCase(value.charAt(0))+value.substring(2);
    }

    JsonNode ready(Duration timeout)throws IOException,InterruptedException{
        String line=read(timeout);if(line==null)throw new IOException("IMPORT_NATIVE_READY_MISSING");
        JsonNode frame=JSON.readTree(line);
        if(!"worker.ready".equals(frame.path("event").asText())||!"POSTGRES".equals(frame.path("persistence").asText())
                ||!frame.path("importOnly").asBoolean()||!editId.toString().equals(frame.path("editId").asText())
                ||!leaseId.toString().equals(frame.path("leaseId").asText())
                ||!frame.path("nativePid").isTextual()||!frame.path("nativePid").asText().matches("[1-9][0-9]{0,18}")
                ||!frame.path("nativeStartTicks").isTextual()||!frame.path("nativeStartTicks").asText().matches("[1-9][0-9]{0,19}"))
            throw new IOException("IMPORT_NATIVE_READY_INVALID");
        return frame;
    }

    String read(Duration timeout)throws IOException,InterruptedException{
        long deadline=System.nanoTime()+timeout.toNanos();
        do{
            String line=output.poll(Math.min(250,Math.max(1,TimeUnit.NANOSECONDS.toMillis(deadline-System.nanoTime()))),TimeUnit.MILLISECONDS);
            if(malformed)throw new IOException("IMPORT_NATIVE_PROTOCOL_INVALID");
            if(line!=null)return line;
            if(eof)return null;
        }while(System.nanoTime()<deadline);
        throw new IOException("IMPORT_NATIVE_EVENT_TIMEOUT");
    }

    void send(String frame)throws IOException{input.write(frame);input.newLine();input.flush();}
    long pid(){return process.pid();}
    Instant started(){return process.info().startInstant().orElseThrow();}

    synchronized void stop()throws IOException,InterruptedException{
        // 1. 先关闭宿主入口，再核对平台登记的原生PID/启动标识；不按名称清理。
        stopRequested=true;
        if(input!=null)try{input.close();}catch(IOException ignored){ }
        if(process!=null&&!process.waitFor(2,TimeUnit.SECONDS)){
            var descendants=process.descendants().toList();
            descendants.forEach(ProcessHandle::destroyForcibly);process.destroyForcibly();
            if(!process.waitFor(3,TimeUnit.SECONDS)||descendants.stream().anyMatch(ProcessHandle::isAlive))
                throw new IOException("IMPORT_NATIVE_LAUNCHER_STOP_UNCONFIRMED");
        }
        stopRegistered(editId,leaseId,repository);
    }

    static void stopUnstarted(UUID edit,UUID lease)throws IOException,InterruptedException{
        stopRegistered(edit,lease,linuxPath(Path.of(System.getProperty("user.dir")).toAbsolutePath().normalize()));
    }

    static void recover(ImportAgentEditLedger.Row row,Map<String,Object> worker)throws IOException,InterruptedException{
        UUID lease=(UUID)worker.get("lease_id");
        stopRegistered(row.identity().id(),lease,linuxPath(Path.of(System.getProperty("user.dir")).toAbsolutePath().normalize()));
        if(worker.get("windows_pid")==null||worker.get("windows_start")==null)
            throw new IOException("IMPORT_NATIVE_LAUNCHER_IDENTITY_UNCONFIRMED");
        long pid=((Number)worker.get("windows_pid")).longValue();
        Instant started=((java.sql.Timestamp)worker.get("windows_start")).toInstant();
        var found=ProcessHandle.of(pid);
        if(found.isEmpty()||!found.get().isAlive())return;
        var process=found.get();var actual=process.info().startInstant();
        if(actual.isEmpty())throw new IOException("IMPORT_NATIVE_LAUNCHER_IDENTITY_UNCONFIRMED");
        if(!actual.get().truncatedTo(java.time.temporal.ChronoUnit.MICROS).equals(started))return;
        var descendants=process.descendants().toList();
        descendants.forEach(ProcessHandle::destroyForcibly);process.destroyForcibly();
        long deadline=System.nanoTime()+TimeUnit.SECONDS.toNanos(5);
        while((process.isAlive()||descendants.stream().anyMatch(ProcessHandle::isAlive))&&System.nanoTime()<deadline)Thread.sleep(50);
        if(process.isAlive()||descendants.stream().anyMatch(ProcessHandle::isAlive))throw new IOException("IMPORT_NATIVE_LAUNCHER_STOP_UNCONFIRMED");
    }

    static void stopRegistered(UUID edit,UUID lease,String repo)throws IOException,InterruptedException{
        var command=prefix();command.addAll(List.of("python3",repo+"/scripts/import_worker_control.py","--edit-id",edit.toString(),"--lease-id",lease.toString()));
        Process helper=new ProcessBuilder(command).redirectError(ProcessBuilder.Redirect.DISCARD).start();
        if(!helper.waitFor(15,TimeUnit.SECONDS)){helper.destroyForcibly();throw new IOException("IMPORT_NATIVE_STOP_UNCONFIRMED");}
        byte[] body=helper.getInputStream().readNBytes(8193);
        if(helper.exitValue()!=0||body.length>8192)throw new IOException("IMPORT_NATIVE_STOP_UNCONFIRMED");
        JsonNode proof=JSON.readTree(body);
        if(!proof.path("nativeStopped").asBoolean()||!edit.toString().equals(proof.path("editId").asText())
                ||!lease.toString().equals(proof.path("leaseId").asText()))throw new IOException("IMPORT_NATIVE_STOP_UNCONFIRMED");
    }
}
