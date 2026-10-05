package com.dbts.glyahhaigeneratecode.projectimport;

import org.junit.jupiter.api.Test;
import java.time.*;
import java.util.*;
import java.util.stream.Stream;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class ImportStartupResourceGuardTest {
    ImportStartupResourceGuard guard=new ImportStartupResourceGuard();Instant start=Instant.parse("2026-10-04T00:00:00Z");
    ImportStartupScan task(List<ImportProcessRunner.Resource> resources){return new ImportStartupScan(UUID.randomUUID(),UUID.randomUUID(),42,7,UUID.randomUUID(),0,0,".",ImportStartupScan.Status.RECOVERY_REQUIRED,1,UUID.randomUUID(),123,start,start.plusSeconds(60),resources,null,"UNVERIFIED",null,false);}
    @Test void reusedPidIsNotStoppedAndOldResourceIsGone(){
        ProcessHandle handle=mock(ProcessHandle.class);ProcessHandle.Info info=mock(ProcessHandle.Info.class);
        when(handle.isAlive()).thenReturn(true);when(handle.info()).thenReturn(info);when(info.startInstant()).thenReturn(Optional.of(start.plusSeconds(1)));
        try(var handles=mockStatic(ProcessHandle.class)){
            handles.when(()->ProcessHandle.of(123)).thenReturn(Optional.of(handle));var task=task(List.of(new ImportProcessRunner.Resource(123,start)));
            assertEquals(ImportStartupResourceGuard.Parent.GONE,guard.parent(task));assertTrue(guard.stopped(task));assertTrue(guard.stop(task,r->{},Duration.ofMillis(20)));
            verify(handle,never()).destroyForcibly();
        }
    }
    @Test void inaccessibleStartTimeDoesNotAllowKillOrCleanup(){
        ProcessHandle handle=mock(ProcessHandle.class);ProcessHandle.Info info=mock(ProcessHandle.Info.class);
        when(handle.isAlive()).thenReturn(true);when(handle.info()).thenReturn(info);when(info.startInstant()).thenReturn(Optional.empty());
        try(var handles=mockStatic(ProcessHandle.class)){
            handles.when(()->ProcessHandle.of(123)).thenReturn(Optional.of(handle));var task=task(List.of(new ImportProcessRunner.Resource(123,start)));
            assertEquals(ImportStartupResourceGuard.Parent.UNKNOWN,guard.parent(task));assertFalse(guard.stopped(task));assertFalse(guard.stop(task,r->{},Duration.ofMillis(20)));
            verify(handle,never()).destroyForcibly();
        }
    }
    @Test void confirmedIdentityMustActuallyExitBeforeStopIsConfirmed(){
        ProcessHandle handle=mock(ProcessHandle.class);ProcessHandle.Info info=mock(ProcessHandle.Info.class);
        java.util.concurrent.atomic.AtomicBoolean live=new java.util.concurrent.atomic.AtomicBoolean(true);
        when(handle.isAlive()).thenAnswer(call->live.get());when(handle.info()).thenReturn(info);when(info.startInstant()).thenReturn(Optional.of(start));when(handle.descendants()).thenAnswer(call->Stream.empty());
        when(handle.destroyForcibly()).thenAnswer(call->{live.set(false);return true;});
        try(var handles=mockStatic(ProcessHandle.class)){
            handles.when(()->ProcessHandle.of(123)).thenReturn(Optional.of(handle));assertTrue(guard.stop(task(List.of(new ImportProcessRunner.Resource(123,start))),r->{},Duration.ofMillis(50)));
            verify(handle).destroyForcibly();assertFalse(live.get());
        }
    }
    @Test void failedDestroyKeepsUnconfirmedState(){
        ProcessHandle handle=mock(ProcessHandle.class);ProcessHandle.Info info=mock(ProcessHandle.Info.class);
        when(handle.isAlive()).thenReturn(true);when(handle.info()).thenReturn(info);when(info.startInstant()).thenReturn(Optional.of(start));when(handle.descendants()).thenAnswer(call->Stream.empty());
        when(handle.destroyForcibly()).thenReturn(false);
        try(var handles=mockStatic(ProcessHandle.class)){
            handles.when(()->ProcessHandle.of(123)).thenReturn(Optional.of(handle));assertFalse(guard.stop(task(List.of(new ImportProcessRunner.Resource(123,start))),r->{},Duration.ofMillis(20)));
        }
    }
    @Test void concurrentExitAfterFailedDestroyIsConfirmedByIdentity(){
        ProcessHandle handle=mock(ProcessHandle.class);ProcessHandle.Info info=mock(ProcessHandle.Info.class);
        var live=new java.util.concurrent.atomic.AtomicBoolean(true);
        when(handle.isAlive()).thenAnswer(call->live.get());when(handle.info()).thenReturn(info);
        when(info.startInstant()).thenReturn(Optional.of(start));when(handle.descendants()).thenAnswer(call->Stream.empty());
        when(handle.destroyForcibly()).thenAnswer(call->{live.set(false);return false;});
        try(var handles=mockStatic(ProcessHandle.class)){
            handles.when(()->ProcessHandle.of(123)).thenReturn(Optional.of(handle));
            assertTrue(guard.stop(task(List.of(new ImportProcessRunner.Resource(123,start))),r->{},Duration.ofMillis(50)));
        }
    }
    @Test void realOwnedPlatformFixtureStopsWithoutStoppingSibling()throws Exception{
        var python=new ImportProperties().getCredentialPython().toAbsolutePath();org.junit.jupiter.api.Assumptions.assumeTrue(java.nio.file.Files.isRegularFile(python));
        // 平台测试代码，不是导入工程；两个独立父进程验证停止不会按进程名波及旁路。
        var first=new ProcessBuilder(python.toString(),"-I","-S","-c","import time; time.sleep(30)").start();
        var sibling=new ProcessBuilder(python.toString(),"-I","-S","-c","import time; time.sleep(30)").start();
        var captured=new ArrayList<ImportProcessRunner.Resource>();
        try{
            Thread.sleep(150);
            var resource=new ImportProcessRunner.Resource(first.pid(),first.info().startInstant().orElseThrow());
            assertTrue(guard.stopResources(List.of(resource),captured::add,Duration.ofSeconds(5)));assertFalse(first.isAlive());assertTrue(sibling.isAlive());
            assertTrue(guard.stoppedResources(List.of(resource)));
            for(var child:captured){var actual=ProcessHandle.of(child.pid());assertTrue(actual.isEmpty()||!actual.get().isAlive()||!actual.get().info().startInstant().orElseThrow().equals(child.started()));}
        }finally{
            for(var root:List.of(first,sibling)){try(var children=root.toHandle().descendants()){children.forEach(child->child.destroyForcibly());}root.destroyForcibly();root.waitFor(5,java.util.concurrent.TimeUnit.SECONDS);}
        }
    }
}
