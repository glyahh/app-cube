package com.dbts.glyahhaigeneratecode.service.support;

import com.dbts.glyahhaigeneratecode.config.WorkspaceProperties;
import com.dbts.glyahhaigeneratecode.exception.MyException;
import com.dbts.glyahhaigeneratecode.model.enums.CodeGenTypeEnum;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 路径构造与越界防护。
 *
 * <p>这是安全控制，不是便利方法 —— 收敛前两个 Resource Controller 把 URL 子路径
 * 直接字符串拼接，可以读到产物目录之外的文件。所以这里的重点是**拒绝**用例。
 */
@DisplayName("产物路径解析")
class WorkspacePathResolverTest {

    @TempDir
    Path tempRoot;

    private WorkspacePathResolver resolver;

    @BeforeEach
    void setUp() {
        WorkspaceProperties props = new WorkspaceProperties();
        props.setOutputRoot(tempRoot.resolve("code_output"));
        props.setDeployRoot(tempRoot.resolve("code_deploy"));
        resolver = new WorkspacePathResolver(props, org.mockito.Mockito.mock(com.dbts.glyahhaigeneratecode.projectimport.ImportWorkspaceGate.class));
    }

    @Test
    @DisplayName("目录名：VUE 走 vue_project_、AUTO 走 project_ 前缀，其余用枚举 value")
    void dirNameUsesCorrectPrefix() {
        assertEquals("html_123", resolver.dirName(123L, CodeGenTypeEnum.HTML));
        assertEquals("multi_file_123", resolver.dirName(123L, CodeGenTypeEnum.MULTI_FILE));
        // VUE 的产物目录一直是 vue_project_，与枚举 value("vue") 不一致，是历史约定
        assertEquals("vue_project_123", resolver.dirName(123L, CodeGenTypeEnum.VUE));
        // AUTO 的目录名与语言无关，不再编码「是哪一种生成模式」
        assertEquals("project_123", resolver.dirName(123L, CodeGenTypeEnum.AUTO));
    }

    @Test
    @DisplayName("目录名参数不合法时拒绝")
    void dirNameRejectsBadInput() {
        assertThrows(MyException.class, () -> resolver.dirName(null, CodeGenTypeEnum.HTML));
        assertThrows(MyException.class, () -> resolver.dirName(0L, CodeGenTypeEnum.HTML));
        assertThrows(MyException.class, () -> resolver.dirName(-1L, CodeGenTypeEnum.HTML));
        assertThrows(MyException.class, () -> resolver.dirName(1L, null));
    }

    @Test
    @DisplayName("正常路径解析到产物目录下")
    void resolvesNormalPath() {
        Path dir = resolver.outputDir(123L, CodeGenTypeEnum.HTML);
        assertTrue(dir.startsWith(tempRoot.resolve("code_output").toAbsolutePath()));

        Path file = resolver.resolveOutputResource("html_123", "/index.html");
        assertTrue(file.startsWith(tempRoot.resolve("code_output").toAbsolutePath()));
        assertTrue(file.endsWith("index.html"));
    }

    @Test
    @DisplayName("资源路径为空或裸斜杠时落在目录本身")
    void emptyResourcePathLandsOnDir() {
        Path dir = resolver.resolveOutputResource("html_123", "/");
        assertEquals(tempRoot.resolve("code_output").resolve("html_123").toAbsolutePath().normalize(), dir);
    }

    @Test
    @DisplayName("产物根上级的目录联接不拦截普通预览")
    void junctionAboveOutputRootStillResolves() throws Exception {
        Path realParent = tempRoot.resolve("real-parent");
        Path file = realParent.resolve("code_output").resolve("html_123").resolve("index.html");
        Files.createDirectories(file.getParent());
        Files.writeString(file, "ok");
        Path linkParent = tempRoot.resolve("link-parent");
        assertEquals(0, junction(linkParent, realParent));
        WorkspaceProperties props = new WorkspaceProperties();
        props.setOutputRoot(linkParent.resolve("code_output"));
        props.setDeployRoot(tempRoot.resolve("code_deploy"));
        WorkspacePathResolver throughLink = new WorkspacePathResolver(props,
                org.mockito.Mockito.mock(com.dbts.glyahhaigeneratecode.projectimport.ImportWorkspaceGate.class));
        Path resolved = throughLink.resolveOutputResource("html_123", "index.html");
        assertTrue(Files.isSameFile(resolved, file));
    }

    @Test
    @DisplayName("工作区内部的目录联接不能解析到目录外")
    void rejectsJunctionInsideWorkspace() throws Exception {
        Path outside = tempRoot.resolve("outside");
        Files.createDirectories(outside);
        Files.writeString(outside.resolve("secret.txt"), "secret");
        Path link = tempRoot.resolve("code_output").resolve("html_123").resolve("linked");
        Files.createDirectories(link.getParent());
        assertEquals(0, junction(link, outside));
        assertThrows(MyException.class, () -> resolver.resolveOutputResource("html_123", "linked/secret.txt"));
    }

    private static int junction(Path link, Path target) throws Exception {
        Process process = new ProcessBuilder("cmd", "/c", "mklink", "/J", link.toString(), target.toString()).start();
        return process.waitFor();
    }

    @Test
    @DisplayName("工作区内部的符号链接不能解析到目录外")
    void rejectsSymlinkInsideWorkspace() throws Exception {
        Path outside = tempRoot.resolve("secret.txt");
        Files.writeString(outside, "secret");
        Path link = tempRoot.resolve("code_output").resolve("html_123").resolve("link.txt");
        Files.createDirectories(link.getParent());
        try {
            Files.createSymbolicLink(link, outside);
        } catch (Exception error) {
            org.junit.jupiter.api.Assumptions.assumeTrue(false, error.getMessage());
        }
        assertThrows(MyException.class, () -> resolver.resolveOutputResource("html_123", "link.txt"));
    }

    @Test
    @DisplayName("产物根下的普通文件可以解析，检查不到产物根的上级")
    void normalFileUnderOutputRootResolves() throws Exception {
        Path file = tempRoot.resolve("code_output").resolve("html_123").resolve("index.html");
        Files.createDirectories(file.getParent());
        Files.writeString(file, "ok");
        ProjectImportStaging.guardInside(tempRoot.resolve("code_output"), file);
        Path resolved = resolver.resolveOutputResource("html_123", "index.html");
        assertTrue(resolved.endsWith("index.html"));
        assertTrue(Files.isSameFile(resolved, file));
    }

    @Test
    @DisplayName("拒绝 ../ 穿越")
    void rejectsParentTraversal() {
        assertThrows(MyException.class,
                () -> resolver.resolveOutputResource("html_123", "/../../../etc/passwd"));
        assertThrows(MyException.class,
                () -> resolver.resolveOutputResource("../../../etc", "/passwd"));
    }

    @Test
    @DisplayName("拒绝反斜杠穿越（Windows 分隔符）")
    void rejectsBackslashTraversal() {
        assertThrows(MyException.class,
                () -> resolver.resolveOutputResource("html_123", "\\..\\..\\windows\\system32\\config"));
        assertThrows(MyException.class,
                () -> resolver.resolveOutputResource("..\\..\\secret", "/x"));
    }

    @Test
    @DisplayName("拒绝绝对路径注入 —— Path.resolve 遇到绝对路径会替换掉根")
    void rejectsAbsolutePathInjection() {
        // 这是最容易被忽略的一种：resolve("/etc/passwd") 的结果是 /etc/passwd 本身
        assertThrows(MyException.class,
                () -> resolver.resolveOutputResource("/etc/passwd", ""));
        assertThrows(MyException.class,
                () -> resolver.resolveDeployResource("C:/Windows/System32", "/config/sam"));
    }

    @Test
    @DisplayName("同级同前缀目录不被误放行")
    void siblingWithSharedPrefixIsRejected() {
        // code_output_backup 与 code_output 的**字符串前缀**相同，但按路径元素比是不同的目录。
        // 用字符串 startsWith 实现守卫会在这一条上放行，必须用 Path.startsWith。
        MyException ex = assertThrows(MyException.class,
                () -> resolver.resolveOutputResource("../code_output_backup", "/x.js"));
        assertEquals("路径越界", ex.getMessage());
    }

    @Test
    @DisplayName("深层子目录可以正常访问")
    void allowsDeepSubdirectories() {
        Path file = resolver.resolveDeployResource("gOGMr8S8", "/assets/js/index-0147ab11.js");
        assertTrue(file.startsWith(tempRoot.resolve("code_deploy").toAbsolutePath()));
        assertTrue(file.endsWith("index-0147ab11.js"));
    }

    @Test
    @DisplayName("发布目录与产物目录互不越界")
    void deployAndOutputRootsAreIsolated() {
        // 从 deploy 目录穿到 output 目录也不行
        assertThrows(MyException.class,
                () -> resolver.resolveDeployResource("gOGMr8S8", "/../../code_output/html_1/index.html"));
    }
}
