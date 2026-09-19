package com.dbts.glyahhaigeneratecode.knowledge;

import cn.hutool.json.JSONUtil;
import com.dbts.glyahhaigeneratecode.mapper.KnowledgeSnapshotMapper;
import com.dbts.glyahhaigeneratecode.config.WorkspaceProperties;
import com.dbts.glyahhaigeneratecode.model.Entity.KnowledgeSnapshot;
import com.mybatisflex.core.query.QueryWrapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.stereotype.Service;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.FileVisitResult;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.LinkOption;
import java.nio.file.SimpleFileVisitor;
import java.nio.file.attribute.BasicFileAttributes;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HexFormat;
import java.util.List;
import java.util.Locale;
import java.util.Set;

/**
 * 源码快照：知识生成的不可变输入。
 *
 * <p>版本按**文件内容**算，不依赖宿主仓库的 Git SHA —— 产物目录不是 git 仓库，
 * 而且真正的输入是这次磁盘上的内容。
 *
 * <p>排除清单比「工作区版本摘要」严得多，因为快照内容会进入模型上下文：
 * 凭据（.env / 私钥）、SQLite 数据文件、依赖目录、日志与构建缓存都必须挡在外面。
 * 这些内容一旦进了模型，就不是「删掉本地文件」能收回的了。
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class KnowledgeSnapshotService {

    private final KnowledgeSnapshotMapper snapshotMapper;
    private final WorkspaceProperties workspaceProperties;

    /** 单个文件超过这个大小就不纳入：进模型上下文没有意义，还挤掉别的文件。 */
    private static final long MAX_FILE_BYTES = 256 * 1024;

    /** 纳入快照的文件数上限，防止超大项目把任务拖垮。 */
    private static final int MAX_FILES = 400;

    /** 目录名排除清单。 */
    private static final Set<String> EXCLUDED_DIRS = Set.of(
            "node_modules", "vendor", ".venv", "venv", "env",
            "dist", "build", "target", "out", ".next", ".nuxt", ".output",
            "__pycache__", ".pytest_cache", ".mypy_cache", ".ruff_cache",
            ".cache", "coverage", ".nyc_output",
            "logs", "log", "tmp", "temp",
            ".git", ".glyahh", ".idea", ".vscode",
            ".gradle", ".mvn"
    );

    /** 文件排除：凭据与数据库数据优先。 */
    private static final Set<String> EXCLUDED_FILE_NAMES = Set.of(
            ".env", ".env.local", ".env.production", ".env.development",
            "credentials", "credentials.json", "secrets.json", "id_rsa", "id_dsa",
            ".netrc", ".npmrc", ".pypirc"
    );

    private static final Set<String> EXCLUDED_EXTENSIONS = Set.of(
            // 凭据类
            "pem", "key", "p12", "pfx", "jks", "keystore", "secret",
            // SQLite 数据
            "db", "sqlite", "sqlite3", "db-journal", "db-wal", "db-shm",
            // 日志与二进制
            "log", "tmp", "cache",
            "png", "jpg", "jpeg", "gif", "webp", "ico", "bmp", "svg",
            "zip", "tar", "gz", "7z", "rar", "jar", "war", "class", "exe", "dll", "so", "dylib",
            "woff", "woff2", "ttf", "otf", "eot", "mp4", "mp3", "wav", "pdf"
    );

    /**
     * 为工作区创建（或复用）快照。
     *
     * @param appId     应用 id
     * @param workspace 工作区根目录
     * @return 快照记录；工作区不可读时返回 null
     */
    public KnowledgeSnapshot snapshot(Long appId, Path workspace) {
        if (workspace == null || !Files.isDirectory(workspace)) {
            return null;
        }

        List<FileEntry> files = collectFiles(workspace);
        if (files.isEmpty()) {
            log.warn("应用 {} 的工作区没有可纳入快照的文件", appId);
            return null;
        }

        files.sort(Comparator.comparing(FileEntry::relativePath));
        String version = contentVersion(files);
        long totalBytes = files.stream().mapToLong(FileEntry::bytes).sum();
        preserveFiles(appId, version, workspace, files);

        KnowledgeSnapshot existing = findByVersion(appId, version);
        if (existing != null) {
            // 内容没变就复用：同一版本不该产生第二份快照
            return existing;
        }

        KnowledgeSnapshot snapshot = KnowledgeSnapshot.builder()
                .appId(appId)
                .version(version)
                .fileCount(files.size())
                .totalBytes(totalBytes)
                .manifestJson(JSONUtil.toJsonStr(files))
                .createTime(LocalDateTime.now())
                .updateTime(LocalDateTime.now())
                .isDelete(0)
                .build();
        try {
            snapshotMapper.insertSelective(snapshot);
            log.info("应用 {} 建立知识快照 {}（{} 个文件，{} 字节）",
                    appId, version, files.size(), totalBytes);
            return snapshot;
        } catch (DuplicateKeyException e) {
            // 并发下另一个请求刚建好同一版本，直接用它的
            return findByVersion(appId, version);
        }
    }

    public KnowledgeSnapshot findByVersion(Long appId, String version) {
        List<KnowledgeSnapshot> list = snapshotMapper.selectListByQuery(
                QueryWrapper.create().eq("app_id", appId).eq("version", version).limit(1));
        return list.isEmpty() ? null : list.get(0);
    }

    /** 最近一次快照。 */
    public KnowledgeSnapshot latest(Long appId) {
        List<KnowledgeSnapshot> list = snapshotMapper.selectListByQuery(
                QueryWrapper.create().eq("app_id", appId).orderBy("id", false).limit(1));
        return list.isEmpty() ? null : list.get(0);
    }

    public KnowledgeSnapshot byId(Long snapshotId) {
        return snapshotMapper.selectOneById(snapshotId);
    }

    /** 返回已保存的独立源码目录；旧快照缺少文件时拒绝冒用当前源码。 */
    public Path snapshotWorkspace(KnowledgeSnapshot snapshot, Path workspace) {
        Path directory = snapshotDirectory(snapshot.getAppId(), snapshot.getVersion(), workspace);
        if (!Files.isDirectory(directory)) {
            throw new IllegalStateException("旧知识快照缺少源码副本，请重新生成知识");
        }
        return directory;
    }

    private Path snapshotDirectory(Long appId, String version, Path workspace) {
        if (appId == null || version == null || !version.matches("[0-9a-f]{16}")) {
            throw new IllegalArgumentException("知识快照标识无效");
        }
        return workspaceProperties.getOutputRoot().toAbsolutePath().normalize()
                .resolveSibling("knowledge_snapshots").resolve(appId.toString()).resolve(version);
    }

    private void preserveFiles(Long appId, String version, Path workspace, List<FileEntry> files) {
        Path destination = snapshotDirectory(appId, version, workspace);
        if (Files.isDirectory(destination)) {
            return;
        }
        Path staging = null;
        try {
            // 1. 先复制到独立目录，校验内容仍与清单一致，避免生成时混入新版本。
            Files.createDirectories(destination.getParent());
            staging = Files.createTempDirectory(destination.getParent(), ".pending-");
            for (FileEntry entry : files) {
                String content = readFileForEvidence(workspace, entry.relativePath());
                if (content == null) {
                    throw new IOException("源码已变化或不可读取：" + entry.relativePath());
                }
                Path target = staging.resolve(entry.relativePath()).normalize();
                if (!target.startsWith(staging)) {
                    throw new IOException("快照路径越界");
                }
                Files.createDirectories(target.getParent());
                Files.writeString(target, content);
                if (!entry.hash().equals(hashFile(target))) {
                    throw new IOException("源码在快照期间变化，请重试");
                }
            }
            // 2. 只有完整副本才对后台任务可见；并发生成同版本时保留先完成的副本。
            try {
                Files.move(staging, destination);
            } catch (IOException e) {
                if (!Files.isDirectory(destination)) {
                    throw e;
                }
            }
        } catch (IOException e) {
            throw new IllegalStateException("保存知识源码快照失败", e);
        } finally {
            if (staging != null && Files.exists(staging)) {
                try (var paths = Files.walk(staging)) {
                    for (Path path : paths.sorted(Comparator.reverseOrder()).toList()) {
                        Files.deleteIfExists(path);
                    }
                } catch (IOException e) {
                    log.warn("清理未完成快照失败: {}", staging);
                }
            }
        }
    }

    /**
     * 读取快照里某个文件的内容，供问答时作为证据。
     *
     * <p>路径必须落在工作区内且通过排除规则 —— 问答复用同一套边界，
     * 不能因为「只是读」就放行凭据文件。
     */
    public String readFileForEvidence(Path workspace, String relativePath) {
        if (workspace == null || relativePath == null || relativePath.isBlank()) {
            return null;
        }
        if (relativePath.contains("..")) {
            return null;
        }
        Path target = workspace.resolve(relativePath).normalize();
        if (!target.startsWith(workspace.normalize())) {
            return null;
        }
        if (isExcluded(target)) {
            return null;
        }
        for (Path segment : workspace.normalize().relativize(target)) {
            if (isExcludedDir(segment)) {
                return null;
            }
        }
        try {
            if (!Files.isRegularFile(target, LinkOption.NOFOLLOW_LINKS)
                    || !target.toRealPath().startsWith(workspace.toRealPath())
                    || Files.size(target) > MAX_FILE_BYTES) {
                return null;
            }
            return Files.readString(target);
        } catch (IOException e) {
            return null;
        }
    }

    /**
     * 收集纳入快照的文件。
     *
     * <p>公开出来是为了能单独测排除规则 —— 这是「什么内容可以进模型上下文」的边界，
     * 不该只能通过整条 snapshot 链路间接验证。
     */
    public List<FileEntry> collectFiles(Path workspace) {
        List<FileEntry> collected = new ArrayList<>();        try {
            Files.walkFileTree(workspace, new SimpleFileVisitor<>() {
                @Override
                public FileVisitResult preVisitDirectory(Path dir, BasicFileAttributes attrs) {
                    if (!dir.equals(workspace) && isExcludedDir(dir)) {
                        return FileVisitResult.SKIP_SUBTREE;
                    }
                    return FileVisitResult.CONTINUE;
                }

                @Override
                public FileVisitResult visitFile(Path file, BasicFileAttributes attrs) {
                    if (!attrs.isRegularFile() || collected.size() >= MAX_FILES
                            || isExcluded(file) || attrs.size() > MAX_FILE_BYTES) {
                        return FileVisitResult.CONTINUE;
                    }
                    String relative = workspace.relativize(file).toString().replace('\\', '/');
                    String hash = hashFile(file);
                    if (hash != null) {
                        collected.add(new FileEntry(relative, attrs.size(), hash));
                    }
                    return FileVisitResult.CONTINUE;
                }

                @Override
                public FileVisitResult visitFileFailed(Path file, IOException exc) {
                    return FileVisitResult.CONTINUE;
                }
            });
        } catch (IOException e) {
            log.warn("遍历工作区建立快照失败 {}: {}", workspace, e.getMessage());
        }
        return collected;
    }

    private static boolean isExcludedDir(Path dir) {
        Path name = dir.getFileName();
        return name != null && EXCLUDED_DIRS.contains(name.toString().toLowerCase(Locale.ROOT));
    }

    private static boolean isExcluded(Path file) {
        Path fileName = file.getFileName();
        if (fileName == null) {
            return true;
        }
        String name = fileName.toString().toLowerCase(Locale.ROOT);
        if (EXCLUDED_FILE_NAMES.contains(name)) {
            return true;
        }
        // .env.* 这类带后缀的凭据文件
        if (name.startsWith(".env")) {
            return true;
        }
        int dot = name.lastIndexOf('.');
        if (dot < 0 || dot == name.length() - 1) {
            // 没有扩展名的文件仍可能有用（如 Makefile），放行
            return false;
        }
        return EXCLUDED_EXTENSIONS.contains(name.substring(dot + 1));
    }

    private static String hashFile(Path file) {
        try {
            byte[] bytes = Files.readAllBytes(file);
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            return HexFormat.of().formatHex(digest.digest(bytes)).substring(0, 16);
        } catch (IOException | NoSuchAlgorithmException e) {
            return null;
        }
    }

    /** 快照版本 = 所有（路径 + 内容摘要）的摘要。 */
    private static String contentVersion(List<FileEntry> files) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            for (FileEntry entry : files) {
                digest.update((entry.relativePath() + ":" + entry.hash()).getBytes(StandardCharsets.UTF_8));
                digest.update((byte) '\n');
            }
            return HexFormat.of().formatHex(digest.digest()).substring(0, 16);
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 不可用", e);
        }
    }

    /** 快照清单条目。 */
    public record FileEntry(String relativePath, long bytes, String hash) {
    }
}
