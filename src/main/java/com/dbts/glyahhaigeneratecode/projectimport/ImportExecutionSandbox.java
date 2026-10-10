package com.dbts.glyahhaigeneratecode.projectimport;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.time.Duration;
import java.util.*;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.BooleanSupplier;
import java.util.regex.Pattern;

/**
 * 导入工程容器出口：使用独立副本和计划指定的网络，执行前校验内存、CPU 与进程限额。
 * 服务就绪后继续有界日志和退出监督；正式入口使用已核对的固定容量 ext4 持久卷。
 */
public final class ImportExecutionSandbox {
    /** 内存保证方式：生产固定要求硬上限；仅受控验证可在已知不保证时继续，用于核对其他边界。 */
    public enum Memory {REQUIRE_HARD,VERIFICATION_ONLY_UNGUARANTEED}
    public record Limits(double cpus,long memoryBytes,int processes,long diskBytes,long tmpBytes,Duration wall,long outputBytes,Memory memory){
        public Limits{
            if(!(cpus>=0.1&&cpus<=16)||memoryBytes%4096!=0||memoryBytes<16L<<20||memoryBytes>16L<<30||processes<8||processes>1024||diskBytes<1L<<20||diskBytes>8L<<30
                ||tmpBytes<1L<<20||tmpBytes>1L<<30||wall==null||wall.compareTo(Duration.ofSeconds(1))<0||wall.compareTo(Duration.ofHours(6))>0
                ||outputBytes<1024||outputBytes>64L<<20||memory==null)throw new IllegalArgumentException("执行限额无效。");
        }
        public static Limits defaults(){return new Limits(1,1L<<30,128,1L<<30,64L<<20,Duration.ofMinutes(15),4L<<20,Memory.REQUIRE_HARD);}
        long cpuQuota(){return Math.round(cpus*100_000);}
    }
    /** 扫描保持无网络；已同意的安装和构建只出站；写明端口的服务只发布到宿主回环。 */
    public enum Network {NONE,EGRESS,LOOPBACK}
    /** 安装和构建等到进程结束；服务启动后保持运行，退出码 0 不算就绪。 */
    public enum Lifetime {FINISH,SERVE}
    public record Request(UUID id,long appId,Path copy,String directory,String command,Limits limits,Network network,Lifetime lifetime,boolean persist,Integer port,String healthPath,String mountedCopy){
        public Request(UUID id,long appId,Path copy,String directory,String command,Limits limits,Network network,Lifetime lifetime,boolean persist,Integer port,String healthPath){
            this(id,appId,copy,directory,command,limits,network,lifetime,persist,port,healthPath,null);
        }
        public Request(UUID id,long appId,Path copy,String directory,String command,Limits limits,Network network,Lifetime lifetime,boolean persist,Integer port){
            this(id,appId,copy,directory,command,limits,network,lifetime,persist,port,"/");
        }
        public Request(UUID id,long appId,Path copy,String directory,String command,Limits limits){this(id,appId,copy,directory,command,limits,Network.NONE,Lifetime.FINISH,false,null);}
        public Request(UUID id,long appId,Path copy,String directory,String command,Limits limits,Network network,Lifetime lifetime,boolean persist){
            this(id,appId,copy,directory,command,limits,network,lifetime,persist,null);
        }
        public Request{
            if(mountedCopy!=null&&(!persist||!mountedCopy.equals("/var/lib/glyahh/import-disks/"+appId+"/work")))throw new IllegalArgumentException("受限卷路径无效。");
            healthPath=ImportHttpReadiness.path(healthPath==null?"/":healthPath);
            if(network==null)network=Network.NONE;
            if(lifetime==null)lifetime=Lifetime.FINISH;
            if(id==null||appId<=0||copy==null||limits==null||directory==null||!DIRECTORY.matcher(directory).matches()||Arrays.asList(directory.split("/")).contains("..")
                ||command==null||command.isBlank()||command.length()>4096||command.chars().anyMatch(c->c==0||c=='\n'||c=='\r'))throw new IllegalArgumentException("执行请求无效。");
            if(network==Network.LOOPBACK&&(port==null||port<1||port>65535))throw new IllegalArgumentException("回环端口无效。");
            if(network!=Network.LOOPBACK&&port!=null)throw new IllegalArgumentException("未声明回环发布时不能带端口。");
        }
    }
    /** state：SUCCEEDED/FAILED/REFUSED/STOPPED/STOP_FAILED/RUNNING；reason 为稳定代码。RUNNING 表示容器仍在。 */
    public record Outcome(String state,String reason,Integer exitCode,long outputBytes,String containerId,String address){
        public Outcome(String state,String reason,Integer exitCode,long outputBytes,String containerId){this(state,reason,exitCode,outputBytes,containerId,null);}
    }
    /** 容器引擎调用；生产实现为 DockerCli（含 WSL 入口），测试可替换。 */
    public interface Engine {
        record Result(boolean ok,int exitCode,String stdout,String stderr){ }
        Result run(List<String> args,int timeoutSeconds);
        Process attach(List<String> args) throws IOException;
        String path(Path host);
        default Result platform(List<String> args,int timeoutSeconds){return new Result(false,-1,"","PLATFORM_UNAVAILABLE");}
        /** 容器已启动后，把该容器的 memory.swap.max 写成 0。读回不是 0 就返回 false。 */
        default boolean pinHardSwap(String containerId){return false;}
        /** 持久副本要让容器内非 root 用户能写入。默认不做任何事。 */
        default void prepareCopy(Path copy){ }
    }
    /** 容器身份一出现就交给账本；登记失败视为不可继续。 */
    public interface Journal {
        void created(String containerId);
        void started(String startedAt);
        default void finished(Outcome outcome){ }
    }

    public static final String LABEL="glyahh.import.execution";
    public static final String PREFIX="glyahh-imp-exec-";
    static final String OK_MARKER="__GLYAHH_GUARD_OK__",FAIL_MARKER="__GLYAHH_GUARD_FAIL__";
    private static final Pattern DIRECTORY=Pattern.compile("^(\\.|[A-Za-z0-9._@+-]+(/[A-Za-z0-9._@+-]+)*)$");
    private static final Pattern CONTAINER=Pattern.compile("^[0-9a-f]{64}$");
    /** 容器内守卫：先核对内核实际生效的限额与网络，任何一项不符直接退出，工程命令不运行。 */
    static final String GUARD="""
        set -u
        n="$GLYAHH_NONCE"; unset GLYAHH_NONCE
        fail(){ echo "__GLYAHH_GUARD_FAIL__ $1" >&2; exit 97; }
        read -r go || fail START
        [ "$go" = go ] || fail START
        [ "$(cat /sys/fs/cgroup/pids.max)" = "$GLYAHH_PIDS" ] || fail PIDS
        [ "$(cat /sys/fs/cgroup/cpu.max)" = "$GLYAHH_CPU 100000" ] || fail CPU
        [ "$(cat /sys/fs/cgroup/memory.max)" = "$GLYAHH_MEMORY" ] || fail MEMORY
        if [ "$GLYAHH_HARD_MEMORY" = 1 ]; then
          s=$(cat /sys/fs/cgroup/memory.swap.max 2>/dev/null || echo none)
          [ "$s" = 0 ] || fail MEMORY_SWAP
        fi
        if [ "$GLYAHH_NETWORK" = egress ] || [ "$GLYAHH_NETWORK" = loopback ]; then
          [ -d /sys/class/net/eth0 ] && [ -d /sys/class/net/lo ] || fail NETWORK
          [ "$(ls /sys/class/net | wc -l | tr -d ' ')" = 2 ] || fail NETWORK
        else
          [ "$(ls /sys/class/net)" = lo ] || fail NETWORK
        fi
        touch /.glyahh-rootfs-probe 2>/dev/null && fail ROOTFS_WRITABLE
        touch /input/.glyahh-input-probe 2>/dev/null && fail INPUT_WRITABLE
        if [ "${GLYAHH_PERSIST:-0}" = 1 ]; then
          [ -d /work ] || fail DIRECTORY
          [ "${GLYAHH_DISK_HARD:-0}" != 1 ] || [ "$(df -k /work | awk 'NR==2{print $2}')" -le "$GLYAHH_DISK_KB" ] || fail DISK
        else
          [ "$(df -k /work | awk 'NR==2{print $2}')" -le "$GLYAHH_DISK_KB" ] || fail DISK
          cp -R /input/. /work/ || fail COPY
        fi
        cd "/work/$GLYAHH_DIR" || fail DIRECTORY
        c="$GLYAHH_COMMAND"; w="$GLYAHH_WALL"; life="$GLYAHH_LIFETIME"
        unset GLYAHH_COMMAND GLYAHH_WALL GLYAHH_DIR GLYAHH_PIDS GLYAHH_CPU GLYAHH_MEMORY GLYAHH_HARD_MEMORY GLYAHH_DISK_KB GLYAHH_DISK_HARD GLYAHH_PERSIST GLYAHH_NETWORK GLYAHH_LIFETIME
        echo "__GLYAHH_GUARD_OK__ $n" >&2
        # 服务不套超时：就绪后保持运行，直到外部停止。安装和构建仍等到进程结束。
        if [ "$life" = serve ]; then exec sh -c "$c" </dev/null; fi
        exec timeout -s KILL "$w" sh -c "$c" </dev/null
        """;

    /**
     * 生产引擎：沿用 DockerCli 探测到的 docker/WSL 入口。WSL 的 "--" 会经默认 shell 解释参数（$、| 等被改写），
     * 这里改用 --exec 原样传参；输出按 1MiB 截断，只用于短命令。
     */
    public static Engine docker(com.dbts.glyahhaigeneratecode.runtime.DockerCli docker){
        return new Engine(){
            private List<String> command(List<String> args)throws IOException{
                var prefix=docker.commandPrefix();if(prefix==null)throw new IOException("docker unavailable");
                var command=new ArrayList<>(prefix);int split=command.indexOf("--");
                if(split>0&&command.getFirst().toLowerCase(Locale.ROOT).startsWith("wsl"))command.set(split,"--exec");
                command.addAll(args);return command;
            }
            @Override public Result run(List<String> args,int timeoutSeconds){
                try(var span=ImportInstallDiagnostics.start("DOCKER_CLI","DOCKER_"+args.getFirst().toUpperCase(Locale.ROOT))){
                    var command=ImportInstallDiagnostics.call("DOCKER_ENTRYPOINT",()->command(args));
                    var result=spawn(command,timeoutSeconds);
                    span.result(result.ok()?"SUCCEEDED":"FAILED",result.ok()?null:"DOCKER_COMMAND_FAILED",result.exitCode());
                    return result;
                }
                catch(IOException error){return new Result(false,-1,"",error.getMessage());}
            }
            @Override public Result platform(List<String> args,int timeoutSeconds){
                try{
                    var prefix=docker.commandPrefix();
                    if(prefix==null||prefix.isEmpty()||!prefix.getFirst().toLowerCase(Locale.ROOT).startsWith("wsl"))return new Result(false,-1,"","LINUX_PLATFORM_ROOT_REQUIRED");
                    int at=prefix.indexOf("-d");
                    if(at<0||at+1>=prefix.size()||!prefix.get(at+1).matches("[A-Za-z0-9._-]+"))return new Result(false,-1,"","WSL_DISTRO_UNKNOWN");
                    var command=new ArrayList<>(List.of(prefix.getFirst(),"-d",prefix.get(at+1),"-u","root","--exec"));
                    command.addAll(args);
                    try(var span=ImportInstallDiagnostics.start("PLATFORM_CLI")){
                        var result=spawn(command,timeoutSeconds);
                        span.result(result.ok()?"SUCCEEDED":"FAILED",result.ok()?null:"PLATFORM_COMMAND_FAILED",result.exitCode());
                        return result;
                    }
                }catch(IOException error){return new Result(false,-1,"","PLATFORM_COMMAND_FAILED");}
            }
            @Override public void prepareCopy(Path copy){
                String mounted=path(copy);
                if(mounted==null||mounted.isBlank())return;
                List<String> prefix=docker.commandPrefix();
                if(prefix==null||prefix.isEmpty()||!prefix.getFirst().toLowerCase(Locale.ROOT).startsWith("wsl"))return;
                int distroAt=prefix.indexOf("-d");
                if(distroAt<0||distroAt+1>=prefix.size())return;
                String distro=prefix.get(distroAt+1);
                if(!distro.matches("[A-Za-z0-9._-]+")||!mounted.startsWith("/mnt/"))return;
                spawnQuiet(List.of(prefix.getFirst(),"-d",distro,"--exec","chmod","-R","a+rwX",mounted));
            }
            private void spawnQuiet(List<String> command){
                try{spawn(command,30);}catch(IOException ignored){ }
            }
            @Override public boolean pinHardSwap(String containerId){
                if(containerId==null||!CONTAINER.matcher(containerId).matches())return false;
                String pid="";
                for(int attempt=0;attempt<20;attempt++){
                    var inspected=ImportInstallDiagnostics.call("SWAP_PID_QUERY",()->run(List.of("inspect","--format","{{.State.Pid}}",containerId),30));
                    String value=inspected.ok()?inspected.stdout().strip():"";
                    if(value.matches("[1-9][0-9]*")){pid=value;break;}
                    try{Thread.sleep(100);}catch(InterruptedException error){Thread.currentThread().interrupt();return false;}
                }
                if(pid.isEmpty())return false;
                // 只把已校验的数字 pid 拼进命令，容器 id 不进入 shell。
                var written=root("cg=$(cut -d: -f3 /proc/"+pid+"/cgroup); echo 0 > \"/sys/fs/cgroup${cg}/memory.swap.max\"; cat \"/sys/fs/cgroup${cg}/memory.swap.max\"");
                return written.ok()&&"0".equals(written.stdout().strip());
            }
            private Result root(String script){
                try{
                    var prefix=docker.commandPrefix();
                    if(prefix==null||prefix.isEmpty()||!prefix.getFirst().toLowerCase(Locale.ROOT).startsWith("wsl"))return new Result(false,-1,"","swap pin needs wsl root");
                    int distroAt=prefix.indexOf("-d");
                    if(distroAt<0||distroAt+1>=prefix.size())return new Result(false,-1,"","swap pin needs wsl distro");
                    String distro=prefix.get(distroAt+1);
                    if(!distro.matches("[A-Za-z0-9._-]+"))return new Result(false,-1,"","swap pin distro invalid");
                    try(var span=ImportInstallDiagnostics.start("SWAP_WRITE_READ")){
                        var result=spawn(List.of(prefix.getFirst(),"-d",distro,"-u","root","--exec","sh","-c",script),30);
                        span.result(result.ok()?"SUCCEEDED":"FAILED",result.ok()?null:"SWAP_WRITE_FAILED",result.exitCode());
                        return result;
                    }
                }catch(IOException error){return new Result(false,-1,"",error.getMessage());}
            }
            private Result spawn(List<String> command,int timeoutSeconds)throws IOException{
                Process process=null;
                try{
                    process=ImportInstallDiagnostics.call("CLI_PROCESS_START",()->new ProcessBuilder(command).start());process.getOutputStream().close();
                    var out=drain(process.getInputStream());var err=drain(process.getErrorStream());
                    boolean finished;
                    try(var span=ImportInstallDiagnostics.start("CLI_PROCESS_WAIT")){
                        finished=process.waitFor(timeoutSeconds,java.util.concurrent.TimeUnit.SECONDS);
                        span.result(finished?"SUCCEEDED":"FAILED",finished?null:"TIMEOUT",finished?process.exitValue():124);
                    }
                    if(!finished){process.destroyForcibly();return new Result(false,124,"","timeout");}
                    try(var span=ImportInstallDiagnostics.start("CLI_OUTPUT_DRAIN")){
                        out.join(5000);err.join(5000);
                        span.result(out.isAlive()||err.isAlive()?"INCOMPLETE":"SUCCEEDED",out.isAlive()||err.isAlive()?"DRAIN_PENDING":null,null);
                    }
                    return new Result(process.exitValue()==0,process.exitValue(),out.text(),err.text());
                }catch(InterruptedException error){Thread.currentThread().interrupt();return new Result(false,-1,"","interrupted");}
                finally{if(process!=null&&process.isAlive())process.destroyForcibly();}
            }
            @Override public Process attach(List<String> args)throws IOException{return new ProcessBuilder(command(args)).start();}
            @Override public String path(Path host){return docker.toEnginePath(host);}
        };
    }
    /** 并发读取子进程输出，最多保留 1MiB，其余丢弃以免管道写满。 */
    private static final class Drain extends Thread {
        private final InputStream input;private volatile byte[] bytes=new byte[0];
        Drain(InputStream input){this.input=input;setDaemon(true);}
        @Override public void run(){
            try(input){bytes=input.readNBytes(1<<20);input.transferTo(OutputStream.nullOutputStream());}catch(IOException ignored){/* 进程结束 */}
        }
        String text(){return new String(bytes,StandardCharsets.UTF_8);}
    }
    private static Drain drain(InputStream input){var drain=new Drain(input);drain.start();return drain;}

    private final Engine engine;
    private final String image;
    public ImportExecutionSandbox(Engine engine,String image){
        if(engine==null||image==null||image.isBlank())throw new IllegalArgumentException("执行环境无效。");
        this.engine=engine;this.image=image;
    }

    /** 返回无法保证的边界；为空才允许执行。不缓存：引擎或宿主状态可能变化。 */
    public List<String> preflight(Limits limits){
        var refused=new ArrayList<String>();
        ImportInstallControl.check();
        var info=engine.run(List.of("info","--format","{{.MemoryLimit}}|{{.PidsLimit}}|{{.CPUCfsQuota}}|{{.SwapLimit}}"),30);
        if(!info.ok()){refused.add("ENGINE_UNAVAILABLE");return refused;}
        String[] flags=info.stdout().strip().split("\\|");
        if(flags.length!=4){refused.add("ENGINE_CAPABILITY_UNKNOWN");return refused;}
        if(!"true".equals(flags[0]))refused.add("MEMORY_LIMIT_UNSUPPORTED");
        if(!"true".equals(flags[1]))refused.add("PROCESS_LIMIT_UNSUPPORTED");
        if(!"true".equals(flags[2]))refused.add("CPU_LIMIT_UNSUPPORTED");
        if(!engine.run(List.of("image","inspect","--format","{{.Id}}",image),30).ok()){refused.add("IMAGE_MISSING");return refused;}
        // 1. 宿主没有 swap 不是硬限额；引擎不支持时，必须实际验证容器交换区可限制为 0。
        if(limits.memory()==Memory.REQUIRE_HARD&&!"true".equals(flags[3])){
            String failure=hardSwapPinFailure();
            if(failure!=null)refused.add(failure);
        }
        return refused;
    }

    /** 用一只不挂工程的探针容器确认：启动后能把 memory.swap.max 写成 0。用完即删。 */
    private String hardSwapPinFailure(){
        // 每次探针独占名称，不能用固定名称删除另一个请求正在验证的容器。
        ImportInstallControl.check();
        String name=PREFIX+"pinprobe-"+UUID.randomUUID();
        ImportInstallControl.probe(name,null);
        String job=ImportInstallControl.job();
        var arguments=new ArrayList<>(List.of("create","--name",name,"--network","none","--read-only","--user","65534:65534","--cap-drop","ALL",
            "--security-opt","no-new-privileges","--pids-limit","8","--memory","32m","--memory-swap","32m","--log-driver","none",
            "--label",LABEL+"=pinprobe"));
        if(job!=null)arguments.addAll(List.of("--label","glyahh.import.install-job="+job));
        arguments.addAll(List.of("--entrypoint","sleep",image,"20"));
        var created=engine.run(arguments,60);
        String id=created.ok()?created.stdout().strip():"";
        boolean pinned=false;
        try{
            if(CONTAINER.matcher(id).matches()){
                ImportInstallControl.probe(name,id);ImportInstallControl.check();
                pinned=engine.run(List.of("start",id),30).ok()&&engine.pinHardSwap(id);
            }
        }finally{
            if(!removed(CONTAINER.matcher(id).matches()?id:name)){if(job==null)return "MEMORY_PROBE_STOP_UNCONFIRMED";throw new IllegalStateException("MEMORY_PROBE_STOP_UNCONFIRMED");}
        }
        ImportInstallControl.check();
        return pinned?null:"MEMORY_LIMIT_UNENFORCEABLE";
    }

    /** 建容器、核对实际配置、附着运行；wall/输出/停止任一触发即强制结束，最后以容器不存在为停止依据。 */
    public Outcome execute(Request request,Path guard,Path environment,Path output,Journal journal,BooleanSupplier stopRequested){
        ImportInstallControl.check();
        var limits=request.limits();String name=PREFIX+request.id();
        if(request.network()==Network.EGRESS)ensureEgress();
        if(request.persist()&&request.mountedCopy()==null)engine.prepareCopy(request.copy());
        String nonce=UUID.randomUUID().toString().replace("-","");
        try{
            writePrivate(guard,GUARD.getBytes(StandardCharsets.UTF_8));
            writePrivate(environment,env(request,nonce).getBytes(StandardCharsets.UTF_8));
        }catch(IOException error){return new Outcome("FAILED","EXECUTION_FILES_FAILED",null,0,null);}
        var created=ImportInstallDiagnostics.call("CREATE_CONTAINER",()->engine.run(createArgs(request,name,guard,environment),120));
        String id=created.ok()?created.stdout().strip():"";
        if(!CONTAINER.matcher(id).matches()){
            // 创建结果不明时按名称清理并核实，避免留下未登记容器。
            return removed(name)?new Outcome("FAILED","CONTAINER_CREATE_FAILED",null,0,null):new Outcome("STOP_FAILED","CONTAINER_CREATE_UNCONFIRMED",null,0,null);
        }
        try{journal.created(id);}catch(RuntimeException error){
            return removed(id)?new Outcome("FAILED","LEDGER_UNAVAILABLE",null,0,id):new Outcome("STOP_FAILED","CONTAINER_NOT_CONFIRMED_GONE",null,0,id);
        }
        // 1. 启动前核对引擎实际记录的配置；与请求不符说明限额没有生效，直接删除不运行。
        String mismatch=ImportInstallDiagnostics.call("VERIFY",()->verify(id,request,guard,environment));
        if(mismatch!=null)return removed(id)?new Outcome("REFUSED","LIMIT_NOT_APPLIED_"+mismatch,null,0,id):new Outcome("STOP_FAILED","CONTAINER_NOT_CONFIRMED_GONE",null,0,id);
        var counted=new AtomicLong();var exceeded=new AtomicBoolean();var logFailed=new AtomicBoolean();var markers=new StringBuffer();
        String killed=null;Process attach=null;Thread[] readers=new Thread[0];boolean serving=false;
        ImportInstallDiagnostics.Span commandTiming=null;
        try(DeferredLog log=new DeferredLog(output,Files.newOutputStream(output,StandardOpenOption.CREATE_NEW,StandardOpenOption.WRITE,LinkOption.NOFOLLOW_LINKS),limits.outputBytes(),exceeded)){
            // 先启动再附着：守卫读到 go 才继续，附着前不会有输出丢失；sig-proxy 关闭，附着进程退出不会把信号转给容器。
            if(!ImportInstallDiagnostics.call("START_CONTAINER",()->engine.run(List.of("start",id),60)).ok())killed="START_FAILED";
            else if(limits.memory()==Memory.REQUIRE_HARD&&!engine.pinHardSwap(id)){
                // 守卫要看到交换区为 0 才放行。钉不住就删除容器，不发送 go，工程命令不运行。
                return removed(id)?new Outcome("REFUSED","MEMORY_LIMIT_UNENFORCEABLE",null,0,id):new Outcome("STOP_FAILED","CONTAINER_NOT_CONFIRMED_GONE",null,0,id);
            }
            else try{attach=engine.attach(List.of("attach","--sig-proxy=false",id));}
            catch(IOException error){killed="ATTACH_FAILED";}
            if(attach!=null){
                commandTiming=ImportInstallDiagnostics.start(ImportInstallDiagnostics.commandStage(request.command()));
                try(var stdin=attach.getOutputStream()){stdin.write("go\n".getBytes(StandardCharsets.US_ASCII));}
                readers=new Thread[]{reader(attach.getInputStream(),log.stream(),limits.outputBytes(),counted,exceeded,null,logFailed),
                    reader(attach.getErrorStream(),log.stream(),limits.outputBytes(),counted,exceeded,markers,logFailed)};
                // 登记引擎记录的真实启动时间（容器身份的一部分），最多等 10 秒。
                String startedAt="UNKNOWN";
                for(int attempt=0;attempt<100;attempt++){
                    var started=engine.run(List.of("inspect","--format","{{.State.StartedAt}}",id),30);
                    String value=started.ok()?started.stdout().strip():"";
                    if(!value.isEmpty()&&!value.startsWith("0001-")){startedAt=value;break;}
                    if(!attach.isAlive())break;
                    attach.waitFor(100,java.util.concurrent.TimeUnit.MILLISECONDS);
                }
                try{journal.started(startedAt);}catch(RuntimeException error){killed="LEDGER_UNAVAILABLE";}
                if(killed==null&&request.lifetime()==Lifetime.SERVE)serving=awaitServing(id,attach,markers);
                long deadline=System.nanoTime()+limits.wall().toNanos();
                // 2. 轮询退出、输出上限、耗时与停止请求；任一触发都用 kill 结束整个容器。
                while(killed==null&&!serving&&attach.isAlive()){
                    if(exceeded.get())killed="OUTPUT_LIMIT";
                    else if(logFailed.get())killed="EXECUTION_IO_FAILED";
                    else if(System.nanoTime()>=deadline)killed="TIMEOUT";
                    else if(stopRequested.getAsBoolean())killed="STOPPED";
                    else attach.waitFor(100,java.util.concurrent.TimeUnit.MILLISECONDS);
                }
                if(killed==null&&exceeded.get())killed="OUTPUT_LIMIT";
                if(killed==null&&logFailed.get())killed="EXECUTION_IO_FAILED";
                commandTiming.stopClock();
            }
            if(killed!=null)serving=false;
            if(serving){
                // 就绪只结束等待，不结束日志采集；监督线程接管附着进程和日志的关闭责任。
                log.kept=true;
                supervise(id,attach,readers,log,limits,counted,exceeded,logFailed,journal,stopRequested);
            }
            else if(killed!=null)engine.run(List.of("kill",id),30);
            if(!serving||killed!=null){
                if(attach!=null&&!attach.waitFor(15,java.util.concurrent.TimeUnit.SECONDS))attach.destroyForcibly();
                for(var thread:readers)thread.join(5000);
            }
        }catch(IOException error){
            killed=killed==null?"EXECUTION_IO_FAILED":killed;engine.run(List.of("kill",id),30);
        }catch(InterruptedException error){
            Thread.currentThread().interrupt();killed="INTERRUPTED";engine.run(List.of("kill",id),30);
        }finally{
            if(commandTiming!=null){commandTiming.stopClock();if(serving){commandTiming.result(killed==null?"RUNNING":"FAILED",killed,null);commandTiming.close();}}
            if((!serving||killed!=null)&&attach!=null&&attach.isAlive())attach.destroyForcibly();
        }
        if(serving){
            // 3. 服务容器保持运行。进程已经退出时不把退出码 0 记成就绪。写明端口时还要对本机地址请求成功。
            var live=engine.run(List.of("inspect","--format","{{.State.Running}}",id),30);
            long bytes=Math.min(counted.get(),limits.outputBytes());
            if(!(live.ok()&&"true".equals(live.stdout().strip())))
                return removed(id)?new Outcome("FAILED","NOT_READY",null,bytes,id):new Outcome("STOP_FAILED","CONTAINER_NOT_CONFIRMED_GONE",null,0,id);
            if(request.network()!=Network.LOOPBACK)return new Outcome("RUNNING",null,null,bytes,id);
            String address=loopbackAddress(id,request.port());
            if(address!=null&&ImportHttpReadiness.ready(address,request.healthPath(),Duration.ofSeconds(15)))return new Outcome("RUNNING",null,null,bytes,id,address);
            return removed(id)?new Outcome("FAILED","NOT_READY",null,bytes,id):new Outcome("STOP_FAILED","CONTAINER_NOT_CONFIRMED_GONE",null,0,id);
        }
        // 4. 安装和构建读取退出信息后删除容器，并以引擎查不到该容器作为资源已退出的依据。
        String format="{{.State.Running}}|{{.State.ExitCode}}|{{.State.OOMKilled}}";
        var state=engine.run(List.of("inspect","--format",format,id),30);
        String[] fields=state.ok()?state.stdout().strip().split("\\|"):new String[0];
        if(fields.length==3&&"true".equals(fields[0])){
            // 附着进程意外退出但容器仍在运行：不能当作正常结束，强制结束后重读退出信息。
            killed=killed==null?"ATTACH_LOST":killed;engine.run(List.of("kill",id),30);
            state=engine.run(List.of("inspect","--format",format,id),30);
            fields=state.ok()?state.stdout().strip().split("\\|"):new String[0];
        }
        Integer exit=fields.length==3&&fields[1].matches("-?\\d+")?Integer.valueOf(fields[1]):null;
        boolean oom=fields.length==3&&"true".equals(fields[2]);
        long bytes=Math.min(counted.get(),limits.outputBytes());
        var outcome=classify(killed,exit,oom,markers.toString(),nonce,bytes,id);
        if(commandTiming!=null){commandTiming.result(outcome.state(),outcome.reason(),exit);commandTiming.close();}
        if(!removed(id))return new Outcome("STOP_FAILED","CONTAINER_NOT_CONFIRMED_GONE",exit,bytes,id);
        try(var verified=ImportInstallDiagnostics.start("VERIFY","COMMAND_EXIT")){verified.result(outcome.state(),outcome.reason(),exit);}
        if(request.lifetime()==Lifetime.SERVE&&"SUCCEEDED".equals(outcome.state()))return new Outcome("FAILED","NOT_READY",exit,bytes,id);
        return outcome;
    }

    /** 运行期间继续收集有界日志；停止或超限必须核对容器消失后才结束登记。 */
    private void supervise(String id,Process attach,Thread[] readers,DeferredLog log,Limits limits,AtomicLong counted,AtomicBoolean exceeded,AtomicBoolean logFailed,Journal journal,BooleanSupplier stopRequested){
        var thread=new Thread(()->{
            String reason="SERVICE_EXITED";
            try{
                // 1. 就绪后继续监督；输出或日志失败时结束服务，不静默丢掉证据。
                while(attach.isAlive()){
                    if(exceeded.get()){reason="OUTPUT_LIMIT";break;}
                    if(logFailed.get()){reason="EXECUTION_IO_FAILED";break;}
                    if(stopRequested.getAsBoolean()){reason="STOP_REQUESTED";break;}
                    attach.waitFor(100,java.util.concurrent.TimeUnit.MILLISECONDS);
                }
                if(exceeded.get())reason="OUTPUT_LIMIT";
                else if("RUNNING".equals(executionState(id))&&"SERVICE_EXITED".equals(reason))reason="ATTACH_LOST";
            }catch(InterruptedException error){Thread.currentThread().interrupt();reason="INTERRUPTED";}
            finally{
                // 2. 结束进程、读取退出依据，再确认容器实际消失。
                if(attach.isAlive())engine.run(List.of("kill",id),30);
                var state=engine.run(List.of("inspect","--format","{{.State.Running}}|{{.State.ExitCode}}|{{.State.OOMKilled}}",id),30);
                String[] fields=state.ok()?state.stdout().strip().split("\\|"):new String[0];
                Integer exit=fields.length==3&&fields[1].matches("-?\\d+")?Integer.valueOf(fields[1]):null;
                if(fields.length==3&&"true".equals(fields[2])&&"SERVICE_EXITED".equals(reason))reason="MEMORY_LIMIT";
                boolean gone=removed(id);
                if(attach.isAlive())attach.destroyForcibly();
                for(var reader:readers)try{reader.join(5000);}catch(InterruptedException error){Thread.currentThread().interrupt();}
                try{log.finish();}catch(IOException error){reason="EXECUTION_IO_FAILED";}
                // 3. 只回写本次执行终态；业务层按冻结执行编号和版本保护迟到结果。
                var result=new Outcome(gone?("STOP_REQUESTED".equals(reason)?"STOPPED":"FAILED"):"STOP_FAILED",
                    gone?reason:"CONTAINER_NOT_CONFIRMED_GONE",exit,Math.min(counted.get(),limits.outputBytes()),id);
                try{journal.finished(result);}catch(RuntimeException error){
                    org.slf4j.LoggerFactory.getLogger(ImportExecutionSandbox.class).warn("导入服务终态保存失败 executionContainer={} error={}",id,error.getClass().getSimpleName());
                }
            }
        },"import-service-supervisor-"+id.substring(0,12));
        thread.setDaemon(true);thread.start();
    }

    private static final class DeferredLog extends OutputStream {
        private final OutputStream output;
        private final long cap;
        private final AtomicBoolean exceeded;
        private long written;
        private final List<OutputStream> streams = new ArrayList<>();
        private final OutputStream direct;
        volatile boolean kept;
        private boolean closed;
        DeferredLog(Path path,OutputStream output){this(path,output,64L<<20,new AtomicBoolean());}
        DeferredLog(Path path,OutputStream output,long cap,AtomicBoolean exceeded){
            this.output=output;this.cap=cap;this.exceeded=exceeded;this.direct=stream();
        }
        OutputStream stream(){
            var channel=ImportLogMask.lines(new OutputStream(){
                @Override public void write(int value)throws IOException{write(new byte[]{(byte)value});}
                @Override public void write(byte[] bytes,int offset,int length)throws IOException{
                    synchronized(DeferredLog.this){
                        if(closed)throw new IOException("执行日志已关闭。");
                        int accepted=(int)Math.min(length,cap-written);
                        if(accepted<length){
                            exceeded.set(true);
                            // 替换非法字节可能增大日志；仍按实际落盘字节限额，并保留完整 UTF-8 字符。
                            while(accepted>0&&(bytes[offset+accepted]&0xc0)==0x80)accepted--;
                        }
                        output.write(bytes,offset,accepted);written+=accepted;output.flush();
                    }
                }
            });
            streams.add(channel);return channel;
        }
        @Override public void write(int value)throws IOException{direct.write(value);}
        @Override public void write(byte[] bytes,int offset,int length)throws IOException{direct.write(bytes,offset,length);}
        @Override public void close()throws IOException{if(!kept)finish();}
        void finish()throws IOException{
            kept=false;
            // 不持有文件锁等待各流，避免和正在提交完整行的 reader 相互等待。
            try{for(var stream:streams)stream.close();}
            finally{synchronized(this){if(!closed){closed=true;output.close();}}}
        }
    }

    /** 看到守卫放行且容器仍在运行后返回，不结束容器。 */
    private boolean awaitServing(String id,Process attach,StringBuffer markers)throws InterruptedException{
        long deadline=System.nanoTime()+Duration.ofSeconds(20).toNanos();
        while(System.nanoTime()<deadline){
            if(markers.toString().contains(OK_MARKER)){
                var live=engine.run(List.of("inspect","--format","{{.State.Running}}",id),30);
                return live.ok()&&"true".equals(live.stdout().strip());
            }
            if(attach!=null&&!attach.isAlive())return false;
            if(attach!=null)attach.waitFor(100,java.util.concurrent.TimeUnit.MILLISECONDS);
            else Thread.sleep(100);
        }
        return false;
    }

    /** 读取 Docker 实际分配的宿主端口。不是 127.0.0.1 就不返回地址。 */
    private String loopbackAddress(String id,int port){
        var inspected=engine.run(List.of("inspect","--format","{{json .NetworkSettings.Ports}}",id),30);
        if(!inspected.ok())return null;
        try{
            var bindings=new com.fasterxml.jackson.databind.ObjectMapper().readTree(inspected.stdout()).path(port+"/tcp");
            if(!bindings.isArray()||bindings.isEmpty())return null;
            String ip=bindings.get(0).path("HostIp").asText("");
            String hostPort=bindings.get(0).path("HostPort").asText("");
            if(!"127.0.0.1".equals(ip)||!hostPort.matches("[1-9][0-9]{0,4}"))return null;
            return "http://127.0.0.1:"+hostPort+"/";
        }catch(IOException error){return null;}
    }


    private void ensureEgress(){
        var created=engine.run(List.of("network","create","glyahh-imp-egress"),30);
        if(created.ok())return;
        String detail=(created.stderr()+created.stdout()).toLowerCase(Locale.ROOT);
        if(!detail.contains("already exists"))throw new IllegalStateException("EGRESS_NETWORK_UNAVAILABLE");
    }

    static Outcome classify(String killed,Integer exit,boolean oom,String markers,String nonce,long bytes,String id){
        boolean guarded=markers.contains(OK_MARKER+" "+nonce);
        if(!guarded&&killed==null){
            var matcher=Pattern.compile(FAIL_MARKER+" ([A-Z_]+)").matcher(markers);
            return new Outcome("REFUSED",matcher.find()?"GUARD_"+matcher.group(1):"GUARD_UNCONFIRMED",exit,bytes,id);
        }
        if("STOPPED".equals(killed))return new Outcome("STOPPED","STOP_REQUESTED",exit,bytes,id);
        if(killed!=null)return new Outcome("FAILED",killed,exit,bytes,id);
        if(oom)return new Outcome("FAILED","MEMORY_LIMIT",exit,bytes,id);
        if(exit!=null&&exit==0)return new Outcome("SUCCEEDED",null,0,bytes,id);
        // 容器内 timeout 兜底：后端未及时结束时由容器自身在 wall+5s 强制退出。
        if(exit!=null&&exit==137)return new Outcome("FAILED","KILLED",exit,bytes,id);
        return new Outcome("FAILED","EXIT_NONZERO",exit,bytes,id);
    }

    /** 只读检查服务容器，无法访问引擎时不推断服务仍然就绪。 */
    public String executionState(String id){
        if(id==null||!CONTAINER.matcher(id).matches())return "UNKNOWN";
        var state=engine.run(List.of("inspect","--format","{{.State.Running}}",id),30);
        if(state.ok())return switch(state.stdout().strip()){case "true"->"RUNNING";case "false"->"FAILED";default->"UNKNOWN";};
        return (state.stderr()+state.stdout()).toLowerCase(Locale.ROOT).contains("no such")?"FAILED":"UNKNOWN";
    }

    /** 强制删除并核实：kill/rm 的返回值不作为依据，只认 inspect 查不到。 */
    public boolean removed(String idOrName){
        return ImportInstallDiagnostics.call("CLEANUP",()->removedObserved(idOrName));
    }
    private boolean removedObserved(String idOrName){
        engine.run(List.of("rm","-f",idOrName),60);
        var inspect=engine.run(List.of("inspect","--format","{{.Id}}",idOrName),30);
        return !inspect.ok()&&(inspect.stderr()+inspect.stdout()).toLowerCase(Locale.ROOT).contains("no such");
    }

    /** 列出带执行标签的全部容器：执行 id → 容器 id。引擎不可用时返回 null。 */
    public Map<String,String> managed(){
        // 格式串不带引号：经 wsl.exe 转发时内嵌引号会被改写。标签值从 k=v 列表中取。
        var listed=engine.run(List.of("ps","-a","--no-trunc","--filter","label="+LABEL,"--format","{{.ID}}|{{.Labels}}"),60);
        if(!listed.ok())return null;
        var result=new LinkedHashMap<String,String>();
        for(String line:listed.stdout().split("\\R")){
            String[] parts=line.strip().split("\\|",2);
            if(parts.length!=2||!CONTAINER.matcher(parts[0]).matches())continue;
            String value="";
            for(String label:parts[1].split(","))if(label.startsWith(LABEL+"="))value=label.substring(LABEL.length()+1);
            // 安装准入探针由任务专属恢复核验，不能被通用孤儿容器对账误删。
            if(!"pinprobe".equals(value))result.put(parts[0],value);
        }
        return result;
    }

    List<String> createArgs(Request request,String name,Path guard,Path environment){
        var limits=request.limits();
        String network=request.network()==Network.EGRESS?"glyahh-imp-egress":request.network()==Network.LOOPBACK?"bridge":"none";
        var args=new ArrayList<>(List.of("create","--name",name,"--label",LABEL+"="+request.id(),"--label","glyahh.import.app="+request.appId(),
            "--network",network,"--read-only","--user","65534:65534","--cap-drop","ALL","--security-opt","no-new-privileges",
            "--memory",Long.toString(limits.memoryBytes()),"--memory-swap",Long.toString(limits.memoryBytes()),
            "--cpu-period","100000","--cpu-quota",Long.toString(limits.cpuQuota()),"--pids-limit",Integer.toString(limits.processes()),
            "--ulimit","core=0","--ulimit","nofile=1024:1024"));
        if(!request.persist())args.addAll(List.of("--tmpfs","/work:rw,exec,nosuid,nodev,size="+limits.diskBytes()+",mode=1777"));
        args.addAll(List.of("--tmpfs","/tmp:rw,nosuid,nodev,size="+limits.tmpBytes()+",mode=1777","--shm-size","1m",
            "--log-driver","none","--stop-timeout","1","--env-file",engine.path(environment)));
        if(request.persist())args.addAll(List.of("-v",(request.mountedCopy()==null?engine.path(request.copy()):request.mountedCopy())+":/work:rw"));
        else args.addAll(List.of("-v",engine.path(request.copy())+":/input:ro"));
        if(request.network()==Network.LOOPBACK)args.addAll(List.of("-p","127.0.0.1::"+request.port()));
        args.addAll(List.of("-v",engine.path(guard)+":/glyahh/guard.sh:ro","--interactive","--workdir","/work","--entrypoint","sh",image,"/glyahh/guard.sh"));
        String joined=String.join(" ",args);
        if(joined.contains("--network host")||joined.contains("0.0.0.0")||(request.network()!=Network.LOOPBACK&&joined.contains("-p ")))throw new IllegalArgumentException("执行网络无效。");
        return List.copyOf(args);
    }

    private String env(Request request,String nonce){
        var limits=request.limits();long seconds=limits.wall().toSeconds()+5;
        return "HOME=/tmp\nGLYAHH_NONCE="+nonce+"\nGLYAHH_PIDS="+limits.processes()+"\nGLYAHH_CPU="+limits.cpuQuota()+"\nGLYAHH_MEMORY="+limits.memoryBytes()
            +"\nGLYAHH_HARD_MEMORY="+(limits.memory()==Memory.REQUIRE_HARD?1:0)+"\nGLYAHH_DISK_KB="+(limits.diskBytes()/1024)
            +"\nGLYAHH_PERSIST="+(request.persist()?1:0)
            +"\nGLYAHH_DISK_HARD="+(request.mountedCopy()!=null?1:0)
            +"\nGLYAHH_NETWORK="+(request.network()==Network.EGRESS?"egress":request.network()==Network.LOOPBACK?"loopback":"none")
            +"\nGLYAHH_LIFETIME="+(request.lifetime()==Lifetime.SERVE?"serve":"finish")
            +"\nGLYAHH_DIR="+request.directory()+"\nGLYAHH_WALL="+seconds+"\nGLYAHH_COMMAND="+request.command()+"\n";
    }

    /** 逐项比较引擎记录；返回第一项不符的名称，全部相符返回 null。 */
    private String verify(String id,Request request,Path guard,Path environment){
        var limits=request.limits();
        var result=engine.run(List.of("inspect","--format","{{.HostConfig.Memory}}|{{.HostConfig.CpuQuota}}|{{.HostConfig.CpuPeriod}}|{{.HostConfig.PidsLimit}}|{{.HostConfig.NetworkMode}}|"
            +"{{.HostConfig.ReadonlyRootfs}}|{{.HostConfig.Privileged}}|{{json .HostConfig.CapDrop}}|{{json .HostConfig.Tmpfs}}|{{json .HostConfig.Binds}}|{{.Config.User}}|{{json .HostConfig.PortBindings}}|{{len .HostConfig.Devices}}",id),30);
        if(!result.ok())return "INSPECT";
        String[] f=result.stdout().strip().split("\\|",-1);
        if(f.length!=13)return "INSPECT";
        if(!f[0].equals(Long.toString(limits.memoryBytes())))return "MEMORY";
        if(!f[1].equals(Long.toString(limits.cpuQuota()))||!f[2].equals("100000"))return "CPU";
        if(!f[3].equals(Integer.toString(limits.processes())))return "PIDS";
        String network=request.network()==Network.EGRESS?"glyahh-imp-egress":request.network()==Network.LOOPBACK?"bridge":"none";
        if(!f[4].equals(network))return "NETWORK";
        if(request.network()==Network.LOOPBACK){
            if(f[11].contains("0.0.0.0")||!f[11].contains("127.0.0.1")||!f[11].contains("\""+request.port()+"/tcp\""))return "NETWORK";
        }else if(!(f[11].equals("{}")||f[11].equals("null")))return "NETWORK";
        if(!f[5].equals("true")||!f[6].equals("false")||!f[7].equals("[\"ALL\"]")||!f[10].equals("65534:65534")||!f[12].equals("0"))return "PRIVILEGE";
        if(!f[8].contains("\"/tmp\":\"rw,nosuid,nodev,size="+limits.tmpBytes()+",mode=1777\""))return "DISK";
        if(!request.persist()&&!f[8].contains("\"/work\":\"rw,exec,nosuid,nodev,size="+limits.diskBytes()+",mode=1777\""))return "DISK";
        if(request.persist()&&f[8].contains("\"/work\""))return "DISK";
        // 挂载只能是副本和守卫；docker CLI 以 map 收集 -v，记录顺序不固定，按集合比较。
        String input=request.persist()?(request.mountedCopy()==null?engine.path(request.copy()):request.mountedCopy())+":/work:rw":engine.path(request.copy())+":/input:ro";
        try{
            var binds=new com.fasterxml.jackson.databind.ObjectMapper().readValue(f[9],String[].class);
            if(binds==null||!new HashSet<>(Arrays.asList(binds)).equals(Set.of(input,engine.path(guard)+":/glyahh/guard.sh:ro"))||binds.length!=2)return "MOUNTS";
        }catch(IOException error){return "MOUNTS";}
        return null;
    }

    private static Thread reader(InputStream input,OutputStream log,long cap,AtomicLong counted,AtomicBoolean exceeded,StringBuffer markers,AtomicBoolean logFailed){
        var thread=new Thread(()->{
            byte[] buffer=new byte[16*1024];
            try(input;log){
                int read;
                while((read=input.read(buffer))>=0){
                    if(markers!=null&&markers.length()<8192)markers.append(new String(buffer,0,Math.min(read,8192),StandardCharsets.UTF_8));
                    long before=counted.getAndAdd(read);
                    // 上限内的字节写入日志；超出部分丢弃并触发结束，不继续累积。
                    if(before<cap)synchronized(log){log.write(buffer,0,(int)Math.min(read,cap-before));}
                    if(before+read>cap)exceeded.set(true);
                }
            }catch(IOException ignored){
                logFailed.set(true);
            }
        },"import-execution-output");
        thread.setDaemon(true);thread.start();return thread;
    }

    private static void writePrivate(Path file,byte[] content)throws IOException{
        com.dbts.glyahhaigeneratecode.service.support.ProjectImportStaging.guardManagedPath(file);
        Files.write(file,content,StandardOpenOption.CREATE_NEW,StandardOpenOption.WRITE,LinkOption.NOFOLLOW_LINKS);
    }
}
