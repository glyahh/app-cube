package com.dbts.glyahhaigeneratecode.service.support;

import java.io.IOException;
import java.nio.file.*;
import java.nio.file.attribute.BasicFileAttributes;
import java.util.Locale;
import java.util.Set;

/** Public deployment is a separate boundary from permission to import private source files. */
public final class PublicDeployFiles {
    private static final Set<String> EXTENSIONS = Set.of(
            "html", "htm", "css", "js", "mjs", "cjs", "json", "webmanifest", "xml", "txt", "csv",
            "svg", "png", "jpg", "jpeg", "gif", "webp", "avif", "ico", "bmp",
            "woff", "woff2", "ttf", "otf", "eot", "wasm", "mp3", "ogg", "wav", "mp4", "webm", "pdf");
    private static final Set<String> PRIVATE_DIRECTORIES = Set.of(
            "node_modules", "venv", "__pycache__", "target", "platform-utils");

    private PublicDeployFiles() { }

    public static boolean isPublicPath(String path) {
        if (path == null || path.isBlank()) return false;
        String normalized = path.replace('\\', '/');
        for (String part : normalized.split("/")) {
            String name = part.toLowerCase(Locale.ROOT);
            if (name.isEmpty() || name.startsWith(".") || PRIVATE_DIRECTORIES.contains(name)
                    || ProjectImportManifest.isSensitivePath(name)) return false;
        }
        String name = normalized.substring(normalized.lastIndexOf('/') + 1).toLowerCase(Locale.ROOT);
        int dot = name.lastIndexOf('.');
        return dot >= 0 && EXTENSIONS.contains(name.substring(dot + 1));
    }

    /** Stage only approved regular assets, then replace rather than merge with a stale deployment. */
    public static void publish(Path source, Path destination) throws IOException {
        Path root = source.toAbsolutePath().normalize();
        Path target = destination.toAbsolutePath().normalize();
        if (!Files.isDirectory(root, LinkOption.NOFOLLOW_LINKS) || Files.isSymbolicLink(root)
                || target.startsWith(root) || root.startsWith(target)) throw new IOException("Invalid deployment roots");
        Files.createDirectories(target.getParent());
        Path staged = Files.createTempDirectory(target.getParent(), ".deploy-stage-");
        Path backup = null;
        try {
            Path realRoot = root.toRealPath();
            Files.walkFileTree(root, new SimpleFileVisitor<>() {
                @Override public FileVisitResult preVisitDirectory(Path dir, BasicFileAttributes attrs) {
                    if (dir.equals(root)) return FileVisitResult.CONTINUE;
                    // Windows junction 不是 symbolic link，walkFileTree 会继续遍历；
                    // 必须按真实路径确认目录仍在产物根内，否则公开部署会带出外部文件。
                    try {
                        if (!dir.toRealPath().startsWith(realRoot)) return FileVisitResult.SKIP_SUBTREE;
                    } catch (IOException failure) {
                        return FileVisitResult.SKIP_SUBTREE;
                    }
                    String relative = root.relativize(dir).toString().replace('\\', '/');
                    for (String part : relative.split("/")) {
                        String name = part.toLowerCase(Locale.ROOT);
                        if (name.startsWith(".") || PRIVATE_DIRECTORIES.contains(name)
                                || ProjectImportManifest.isSensitivePath(name)) return FileVisitResult.SKIP_SUBTREE;
                    }
                    return FileVisitResult.CONTINUE;
                }
                @Override public FileVisitResult visitFile(Path file, BasicFileAttributes attrs) throws IOException {
                    String relative = root.relativize(file).toString();
                    if (attrs.isRegularFile() && !Files.isSymbolicLink(file) && isPublicPath(relative)) {
                        Path output = staged.resolve(relative);
                        Files.createDirectories(output.getParent());
                        Files.copy(file, output, LinkOption.NOFOLLOW_LINKS);
                    }
                    return FileVisitResult.CONTINUE;
                }
            });
            if (Files.exists(target, LinkOption.NOFOLLOW_LINKS)) {
                backup = target.resolveSibling(".deploy-backup-" + java.util.UUID.randomUUID());
                Files.move(target, backup);
            }
            try {
                Files.move(staged, target);
            } catch (IOException failure) {
                if (backup != null) Files.move(backup, target);
                throw failure;
            }
            if (backup != null) removeTree(backup);
        } finally {
            if (Files.exists(staged, LinkOption.NOFOLLOW_LINKS)) removeTree(staged);
        }
    }

    private static void removeTree(Path root) throws IOException {
        Files.walkFileTree(root, new SimpleFileVisitor<>() {
            @Override public FileVisitResult visitFile(Path file, BasicFileAttributes attrs) throws IOException {
                Files.delete(file); return FileVisitResult.CONTINUE;
            }
            @Override public FileVisitResult postVisitDirectory(Path dir, IOException failure) throws IOException {
                if (failure != null) throw failure;
                Files.delete(dir); return FileVisitResult.CONTINUE;
            }
        });
    }
}
