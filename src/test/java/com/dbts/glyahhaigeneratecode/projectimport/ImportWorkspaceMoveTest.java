package com.dbts.glyahhaigeneratecode.projectimport;

import org.junit.jupiter.api.Test;
import java.nio.file.*;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

/** 改名有界恢复只补瞬时失败，不覆盖目标、不掩盖永久失败或中断。 */
class ImportWorkspaceMoveTest {
    private final Path source=Path.of("source"), target=Path.of("target");

    @Test void 瞬时占用后重试同一个副本() throws Exception {
        try(var files=mockStatic(Files.class)) {
            files.when(()->Files.move(source,target)).thenThrow(new AccessDeniedException("fixture")).thenReturn(target);
            ImportTaskService.moveWorkspace(source,target);
            files.verify(()->Files.move(source,target),times(2));
        }
    }

    @Test void 已存在目标不得重试或覆盖() {
        try(var files=mockStatic(Files.class)) {
            files.when(()->Files.move(source,target)).thenThrow(new FileAlreadyExistsException("fixture"));
            assertThrows(FileAlreadyExistsException.class,()->ImportTaskService.moveWorkspace(source,target));
            files.verify(()->Files.move(source,target),times(1));
        }
    }

    @Test void 永久失败只能尝试四次() {
        try(var files=mockStatic(Files.class)) {
            files.when(()->Files.move(source,target)).thenThrow(new AccessDeniedException("fixture"));
            assertThrows(AccessDeniedException.class,()->ImportTaskService.moveWorkspace(source,target));
            files.verify(()->Files.move(source,target),times(4));
        }
    }

    @Test void 中断保留资格撤销信号不继续重试() {
        try(var files=mockStatic(Files.class)) {
            files.when(()->Files.move(source,target)).thenThrow(new AccessDeniedException("fixture"));
            Thread.currentThread().interrupt();
            try {
                assertThrows(AccessDeniedException.class,()->ImportTaskService.moveWorkspace(source,target));
                assertTrue(Thread.currentThread().isInterrupted());
                files.verify(()->Files.move(source,target),times(1));
            } finally {Thread.interrupted();}
        }
    }
}
