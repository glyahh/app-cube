package com.dbts.glyahhaigeneratecode.projectimport;

import com.dbts.glyahhaigeneratecode.agent.AppRunCoordinator;
import com.dbts.glyahhaigeneratecode.exception.MyException;
import com.dbts.glyahhaigeneratecode.runtime.DockerCli;
import org.junit.jupiter.api.Test;
import reactor.core.publisher.Flux;

import java.util.List;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

class ImportAgentCleanupTest {
    final ImportTaskRepository tasks = mock(ImportTaskRepository.class);
    final DockerCli docker = mock(DockerCli.class);
    final AppRunCoordinator runs = new AppRunCoordinator();
    final ImportAgentCleanup cleanup = new ImportAgentCleanup(tasks, runs, docker, org.mockito.Mockito.mock(ImportAgentPublication.class));
    final String id = "abcdef012345";

    void imported() { when(tasks.publicationByApp(42)).thenReturn(mock(ImportTaskRepository.Publication.class)); }
    DockerCli.Result ok(String value) { return new DockerCli.Result(true, 0, value, ""); }
    List<String> list() { return List.of("ps", "-a", "--filter", "label=glyahh.session_id=app:42", "--format", "{{.ID}}"); }

    @Test void 非导入应用不调用导入清理() {
        cleanup.release(42);
        assertEquals("done", cleanup.whenIdle(42, () -> "done"));
        verifyNoInteractions(docker);
    }

    @Test void 仅删除身份相同的会话并核实消失() {
        imported();
        when(docker.run(list(), 30)).thenReturn(ok(id + "\n"), ok(""));
        when(docker.run(List.of("inspect", "--format", "{{json .Config.Labels}}", id), 30)).thenReturn(ok("{\"glyahh.session_id\":\"app:42\"}"));
        when(docker.run(List.of("rm", "-f", id), 60)).thenReturn(ok(id));
        cleanup.release(42);
        verify(docker).run(List.of("rm", "-f", id), 60);
        verify(docker, times(2)).run(list(), 30);
    }

    @Test void 无法查询不能伪装为没有容器() {
        imported();
        when(docker.run(list(), 30)).thenReturn(new DockerCli.Result(false, 124, "", "timeout"));
        assertThrows(MyException.class, () -> cleanup.release(42));
        verify(docker, never()).run(argThat(args -> args.getFirst().equals("rm")), anyInt());
    }

    @Test void 身份错配不删除其他应用() {
        imported();
        when(docker.run(list(), 30)).thenReturn(ok(id));
        when(docker.run(List.of("inspect", "--format", "{{json .Config.Labels}}", id), 30)).thenReturn(ok("{\"glyahh.session_id\":\"app:43\"}"));
        assertThrows(MyException.class, () -> cleanup.release(42));
        verify(docker, never()).run(argThat(args -> args.getFirst().equals("rm")), anyInt());
    }

    @Test void 删除后仍有残留不能宣称成功() {
        imported();
        when(docker.run(list(), 30)).thenReturn(ok(id), ok(id));
        when(docker.run(List.of("inspect", "--format", "{{json .Config.Labels}}", id), 30)).thenReturn(ok("{\"glyahh.session_id\":\"app:42\"}"));
        when(docker.run(List.of("rm", "-f", id), 60)).thenReturn(ok(id));
        assertThrows(MyException.class, () -> cleanup.release(42));
    }

    @Test void 编码进行中拒绝删除且不会调用删除动作() {
        imported();
        var active = runs.serialize(42L, () -> Flux.never()).subscribe();
        try {
            assertThrows(MyException.class, () -> cleanup.whenIdle(42, () -> { fail("不能删除"); return null; }));
        } finally { active.dispose(); }
        assertEquals("done", cleanup.whenIdle(42, () -> "done"));
    }

    @Test void 删除动作失败归还同一应用许可() {
        imported();
        assertThrows(IllegalStateException.class, () -> cleanup.whenIdle(42, () -> { throw new IllegalStateException(); }));
        assertEquals("again", cleanup.whenIdle(42, () -> "again"));
    }

    @Test void 标签格式错误保留容器并拒绝删除() {
        imported();
        when(docker.run(list(), 30)).thenReturn(ok(id));
        when(docker.run(List.of("inspect", "--format", "{{json .Config.Labels}}", id), 30)).thenReturn(ok("broken-json"));
        assertThrows(MyException.class, () -> cleanup.release(42));
        verify(docker, never()).run(argThat(args -> args.getFirst().equals("rm")), anyInt());
    }
}
