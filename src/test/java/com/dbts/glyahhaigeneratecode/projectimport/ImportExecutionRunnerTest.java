package com.dbts.glyahhaigeneratecode.projectimport;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.time.Instant;
import java.util.*;
import java.util.function.Supplier;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

class ImportExecutionRunnerTest {
    @TempDir Path temp;
    Path workspace,root;
    ImportExecutionSandbox sandbox=mock(ImportExecutionSandbox.class);
    ImportStartupResourceGuard guard=mock(ImportStartupResourceGuard.class);
    MemoryLedger ledger=new MemoryLedger();
    List<Long> locked=new ArrayList<>();
    ImportExecutionRunner runner;
    static final ImportExecutionSandbox.Limits LIMITS=new ImportExecutionSandbox.Limits(0.5,128L<<20,32,32L<<20,8L<<20,Duration.ofSeconds(20),4096,ImportExecutionSandbox.Memory.REQUIRE_HARD);

    /** 内存账本：按真实约束模拟 CAS 与结束行只读。 */
    static final class MemoryLedger extends ImportExecutionLedger {
        final Map<UUID,Row> rows=new LinkedHashMap<>();boolean unresolved;
        MemoryLedger(){super(null,null);}
        Row put(Row row){rows.put(row.id(),row);return row;}
        Row with(Row r,String state,String reason,String container,String cleanup){return new Row(r.id(),r.ownerId(),r.appId(),r.planId(),r.executorPid(),r.executorStart(),r.containerName(),container,state,reason,cleanup,r.revision()+1);}
        @Override public Row open(Entry e,String state,String reason){return put(new Row(e.id(),e.ownerId(),e.appId(),e.planId(),e.executorPid(),e.executorStart(),ImportExecutionSandbox.PREFIX+e.id(),null,state,reason,"REFUSED".equals(state)?"DONE":"PENDING",0));}
        @Override public Row container(Row r,String id){return put(with(r,r.state(),r.reason(),id,r.cleanup()));}
        @Override public Row running(Row r,String at){return put(with(r,"RUNNING",null,r.containerId(),r.cleanup()));}
        @Override public Row stopping(Row r){return put(with(r,"STOPPING",null,r.containerId(),r.cleanup()));}
        @Override public Row finish(Row r,String state,String reason,Integer exit,long bytes,String cleanup){
            var current=rows.get(r.id());
            if(!current.active()||current.revision()!=r.revision())throw new com.dbts.glyahhaigeneratecode.exception.MyException(com.dbts.glyahhaigeneratecode.exception.ErrorCode.CONFLICT_ERROR,"fixture CAS conflict");
            return put(with(r,state,reason,r.containerId(),cleanup));
        }
        @Override public Row cleaned(Row r){assertEquals("FAILED",r.cleanup());assertFalse(r.active());return put(with(r,r.state(),r.reason(),r.containerId(),"DONE"));}
        @Override public Row find(UUID id){return rows.get(id);}
        @Override public List<Row> outstanding(){return rows.values().stream().filter(r->r.active()||!"DONE".equals(r.cleanup())).toList();}
        @Override public boolean unresolved(long appId){return unresolved||rows.values().stream().anyMatch(r->r.appId()==appId&&(r.active()||!"DONE".equals(r.cleanup())));}
        @Override public boolean blocking(long appId,String slot){return unresolved(appId);}
        @Override public java.util.List<UUID> activeOther(long appId,String planRevision){return java.util.List.of();}
        @Override public List<Row> forApp(long appId){return rows.values().stream().filter(r->r.appId()==appId).toList();}
    }

    @BeforeEach void setUp()throws Exception{
        workspace=Files.createDirectories(temp.resolve("ws"));root=temp.resolve("exec");
        Files.createDirectories(workspace.resolve("src"));Files.writeString(workspace.resolve("index.html"),"<html>fixture</html>");Files.writeString(workspace.resolve("src/a.py"),"print(1)");
        var copies=new ImportStartupSnapshotStore(root,List.of(workspace),100,1<<20);
        runner=new ImportExecutionRunner(ledger,sandbox,copies,guard,new ImportExecutionRunner.Locker(){
            @Override public <T> T withLock(long appId,Supplier<T> action){locked.add(appId);return action.get();}
        },root);
        when(sandbox.preflight(any())).thenReturn(List.of());
    }
    String hash()throws Exception{
        var store=new ImportStartupSnapshotStore(temp.resolve("probe"),List.of(workspace),100,1<<20);var id=UUID.randomUUID();
        try{return store.capture(id,workspace,()->true).contentHash();}finally{store.discard(id);}
    }
    ImportExecutionRunner.Target target(String hash){return new ImportExecutionRunner.Target(1,7,UUID.randomUUID(),"b".repeat(64),hash,workspace,".","python3 src/a.py");}
    Map<String,String> tree(Path base)throws Exception{
        var map=new TreeMap<String,String>();
        try(var walk=Files.walk(base)){for(var p:walk.filter(Files::isRegularFile).toList())map.put(base.relativize(p).toString().replace('\\','/'),Files.readString(p));}
        return map;
    }

    @Test void runtimeQueryChecksFrozenOwnershipAndFinalizesExitedContainer(){
        UUID id=UUID.randomUUID(),plan=UUID.randomUUID();
        var row=new ImportExecutionLedger.Row(id,1,7,plan,ProcessHandle.current().pid(),Instant.now(),"owned", "c".repeat(64),"RUNNING",null,"PENDING",0);
        ledger.put(row);
        assertEquals("UNKNOWN",runner.executionState(2,7,plan,id));
        assertEquals("UNKNOWN",runner.executionState(1,8,plan,id));
        assertEquals("UNKNOWN",runner.executionState(1,7,UUID.randomUUID(),id));
        verify(sandbox,never()).executionState(any());
        when(sandbox.executionState(row.containerId())).thenReturn("RUNNING","UNKNOWN","FAILED");
        assertEquals("RUNNING",runner.executionState(1,7,plan,id));
        assertEquals("UNKNOWN",runner.executionState(1,7,plan,id));
        assertEquals("RUNNING",ledger.find(id).state());
        when(sandbox.removed(anyString())).thenReturn(true);
        assertEquals("FAILED",runner.executionState(1,7,plan,id));
        assertEquals("SERVICE_EXITED",ledger.find(id).reason());
        assertNull(runner.executionReason(2,7,plan,id));
        assertEquals("SERVICE_EXITED",runner.executionReason(1,7,plan,id));
        assertFalse(ledger.find(id).active());
    }

    @Test void queryLosingCasToSupervisorReturnsAuthoritativeTerminalInsteadOfUnknown(){
        var id=UUID.randomUUID();var plan=UUID.randomUUID();
        var row=new ImportExecutionLedger.Row(id,1,7,plan,ProcessHandle.current().pid(),Instant.now(),"owned","c".repeat(64),"RUNNING",null,"PENDING",0);
        ledger.put(row);
        when(sandbox.executionState(row.containerId())).thenReturn("FAILED");
        var completed=new java.util.concurrent.atomic.AtomicBoolean();
        when(sandbox.removed(anyString())).thenAnswer(call->{
            if(completed.compareAndSet(false,true))ledger.put(ledger.with(row,"FAILED","SERVICE_EXITED",row.containerId(),"DONE"));
            return true;
        });
        assertEquals("FAILED",runner.executionState(1,7,plan,id));
        assertEquals("SERVICE_EXITED",runner.executionReason(1,7,plan,id));
    }

    @Test void supervisorFinalizesOnlyItsExecutionAndLateCallbackCannotOverwriteTerminal()throws Exception{
        var journal=new java.util.concurrent.atomic.AtomicReference<ImportExecutionSandbox.Journal>();
        when(sandbox.execute(any(),any(),any(),any(),any(),any())).thenAnswer(call->{
            ImportExecutionSandbox.Journal value=call.getArgument(4);journal.set(value);
            value.created("c".repeat(64));value.started("now");
            return new ImportExecutionSandbox.Outcome("RUNNING",null,null,4,"c".repeat(64));
        });
        var running=runner.execute(target(hash()),LIMITS);
        assertEquals("RUNNING",running.state());
        journal.get().finished(new ImportExecutionSandbox.Outcome("FAILED","OUTPUT_LIMIT",null,4096,"c".repeat(64)));
        var finished=ledger.find(running.executionId());
        assertEquals("FAILED",finished.state());assertEquals("OUTPUT_LIMIT",finished.reason());
        assertEquals("DONE",finished.cleanup());
        journal.get().finished(new ImportExecutionSandbox.Outcome("STOPPED","STOP_REQUESTED",null,0,"c".repeat(64)));
        assertEquals(finished,ledger.find(running.executionId()));
        assertTrue(Files.exists(workspace.resolve("src/a.py")));
    }

    @Test void refusedBoundaryNeverCopiesOrCreatesContainer()throws Exception{
        when(sandbox.preflight(any())).thenReturn(List.of("MEMORY_LIMIT_UNENFORCEABLE"));
        var result=runner.execute(target(hash()),LIMITS);
        assertEquals("REFUSED",result.state());assertEquals("MEMORY_LIMIT_UNENFORCEABLE",result.reason());
        verify(sandbox,never()).execute(any(),any(),any(),any(),any(),any());assertTrue(locked.isEmpty());
        try(var list=Files.list(root)){assertEquals(0,list.count());}
    }
    @Test void executesOnIndependentVerifiedCopyAndLeavesOriginalUntouched()throws Exception{
        var before=tree(workspace);
        when(sandbox.execute(any(),any(),any(),any(),any(),any())).thenAnswer(call->{
            ImportExecutionSandbox.Request request=call.getArgument(0);
            // 1. 副本是独立目录，内容与原工程相同，且不在原工程之内。
            assertFalse(request.copy().startsWith(workspace));assertTrue(request.copy().startsWith(root.resolve("apps")));
            assertEquals(before,tree(request.copy()));
            ImportExecutionSandbox.Journal journal=call.getArgument(4);journal.created("c".repeat(64));journal.started("now");
            assertEquals("RUNNING",ledger.find(request.id()).state());
            return new ImportExecutionSandbox.Outcome("SUCCEEDED",null,0,12,"c".repeat(64));
        });
        var result=runner.execute(target(hash()),LIMITS);
        assertEquals("SUCCEEDED",result.state());assertEquals(List.of(7L),locked);
        assertEquals("DONE",ledger.find(result.executionId()).cleanup());assertEquals("c".repeat(64),ledger.find(result.executionId()).containerId());
        assertEquals(before,tree(workspace));
        assertTrue(Files.isDirectory(root.resolve("apps").resolve("7")));
        try(var list=Files.list(root)){assertEquals(List.of("apps"),list.map(p->p.getFileName().toString()).filter(n->!n.endsWith(".output.log")).sorted().toList());}
    }
    @Test void changedContentFailsWithoutExecutingAndKeepsProject()throws Exception{
        var result=runner.execute(target("0".repeat(64)),LIMITS);
        assertEquals("FAILED",result.state());assertEquals("PLAN_CONTENT_CHANGED",result.reason());
        verify(sandbox,never()).execute(any(),any(),any(),any(),any(),any());
        assertTrue(Files.exists(workspace.resolve("src/a.py")));assertEquals("DONE",ledger.find(result.executionId()).cleanup());
    }
    @Test void productionVolumeFailureCannotExecuteFallbackAndKeepsResourceRegistration()throws Exception{
        var volume=mock(ImportDiskVolume.class);
        when(volume.prepare(anyLong(),anyString(),anyLong(),any(),any())).thenThrow(new java.io.IOException("DISK_VOLUME_HOST_SPACE_INSUFFICIENT"));
        var copies=new ImportStartupSnapshotStore(root,List.of(workspace),100,1<<20);
        runner=new ImportExecutionRunner(ledger,sandbox,copies,guard,new ImportExecutionRunner.Locker(){
            public <T> T withLock(long appId,Supplier<T> action){return action.get();}
        },root,volume);
        var result=runner.execute(target(hash()),LIMITS);
        assertEquals("FAILED",result.state());assertEquals("DISK_VOLUME_HOST_SPACE_INSUFFICIENT",result.reason());
        assertTrue(Files.exists(root.resolve("apps/7.volume.json")));
        verify(sandbox,never()).execute(any(),any(),any(),any(),any(),any());
        when(volume.release(anyLong(),anyString(),anyLong())).thenReturn(false);
        assertFalse(runner.releaseApp(7));assertTrue(Files.exists(root.resolve("apps/7.volume.json")));
        when(volume.release(anyLong(),anyString(),anyLong())).thenReturn(true);
        assertTrue(runner.releaseApp(7));assertFalse(Files.exists(root.resolve("apps/7.volume.json")));
    }

    @Test void productionRequestUsesVerifiedVolumeAndPersistedSnapshotManifest()throws Exception{
        var volume=mock(ImportDiskVolume.class);
        when(volume.prepare(anyLong(),anyString(),anyLong(),any(),any())).thenReturn("/var/lib/glyahh/import-disks/7/work");
        var copies=new ImportStartupSnapshotStore(root,List.of(workspace),100,1<<20);
        runner=new ImportExecutionRunner(ledger,sandbox,copies,guard,new ImportExecutionRunner.Locker(){
            public <T> T withLock(long appId,Supplier<T> action){return action.get();}
        },root,volume);
        Files.createDirectories(workspace.resolve("empty"));
        when(sandbox.execute(any(),any(),any(),any(),any(),any())).thenAnswer(call->{
            ImportExecutionSandbox.Request request=call.getArgument(0);
            assertEquals("/var/lib/glyahh/import-disks/7/work",request.mountedCopy());
            var manifest=new com.fasterxml.jackson.databind.ObjectMapper().readTree(Files.readString(root.resolve("apps/7.manifest.json")));
            assertEquals(2,manifest.path("files").size());assertTrue(manifest.path("directories").toString().contains("empty"));
            return new ImportExecutionSandbox.Outcome("SUCCEEDED",null,0,0,null);
        });
        assertEquals("SUCCEEDED",runner.execute(target(hash()),LIMITS).state());
        assertTrue(Files.readString(root.resolve("apps/7.volume.json")).contains("READY"));
        Files.writeString(workspace.resolve("src/a.py"),"print(2)");
        when(volume.release(anyLong(),anyString(),anyLong())).thenReturn(false);
        assertEquals("PLAN_CONTENT_CHANGED",runner.execute(target(hash()),LIMITS).reason());
        when(volume.release(anyLong(),anyString(),anyLong())).thenReturn(true);
        assertEquals("SUCCEEDED",runner.execute(target(hash()),LIMITS).state());
        assertEquals("print(2)",Files.readString(root.resolve("apps/7/src/a.py")));
    }
    @Test void unresolvedPreviousExecutionBlocksNewOne()throws Exception{
        ledger.unresolved=true;
        var result=runner.execute(target(hash()),LIMITS);
        assertEquals("REFUSED",result.state());assertEquals("PREVIOUS_EXECUTION_UNRESOLVED",result.reason());
    }
    @Test void unconfirmedStopKeepsCopyUntilReconcileConfirmsResourceGone()throws Exception{
        when(sandbox.execute(any(),any(),any(),any(),any(),any())).thenReturn(new ImportExecutionSandbox.Outcome("STOP_FAILED","CONTAINER_NOT_CONFIRMED_GONE",null,0,"d".repeat(64)));
        var result=runner.execute(target(hash()),LIMITS);
        assertEquals("STOP_FAILED",result.state());assertEquals("FAILED",ledger.find(result.executionId()).cleanup());
        assertTrue(Files.isDirectory(root.resolve("apps").resolve("7")));
        // 1. 新执行在残留未解决前被拒绝；对账确认容器消失后才补记清理并删除副本。
        assertEquals("PREVIOUS_EXECUTION_UNRESOLVED",runner.execute(target(hash()),LIMITS).reason());
        when(guard.parent(anyLong(),any())).thenReturn(ImportStartupResourceGuard.Parent.GONE);
        when(sandbox.removed(anyString())).thenReturn(true);when(sandbox.managed()).thenReturn(Map.of());
        var reconciled=runner.reconcile();
        assertEquals(List.of(),reconciled.unresolved());
        assertEquals("DONE",ledger.find(result.executionId()).cleanup());assertEquals("STOP_FAILED",ledger.find(result.executionId()).state());
        assertFalse(Files.exists(root.resolve(result.executionId()+".workspace")));
    }
    @Test void reconcileStopsDeadExecutorsOnlyAndRemovesUnownedLeftovers()throws Exception{
        Files.createDirectories(root);
        var dead=ledger.put(new ImportExecutionLedger.Row(UUID.randomUUID(),1,7,UUID.randomUUID(),999_999,Instant.parse("2026-01-01T00:00:00Z"),"n1","1".repeat(64),"RUNNING",null,"PENDING",3));
        var alive=ledger.put(new ImportExecutionLedger.Row(UUID.randomUUID(),1,8,UUID.randomUUID(),4242,Instant.parse("2026-01-01T00:00:00Z"),"n2","2".repeat(64),"RUNNING",null,"PENDING",1));
        var orphan=UUID.randomUUID();
        for(var id:List.of(dead.id(),alive.id(),orphan))Files.createDirectories(root.resolve(id+".workspace"));
        Files.writeString(root.resolve(orphan+".env"),"x");Files.writeString(root.resolve(dead.id()+".output.log"),"kept");
        when(guard.parent(eq(999_999L),any())).thenReturn(ImportStartupResourceGuard.Parent.GONE);
        when(guard.parent(eq(4242L),any())).thenReturn(ImportStartupResourceGuard.Parent.ALIVE);
        when(sandbox.removed(anyString())).thenReturn(true);
        when(sandbox.managed()).thenReturn(Map.of("1".repeat(64),dead.id().toString(),"2".repeat(64),alive.id().toString(),"3".repeat(64),orphan.toString()));
        var result=runner.reconcile();
        assertEquals("INTERRUPTED",ledger.find(dead.id()).state());assertEquals("EXECUTOR_GONE",ledger.find(dead.id()).reason());assertEquals("DONE",ledger.find(dead.id()).cleanup());
        assertEquals("RUNNING",ledger.find(alive.id()).state());
        verify(sandbox).removed("1".repeat(64));verify(sandbox).removed("3".repeat(64));verify(sandbox,never()).removed("2".repeat(64));
        assertTrue(Files.exists(root.resolve(alive.id()+".workspace")));assertFalse(Files.exists(root.resolve(orphan+".workspace")));
        assertFalse(Files.exists(root.resolve(orphan+".env")));assertTrue(Files.exists(root.resolve(dead.id()+".output.log")));
        assertEquals(1,result.rows());assertEquals(List.of(),result.unresolved());
    }
    @Test void releaseAppKeepsDurableCopyUntilContainerIsConfirmedGone()throws Exception{
        Files.createDirectories(root.resolve("apps").resolve("7"));
        Files.writeString(root.resolve("apps").resolve("7.source"),"a".repeat(64));
        Files.writeString(root.resolve("apps").resolve("7").resolve("keep.txt"),"kept");
        Files.createDirectories(root.resolve("apps").resolve("8"));
        var running=ledger.put(new ImportExecutionLedger.Row(UUID.randomUUID(),1,7,UUID.randomUUID(),999_999,Instant.parse("2026-01-01T00:00:00Z"),"n1","a".repeat(64),"RUNNING",null,"PENDING",1));
        when(sandbox.removed(anyString())).thenReturn(false);
        assertFalse(runner.releaseApp(7));
        assertEquals("STOP_FAILED",ledger.find(running.id()).state());
        assertTrue(Files.isDirectory(root.resolve("apps").resolve("7")));
        assertTrue(Files.exists(root.resolve("apps").resolve("7.source")));
        when(sandbox.removed(anyString())).thenReturn(true);
        assertTrue(runner.releaseApp(7));
        assertEquals("DONE",ledger.find(running.id()).cleanup());
        assertFalse(Files.exists(root.resolve("apps").resolve("7")));
        assertFalse(Files.exists(root.resolve("apps").resolve("7.source")));
        assertTrue(Files.isDirectory(root.resolve("apps").resolve("8")));
    }

    @Test void 额度被上一轮残留占满时丢弃副本使重试不被永久阻塞()throws Exception{
        var small=new ImportExecutionSandbox.Limits(0.5,128L<<20,32,1L<<20,1L<<20,Duration.ofSeconds(20),4096,ImportExecutionSandbox.Memory.REQUIRE_HARD);
        var written=new java.util.concurrent.atomic.AtomicBoolean();
        when(sandbox.execute(any(),any(),any(),any(),any(),any())).thenAnswer(call->{
            ImportExecutionSandbox.Journal journal=call.getArgument(4);
            journal.created("c".repeat(64));journal.started("now");
            // 工程自己把副本写到超过额度，模拟失败/成功后留下的残留
            if(written.compareAndSet(false,true))Files.write(root.resolve("apps").resolve("7").resolve("big.bin"),new byte[2<<20]);
            return new ImportExecutionSandbox.Outcome("SUCCEEDED",null,0,4,"c".repeat(64));
        });
        var first=runner.execute(target(hash()),small);
        assertEquals("FAILED",first.state());assertEquals("DISK_QUOTA",first.reason());
        assertFalse(Files.exists(root.resolve("apps").resolve("7"),java.nio.file.LinkOption.NOFOLLOW_LINKS));
        assertFalse(Files.exists(root.resolve("apps").resolve("7.source"),java.nio.file.LinkOption.NOFOLLOW_LINKS));
        var retry=runner.execute(target(hash()),small);
        assertEquals("SUCCEEDED",retry.state());
    }
}
