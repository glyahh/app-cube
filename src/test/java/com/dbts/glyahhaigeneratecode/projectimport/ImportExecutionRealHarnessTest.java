package com.dbts.glyahhaigeneratecode.projectimport;

import com.dbts.glyahhaigeneratecode.config.RuntimeProperties;
import com.dbts.glyahhaigeneratecode.runtime.DockerCli;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.*;
import java.util.concurrent.CompletableFuture;
import java.util.function.Supplier;

/**
 * [受控夹具] 真实 Docker/PostgreSQL 隔离执行核对：只在验证脚本传入夹具描述时运行，否则跳过。
 * 夹具工程由 scripts/verify-startup-isolation-real.py 生成并导入，命令全部是夹具内的小脚本；不运行用户真实工程。
 */
class ImportExecutionRealHarnessTest {
    static final ObjectMapper JSON=new ObjectMapper();

    @Test void controlledFixtureCases()throws Exception{
        String fixtureFile=System.getProperty("import.exec.harness");
        Assumptions.assumeTrue(fixtureFile!=null&&!fixtureFile.isBlank(),"未提供受控夹具，跳过真实隔离核对");
        var fixture=JSON.readTree(Path.of(fixtureFile).toFile());
        var source=new DriverManagerDataSource(System.getenv("GLYAHH_HARNESS_JDBC_URL"),System.getenv("GLYAHH_HARNESS_DB_USER"),System.getenv("GLYAHH_HARNESS_DB_PASSWORD"));
        var jdbc=new JdbcTemplate(source);
        long owner=fixture.get("ownerId").asLong(),app=fixture.get("appId").asLong();var planId=UUID.fromString(fixture.get("planId").asText());
        var plan=new ImportStartupPlanRepository(jdbc,JSON).find(owner,app,planId);
        if(plan==null||!"CONFIRMED".equals(plan.state()))throw new IllegalStateException("夹具计划未确认");
        Path root=Path.of(fixture.get("executionRoot").asText()),workspace=Path.of(fixture.get("workspace").asText());
        var copies=new ImportStartupSnapshotStore(root,List.of(Path.of("temp/code_output"),Path.of("temp/code_deploy"),Path.of("temp/project_import_private"),Path.of("temp/project_startup_private")),10_000,1L<<30);
        var docker=new DockerCli(new RuntimeProperties());
        var engine=ImportExecutionSandbox.docker(docker);
        var sandbox=new ImportExecutionSandbox(engine,new RuntimeProperties().getImage());
        var coordinator=new ImportWorkspaceCoordinator(source);
        var ledger=new ImportExecutionLedger(jdbc,JSON);
        var volumes=new ImportDiskVolume(engine,Path.of("scripts/import_disk_volume.py"));
        var runner=new ImportExecutionRunner(ledger,sandbox,copies,new ImportStartupResourceGuard(),new ImportExecutionRunner.Locker(){
            @Override public <T> T withLock(long id,Supplier<T> action){return coordinator.withLock(id,action);}
        },root,volumes);
        var out=Path.of(fixture.get("output").asText());
        var results=JSON.createArrayNode();
        String mode=System.getProperty("import.exec.mode","cases");
        if(mode.equals("crash")){
            // 1. 崩溃夹具：启动长时间执行，登记为 RUNNING 后写出标记，等待验证脚本强制结束本 JVM。
            var running=CompletableFuture.supplyAsync(()->runner.execute(t(owner,app,planId,plan,workspace,"sh tools/sleep.sh 600"),limits(600,ImportExecutionSandbox.Memory.VERIFICATION_ONLY_UNGUARANTEED)));
            var row=awaitRunning(jdbc,app);
            var marker=JSON.createObjectNode().put("executionId",row.get("execution_id")).put("containerId",row.get("container_id")).put("executorPid",ProcessHandle.current().pid());
            Files.writeString(out,marker.toString());
            Thread.sleep(Duration.ofMinutes(9).toMillis());
            running.cancel(true);
            return;
        }
        // 生产默认额度与验证模式额度不同，受限卷按额度绑定身份，必须用各自的应用承载。
        if(mode.equals("defaults")){
            results.add(record("hard-memory-default",runner.execute(t(owner,app,planId,plan,workspace,"sh tools/ok.sh"),ImportExecutionSandbox.Limits.defaults()),jdbc,engine,root));
            Files.writeString(out,results.toString());
            return;
        }
        if(mode.equals("core")){
            String[][] coreCases={{"ok","sh tools/ok.sh","20"},{"memory-hard","python3 tools/mem.py","20"},
                {"fork","python3 tools/fork.py","25"},{"disk","sh tools/disk.sh","20"},
                {"output","sh tools/out.sh","20"},{"timeout","sh tools/sleep.sh 600","5"},{"cpu","sh tools/cpu.sh","20"}};
            for(var item:coreCases)results.add(record(item[0],runner.execute(t(owner,app,planId,plan,workspace,item[1]),
                limits(Integer.parseInt(item[2]),ImportExecutionSandbox.Memory.REQUIRE_HARD)),jdbc,engine,root));
            Files.writeString(out,JSON.writerWithDefaultPrettyPrinter().writeValueAsString(results));
            return;
        }
        var verify=ImportExecutionSandbox.Memory.VERIFICATION_ONLY_UNGUARANTEED;
        String[][] cases={{"ok","sh tools/ok.sh","20"},{"exit-nonzero","sh tools/fail.sh","20"},{"tamper","sh tools/tamper.sh","20"},{"memory-gap","python3 tools/mem.py","30"},
            {"fork","python3 tools/fork.py","30"},{"disk","sh tools/disk.sh","30"},{"output","sh tools/out.sh","30"},{"timeout","sh tools/sleep.sh 600","5"},{"network","python3 tools/net.py","30"},{"cpu","sh tools/cpu.sh","30"}};
        for(var item:cases)results.add(record(item[0],runner.execute(t(owner,app,planId,plan,workspace,item[1]),limits(Integer.parseInt(item[2]),verify)),jdbc,engine,root));
        // 3. 内容身份与计划不符（等同计划确认后内容漂移）：不执行。
        var drift=new ImportExecutionRunner.Target(owner,app,planId,plan.planRevision(),"0".repeat(64),workspace,".","sh tools/ok.sh");
        results.add(record("content-drift",runner.execute(drift,limits(20,verify)),jdbc,engine,root));
        // 4. 停止：运行中请求停止，核对容器内进程在 WSL 中真实消失后才记为 STOPPED；并发第二个执行被拒绝。
        var running=CompletableFuture.supplyAsync(()->runner.execute(t(owner,app,planId,plan,workspace,"sh tools/sleep.sh 600"),limits(120,verify)));
        var row=awaitRunning(jdbc,app);String container=row.get("container_id");
        var live=engine.run(List.of("inspect","--format","{{.State.Pid}}|{{.State.Running}}",container),30).stdout().strip();
        String pid=live.split("\\|")[0];
        var cgroup=wsl("cat /proc/"+pid+"/cgroup");
        var second=runner.execute(t(owner,app,planId,plan,workspace,"sh tools/ok.sh"),limits(20,verify));
        long stopAt=System.nanoTime();
        var stopped=runner.stop(UUID.fromString(row.get("execution_id")),Duration.ofSeconds(60));
        var stopResult=running.get();
        var node=record("stop",stopResult,jdbc,engine,root);
        node.put("stopMillis",(System.nanoTime()-stopAt)/1_000_000).put("liveInspect",live).put("liveCgroupHasContainer",cgroup.contains(container))
            .put("pidGoneInWsl",wsl("test -e /proc/"+pid+" && echo PRESENT || echo GONE").strip()).put("ledgerAfterStop",stopped.state());
        results.add(node);
        results.add(record("concurrent-second",second,jdbc,engine,root));
        Files.writeString(out,JSON.writerWithDefaultPrettyPrinter().writeValueAsString(results));
    }

    static ImportExecutionRunner.Target t(long owner,long app,UUID planId,ImportStartupPlanRepository.Plan plan,Path workspace,String command){
        return new ImportExecutionRunner.Target(owner,app,planId,plan.planRevision(),plan.content().contentHash(),workspace,".",command);
    }
    static ImportExecutionSandbox.Limits limits(int wall,ImportExecutionSandbox.Memory memory){
        return new ImportExecutionSandbox.Limits(0.5,128L<<20,32,32L<<20,8L<<20,Duration.ofSeconds(wall),256L<<10,memory);
    }
    static Map<String,String> awaitRunning(JdbcTemplate jdbc,long app)throws InterruptedException{
        long deadline=System.nanoTime()+Duration.ofSeconds(90).toNanos();
        while(System.nanoTime()<deadline){
            var rows=jdbc.queryForList("SELECT execution_id::text,container_id FROM biz.project_import_startup_execution WHERE app_id=? AND state='RUNNING'",app);
            if(!rows.isEmpty()){var map=new HashMap<String,String>();rows.getFirst().forEach((k,v)->map.put(k,v==null?null:v.toString()));return map;}
            Thread.sleep(200);
        }
        throw new IllegalStateException("执行未进入 RUNNING");
    }
    /** 每个用例结束后核对：账本终态、容器不存在、本次副本与临时文件已删除、输出日志大小。 */
    static ObjectNode record(String name,ImportExecutionRunner.Result result,JdbcTemplate jdbc,ImportExecutionSandbox.Engine docker,Path root)throws Exception{
        var node=JSON.createObjectNode().put("case",name).put("executionId",result.executionId().toString()).put("state",result.state()).put("reason",result.reason())
            .put("outputBytes",result.outputBytes()).put("containerId",result.containerId());
        if(result.exitCode()!=null)node.put("exitCode",result.exitCode());
        var row=jdbc.queryForMap("SELECT state,reason,cleanup,container_id,container_started,finished_at IS NOT NULL AS finished,limits_json::text AS limits FROM biz.project_import_startup_execution WHERE execution_id=?",result.executionId());
        row.forEach((k,v)->node.put("db_"+k,v==null?null:v.toString()));
        var listed=docker.run(List.of("ps","-a","--filter","label="+ImportExecutionSandbox.LABEL+"="+result.executionId(),"--format","{{.ID}}"),30);
        node.put("containersLeft",listed.ok()?listed.stdout().strip():"ENGINE_ERROR");
        var leftovers=new ArrayList<String>();
        for(String suffix:List.of("",".workspace",".env",".guard.sh"))if(Files.exists(root.resolve(result.executionId()+suffix)))leftovers.add(suffix.isEmpty()?"blobs":suffix);
        node.put("filesLeft",String.join(",",leftovers));
        var log=root.resolve(result.executionId()+".output.log");
        node.put("logBytes",Files.exists(log)?Files.size(log):-1);
        if(Files.exists(log)){String text=Files.readString(log,java.nio.charset.StandardCharsets.ISO_8859_1);node.put("logTail",text.length()>3000?text.substring(text.length()-3000):text);}
        return node;
    }
    static String wsl(String command)throws Exception{
        var process=new ProcessBuilder("wsl.exe","-d",System.getenv().getOrDefault("AGENT_WSL_DISTRO","Ubuntu"),"--exec","sh","-c",command).redirectErrorStream(true).start();
        process.getOutputStream().close();
        var text=new String(process.getInputStream().readAllBytes(),java.nio.charset.StandardCharsets.UTF_8);
        process.waitFor();return text;
    }
}
