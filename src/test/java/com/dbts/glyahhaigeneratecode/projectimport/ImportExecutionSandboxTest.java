package com.dbts.glyahhaigeneratecode.projectimport;

import com.dbts.glyahhaigeneratecode.config.RuntimeProperties;
import com.dbts.glyahhaigeneratecode.runtime.DockerCli;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import java.io.*;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.*;
import java.util.function.Function;
import static org.junit.jupiter.api.Assertions.*;

class ImportExecutionSandboxTest {
    @TempDir Path dir;
    static final String ID="a".repeat(64);
    static final ImportExecutionSandbox.Limits LIMITS=new ImportExecutionSandbox.Limits(0.5,128L<<20,32,32L<<20,8L<<20,Duration.ofSeconds(20),4096,ImportExecutionSandbox.Memory.REQUIRE_HARD);

    /** 按子命令应答的假引擎；记录全部调用。 */
    static final class Fake implements ImportExecutionSandbox.Engine {
        final List<List<String>> calls=new ArrayList<>();
        final Map<String,Function<List<String>,Result>> answers=new HashMap<>();
        Process attached;
        boolean allowPin=true;
        @Override public Result run(List<String> args,int timeout){calls.add(args);var answer=answers.get(args.get(0));return answer==null?new Result(true,0,"",""):answer.apply(args);}
        @Override public Process attach(List<String> args){calls.add(args);return attached;}
        @Override public String path(Path host){return "/mnt/x/"+host.getFileName();}
        @Override public boolean pinHardSwap(String containerId){calls.add(List.of("pin",containerId));return allowPin&&containerId!=null&&containerId.matches("[0-9a-f]{64}");}
    }
    static Fake engine(String swapLimit,String swapTotal){
        var fake=new Fake();
        fake.answers.put("info",args->new ImportExecutionSandbox.Engine.Result(true,0,"true|true|true|"+swapLimit+"\n",""));
        fake.answers.put("run",args->new ImportExecutionSandbox.Engine.Result(true,0,swapTotal+"\n",""));
        return fake;
    }

    @Test void runtimeInspectionDistinguishesRunningExitedMissingAndUnavailable(){
        var fake=engine("true","0");var sandbox=new ImportExecutionSandbox(fake,"img");
        fake.answers.put("inspect",args->new ImportExecutionSandbox.Engine.Result(true,0,"true", ""));
        assertEquals("RUNNING",sandbox.executionState(ID));
        fake.answers.put("inspect",args->new ImportExecutionSandbox.Engine.Result(true,0,"false", ""));
        assertEquals("FAILED",sandbox.executionState(ID));
        fake.answers.put("inspect",args->new ImportExecutionSandbox.Engine.Result(true,0,"unexpected", ""));
        assertEquals("UNKNOWN",sandbox.executionState(ID));
        fake.answers.put("inspect",args->new ImportExecutionSandbox.Engine.Result(false,1,"", "No such container"));
        assertEquals("FAILED",sandbox.executionState(ID));
        fake.answers.put("inspect",args->new ImportExecutionSandbox.Engine.Result(false,1,"", "engine unavailable"));
        assertEquals("UNKNOWN",sandbox.executionState(ID));
        assertEquals("UNKNOWN",sandbox.executionState(null));
        assertTrue(fake.calls.stream().noneMatch(call->List.of("kill","rm","start").contains(call.getFirst())));
    }

    @Test void supervisorKeepsLateOutputAndStopsOnOutputLimit()throws Exception{
        var fake=running(new byte[0]);
        var pipe=new PipedInputStream(16384);var writer=new PipedOutputStream(pipe);
        fake.attached=new FakeProcess(new byte[0]){
            @Override public InputStream getInputStream(){return pipe;}
            @Override public InputStream getErrorStream(){return new ByteArrayInputStream((ImportExecutionSandbox.OK_MARKER+" test\n").getBytes());}
        };
        fake.answers.put("inspect",args->args.contains("{{.State.Running}}")?
            new ImportExecutionSandbox.Engine.Result(true,0,"true",""):args.contains("{{.Id}}")?
            new ImportExecutionSandbox.Engine.Result(false,1,"","No such container"):inspect(args));
        fake.answers.put("rm",args->{fake.attached.destroy();try{writer.close();}catch(IOException ignored){}return new ImportExecutionSandbox.Engine.Result(true,0,"","");});
        var finished=new java.util.concurrent.CompletableFuture<ImportExecutionSandbox.Outcome>();
        var log=dir.resolve("late.log");
        var result=new ImportExecutionSandbox(fake,"img").execute(new ImportExecutionSandbox.Request(UUID.randomUUID(),7,dir.resolve("copy"),".","serve",LIMITS,
            ImportExecutionSandbox.Network.NONE,ImportExecutionSandbox.Lifetime.SERVE,false),dir.resolve("g.sh"),dir.resolve("e.env"),log,
            new ImportExecutionSandbox.Journal(){public void created(String id){}public void started(String at){}public void finished(ImportExecutionSandbox.Outcome outcome){finished.complete(outcome);}},()->false);
        try{
            assertEquals("RUNNING",result.state());assertTrue(fake.attached.isAlive());
            writer.write("late-after-ready\n".getBytes());writer.flush();
            long deadline=System.nanoTime()+Duration.ofSeconds(3).toNanos();
            while(!Files.readString(log).contains("late-after-ready")&&System.nanoTime()<deadline)Thread.sleep(20);
            assertTrue(Files.readString(log).contains("late-after-ready"));
            writer.write(new byte[8192]);writer.flush();
            var ended=finished.get(12,java.util.concurrent.TimeUnit.SECONDS);
            assertEquals("FAILED",ended.state());assertEquals("OUTPUT_LIMIT",ended.reason());
            assertFalse(fake.attached.isAlive());assertTrue(Files.size(log)<=LIMITS.outputBytes());
        }finally{writer.close();fake.attached.destroy();}
    }

    @Test void outputWriteFailureIsNotSilentlyIgnored()throws Exception{
        var method=ImportExecutionSandbox.class.getDeclaredMethod("reader",InputStream.class,OutputStream.class,long.class,
            java.util.concurrent.atomic.AtomicLong.class,java.util.concurrent.atomic.AtomicBoolean.class,StringBuffer.class,java.util.concurrent.atomic.AtomicBoolean.class);
        method.setAccessible(true);
        var failed=new java.util.concurrent.atomic.AtomicBoolean();var exceeded=new java.util.concurrent.atomic.AtomicBoolean();
        var broken=new OutputStream(){@Override public void write(int value)throws IOException{throw new IOException("fixture write failure");}};
        var thread=(Thread)method.invoke(null,new ByteArrayInputStream("event".getBytes()),broken,4096L,new java.util.concurrent.atomic.AtomicLong(),exceeded,null,failed);
        thread.join(3000);
        assertFalse(thread.isAlive());assertTrue(failed.get());assertFalse(exceeded.get());
    }

    @Test void memoryWithoutSwapControlIsRefusedNotFaked(){
        var fake=engine("false","4194304");
        assertEquals(List.of("MEMORY_LIMIT_UNENFORCEABLE"),new ImportExecutionSandbox(fake,"img").preflight(LIMITS));
        // 1. 探测容器本身也无网络、只读、非特权，不挂载工程。
        var probe=fake.calls.stream().filter(call->call.get(0).equals("run")).findFirst().orElseThrow();
        assertTrue(probe.containsAll(List.of("--network","none","--read-only","--cap-drop","ALL")));assertFalse(String.join(" ",probe).contains("-v"));
        assertEquals(List.of(),new ImportExecutionSandbox(engine("false","0"),"img").preflight(LIMITS));
        assertEquals(List.of(),new ImportExecutionSandbox(engine("true","4194304"),"img").preflight(LIMITS));
        var verification=new ImportExecutionSandbox.Limits(0.5,128L<<20,32,32L<<20,8L<<20,Duration.ofSeconds(20),4096,ImportExecutionSandbox.Memory.VERIFICATION_ONLY_UNGUARANTEED);
        assertEquals(List.of(),new ImportExecutionSandbox(engine("false","4194304"),"img").preflight(verification));
    }
    @Test void hardSwapPinLetsPreflightPassAndUnpinRefusesBeforeGo()throws IOException{
        var ready=engine("false","4194304");
        ready.answers.put("create",args->new ImportExecutionSandbox.Engine.Result(true,0,ID+"\n",""));
        ready.answers.put("start",args->new ImportExecutionSandbox.Engine.Result(true,0,ID,""));
        assertEquals(List.of(),new ImportExecutionSandbox(ready,"img").preflight(LIMITS));
        assertTrue(ready.calls.stream().anyMatch(call->call.get(0).equals("pin")&&ID.equals(call.get(1))));
        assertTrue(ready.calls.stream().anyMatch(call->call.equals(List.of("rm","-f",ID))));
        var blocked=running(new byte[0]);blocked.allowPin=false;
        var outcome=new ImportExecutionSandbox(blocked,"img").execute(new ImportExecutionSandbox.Request(UUID.randomUUID(),7,dir.resolve("copy"),".","echo",LIMITS),
            dir.resolve("g.sh"),dir.resolve("e.env"),dir.resolve("o.log"),journal(new ArrayList<>()),()->false);
        assertEquals("REFUSED",outcome.state());assertEquals("MEMORY_LIMIT_UNENFORCEABLE",outcome.reason());
        assertTrue(blocked.calls.stream().noneMatch(call->call.get(0).equals("attach")));
    }
    @Test void liveHardMemoryRunsSmallCommandAndKillsOversize()throws Exception{
        Assumptions.assumeTrue(Boolean.getBoolean("import.exec.pin")||"1".equals(System.getenv("IMPORT_EXEC_PIN")),"未要求真实钉交换区核对");
        var docker=new DockerCli(new RuntimeProperties());
        Assumptions.assumeTrue(docker.commandPrefix()!=null,"docker 不可用");
        var sandbox=new ImportExecutionSandbox(ImportExecutionSandbox.docker(docker),"glyahh-sandbox-runtime:24.04");
        assertEquals(List.of(),sandbox.preflight(ImportExecutionSandbox.Limits.defaults()));
        Files.createDirectories(dir.resolve("copy"));Files.writeString(dir.resolve("copy").resolve("ok.txt"),"ok");
        var small=sandbox.execute(new ImportExecutionSandbox.Request(UUID.randomUUID(),7,dir.resolve("copy"),".","echo live-ok",ImportExecutionSandbox.Limits.defaults()),
            dir.resolve("g.sh"),dir.resolve("e.env"),dir.resolve("small.log"),journal(new ArrayList<>()),()->false);
        assertEquals("SUCCEEDED",small.state(),small.reason());
        assertTrue(Files.readString(dir.resolve("small.log")).contains("live-ok"));
        var tight=new ImportExecutionSandbox.Limits(0.5,64L<<20,32,32L<<20,8L<<20,Duration.ofSeconds(30),4096,ImportExecutionSandbox.Memory.REQUIRE_HARD);
        var over=sandbox.execute(new ImportExecutionSandbox.Request(UUID.randomUUID(),7,dir.resolve("copy"),".","python3 -c 'buf=bytearray(180*1024*1024); print(len(buf))'",tight),
            dir.resolve("g2.sh"),dir.resolve("e2.env"),dir.resolve("over.log"),journal(new ArrayList<>()),()->false);
        assertEquals("FAILED",over.state());assertEquals("MEMORY_LIMIT",over.reason());
    }
    @Test void livePersistKeepsInstallFilesAndServeStaysUntilRemoved()throws Exception{
        Assumptions.assumeTrue(Boolean.getBoolean("import.exec.pin")||"1".equals(System.getenv("IMPORT_EXEC_PIN")),"未要求真实钉交换区核对");
        var docker=new DockerCli(new RuntimeProperties());
        Assumptions.assumeTrue(docker.commandPrefix()!=null,"docker 不可用");
        var sandbox=new ImportExecutionSandbox(ImportExecutionSandbox.docker(docker),"glyahh-sandbox-runtime:24.04");
        assertEquals(List.of(),sandbox.preflight(ImportExecutionSandbox.Limits.defaults()));
        Path original=dir.resolve("original");Path copy=dir.resolve("copy");
        Files.createDirectories(original);Files.writeString(original.resolve("check.sh"),"exit 1\n");
        Files.createDirectories(copy);Files.writeString(copy.resolve("check.sh"),"exit 1\n");
        var limits=ImportExecutionSandbox.Limits.defaults();
        var broken=sandbox.execute(new ImportExecutionSandbox.Request(UUID.randomUUID(),7,copy,".","sh check.sh",limits,ImportExecutionSandbox.Network.EGRESS,ImportExecutionSandbox.Lifetime.FINISH,true),
            dir.resolve("g1.sh"),dir.resolve("e1.env"),dir.resolve("broken.log"),journal(new ArrayList<>()),()->false);
        assertEquals("FAILED",broken.state(),broken.reason());
        assertEquals("exit 1\n",Files.readString(original.resolve("check.sh")));
        assertTrue(Files.exists(copy.resolve("check.sh")));
        Files.writeString(copy.resolve("check.sh"),"printf kept > kept.txt\nls /sys/class/net > nets.txt\nexit 0\n");
        var fixed=sandbox.execute(new ImportExecutionSandbox.Request(UUID.randomUUID(),7,copy,".","sh check.sh",limits,ImportExecutionSandbox.Network.EGRESS,ImportExecutionSandbox.Lifetime.FINISH,true),
            dir.resolve("g2.sh"),dir.resolve("e2.env"),dir.resolve("fixed.log"),journal(new ArrayList<>()),()->false);
        assertEquals("SUCCEEDED",fixed.state(),fixed.reason()+"\n"+Files.readString(dir.resolve("fixed.log")));
        assertEquals("kept",Files.readString(copy.resolve("kept.txt")).strip());
        assertTrue(Files.readString(copy.resolve("nets.txt")).contains("eth0"));
        assertFalse(Files.exists(original.resolve("kept.txt")));
        var exited=sandbox.execute(new ImportExecutionSandbox.Request(UUID.randomUUID(),7,copy,".","echo bye",limits,ImportExecutionSandbox.Network.NONE,ImportExecutionSandbox.Lifetime.SERVE,true),
            dir.resolve("g3.sh"),dir.resolve("e3.env"),dir.resolve("exit.log"),journal(new ArrayList<>()),()->false);
        assertEquals("FAILED",exited.state());assertEquals("NOT_READY",exited.reason());
        String servedId=null;
        try{
            var served=sandbox.execute(new ImportExecutionSandbox.Request(UUID.randomUUID(),7,copy,".","sleep 120",limits,ImportExecutionSandbox.Network.NONE,ImportExecutionSandbox.Lifetime.SERVE,true),
                dir.resolve("g4.sh"),dir.resolve("e4.env"),dir.resolve("serve.log"),journal(new ArrayList<>()),()->false);
            servedId=served.containerId();
            assertEquals("RUNNING",served.state(),served.reason()+"\n"+Files.readString(dir.resolve("serve.log")));
            assertNull(served.address());
            var live=ImportExecutionSandbox.docker(docker).run(List.of("inspect","--format","{{.State.Running}}|{{json .HostConfig.PortBindings}}",servedId),30);
            assertTrue(live.ok());assertTrue(live.stdout().startsWith("true|"));assertTrue(live.stdout().contains("{}")||live.stdout().contains("null"));
        }finally{if(servedId!=null)assertTrue(sandbox.removed(servedId));}
    }
    @Test void unavailableOrIncapableEngineFailsClosed(){
        var down=new Fake();down.answers.put("info",args->new ImportExecutionSandbox.Engine.Result(false,1,"","no daemon"));
        assertEquals(List.of("ENGINE_UNAVAILABLE"),new ImportExecutionSandbox(down,"img").preflight(LIMITS));
        var weak=new Fake();weak.answers.put("info",args->new ImportExecutionSandbox.Engine.Result(true,0,"false|false|false|true",""));
        assertEquals(List.of("MEMORY_LIMIT_UNSUPPORTED","PROCESS_LIMIT_UNSUPPORTED","CPU_LIMIT_UNSUPPORTED"),new ImportExecutionSandbox(weak,"img").preflight(LIMITS));
        var noImage=engine("true","0");noImage.answers.put("image",args->new ImportExecutionSandbox.Engine.Result(false,1,"","No such image"));
        assertEquals(List.of("IMAGE_MISSING"),new ImportExecutionSandbox(noImage,"img").preflight(LIMITS));
    }
    @Test void loopbackPublishBindsOnlyLocalhost(){
        var sandbox=new ImportExecutionSandbox(new Fake(),"img");
        var request=new ImportExecutionSandbox.Request(UUID.randomUUID(),7,dir.resolve("copy"),".","python3 app.py",LIMITS,ImportExecutionSandbox.Network.LOOPBACK,ImportExecutionSandbox.Lifetime.SERVE,true,8080);
        String text=String.join(" ",sandbox.createArgs(request,"n",dir.resolve("g.sh"),dir.resolve("e.env")));
        assertTrue(text.contains("--network bridge"));
        assertTrue(text.contains("-p 127.0.0.1::8080"));
        assertFalse(text.contains("0.0.0.0"));
        assertFalse(text.contains("--network host"));
        assertFalse(text.contains("--network none"));
        assertThrows(IllegalArgumentException.class,()->new ImportExecutionSandbox.Request(UUID.randomUUID(),7,dir.resolve("copy"),".","python3 app.py",LIMITS,ImportExecutionSandbox.Network.NONE,ImportExecutionSandbox.Lifetime.SERVE,true,8080));
    }
    @Test void liveLoopbackHttpIsReadyOnlyAfterARealRequest()throws Exception{
        Assumptions.assumeTrue(Boolean.getBoolean("import.exec.pin")||"1".equals(System.getenv("IMPORT_EXEC_PIN")),"未要求真实回环核对");
        var docker=new DockerCli(new RuntimeProperties());
        Assumptions.assumeTrue(docker.commandPrefix()!=null,"docker 不可用");
        var sandbox=new ImportExecutionSandbox(ImportExecutionSandbox.docker(docker),"glyahh-sandbox-runtime:24.04");
        Path copy=dir.resolve("copy");Files.createDirectories(copy);
        Files.writeString(copy.resolve("app.py"),"""
            from http.server import BaseHTTPRequestHandler, ThreadingHTTPServer
            class Handler(BaseHTTPRequestHandler):
                def do_GET(self):
                    body=b'{"ok":true}'
                    self.send_response(200); self.send_header('Content-Length', str(len(body))); self.end_headers(); self.wfile.write(body)
            ThreadingHTTPServer(('0.0.0.0', 8080), Handler).serve_forever()
            """);
        var limits=ImportExecutionSandbox.Limits.defaults();
        String id=null;
        try{
            var served=sandbox.execute(new ImportExecutionSandbox.Request(UUID.randomUUID(),7,copy,".","python3 app.py",limits,ImportExecutionSandbox.Network.LOOPBACK,ImportExecutionSandbox.Lifetime.SERVE,true,8080),
                dir.resolve("g.sh"),dir.resolve("e.env"),dir.resolve("serve.log"),journal(new ArrayList<>()),()->false);
            id=served.containerId();
            assertEquals("RUNNING",served.state(),served.reason());
            assertNotNull(served.address());
            assertTrue(served.address().startsWith("http://127.0.0.1:"));
            var response=java.net.http.HttpClient.newHttpClient().send(java.net.http.HttpRequest.newBuilder(java.net.URI.create(served.address())).GET().build(),java.net.http.HttpResponse.BodyHandlers.ofString());
            assertEquals(200,response.statusCode());
            assertTrue(response.body().contains("\"ok\":true"));
        }finally{if(id!=null)assertTrue(sandbox.removed(id));}
    }
    @Test void egressInstallPublishesNoPortsAndPersistsWork(){
        var sandbox=new ImportExecutionSandbox(new Fake(),"img");
        var request=new ImportExecutionSandbox.Request(UUID.randomUUID(),7,dir.resolve("copy"),".","npm ci",LIMITS,ImportExecutionSandbox.Network.EGRESS,ImportExecutionSandbox.Lifetime.FINISH,true);
        String text=String.join(" ",sandbox.createArgs(request, "n", dir.resolve("g.sh"), dir.resolve("e.env")));
        assertTrue(text.contains("--network glyahh-imp-egress"));
        assertTrue(text.contains(":/work:rw"));
        assertFalse(text.contains("--network none"));
        assertFalse(text.contains("-p "));
        assertFalse(text.contains("--network host"));
    }
    @Test void createArgumentsCarryEveryBoundaryAndNoHostAccess(){
        var sandbox=new ImportExecutionSandbox(new Fake(),"img");var id=UUID.randomUUID();
        var args=sandbox.createArgs(new ImportExecutionSandbox.Request(id,7,dir.resolve("copy"),".","python3 x.py",LIMITS),"n",dir.resolve("g.sh"),dir.resolve("e.env"));
        String text=String.join(" ",args);
        for(String part:List.of("--network none","--read-only","--user 65534:65534","--cap-drop ALL","--security-opt no-new-privileges","--memory 134217728","--memory-swap 134217728",
            "--cpu-period 100000","--cpu-quota 50000","--pids-limit 32","size=33554432","size=8388608","--log-driver none","/mnt/x/copy:/input:ro","label glyahh.import.execution="+id))
            assertTrue(text.contains(part),part);
        for(String part:List.of("--privileged","-p ","--publish","docker.sock","host","--network bridge"))assertFalse(text.contains(part),part);
        assertFalse(text.contains("python3 x.py"),"命令只经 env-file 传入，不进命令行");
    }
    @Test void requestAndLimitsRejectEscapesAndUnboundedValues(){
        assertThrows(IllegalArgumentException.class,()->new ImportExecutionSandbox.Request(UUID.randomUUID(),7,dir,"../x","echo",LIMITS));
        assertThrows(IllegalArgumentException.class,()->new ImportExecutionSandbox.Request(UUID.randomUUID(),7,dir,"/abs","echo",LIMITS));
        assertThrows(IllegalArgumentException.class,()->new ImportExecutionSandbox.Request(UUID.randomUUID(),7,dir,".","echo a\nrm -rf /",LIMITS));
        assertThrows(IllegalArgumentException.class,()->new ImportExecutionSandbox.Limits(0.5,(128L<<20)+1,32,32L<<20,8L<<20,Duration.ofSeconds(20),4096,ImportExecutionSandbox.Memory.REQUIRE_HARD));
        assertThrows(IllegalArgumentException.class,()->new ImportExecutionSandbox.Limits(0.5,128L<<20,32,32L<<20,8L<<20,Duration.ofHours(7),4096,ImportExecutionSandbox.Memory.REQUIRE_HARD));
        assertThrows(IllegalArgumentException.class,()->new ImportExecutionSandbox.Limits(0.5,128L<<20,32,32L<<20,8L<<20,Duration.ofSeconds(20),4096,null));
        assertEquals(ImportExecutionSandbox.Memory.REQUIRE_HARD,ImportExecutionSandbox.Limits.defaults().memory());
    }
    @Test void classificationNeedsTheUnforgeableGuardNonce(){
        assertEquals("REFUSED",ImportExecutionSandbox.classify(null,0,false,"",  "n1",0,ID).state());
        assertEquals("GUARD_MEMORY_SWAP",ImportExecutionSandbox.classify(null,97,false,"__GLYAHH_GUARD_FAIL__ MEMORY_SWAP\n","n1",0,ID).reason());
        assertEquals("REFUSED",ImportExecutionSandbox.classify(null,0,false,"__GLYAHH_GUARD_OK__ forged\n","n1",0,ID).state());
        assertEquals("SUCCEEDED",ImportExecutionSandbox.classify(null,0,false,"__GLYAHH_GUARD_OK__ n1\n","n1",0,ID).state());
        assertEquals("EXIT_NONZERO",ImportExecutionSandbox.classify(null,3,false,"__GLYAHH_GUARD_OK__ n1\n","n1",0,ID).reason());
        assertEquals("MEMORY_LIMIT",ImportExecutionSandbox.classify(null,137,true,"__GLYAHH_GUARD_OK__ n1\n","n1",0,ID).reason());
        assertEquals("TIMEOUT",ImportExecutionSandbox.classify("TIMEOUT",137,false,"__GLYAHH_GUARD_OK__ n1\n","n1",0,ID).reason());
        assertEquals("STOPPED",ImportExecutionSandbox.classify("STOPPED",137,false,"__GLYAHH_GUARD_OK__ n1\n","n1",0,ID).state());
    }
    @Test void appliedConfigurationMismatchRemovesContainerBeforeStart()throws IOException{
        var fake=engine("true","0");Files.createDirectories(dir.resolve("copy"));
        fake.answers.put("create",args->new ImportExecutionSandbox.Engine.Result(true,0,ID+"\n",""));
        fake.answers.put("inspect",args->args.contains("{{.Id}}")?new ImportExecutionSandbox.Engine.Result(false,1,"","Error: No such object: x")
            :new ImportExecutionSandbox.Engine.Result(true,0,"999|50000|100000|32|none|true|false|[\"ALL\"]|{}|[]|65534:65534|{}|0",""));
        var journal=new ArrayList<String>();
        var outcome=new ImportExecutionSandbox(fake,"img").execute(new ImportExecutionSandbox.Request(UUID.randomUUID(),7,dir.resolve("copy"),".","echo",LIMITS),
            dir.resolve("g.sh"),dir.resolve("e.env"),dir.resolve("o.log"),journal(journal),()->false);
        assertEquals("REFUSED",outcome.state());assertEquals("LIMIT_NOT_APPLIED_MEMORY",outcome.reason());
        assertEquals(List.of("created:"+ID),journal);
        assertTrue(fake.calls.stream().noneMatch(call->call.get(0).equals("start")));assertTrue(fake.calls.stream().anyMatch(call->call.equals(List.of("rm","-f",ID))));
    }
    @Test void outputBeyondCapKillsContainerAndKeepsOnlyCappedLog()throws IOException{
        var fake=running(new byte[100_000]);
        var outcome=new ImportExecutionSandbox(fake,"img").execute(new ImportExecutionSandbox.Request(UUID.randomUUID(),7,dir.resolve("copy"),".","yes",LIMITS),
            dir.resolve("g.sh"),dir.resolve("e.env"),dir.resolve("o.log"),journal(new ArrayList<>()),()->false);
        assertEquals("FAILED",outcome.state());assertEquals("OUTPUT_LIMIT",outcome.reason());
        assertEquals(4096,Files.size(dir.resolve("o.log")));assertEquals(4096,outcome.outputBytes());
        assertTrue(fake.calls.stream().anyMatch(call->call.equals(List.of("kill",ID))));
    }
    @Test void unconfirmedRemovalIsStopFailedNeverStopped()throws IOException{
        var fake=running(new byte[10]);
        fake.answers.put("inspect",args->args.contains("{{.Id}}")?new ImportExecutionSandbox.Engine.Result(true,0,ID,""):inspect(args));
        var outcome=new ImportExecutionSandbox(fake,"img").execute(new ImportExecutionSandbox.Request(UUID.randomUUID(),7,dir.resolve("copy"),".","sleep 9",LIMITS),
            dir.resolve("g.sh"),dir.resolve("e.env"),dir.resolve("o.log"),journal(new ArrayList<>()),()->true);
        assertEquals("STOP_FAILED",outcome.state());assertEquals("CONTAINER_NOT_CONFIRMED_GONE",outcome.reason());
    }

    @Test void unexpectedHostMountIsRefusedBeforeStart()throws IOException{
        var fake=running(new byte[0]);
        fake.answers.put("inspect",args->args.contains("{{.Id}}")?new ImportExecutionSandbox.Engine.Result(false,1,"","Error: No such object"):args.get(2).startsWith("{{.HostConfig.Memory}}")
            ?new ImportExecutionSandbox.Engine.Result(true,0,inspect(args).stdout().replace("\"]|65534","\",\"/var/run/docker.sock:/var/run/docker.sock\"]|65534"),""):inspect(args));
        var outcome=new ImportExecutionSandbox(fake,"img").execute(new ImportExecutionSandbox.Request(UUID.randomUUID(),7,dir.resolve("copy"),".","echo",LIMITS),
            dir.resolve("g.sh"),dir.resolve("e.env"),dir.resolve("o.log"),journal(new ArrayList<>()),()->false);
        assertEquals("LIMIT_NOT_APPLIED_MOUNTS",outcome.reason());assertTrue(fake.calls.stream().noneMatch(call->call.get(0).equals("start")));
    }
    @Test void lostAttachWhileContainerRunsIsKilledNotSucceeded()throws IOException{
        var fake=running(new byte[0]);fake.attached.destroy();
        var states=new java.util.ArrayDeque<>(List.of("true|0|false","false|137|false"));
        fake.answers.put("inspect",args->args.contains("{{.Id}}")?new ImportExecutionSandbox.Engine.Result(false,1,"","error: no such object"):args.get(2).startsWith("{{.State.Running}}")
            ?new ImportExecutionSandbox.Engine.Result(true,0,states.size()>1?states.poll():states.peek(),""):inspect(args));
        var outcome=new ImportExecutionSandbox(fake,"img").execute(new ImportExecutionSandbox.Request(UUID.randomUUID(),7,dir.resolve("copy"),".","sleep 9",LIMITS),
            dir.resolve("g.sh"),dir.resolve("e.env"),dir.resolve("o.log"),journal(new ArrayList<>()),()->false);
        assertEquals("FAILED",outcome.state());assertEquals("ATTACH_LOST",outcome.reason());assertEquals(137,outcome.exitCode());
        assertTrue(fake.calls.stream().anyMatch(call->call.equals(List.of("kill",ID))));
    }
    @Test void managedContainersAreReadWithoutQuotedFormat(){
        var fake=new Fake();String other="b".repeat(64);
        fake.answers.put("ps",args->new ImportExecutionSandbox.Engine.Result(true,0,ID+"|glyahh.import.app=7,glyahh.import.execution=e1\n"+other+"|glyahh.import.execution=probe\nbad|x\n",""));
        assertEquals(Map.of(ID,"e1",other,"probe"),new ImportExecutionSandbox(fake,"img").managed());
        assertTrue(fake.calls.getFirst().stream().noneMatch(arg->arg.contains("\"")));
        fake.answers.put("ps",args->new ImportExecutionSandbox.Engine.Result(false,1,"",""));
        assertNull(new ImportExecutionSandbox(fake,"img").managed());
    }

    private Fake running(byte[] stdout)throws IOException{
        var fake=engine("true","0");Files.createDirectories(dir.resolve("copy"));
        fake.answers.put("create",args->new ImportExecutionSandbox.Engine.Result(true,0,ID+"\n",""));
        fake.answers.put("inspect",args->args.contains("{{.Id}}")?new ImportExecutionSandbox.Engine.Result(false,1,"","Error: No such object"):inspect(args));
        fake.attached=new FakeProcess(stdout);
        fake.answers.put("kill",args->{fake.attached.destroy();return new ImportExecutionSandbox.Engine.Result(true,0,ID,"");});
        return fake;
    }
    private ImportExecutionSandbox.Engine.Result inspect(List<String> args){
        String format=args.get(2);
        if(format.startsWith("{{.HostConfig.Memory}}"))return new ImportExecutionSandbox.Engine.Result(true,0,"134217728|50000|100000|32|none|true|false|[\"ALL\"]|"
            +"{\"/tmp\":\"rw,nosuid,nodev,size=8388608,mode=1777\",\"/work\":\"rw,exec,nosuid,nodev,size=33554432,mode=1777\"}|[\"/mnt/x/g.sh:/glyahh/guard.sh:ro\",\"/mnt/x/copy:/input:ro\"]|65534:65534|{}|0","");
        if(format.contains("StartedAt"))return new ImportExecutionSandbox.Engine.Result(true,0,"2026-10-05T00:00:00Z","");
        return new ImportExecutionSandbox.Engine.Result(true,0,"false|137|false","");
    }
    private static ImportExecutionSandbox.Journal journal(List<String> events){
        return new ImportExecutionSandbox.Journal(){
            @Override public void created(String id){events.add("created:"+id);}
            @Override public void started(String at){events.add("started:"+at);}
        };
    }
    /** 输出一段字节后保持存活，直到被 kill（destroy）为止。 */
    static class FakeProcess extends Process {
        private final InputStream out;private volatile boolean alive=true;
        FakeProcess(byte[] stdout){out=new SequenceInputStream(new ByteArrayInputStream(stdout),new InputStream(){
            @Override public int read()throws IOException{while(alive){try{Thread.sleep(10);}catch(InterruptedException e){return -1;}}return -1;}});}
        @Override public OutputStream getOutputStream(){return OutputStream.nullOutputStream();}
        @Override public InputStream getInputStream(){return out;}
        @Override public InputStream getErrorStream(){return InputStream.nullInputStream();}
        @Override public int waitFor()throws InterruptedException{while(alive)Thread.sleep(10);return 137;}
        @Override public boolean waitFor(long timeout,java.util.concurrent.TimeUnit unit)throws InterruptedException{long end=System.nanoTime()+unit.toNanos(timeout);while(alive&&System.nanoTime()<end)Thread.sleep(5);return !alive;}
        @Override public int exitValue(){if(alive)throw new IllegalThreadStateException();return 137;}
        @Override public void destroy(){alive=false;}
        @Override public Process destroyForcibly(){alive=false;return this;}
        @Override public boolean isAlive(){return alive;}
    }
}
