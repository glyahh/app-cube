package com.dbts.glyahhaigeneratecode.service.support;

import cn.hutool.core.util.RandomUtil;
import cn.hutool.crypto.digest.DigestUtil;
import cn.hutool.core.util.StrUtil;
import com.dbts.glyahhaigeneratecode.config.WorkspaceProperties;
import com.dbts.glyahhaigeneratecode.config.RuntimeProperties;
import com.dbts.glyahhaigeneratecode.knowledge.KnowledgeTaskService;
import com.dbts.glyahhaigeneratecode.runtime.DockerCli;
import com.dbts.glyahhaigeneratecode.runtime.RuntimeLogService;
import com.dbts.glyahhaigeneratecode.exception.ErrorCode;
import com.dbts.glyahhaigeneratecode.exception.MyException;
import com.dbts.glyahhaigeneratecode.mapper.AppMapper;
import com.dbts.glyahhaigeneratecode.model.Entity.App;
import com.dbts.glyahhaigeneratecode.model.VO.ProjectFileVO;
import com.dbts.glyahhaigeneratecode.model.enums.CodeGenTypeEnum;
import com.mybatisflex.core.query.QueryWrapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Set;
import java.util.stream.Stream;

/**
 * 应用服务辅助类：code_output 目录清理、项目文件枚举、deployKey 生成、生成产物判定。
 *
 * @author <a href="https://github.com/glyahh">glyahh</a>
 */
@Component
@RequiredArgsConstructor
@Slf4j
public class AppServiceSupport {

    private final AppMapper appMapper;
    private final WorkspaceProperties workspaceProperties;
    private final WorkspacePathResolver workspacePathResolver;
    private final DockerCli dockerCli;
    private final RuntimeProperties runtimeProperties;
    private final RuntimeLogService runtimeLogService;
    private final KnowledgeTaskService knowledgeTaskService;

    /**
     * 解析应用声明的 codeGenType。
     *
     * <p>「按 value 查，查不到再按枚举名查」这段原先在三个地方各写了一遍。
     * 收敛到一处，避免某处漏掉兜底分支而静默返回 null。
     */
    private CodeGenTypeEnum resolveType(String codeGenType) {
        CodeGenTypeEnum type = CodeGenTypeEnum.getEnumByValue(codeGenType);
        if (type == null && StrUtil.isNotBlank(codeGenType)) {
            try {
                type = CodeGenTypeEnum.valueOf(codeGenType);
            } catch (IllegalArgumentException ignored) {
                // 落到调用方的 null 判断
            }
        }
        return type;
    }


    /**
     * 停止并删除应用残留的运行资源与知识痕迹。
     *
     * <p>删除应用时必须调它：
     * <ul>
     *   <li>运行容器不清理会继续占着端口，而用户已经没有入口去停它；</li>
     *   <li>知识任务不取消会继续跑完，然后写回一堆属于已删除应用的数据。</li>
     * </ul>
     */
    public void removeAppArtifacts(Long appId) {
        if (appId == null) {
            return;
        }
        try {
            if (dockerCli.available()) {
                dockerCli.run(List.of("rm", "-f", "glyahh-rt-" + appId), 60);
            }
        } catch (Exception e) {
            // 容器清理失败不该阻断删除流程，留日志即可
            log.warn("清理应用运行容器失败, appId={}", appId, e);
        }
        try {
            runtimeLogService.removeAppLogs(appId);
        } catch (Exception e) {
            log.warn("清理应用运行日志失败, appId={}", appId, e);
        }
        try {
            knowledgeTaskService.purgeApp(appId);
        } catch (Exception e) {
            log.warn("清理应用知识数据失败, appId={}", appId, e);
        }
    }


    /**
     * 按 appId 删除本地 code_output 下对应应用目录（失败不影响主流程）
     *
     * @param appId 应用 id
     */
    public void removeCodeOutputDirByAppId(Long appId) {
        Path codeOutputRootPath = workspaceProperties.getOutputRoot();
        if (!Files.isDirectory(codeOutputRootPath)) {
            return;
        }
        String suffix = "_" + appId;
        try (Stream<Path> stream = Files.list(codeOutputRootPath)) {
            stream.filter(Files::isDirectory)
                    .filter(path -> {
                        String dirName = path.getFileName().toString();
                        return dirName.endsWith(suffix) || dirName.endsWith("_project_" + appId);
                    })
                    .forEach(this::deleteDirectoryQuietly);
        } catch (Exception e) {
            log.warn("扫描 code_output 目录失败, appId={}", appId, e);
        }
    }


    /**
     * 递归删除目录，单文件失败仅打日志不中断
     *
     * @param dir 待删除目录
     */
    public void deleteDirectoryQuietly(Path dir) {
        try (Stream<Path> pathStream = Files.walk(dir)) {
            pathStream.sorted((a, b) -> b.getNameCount() - a.getNameCount())
                    .forEach(path -> {
                        try {
                            Files.deleteIfExists(path);
                        } catch (IOException e) {
                            log.warn("删除目录内容失败, path={}", path, e);
                        }
                    });
        } catch (Exception e) {
            log.warn("删除应用目录失败, dir={}", dir, e);
        }
    }


    /** 平台写进工作区的运行时目录：沙箱启动脚本 {@code runner.sh} 就放在这里。 */
    public static final String PLATFORM_DIR = ".glyahh";

    /** agent 工具输出的溢写目录：属于执行过程的中间产物，不是用户的代码。 */
    public static final String AGENT_SCRATCH_DIR = ".scratch";


    /** 需忽略的目录/文件名集合（与前端 SNAPSHOT_IGNORE_DIRS 保持一致） */
    private static final Set<String> IGNORE_DIRS = Set.of(
            "node_modules", ".git", "dist", "target", "temp", "build",
            "coverage", ".idea", ".vscode", PLATFORM_DIR, AGENT_SCRATCH_DIR
    );


    /** 文件查看与版本化保存共用的文本扩展名；编译产物不开放文本编辑。 */
    private static final Set<String> TEXT_FILE_EXTS = Set.of(
            "java", "kt", "js", "ts", "tsx", "jsx", "vue", "html", "htm",
            "css", "scss", "less", "json", "yaml", "yml", "xml", "md",
            "txt", "properties", "sql", "sh", "bat", "ps1", "py", "toml"
    );


    /**
     * 判断文件路径是否在忽略目录下
     *
     * @param filePath    文件路径
     * @param projectRoot 项目根目录
     * @return 在忽略目录下返回 true
     */
    public boolean isIgnoredDir(Path filePath, Path projectRoot) {
        Path relative = projectRoot.relativize(filePath);
        for (Path part : relative) {
            if (IGNORE_DIRS.contains(part.toString().toLowerCase())) {
                return true;
            }
        }
        return false;
    }


    /**
     * 判断文件是否为文本代码文件（基于扩展名白名单）
     *
     * @param filePath 文件路径
     * @return 属于白名单扩展名返回 true
     */
    public boolean isTextCodeFile(Path filePath) {
        String fileName = filePath.getFileName().toString();
        int dotIdx = fileName.lastIndexOf('.');
        if (dotIdx < 0 || dotIdx == fileName.length() - 1) {
            return false;
        }
        String ext = fileName.substring(dotIdx + 1).toLowerCase();
        return TEXT_FILE_EXTS.contains(ext);
    }


    /**
     * 根据文件路径推断代码语言标识
     *
     * @param path 相对路径
     * @return 语言标识
     */
    public String inferLanguage(String path) {
        String lower = (path != null ? path : "").toLowerCase();
        if (lower.endsWith(".vue")) return "vue";
        if (lower.endsWith(".ts") || lower.endsWith(".tsx")) return "typescript";
        if (lower.endsWith(".js") || lower.endsWith(".jsx")) return "javascript";
        if (lower.endsWith(".css") || lower.endsWith(".scss") || lower.endsWith(".less")) return "css";
        if (lower.endsWith(".html") || lower.endsWith(".htm")) return "html";
        if (lower.endsWith(".py")) return "python";
        if (lower.endsWith(".java")) return "java";
        if (lower.endsWith(".toml")) return "toml";
        return "text";
    }


    /**
     * 遍历项目根目录，收集文本代码文件列表
     *
     * @param projectRoot 项目根目录
     * @return 项目文件 VO 列表
     */
    public List<ProjectFileVO> collectProjectFiles(Path projectRoot) {
        List<ProjectFileVO> files = new java.util.ArrayList<>();
        try (Stream<Path> pathStream = Files.walk(projectRoot)) {
            pathStream.filter(Files::isRegularFile)
                    .filter(p -> !isIgnoredDir(p, projectRoot))
                    .filter(p -> isTextCodeFile(p))
                    .forEach(p -> {
                        try {
                            String relativePath = projectRoot.relativize(p).toString()
                                    .replace('\\', '/');
                            String language = inferLanguage(relativePath);
                            String content = Files.readString(p);
                            long updatedAt = Files.getLastModifiedTime(p).toMillis();

                            ProjectFileVO vo = new ProjectFileVO();
                            vo.setPath(relativePath);
                            vo.setLanguage(language);
                            vo.setContent(content);
                            vo.setUpdatedAt(updatedAt);
                            files.add(vo);
                        } catch (IOException e) {
                            log.warn("读取项目文件失败, path={}", p, e);
                        }
                    });
        } catch (IOException e) {
            log.warn("遍历项目目录失败, root={}", projectRoot, e);
            return List.of();
        }

        files.sort(java.util.Comparator.comparing(ProjectFileVO::getPath));
        return files;
    }


    /**
     * 判断应用是否已有生成代码（code_output 下存在对应目录及 index.html）
     *
     * @param app 应用实体
     * @return 已有 index.html 返回 true
     */
    public boolean hasGeneratedCode(App app) {
        if (app == null || app.getId() == null) {
            return false;
        }
        CodeGenTypeEnum type = resolveType(app.getCodeGenType());
        if (type == null) {
            return false;
        }
        // 必须走 resolver：这里原先手拼 "value + _ + appId"，对 VUE 会拼成 vue_{appId}，
        // 而产物目录实际是 vue_project_{appId} —— 于是 VUE 应用永远被判为「没生成过代码」，
        // 前端据此每次都弹「分析代码 ing~」。目录名只该由 getDirPrefix() 决定。
        Path sourceDir = workspacePathResolver.outputDir(app.getId(), type);
        if (!Files.isDirectory(sourceDir)) {
            return false;
        }
        return Files.isRegularFile(sourceDir.resolve("index.html"));
    }


    /**
     * 识别可直接用静态资源方式打开的入口文件。
     *
     * <p>不能只看扩展名是不是 .html：Vite 工程根目录的 index.html 是构建模板
     * （引用 /src/main.ts），直接打开只会白屏，必须用构建产物 {@code dist/index.html}。
     * 所以先看 dist，再判断根 index.html 是否属于「需要构建的工程」。
     *
     * @param projectRoot 产物目录
     * @return 相对产物目录的入口路径；没有可用静态入口时返回 null
     */
    public String detectStaticEntry(Path projectRoot) {
        if (projectRoot == null) {
            return null;
        }
        Path distIndex = projectRoot.resolve("dist").resolve("index.html");
        if (Files.isRegularFile(distIndex)) {
            return "dist/index.html";
        }
        if (!Files.isRegularFile(projectRoot.resolve("index.html"))) {
            return null;
        }
        // 有 package.json 说明这是需要安装依赖/构建的工程，根 index.html 只是源码模板
        if (Files.isRegularFile(projectRoot.resolve("package.json"))) {
            return null;
        }
        return "index.html";
    }


    /**
     * 文件树里隐藏的条目名（小写比较）。
     *
     * <p>这里放的是**平台与工具自己写进工作区**的东西，不属于用户的代码：
     * <ul>
     *   <li>{@code .git} —— 版本控制内部目录，没有展示与编辑价值，且可能有几十万个文件；</li>
     *   <li>{@code .glyahh} —— 运行沙箱的 {@code runner.sh}，由平台每次启动覆盖写入；</li>
     *   <li>{@code .scratch} —— agent 工具输出的溢写目录。</li>
     * </ul>
     *
     * <p>注意这与 {@link #IGNORE_DIRS} 不同 —— 那是「文件回显不展示」的清单，
     * 而文件树要按计划把 node_modules / dist 这类大目录**显示出来但默认折叠**。
     */
    private static final Set<String> TREE_HIDDEN_NAMES =
            Set.of(".git", PLATFORM_DIR, AGENT_SCRATCH_DIR);


    /**
     * 文件树里是否隐藏该条目。
     *
     * @param path 工作区下的某个条目
     * @return 应隐藏返回 true
     */
    public boolean isHiddenFromTree(Path path) {
        if (path == null || path.getFileName() == null) {
            return true;
        }
        return TREE_HIDDEN_NAMES.contains(path.getFileName().toString().toLowerCase());
    }


    /**
     * 相对路径里是否含有平台内部目录。
     *
     * <p>文件树是一层层展开的，看文件名就够；但读文件、下载拿到的是完整相对路径，
     * 只看最后一段会漏掉 {@code .glyahh/runner.sh} 这种文件名正常的，所以逐段比。
     *
     * @param relativePath 工作区内的相对路径
     * @return 命中平台内部目录返回 true
     */
    public boolean isPlatformInternalPath(String relativePath) {
        if (relativePath == null || relativePath.isEmpty()) {
            return false;
        }
        for (String part : relativePath.replace('\\', '/').split("/")) {
            if (TREE_HIDDEN_NAMES.contains(part.toLowerCase())) {
                return true;
            }
        }
        return false;
    }


    /**
     * 计算文件的内容版本标识，作为保存时的并发校验基准。
     *
     * <p>不用 lastModified：同一毫秒内连续两次写入会得到相同的时间戳，
     * 「保存后立刻再保存」的校验会失效。内容摘要没有这个问题。
     *
     * @param file 文本文件
     * @return 内容摘要的前 16 位十六进制
     */
    public String contentVersion(Path file) {
        try {
            return DigestUtil.sha256Hex(Files.readString(file)).substring(0, 16);
        } catch (IOException e) {
            log.warn("计算文件内容版本失败, path={}", file, e);
            throw new MyException(ErrorCode.SYSTEM_ERROR, "读取文件内容失败");
        }
    }


    /**
     * 生成数据库中不重复的 deployKey（8 位字母数字）
     *
     * @return 唯一 deployKey
     */
    public String generateUniqueDeployKey() {
        // 尝试多次避免极小概率碰撞
        for (int i = 0; i < 10; i++) {
            String candidate = RandomUtil.randomString("abcdefghijklmnopqrstuvwxyzABCDEFGHIJKLMNOPQRSTUVWXYZ0123456789", 8);
            QueryWrapper queryWrapper = new QueryWrapper();
            queryWrapper.eq(App::getDeployKey, candidate);
            long count = appMapper.selectCountByQuery(queryWrapper);
            if (count == 0) {
                return candidate;
            }
        }
        throw new MyException(ErrorCode.SYSTEM_ERROR, "生成 deployKey 失败，请重试");
    }

}
