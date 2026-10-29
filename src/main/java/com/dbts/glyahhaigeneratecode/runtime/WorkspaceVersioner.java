package com.dbts.glyahhaigeneratecode.runtime;

import com.dbts.glyahhaigeneratecode.config.WorkspaceProperties;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.FileVisitResult;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.nio.file.SimpleFileVisitor;
import java.nio.file.attribute.BasicFileAttributes;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.List;
import java.util.Set;

/**
 * 工作区版本指纹：回答「现在磁盘上的代码，和正在运行的那份是不是同一份」。
 *
 * <p>用途是「运行版本落后」提示 —— 用户改了代码但没重新运行，界面要能说清楚。
 *
 * <p>{@link #version(Path)} 的摘要取自「相对路径 + 大小 + 修改时间」而不是文件内容：
 * 运行中随时可能重算，全量读一遍源码代价太大；而大小与 mtime 足够捕捉
 * 用户编辑文件这件事。需要内容级精确性的场景用 {@link #contentVersion(Path)}。
 *
 * <p>排除清单复用 {@link WorkspaceProperties#getIgnore()} —— 仓库里已经有两份
 * 忽略清单（下载、回显），这里刻意不造第三份。
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class WorkspaceVersioner {

    /**
     * 平台自己写进去的目录，不算用户源码。
     *
     * <p>{@code .glyahh/} 里放的是运行脚本，每次启动都会重写 ——
     * 算进摘要的话，刚启动就会判定「运行版本落后」。
     * {@code .scratch/} 是 agent 工具输出的溢写目录，生成过程中随时增删，
     * 同理不该让它的变化影响「用户代码是否改过」这个判断。
     */
    private static final Set<String> PLATFORM_DIRS = Set.of(".glyahh", ".git", ".scratch");

    private final WorkspaceProperties workspaceProperties;

    /**
     * 计算工作区版本。
     *
     * <p>只用于「运行版本落后」提示，不能作为内容证据：大小与修改时间相同不代表字节相同。
     *
     * @param workspace 项目根目录
     * @return 16 位十六进制摘要；目录不存在时返回 null
     */
    public String version(Path workspace) {
        if (workspace == null || !Files.isDirectory(workspace)) {
            return null;
        }
        List<Path> files;
        try {
            files = collectFiles(workspace);
        } catch (IOException e) {
            log.warn("遍历工作区计算版本失败 {}: {}", workspace, e.getMessage());
            return null;
        }

        List<String> entries = new ArrayList<>();
        for (Path file : files) {
            try {
                BasicFileAttributes attrs = Files.readAttributes(file, BasicFileAttributes.class,
                        LinkOption.NOFOLLOW_LINKS);
                entries.add(relative(workspace, file) + "|" + attrs.size() + "|" + attrs.lastModifiedTime().toMillis());
            } catch (IOException e) {
                // 单个文件读不到不影响整体判断，跳过
            }
        }

        // 排序后摘要：目录遍历顺序在不同文件系统上不保证一致
        entries.sort(String::compareTo);
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            for (String entry : entries) {
                digest.update(entry.getBytes(StandardCharsets.UTF_8));
                digest.update((byte) '\n');
            }
            return HexFormat.of().formatHex(digest.digest()).substring(0, 16);
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 不可用", e);
        }
    }

    /**
     * 按文件内容计算工作区摘要，用于候选绑定与合入前核对。
     *
     * <p>全量读取，不参与高频轮询。单个文件读取失败时返回 null，不返回部分结果。
     *
     * @param workspace 项目根目录
     * @return 64 位十六进制摘要；目录不存在或读取失败时返回 null
     */
    public String contentVersion(Path workspace) {
        if (workspace == null || !Files.isDirectory(workspace)) {
            return null;
        }
        List<Path> files;
        try {
            files = collectFiles(workspace);
        } catch (IOException e) {
            log.warn("遍历工作区计算内容版本失败 {}: {}", workspace, e.getMessage());
            return null;
        }

        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            byte[] buffer = new byte[8192];
            for (Path file : files) {
                digest.update(relative(workspace, file).getBytes(StandardCharsets.UTF_8));
                digest.update((byte) '\n');
                try (InputStream input = Files.newInputStream(file)) {
                    int read;
                    while ((read = input.read(buffer)) >= 0) {
                        digest.update(buffer, 0, read);
                    }
                }
                digest.update((byte) '\n');
            }
            return HexFormat.of().formatHex(digest.digest());
        } catch (IOException e) {
            log.warn("读取工作区内容失败 {}: {}", workspace, e.getMessage());
            return null;
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 不可用", e);
        }
    }

    private List<Path> collectFiles(Path workspace) throws IOException {
        Set<String> ignore = Set.copyOf(workspaceProperties.getIgnore());
        List<Path> files = new ArrayList<>();
        Files.walkFileTree(workspace, new SimpleFileVisitor<>() {
            @Override
            public FileVisitResult preVisitDirectory(Path dir, BasicFileAttributes attrs) {
                String name = dir.getFileName() == null ? "" : dir.getFileName().toString();
                if (!dir.equals(workspace)
                        && (ignore.contains(name) || PLATFORM_DIRS.contains(name))) {
                    return FileVisitResult.SKIP_SUBTREE;
                }
                return FileVisitResult.CONTINUE;
            }

            @Override
            public FileVisitResult visitFile(Path file, BasicFileAttributes attrs) {
                if (!ignore.contains(file.getFileName().toString())) {
                    files.add(file);
                }
                return FileVisitResult.CONTINUE;
            }

            @Override
            public FileVisitResult visitFileFailed(Path file, IOException exc) {
                // 单个文件读不到不影响整体判断，跳过
                return FileVisitResult.CONTINUE;
            }
        });
        files.sort((left, right) -> relative(workspace, left).compareTo(relative(workspace, right)));
        return files;
    }

    private static String relative(Path workspace, Path file) {
        return workspace.relativize(file).toString().replace('\\', '/');
    }
}
