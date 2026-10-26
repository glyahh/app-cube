package com.dbts.glyahhaigeneratecode.agentauth;

import com.dbts.glyahhaigeneratecode.config.WorkspaceProperties;
import com.dbts.glyahhaigeneratecode.service.support.WorkspacePathResolver;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/** 回滚真实行为：新增删除、修改还原、删除重建、超限与越界拒绝。 */
class AgentChangeRollbackServiceTest {

    /** Map.of 不接受 null 值，而 before_blob 需要可为空。 */
    private static Map<String, Object> row(String path, String change, String before, boolean truncated) {
        var row = new HashMap<String, Object>();
        row.put("path", path);
        row.put("change", change);
        row.put("before_blob", before);
        row.put("truncated", truncated);
        return row;
    }

    private AgentChangeRollbackService service(Path root, List<Map<String, Object>> plan) {
        var ledger = mock(AgentChangeLedgerRepository.class);
        when(ledger.rollbackPlan(any())).thenReturn(plan);
        var sessions = mock(AgentEditSessionRepository.class);
        when(sessions.require(any(), anyLong())).thenReturn(
                new AgentEditSessionRepository.Session(UUID.randomUUID(), 1L, 2L, 3L,
                        "0".repeat(64), "WORKSPACE_WRITE", "MANUAL", 0,
                        UUID.randomUUID(), UUID.randomUUID(), "EDITING",
                        java.time.Instant.now().plusSeconds(600), false));
        var paths = mock(WorkspacePathResolver.class);
        when(paths.outputDir(anyLong(), any())).thenReturn(root);
        return new AgentChangeRollbackService(ledger, sessions, paths, new WorkspaceProperties());
    }

    @Test
    void addedFileIsDeleted(@TempDir Path temp) throws Exception {
        Path root = temp.resolve("project_2");
        Files.createDirectories(root);
        Files.writeString(root.resolve("added.txt"), "new");
        var plan = List.of(row("added.txt", "ADDED", null, false));

        int count = service(root, plan).rollback(1L, UUID.randomUUID(), 2L);

        assertEquals(1, count);
        assertFalse(Files.exists(root.resolve("added.txt")));
    }

    @Test
    void modifiedFileRestored(@TempDir Path temp) throws Exception {
        Path root = temp.resolve("project_2");
        Files.createDirectories(root);
        Files.writeString(root.resolve("mod.txt"), "after");
        var plan = List.of(row("mod.txt", "MODIFIED", "before", false));

        service(root, plan).rollback(1L, UUID.randomUUID(), 2L);

        assertEquals("before", Files.readString(root.resolve("mod.txt")));
    }

    @Test
    void deletedFileRecreated(@TempDir Path temp) throws Exception {
        Path root = temp.resolve("project_2");
        Files.createDirectories(root);
        var plan = List.of(row("sub/del.txt", "DELETED", "original", false));

        service(root, plan).rollback(1L, UUID.randomUUID(), 2L);

        assertEquals("original", Files.readString(root.resolve("sub/del.txt")));
    }

    @Test
    void truncatedChangeRefusesRollback(@TempDir Path temp) throws Exception {
        Path root = temp.resolve("project_2");
        Files.createDirectories(root);
        var plan = List.of(row("big.bin", "MODIFIED", null, true));

        // 没保留正文就不能假装回滚成功。
        assertThrows(com.dbts.glyahhaigeneratecode.exception.MyException.class,
                () -> service(root, plan).rollback(1L, UUID.randomUUID(), 2L));
    }

    @Test
    void pathEscapeRejected(@TempDir Path temp) {
        Path root = temp.resolve("project_2");
        var plan = List.of(row("../outside.txt", "ADDED", null, false));

        assertThrows(com.dbts.glyahhaigeneratecode.exception.MyException.class,
                () -> service(root, plan).rollback(1L, UUID.randomUUID(), 2L));
    }

    @Test
    void emptyLedgerIsNotSilentSuccess(@TempDir Path temp) throws Exception {
        Path root = temp.resolve("project_2");
        Files.createDirectories(root);

        // 账本丢了不能报「回滚成功 0 个」：调用方已承诺还原，界面会显示已回滚而文件没变。
        assertThrows(com.dbts.glyahhaigeneratecode.exception.MyException.class,
                () -> service(root, List.of()).rollback(1L, UUID.randomUUID(), 2L));
    }
    @Test
    void missingOriginalWithRetainedHashIsNeverTreatedAsNew(@TempDir Path root) throws Exception {
        Files.writeString(root.resolve("existing.txt"), "latest small edit");
        var corruptedLegacyRow = row("existing.txt", "MODIFIED", null, false);
        corruptedLegacyRow.put("before_hash", "a".repeat(64));
        assertThrows(com.dbts.glyahhaigeneratecode.exception.MyException.class,
                () -> service(root, List.of(corruptedLegacyRow)).rollback(1L, UUID.randomUUID(), 2L));
        assertEquals("latest small edit", Files.readString(root.resolve("existing.txt")));
    }

    @Test
    void preflightRejectsWholeBatchWithoutDeletingEarlierNewFile(@TempDir Path root) throws Exception {
        Files.writeString(root.resolve("added.txt"), "keep me");
        Files.writeString(root.resolve("large.txt"), "latest small edit");
        var plan = List.of(row("added.txt", "ADDED", null, false),
                row("large.txt", "MODIFIED", null, true));
        assertThrows(com.dbts.glyahhaigeneratecode.exception.MyException.class,
                () -> service(root, plan).rollback(1L, UUID.randomUUID(), 2L));
        assertEquals("keep me", Files.readString(root.resolve("added.txt")));
        assertEquals("latest small edit", Files.readString(root.resolve("large.txt")));
    }

    @Test
    void fileAddedThenEditedCanStillBeRemoved(@TempDir Path root) throws Exception {
        Files.writeString(root.resolve("new.txt"), "second edit");
        var cumulativeRow = row("new.txt", "MODIFIED", null, false);
        cumulativeRow.put("before_hash", null);
        assertEquals(1, service(root, List.of(cumulativeRow)).rollback(1L, UUID.randomUUID(), 2L));
        assertFalse(Files.exists(root.resolve("new.txt")));
    }

}
