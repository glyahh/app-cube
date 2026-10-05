package com.dbts.glyahhaigeneratecode.service.support;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 静态入口识别。
 *
 * <p>背景：前端原先按 codeGenType 决定预览入口 —— 只要类型是 vue 就固定去取
 * {@code dist/index.html}。一次纯静态页面需求被关键词路由判成 vue 之后，产物目录里
 * 只有根 index.html，预览指向一个不存在的文件，页面空白。
 *
 * <p>所以这里的重点是两件事：产物里确实有 dist 才用 dist；以及**不能**因为后缀是
 * .html 就把未构建的 Vite 模板当成可预览入口。
 */
@DisplayName("静态入口识别")
class AppServiceSupportTest {

    @TempDir
    Path projectRoot;

    /**
     * detectStaticEntry 不依赖注入的协作者，构造时传 null 即可。
     *
     * <p>参数顺序：appMapper、workspaceProperties、workspacePathResolver、
     * dockerCli、runtimeProperties、runtimeLogService、knowledgeTaskService。
     */
    private final AppServiceSupport support =
            new AppServiceSupport(null, null, null, null, null, null, null);

    @Test
    void importedPythonSourcesAreEditableButArtifactsRemainBinary() {
        assertTrue(support.isTextCodeFile(Path.of("app.py")));
        assertTrue(support.isTextCodeFile(Path.of("APP.PY")));
        assertTrue(support.isTextCodeFile(Path.of("pyproject.toml")));
        assertFalse(support.isTextCodeFile(Path.of("module.pyc")));
        assertFalse(support.isTextCodeFile(Path.of("app.jar")));
        assertFalse(support.isTextCodeFile(Path.of("Main.class")));
        assertEquals("python", support.inferLanguage("app.py"));
        assertEquals("java", support.inferLanguage("Main.java"));
        assertEquals("toml", support.inferLanguage("pyproject.toml"));
    }

    @Test
    @DisplayName("有构建产物时优先用 dist/index.html")
    void prefersDistIndex() throws IOException {
        writeFile("index.html", "<script type=\"module\" src=\"/src/main.ts\"></script>");
        writeFile("package.json", "{\"name\":\"demo\"}");
        writeFile("dist/index.html", "<div id=\"app\"></div>");

        assertEquals("dist/index.html", support.detectStaticEntry(projectRoot));
    }

    @Test
    @DisplayName("只有根 index.html 的静态页面，直接作为入口")
    void fallsBackToRootIndexForStaticPage() throws IOException {
        // 这就是那次错配的实际形态：目录里只有 index.html，没有任何工程文件
        writeFile("index.html", "<!DOCTYPE html><html><body>hello</body></html>");

        assertEquals("index.html", support.detectStaticEntry(projectRoot));
    }

    @Test
    @DisplayName("未构建的 Vite 工程：根 index.html 不算入口")
    void rejectsUnbuiltViteTemplate() throws IOException {
        // 后缀是 .html，但它是构建模板，直接打开只会白屏
        writeFile("index.html", "<script type=\"module\" src=\"/src/main.ts\"></script>");
        writeFile("package.json", "{\"name\":\"demo\"}");

        assertNull(support.detectStaticEntry(projectRoot));
    }

    @Test
    @DisplayName("目录下没有 index.html 时返回 null")
    void returnsNullWhenNoIndex() throws IOException {
        writeFile("src/main.ts", "console.log(1)");

        assertNull(support.detectStaticEntry(projectRoot));
    }

    @Test
    @DisplayName("入参为 null 时不抛异常")
    void nullRootIsSafe() {
        assertNull(support.detectStaticEntry(null));
    }

    @Test
    @DisplayName("文件树只隐藏版本控制内部目录")
    void hidesOnlyVcsDirectories() {
        assertTrue(support.isHiddenFromTree(Paths.get("/ws/.git")));
        assertTrue(support.isHiddenFromTree(Paths.get("/ws/.GIT")));
        // node_modules / dist 要显示出来（默认折叠交给前端），不能在这里就过滤掉，
        // 否则用户永远看不到依赖与构建产物
        assertFalse(support.isHiddenFromTree(Paths.get("/ws/node_modules")));
        assertFalse(support.isHiddenFromTree(Paths.get("/ws/dist")));
        assertFalse(support.isHiddenFromTree(Paths.get("/ws/src")));
    }

    @Test
    @DisplayName("内容版本：同内容稳定，内容变了就变")
    void contentVersionTracksContent() throws IOException {
        writeFile("a.txt", "hello");
        writeFile("b.txt", "hello");
        writeFile("c.txt", "hello!");

        String versionA = support.contentVersion(projectRoot.resolve("a.txt"));
        String versionB = support.contentVersion(projectRoot.resolve("b.txt"));
        String versionC = support.contentVersion(projectRoot.resolve("c.txt"));

        assertEquals(versionA, versionB);
        assertNotEquals(versionA, versionC);
    }

    private void writeFile(String relative, String content) throws IOException {
        Path target = projectRoot.resolve(relative);
        Files.createDirectories(target.getParent());
        Files.writeString(target, content);
    }
}
