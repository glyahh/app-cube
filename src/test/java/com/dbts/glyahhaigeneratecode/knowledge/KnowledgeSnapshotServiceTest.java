package com.dbts.glyahhaigeneratecode.knowledge;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Set;
import java.util.stream.Collectors;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 知识快照的收集与排除边界。
 *
 * <p>这是「什么内容可以进模型上下文」的关口。凭据、数据库文件、依赖目录一旦进了快照，
 * 就不是删掉本地文件能收回的了 —— 所以这里逐个锁住排除规则。
 */
@DisplayName("知识快照收集")
class KnowledgeSnapshotServiceTest {

    @TempDir
    Path workspace;

    /** 收集逻辑不碰数据库，mapper 传 null 即可。 */
    private final KnowledgeSnapshotService service = new KnowledgeSnapshotService(null,
            new com.dbts.glyahhaigeneratecode.config.WorkspaceProperties());

    @Test
    void snapshotKeepsOriginalSourceAfterWorkspaceChanges() throws IOException {
        var mapper = org.mockito.Mockito.mock(
                com.dbts.glyahhaigeneratecode.mapper.KnowledgeSnapshotMapper.class);
        var properties = new com.dbts.glyahhaigeneratecode.config.WorkspaceProperties();
        properties.setOutputRoot(workspace.resolve("output"));
        var snapshots = new KnowledgeSnapshotService(mapper, properties);
        Path project = Files.createDirectory(workspace.resolve("project"));
        Files.writeString(project.resolve("main.py"), "print('original')");
        Files.writeString(project.resolve(".env"), "TOKEN=secret");
        var first = snapshots.snapshot(1L, project);
        Path captured = snapshots.snapshotWorkspace(first, project);
        Files.writeString(project.resolve("main.py"), "print('updated')");
        var second = snapshots.snapshot(1L, project);

        assertEquals("print('original')", Files.readString(captured.resolve("main.py")));
        assertFalse(Files.exists(captured.resolve(".env")));
        assertFalse(first.getVersion().equals(second.getVersion()));
        assertEquals("print('updated')", Files.readString(
                snapshots.snapshotWorkspace(second, project).resolve("main.py")));
    }

    @Test
    void evidenceCannotReadExcludedDirectory() throws IOException {
        write("node_modules/library/index.js", "private dependency");
        assertNull(service.readFileForEvidence(workspace, "node_modules/library/index.js"));
    }

    @Test
    @DisplayName("纳入源码文件，排除凭据 / 数据库 / 依赖 / 日志 / 构建产物")
    void excludesSensitiveAndGenerated() throws IOException {
        write("src/main.js", "console.log(1)");
        write("README.md", "# demo");
        write(".env", "SECRET_TOKEN=abc");
        write(".env.production", "SECRET=abc");
        write("id_rsa", "PRIVATE KEY");
        write("app.db", "SQLite format 3");
        write("data.sqlite3", "binary");
        write("server.log", "log line");
        write("logo.png", "PNG");
        write("node_modules/pkg/index.js", "module.exports = {}");
        write("dist/bundle.js", "built");
        write("__pycache__/mod.cpython-311.pyc", "bytecode");
        write(".glyahh/runner.sh", "#!/bin/bash");

        Set<String> paths = service.collectFiles(workspace).stream()
                .map(KnowledgeSnapshotService.FileEntry::relativePath)
                .collect(Collectors.toSet());

        assertTrue(paths.contains("src/main.js"), "源码必须纳入");
        assertTrue(paths.contains("README.md"), "文档必须纳入");

        assertFalse(paths.contains(".env"), "凭据不能进快照");
        assertFalse(paths.contains(".env.production"), "带后缀的凭据也不能进");
        assertFalse(paths.contains("id_rsa"), "私钥不能进快照");
        assertFalse(paths.contains("app.db"), "SQLite 数据不能进快照");
        assertFalse(paths.contains("data.sqlite3"), "SQLite 数据不能进快照");
        assertFalse(paths.contains("server.log"), "日志不能进快照");
        assertFalse(paths.contains("logo.png"), "二进制资源不纳入");
        assertTrue(paths.stream().noneMatch(p -> p.startsWith("node_modules/")), "依赖目录不该被遍历");
        assertTrue(paths.stream().noneMatch(p -> p.startsWith("dist/")), "构建产物不该进来");
        assertTrue(paths.stream().noneMatch(p -> p.startsWith("__pycache__/")), "字节码不该进来");
        assertTrue(paths.stream().noneMatch(p -> p.startsWith(".glyahh/")), "平台自己的文件不该进来");
    }

    @Test
    @DisplayName("内容变了摘要才变：同内容不同时间戳算同一版本")
    void versionFollowsContentNotTimestamps() throws IOException {
        write("a.js", "const x = 1");
        List<KnowledgeSnapshotService.FileEntry> first = service.collectFiles(workspace);

        // 重写同样内容（修改时间会变）—— 按内容算摘要时，这不算新版本
        write("a.js", "const x = 1");
        List<KnowledgeSnapshotService.FileEntry> second = service.collectFiles(workspace);

        assertEquals(first.get(0).hash(), second.get(0).hash());
    }

    @Test
    @DisplayName("改内容会改变文件摘要")
    void contentChangeChangesHash() throws IOException {
        write("a.js", "const x = 1");
        String before = service.collectFiles(workspace).get(0).hash();

        write("a.js", "const x = 2");
        String after = service.collectFiles(workspace).get(0).hash();

        assertFalse(before.equals(after));
    }

    @Test
    @DisplayName("读证据文件同样受排除规则约束")
    void readEvidenceRespectsExclusions() throws IOException {
        write("src/main.js", "console.log(1)");
        write(".env", "SECRET=abc");

        assertNotNull(service.readFileForEvidence(workspace, "src/main.js"));
        assertNull(service.readFileForEvidence(workspace, ".env"), "凭据不该被当作证据读出");
        assertNull(service.readFileForEvidence(workspace, "../../etc/passwd"), "越界路径要拒绝");
        assertNull(service.readFileForEvidence(workspace, "node_modules/pkg/index.js"));
    }

    @Test
    @DisplayName("工作区为空时返回空清单而不是抛错")
    void emptyWorkspaceYieldsNothing() {
        assertTrue(service.collectFiles(workspace).isEmpty());
    }

    private void write(String relative, String content) throws IOException {
        Path target = workspace.resolve(relative);
        Files.createDirectories(target.getParent());
        Files.writeString(target, content);
    }
}
