package com.dbts.glyahhaigeneratecode.runtime;

import com.dbts.glyahhaigeneratecode.config.WorkspaceProperties;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.FileTime;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

/**
 * 工作区版本摘要。
 *
 * <p>它是「运行版本落后」判断的唯一依据，所以两件事必须成立：
 * 用户改了源码要变，平台自己写进去的文件不能算 —— 否则每次启动都会立刻显示「落后」。
 */
@DisplayName("工作区版本摘要")
class WorkspaceVersionerTest {

    @TempDir
    Path workspace;

    private final WorkspaceVersioner versioner = new WorkspaceVersioner(new WorkspaceProperties());

    @Test
    @DisplayName("同一份代码摘要稳定")
    void stableForUnchangedWorkspace() throws IOException {
        write("index.html", "<html></html>");
        write("src/main.js", "console.log(1)");

        assertEquals(versioner.version(workspace), versioner.version(workspace));
    }

    @Test
    @DisplayName("改了文件内容摘要就变")
    void changesWhenSourceChanges() throws IOException {
        write("index.html", "<html>a</html>");
        String before = versioner.version(workspace);

        write("index.html", "<html>bb</html>");
        assertNotEquals(before, versioner.version(workspace));
    }

    @Test
    @DisplayName("平台自己写的 .glyahh/ 不参与摘要")
    void ignoresPlatformDirectory() throws IOException {
        write("index.html", "<html></html>");
        String before = versioner.version(workspace);

        // 运行脚本每次启动都会重写；把它算进来的话，刚启动就会判定「运行版本落后」
        write(".glyahh/runner.sh", "#!/bin/bash\necho hi\n");
        assertEquals(before, versioner.version(workspace));
    }

    @Test
    @DisplayName("依赖与构建目录不参与摘要")
    void ignoresDependencyDirectories() throws IOException {
        write("index.html", "<html></html>");
        String before = versioner.version(workspace);

        write("node_modules/pkg/index.js", "module.exports = {}");
        write("dist/index.html", "<html>built</html>");
        assertEquals(before, versioner.version(workspace));
    }

    @Test
    @DisplayName("目录不存在返回 null")
    void nullForMissingWorkspace() {
        assertNull(versioner.version(workspace.resolve("nope")));
        assertNull(versioner.version(null));
    }

    @Test
    @DisplayName("只看大小与修改时间也会随新增文件变化")
    void detectsAddedFile() throws IOException {
        write("a.txt", "a");
        String before = versioner.version(workspace);

        write("b.txt", "b");
        assertNotEquals(before, versioner.version(workspace));
    }

    private void write(String relative, String content) throws IOException {
        Path target = workspace.resolve(relative);
        Files.createDirectories(target.getParent());
        Files.writeString(target, content);
        // 文件系统时间戳精度有限，显式推进一下，避免同毫秒内两次写入摘要相同
        Files.setLastModifiedTime(target, FileTime.fromMillis(System.currentTimeMillis() + 5));
    }
}
