package com.dbts.glyahhaigeneratecode.runtime;

import cn.hutool.json.JSONObject;
import cn.hutool.json.JSONUtil;
import com.dbts.glyahhaigeneratecode.config.RuntimeProperties;
import com.dbts.glyahhaigeneratecode.exception.ErrorCode;
import com.dbts.glyahhaigeneratecode.exception.MyException;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * 推断一个项目该怎么运行。
 *
 * <p>优先读工作区里的 {@code .glyahh/run.json}（AI 生成复杂项目时可以显式声明）；
 * 没有就按文件特征推断。**推断结果一律要过 {@link #validate}**，不校验就执行
 * 等于把工作区里的任意字符串当命令跑。
 *
 * <p>不追求覆盖所有情况：认不出来的项目返回 {@code UNSUPPORTED} 并说明原因，
 * 明确「不支持」比猜一个跑不起来的命令好 —— 猜错时用户看到的是莫名其妙的报错。
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class RuntimeSpecResolver {

    private final RuntimeProperties props;

    /** 声明文件的相对路径。AI 写它就能覆盖推断结果。 */
    private static final String DECLARED_SPEC_PATH = ".glyahh/run.json";

    /** 命令里禁止出现的字符：这些能拼出管道、重定向、命令串联，是注入面。 */
    private static final Pattern SHELL_METACHARACTERS = Pattern.compile("[;&|<>$`\\n\\r]");

    /** 端口必须落在容器可监听的非特权范围内。 */
    private static final int MIN_PORT = 1024;

    private static final int MAX_PORT = 65535;

    /**
     * 推断运行规格。
     *
     * @param workspace 项目工作区根目录
     * @return 运行规格；认不出来时 {@code kind = UNSUPPORTED} 且 processes 为空
     */
    public RuntimeSpec resolve(Path workspace) {
        if (workspace == null || !Files.isDirectory(workspace)) {
            return RuntimeSpec.builder()
                    .kind(RuntimeSpec.RuntimeKind.UNSUPPORTED)
                    .processes(List.of())
                    .source("workspace-missing")
                    .build();
        }

        // 1. 显式声明优先
        Path declared = workspace.resolve(DECLARED_SPEC_PATH);
        if (Files.isRegularFile(declared)) {
            RuntimeSpec spec = parseDeclared(declared);
            if (spec != null) {
                validate(spec, workspace);
                return spec;
            }
            log.warn("{} 存在但无法解析，回退到文件推断: {}", DECLARED_SPEC_PATH, workspace);
        }

        // 2. 按文件特征推断
        RuntimeSpec inferred = infer(workspace);
        validate(inferred, workspace);
        return inferred;
    }

    private RuntimeSpec parseDeclared(Path file) {
        try {
            JSONObject json = JSONUtil.parseObj(Files.readString(file));
            String kindText = json.getStr("kind", "").toUpperCase(Locale.ROOT);
            RuntimeSpec.RuntimeKind kind;
            try {
                kind = RuntimeSpec.RuntimeKind.valueOf(kindText);
            } catch (IllegalArgumentException e) {
                return null;
            }

            List<RuntimeSpec.ManagedProcess> processes = new ArrayList<>();
            for (Object item : json.getJSONArray("processes") == null
                    ? List.of() : json.getJSONArray("processes")) {
                JSONObject p = (JSONObject) item;
                processes.add(RuntimeSpec.ManagedProcess.builder()
                        .name(p.getStr("name", "app"))
                        .command(p.getStr("command", ""))
                        .workDir(p.getStr("workDir", ""))
                        .port(p.getInt("port", null))
                        .readiness(parseReadiness(p.getStr("readiness", "PORT")))
                        .build());
            }

            return RuntimeSpec.builder()
                    .kind(kind)
                    .processes(processes)
                    .primaryPort(json.getInt("primaryPort", null))
                    .buildRequired(json.getBool("buildRequired", false))
                    .buildCommand(json.getStr("buildCommand", null))
                    .source("declared:" + DECLARED_SPEC_PATH)
                    .build();
        } catch (IOException | ClassCastException e) {
            log.warn("解析 {} 失败: {}", DECLARED_SPEC_PATH, e.getMessage());
            return null;
        }
    }

    private static RuntimeSpec.ManagedProcess.Readiness parseReadiness(String raw) {
        try {
            return RuntimeSpec.ManagedProcess.Readiness.valueOf(raw.toUpperCase(Locale.ROOT));
        } catch (IllegalArgumentException e) {
            return RuntimeSpec.ManagedProcess.Readiness.PORT;
        }
    }

    private RuntimeSpec infer(Path workspace) {
        boolean hasPackageJson = Files.isRegularFile(workspace.resolve("package.json"));
        boolean hasViteConfig = existsAny(workspace, "vite.config.js", "vite.config.ts", "vite.config.mjs");
        boolean hasPythonEntry = existsAny(workspace, "main.py", "app.py", "server.py", "manage.py");
        boolean hasRequirements = existsAny(workspace, "requirements.txt", "pyproject.toml", "Pipfile");
        boolean hasPom = Files.isRegularFile(workspace.resolve("pom.xml"));
        boolean hasGradleBuild = existsAny(workspace, "build.gradle", "build.gradle.kts");
        boolean hasRootIndex = Files.isRegularFile(workspace.resolve("index.html"));

        // Node 优先于静态：有 package.json 就该按工程跑，根 index.html 可能只是模板
        if (hasPackageJson) {
            return inferNode(workspace, hasViteConfig);
        }
        if (hasPythonEntry || hasRequirements) {
            return inferPython(workspace, hasPythonEntry);
        }
        if (hasPom || hasGradleBuild) {
            return inferJava(hasPom);
        }
        if (hasRootIndex) {
            return staticSiteSpec("inferred:static-index", "");
        }
        return RuntimeSpec.builder()
                .kind(RuntimeSpec.RuntimeKind.UNSUPPORTED)
                .processes(List.of())
                .source("inferred:nothing-recognized")
                .build();
    }

    /**
     * 静态站点的运行规格。
     *
     * <p>同样起一个常驻进程（Python 自带的 http.server，无需装依赖），而不是返回空进程列表：
     * 空列表会让 runner 脚本没有可等的子进程、直接退出，端口映射与就绪探测也都无从谈起。
     * 这样「运行」是一个真实动作，静态与动态项目走同一条链路。
     */
    private static RuntimeSpec staticSiteSpec(String source, String workDir) {
        return RuntimeSpec.builder()
                .kind(RuntimeSpec.RuntimeKind.STATIC)
                .processes(List.of(RuntimeSpec.ManagedProcess.builder()
                        .name("static")
                        .command("python3 -m http.server 8080 --bind 0.0.0.0")
                        .workDir(workDir)
                        .port(8080)
                        .readiness(RuntimeSpec.ManagedProcess.Readiness.PORT)
                        .build()))
                .primaryPort(8080)
                .source(source)
                .build();
    }

    private RuntimeSpec inferNode(Path workspace, boolean hasViteConfig) {
        String scripts = readFileQuietly(workspace.resolve("package.json"));
        boolean hasStartScript = scripts != null && scripts.contains("\"start\"");
        boolean hasDevScript = scripts != null && scripts.contains("\"dev\"");
        boolean hasBuildScript = scripts != null && scripts.contains("\"build\"");
        boolean hasDistIndex = Files.isRegularFile(workspace.resolve("dist").resolve("index.html"));

        // Vite 工程：源码入口不能直接当静态页，必须构建或起 dev server
        if (hasViteConfig) {
            if (hasDistIndex) {
                // 已经构建过：直接按静态产物跑，省去每次启动都构建
                return staticSiteSpec("inferred:node-vite-dist", "dist");
            }
            return RuntimeSpec.builder()
                    .kind(RuntimeSpec.RuntimeKind.NODE)
                    .processes(List.of(RuntimeSpec.ManagedProcess.builder()
                            .name("web")
                            .command("npm run dev -- --host 0.0.0.0 --port 5173")
                            .workDir("")
                            .port(5173)
                            .readiness(RuntimeSpec.ManagedProcess.Readiness.PORT)
                            .build()))
                    .primaryPort(5173)
                    .buildRequired(true)
                    .buildCommand("npm install")
                    .source("inferred:node-vite-dev")
                    .build();
        }

        String command;
        int port;
        if (hasStartScript) {
            command = "npm start";
            port = firstDeclaredPort(scripts, 3000);
        } else if (hasDevScript) {
            command = "npm run dev";
            port = firstDeclaredPort(scripts, 3000);
        } else {
            // 没有 scripts 的 package.json：找常见入口文件
            String entry = firstExisting(workspace, "server.js", "index.js", "app.js", "main.js");
            if (entry == null) {
                return RuntimeSpec.builder()
                        .kind(RuntimeSpec.RuntimeKind.UNSUPPORTED)
                        .processes(List.of())
                        .source("inferred:node-no-entry")
                        .build();
            }
            command = "node " + entry;
            port = 3000;
        }

        return RuntimeSpec.builder()
                .kind(RuntimeSpec.RuntimeKind.NODE)
                .processes(List.of(RuntimeSpec.ManagedProcess.builder()
                        .name("app")
                        .command(command)
                        .workDir("")
                        .port(port)
                        .readiness(RuntimeSpec.ManagedProcess.Readiness.PORT)
                        .build()))
                .primaryPort(port)
                // 有 dependencies 才装：纯内置模块的项目跑 npm install 是白等
                .buildRequired(scripts != null && scripts.contains("\"dependencies\""))
                .buildCommand(scripts != null && scripts.contains("\"dependencies\"") ? "npm install" : null)
                .source("inferred:node")
                .build();
    }

    private RuntimeSpec inferPython(Path workspace, boolean hasEntry) {
        String entry = hasEntry
                ? firstExisting(workspace, "main.py", "app.py", "server.py", "manage.py")
                : null;
        if (entry == null) {
            entry = "main.py";
        }
        int port = 8000;

        // 从入口文件里找 uvicorn/flask 的端口线索，找不到就用 8000
        String entryContent = readFileQuietly(workspace.resolve(entry));
        if (entryContent != null) {
            Matcher matcher = Pattern.compile("port\\s*=\\s*(\\d{4,5})").matcher(entryContent);
            if (matcher.find()) {
                port = Integer.parseInt(matcher.group(1));
            }
        }

        // 只有真的存在依赖清单才装依赖：无条件跑 pip install 会把没有依赖的项目
        // 卡在构建阶段失败（requirements.txt 不存在，pip 直接报错退出）
        boolean hasRequirementsFile = Files.isRegularFile(workspace.resolve("requirements.txt"));

        return RuntimeSpec.builder()
                .kind(RuntimeSpec.RuntimeKind.PYTHON)
                .processes(List.of(RuntimeSpec.ManagedProcess.builder()
                        .name("app")
                        .command("python3 " + entry)
                        .workDir("")
                        .port(port)
                        .readiness(RuntimeSpec.ManagedProcess.Readiness.PORT)
                        .build()))
                .primaryPort(port)
                .buildRequired(hasRequirementsFile)
                .buildCommand(hasRequirementsFile ? "pip3 install -r requirements.txt" : null)
                .source("inferred:python")
                .build();
    }

    private RuntimeSpec inferJava(boolean hasPom) {
        if (!hasPom) {
            // Gradle：首期只识别不执行，说明原因而不是猜命令
            return RuntimeSpec.builder()
                    .kind(RuntimeSpec.RuntimeKind.UNSUPPORTED)
                    .processes(List.of())
                    .source("inferred:java-gradle-unsupported")
                    .build();
        }
        return RuntimeSpec.builder()
                .kind(RuntimeSpec.RuntimeKind.JAVA)
                .processes(List.of(RuntimeSpec.ManagedProcess.builder()
                        .name("app")
                        .command("mvn -q -DskipTests spring-boot:run")
                        .workDir("")
                        .port(8080)
                        .readiness(RuntimeSpec.ManagedProcess.Readiness.PORT)
                        .build()))
                .primaryPort(8080)
                .buildRequired(true)
                .buildCommand("mvn -q -DskipTests package")
                .source("inferred:java-maven")
                .build();
    }

    /** 从 package.json 文本里粗略找端口声明（如 {@code --port 4000}）。 */
    private static int firstDeclaredPort(String packageJson, int fallback) {
        Matcher matcher = Pattern.compile("--port[= ](\\d{2,5})").matcher(packageJson);
        if (matcher.find()) {
            return Integer.parseInt(matcher.group(1));
        }
        return fallback;
    }

    /**
     * 校验运行规格。
     *
     * <p>这一步是安全边界，不是形式检查：项目里的文件内容不可信，
     * 命令、工作目录、端口都必须落在允许范围内才执行。
     */
    public void validate(RuntimeSpec spec, Path workspace) {
        if (spec == null) {
            throw new MyException(ErrorCode.PARAMS_ERROR, "运行规格为空");
        }
        if (spec.getKind() == RuntimeSpec.RuntimeKind.UNSUPPORTED) {
            throw new MyException(ErrorCode.OPERATION_ERROR,
                    "无法识别该项目的运行方式（支持 Node.js / Python / Java / 静态页面）");
        }
        if (spec.getProcesses() == null || spec.getProcesses().isEmpty()) {
            throw new MyException(ErrorCode.OPERATION_ERROR, "运行规格里没有受管进程");
        }
        if (spec.getProcesses().size() > props.getMaxProcesses()) {
            throw new MyException(ErrorCode.OPERATION_ERROR,
                    "受管进程数超过上限 " + props.getMaxProcesses());
        }

        for (RuntimeSpec.ManagedProcess process : spec.getProcesses()) {
            if (process.getCommand() == null || process.getCommand().isBlank()) {
                throw new MyException(ErrorCode.OPERATION_ERROR, "进程 " + process.getName() + " 缺少启动命令");
            }
            // 命令是交给容器内 shell 执行的字符串，先挡住能拼出注入的字符
            if (SHELL_METACHARACTERS.matcher(process.getCommand()).find()) {
                throw new MyException(ErrorCode.OPERATION_ERROR,
                        "进程 " + process.getName() + " 的启动命令含不允许的字符");
            }
            String workDir = process.getWorkDir() == null ? "" : process.getWorkDir();
            if (workDir.contains("..")) {
                throw new MyException(ErrorCode.OPERATION_ERROR,
                        "进程 " + process.getName() + " 的工作目录越界");
            }
            if (!workDir.isEmpty()) {
                Path resolved = workspace.resolve(workDir).normalize();
                if (!resolved.startsWith(workspace.normalize())) {
                    throw new MyException(ErrorCode.OPERATION_ERROR,
                            "进程 " + process.getName() + " 的工作目录越界");
                }
            }
            Integer port = process.getPort();
            if (port != null && (port < MIN_PORT || port > MAX_PORT)) {
                throw new MyException(ErrorCode.OPERATION_ERROR,
                        "进程 " + process.getName() + " 的端口不合法：" + port);
            }
        }

        // 入口端口若声明了，必须是某个受管进程的端口，避免指向别的东西
        Integer primary = spec.getPrimaryPort();
        if (primary != null) {
            boolean owned = spec.getProcesses().stream()
                    .anyMatch(p -> primary.equals(p.getPort()));
            if (!owned && spec.getKind() != RuntimeSpec.RuntimeKind.STATIC) {
                throw new MyException(ErrorCode.OPERATION_ERROR, "入口端口不属于任何受管进程");
            }
        }
    }

    private static boolean existsAny(Path dir, String... names) {
        return firstExisting(dir, names) != null;
    }

    private static String firstExisting(Path dir, String... names) {
        for (String name : names) {
            if (Files.isRegularFile(dir.resolve(name))) {
                return name;
            }
        }
        return null;
    }

    private static String readFileQuietly(Path file) {
        try {
            return Files.isRegularFile(file) ? Files.readString(file) : null;
        } catch (IOException e) {
            return null;
        }
    }
}
