package com.dbts.glyahhaigeneratecode.projectimport;

import org.springframework.stereotype.Component;
import java.time.Duration;
import java.util.*;
import java.util.function.Consumer;

/** 只处理可信账本登记的 PID/启动时间与其当前子进程，不按进程名批量终止。 */
@Component
public class ImportStartupResourceGuard {
    public enum Parent {ALIVE,GONE,UNKNOWN}
    public Parent parent(ImportStartupScan task){
        return parent(task.executorPid(),task.executorStart());
    }
    public Parent parent(long pid,java.time.Instant started){
        if(pid<=0||started==null)return Parent.UNKNOWN;
        var process=ProcessHandle.of(pid);if(process.isEmpty()||!process.get().isAlive())return Parent.GONE;
        var start=process.get().info().startInstant();if(start.isEmpty())return Parent.UNKNOWN;
        // PostgreSQL 时间只有微秒精度；容许一次存储取整，不容许按 PID 借新进程身份。
        return Duration.between(started,start.get()).abs().compareTo(Duration.ofNanos(1000))<=0?Parent.ALIVE:Parent.GONE;
    }
    public boolean stopped(ImportStartupScan task){
        return stoppedResources(task.resources());
    }
    public boolean stoppedResources(List<ImportProcessRunner.Resource> resources){
        for(var resource:resources)if(state(resource)!=Parent.GONE)return false;
        return true;
    }
    public boolean stop(ImportStartupScan task,Consumer<ImportProcessRunner.Resource> journal,Duration timeout){
        return stopResources(task.resources(),journal,timeout);
    }
    public boolean stopResources(List<ImportProcessRunner.Resource> resources,Consumer<ImportProcessRunner.Resource> journal,Duration timeout){
        if(timeout==null||timeout.isZero()||timeout.isNegative())return false;
        var owned=new LinkedHashMap<Long,ImportProcessRunner.Resource>();
        try{
            // 1. 先核实根身份，登记仍挂在已确认根下的子进程，再尝试停止。
            for(var resource:resources){
                Parent identity=state(resource);if(identity==Parent.UNKNOWN)return false;if(identity==Parent.GONE)continue;
                var root=ProcessHandle.of(resource.pid()).orElseThrow();owned.put(resource.pid(),resource);
                try(var descendants=root.descendants()){
                    for(var child:descendants.toList()){
                        if(!child.isAlive())continue;var started=child.info().startInstant();if(started.isEmpty())return false;
                        var entry=new ImportProcessRunner.Resource(child.pid(),started.get());
                        if(!resources.contains(entry)){journal.accept(entry);}
                        owned.put(child.pid(),entry);
                    }
                }
            }
            // 2. 操作前再次比较启动时间；PID 已复用只代表旧资源消失，不杀新进程。
            for(var resource:owned.values()){
                Parent identity=state(resource);if(identity==Parent.UNKNOWN)return false;
                if(identity==Parent.ALIVE){
                    var process=ProcessHandle.of(resource.pid());
                    // 根退出可能连带子进程退出；停止调用失败后仍须核实旧资源确已消失。
                    if(process.isPresent()&&!process.get().destroyForcibly()&&state(resource)!=Parent.GONE)return false;
                }
            }
            long deadline=System.nanoTime()+timeout.toNanos();
            while(System.nanoTime()<deadline){
                boolean done=true;for(var resource:owned.values())if(state(resource)!=Parent.GONE){done=false;break;}
                if(done)return true;Thread.sleep(20);
            }
            return false;
        }catch(InterruptedException error){Thread.currentThread().interrupt();return false;}
        catch(RuntimeException error){return false;}
    }
    private static Parent state(ImportProcessRunner.Resource resource){
        ImportStartupScan.requireResource(resource);var process=ProcessHandle.of(resource.pid());
        if(process.isEmpty()||!process.get().isAlive())return Parent.GONE;
        var started=process.get().info().startInstant();if(started.isEmpty())return Parent.UNKNOWN;
        return started.get().equals(resource.started())?Parent.ALIVE:Parent.GONE;
    }
}
