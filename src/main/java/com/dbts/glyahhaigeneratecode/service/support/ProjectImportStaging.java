package com.dbts.glyahhaigeneratecode.service.support;

import java.io.DataOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.channels.Channels;
import java.nio.channels.FileChannel;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.nio.file.attribute.BasicFileAttributes;
import java.security.DigestOutputStream;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Duration;
import java.util.*;
import java.util.function.BooleanSupplier;

import static com.dbts.glyahhaigeneratecode.service.support.ProjectImportManifest.*;

/** 私有暂存只用服务端任务和序号命名，不把用户相对路径当宿主写入路径。 */
public final class ProjectImportStaging {
    private final Path root;
    public record StoredFile(int index, String path, long size, String sha256, boolean directory) {
        public StoredFile(int index,String path,long size,String sha256){this(index,path,size,sha256,false);}
    }

    public ProjectImportStaging(Path root, List<Path> forbiddenRoots) throws IOException {
        this.root = root.toAbsolutePath().normalize();
        for (Path forbidden : forbiddenRoots) {
            Path absolute = forbidden.toAbsolutePath().normalize();
            if (this.root.startsWith(absolute) || absolute.startsWith(this.root)) {
                throw new IllegalArgumentException("导入暂存目录必须与工作区和发布目录隔离。");
            }
        }
        guardAncestors(this.root);
        Files.createDirectories(this.root);
        guardAncestors(this.root);
    }

    public void create(UUID taskId) throws IOException {
        Path directory = directory(taskId);
        guardAncestors(directory);
        Files.createDirectory(directory);
    }

    public StoredFile receive(UUID taskId, int index, FileEntry entry, InputStream input,
                              BooleanSupplier eligible, Duration timeout) throws IOException {
        if (index < 0 || timeout == null || timeout.isNegative() || timeout.isZero()) {
            throw new IllegalArgumentException("上传序号或时限无效。");
        }
        validate(List.of(entry), new Options(false, false, true), new Limits(1, entry.size()));
        Path file = payload(taskId, index);
        boolean created = false;
        try (input) {
            guardAncestors(file);
            if (!Files.isDirectory(directory(taskId), LinkOption.NOFOLLOW_LINKS)) throw new IOException("暂存任务不存在。");
            try (var channel = FileChannel.open(file, StandardOpenOption.CREATE_NEW, StandardOpenOption.WRITE, LinkOption.NOFOLLOW_LINKS)) {
                created = true;
                String hash = transfer(input, Channels.newOutputStream(channel), entry.size(), eligible, timeout);
                channel.force(true);
                return new StoredFile(index, entry.path(), entry.size(), hash,entry.directory());
            }
        } catch (IOException e) {
            if (created) {
                try {
                    guardAncestors(file.getParent());
                    Files.deleteIfExists(file);
                } catch (IOException cleanupError) {
                    throw new IOException("文件接收失败，暂存清理未完成。");
                }
            }
            // 输入流异常可能含工程秘密，不挂接原始消息到通用异常日志。
            throw new IOException("文件接收失败，请检查任务状态。");
        }
    }

    public String seal(UUID taskId, List<StoredFile> files) throws IOException {
        return seal(taskId, files, () -> true, Duration.ofMinutes(5));
    }

    public String seal(UUID taskId, List<StoredFile> files, BooleanSupplier eligible, Duration timeout) throws IOException {
        // 1. 对磁盘完整清单重新读内容，不信大小、mtime 或旧摘要。
        if (files == null || files.isEmpty()) throw new IOException("暂存清单不完整。");
        Path directory = directory(taskId);
        guardAncestors(directory);
        Set<String> expectedNames = new HashSet<>();
        Set<String> expectedPaths = new HashSet<>();
        long started = System.nanoTime();
        for (StoredFile file : files) {
            if (file.index() < 0 || !expectedNames.add(file.index() + ".bin") || !expectedPaths.add(file.path())) {
                throw new IOException("暂存清单重复或无效。");
            }
            Path payload = payload(taskId, file.index());
            guardAncestors(payload);
            if (!Files.isRegularFile(payload, LinkOption.NOFOLLOW_LINKS)) throw new IOException("暂存内容不完整。");
            try (InputStream input = Files.newInputStream(payload, LinkOption.NOFOLLOW_LINKS)) {
                long remaining = timeout.toNanos() - (System.nanoTime() - started);
                if (remaining <= 0) throw new IOException("内容封存超时。");
                String actual = transfer(input, OutputStream.nullOutputStream(), file.size(), eligible, Duration.ofNanos(remaining));
                if (!actual.equals(file.sha256())) throw new IOException("暂存内容已变化。");
            }
        }
        try (var entries = Files.list(directory)) {
            if (!entries.map(p -> p.getFileName().toString()).collect(java.util.stream.Collectors.toSet()).equals(expectedNames)) {
                throw new IOException("暂存文件范围不一致。");
            }
        }

        // 2. 路径、真实字节数和内容摘要共同形成范围身份，与上传顺序无关。
        MessageDigest digest = digest();
        try (var output = new DataOutputStream(new DigestOutputStream(OutputStream.nullOutputStream(), digest))) {
            for (StoredFile file : files.stream().sorted(Comparator.comparing(StoredFile::path)).toList()) {
                byte[] path = file.path().getBytes(StandardCharsets.UTF_8);
                output.writeInt(path.length);
                output.write(path);
                output.writeLong(file.directory()?-1:file.size());
                output.write(HexFormat.of().parseHex(file.sha256()));
            }
        }
        return HexFormat.of().formatHex(digest.digest());
    }

    public void cleanup(UUID taskId) throws IOException {
        Path directory = directory(taskId);
        cleanupDirectory(directory);
    }

    public Path preparedWorkspace(UUID taskId) { return root.resolve(taskId + ".workspace"); }

    public void cleanupPreparedWorkspace(UUID taskId) throws IOException { cleanupDirectory(preparedWorkspace(taskId)); }

    public Path prepareWorkspace(UUID taskId, List<StoredFile> files, BooleanSupplier eligible, Duration timeout) throws IOException {
        // 1. 原始字节先重新封存，再建立独立的可编辑候选副本。
        String expected = seal(taskId, files, eligible, timeout);
        Path target = preparedWorkspace(taskId);
        guardAncestors(target);
        Files.createDirectory(target);
        long started = System.nanoTime();
        for (StoredFile file : files) {
            Path destination = target.resolve(file.path()).normalize();
            if (!destination.startsWith(target)) throw new IOException("工作副本路径越界。");
            guardAncestors(destination);
            Files.createDirectories(destination.getParent());
            if(file.directory()){
                if(!eligible.getAsBoolean())throw new IOException("工作副本资格已撤销。");
                Files.createDirectories(destination);
                continue;
            }
            try (InputStream input = Files.newInputStream(payload(taskId, file.index()), LinkOption.NOFOLLOW_LINKS);
                 FileChannel channel = FileChannel.open(destination, StandardOpenOption.CREATE_NEW, StandardOpenOption.WRITE, LinkOption.NOFOLLOW_LINKS)) {
                String hash = transfer(input, Channels.newOutputStream(channel), file.size(), eligible, remaining(timeout, started));
                if (!hash.equals(file.sha256())) throw new IOException("原始内容已变化。");
                channel.force(true);
            }
        }
        // 2. 副本自身也必须匹配完整范围，不能用复制命令成功替代内容核对。
        if (!expected.equals(verifyWorkspace(target, files, eligible, remaining(timeout, started)))) throw new IOException("工作副本内容不一致。");
        return target;
    }

    public String verifyWorkspace(Path target, List<StoredFile> files, BooleanSupplier eligible, Duration timeout) throws IOException {
        guardAncestors(target);
        validate(files.stream().map(f -> new FileEntry(f.path(), f.size(),f.directory())).toList(), new Options(false, false, true),
                new Limits(files.size(), Long.MAX_VALUE));
        long started = System.nanoTime();
        Set<String> expected = new HashSet<>();
        Set<String> expectedDirectories=new HashSet<>();
        for (StoredFile file : files) {
            if(!file.directory())expected.add(file.path());
            Path relative=Path.of(file.path());
            for(Path directory=file.directory()?relative:relative.getParent();directory!=null;directory=directory.getParent())
                expectedDirectories.add(directory.toString().replace('\\','/'));
            Path path = target.resolve(file.path()).normalize();
            guardAncestors(path);
            if(file.directory()){
                if(!Files.isDirectory(path,LinkOption.NOFOLLOW_LINKS))throw new IOException("工作副本目录缺失。");
                continue;
            }
            if (!Files.isRegularFile(path, LinkOption.NOFOLLOW_LINKS)) throw new IOException("工作副本文件缺失。");
            try (InputStream input = Files.newInputStream(path, LinkOption.NOFOLLOW_LINKS)) {
                if (!transfer(input, OutputStream.nullOutputStream(), file.size(), eligible, remaining(timeout, started)).equals(file.sha256())) {
                    throw new IOException("工作副本内容已变化。");
                }
            }
        }
        Set<String> actual = new HashSet<>();
        Set<String> actualDirectories=new HashSet<>();
        Files.walkFileTree(target, new SimpleFileVisitor<>() {
            @Override public FileVisitResult preVisitDirectory(Path path, BasicFileAttributes attrs) throws IOException {
                if (!eligible.getAsBoolean() || attrs.isOther() || attrs.isSymbolicLink()) throw new IOException("工作副本范围无效。");
                remaining(timeout, started);
                if(!path.equals(target))actualDirectories.add(target.relativize(path).toString().replace('\\','/'));
                return FileVisitResult.CONTINUE;
            }
            @Override public FileVisitResult visitFile(Path path, BasicFileAttributes attrs) throws IOException {
                if (!attrs.isRegularFile() || attrs.isOther() || attrs.isSymbolicLink()) throw new IOException("工作副本包含特殊节点。");
                actual.add(target.relativize(path).toString().replace('\\', '/'));
                return FileVisitResult.CONTINUE;
            }
        });
        if (!actual.equals(expected)||!actualDirectories.equals(expectedDirectories)) throw new IOException("工作副本范围不一致。");
        return contentIdentity(files);
    }

    private static Duration remaining(Duration timeout, long started) throws IOException {
        long value = timeout.toNanos() - (System.nanoTime() - started);
        if (value <= 0) throw new IOException("工作副本准备超时。");
        return Duration.ofNanos(value);
    }

    private static String contentIdentity(List<StoredFile> files) throws IOException {
        MessageDigest digest = digest();
        try (var output = new DataOutputStream(new DigestOutputStream(OutputStream.nullOutputStream(), digest))) {
            for (StoredFile file : files.stream().sorted(Comparator.comparing(StoredFile::path)).toList()) {
                byte[] path = file.path().getBytes(StandardCharsets.UTF_8);
                output.writeInt(path.length); output.write(path); output.writeLong(file.directory()?-1:file.size());
                output.write(HexFormat.of().parseHex(file.sha256()));
            }
        }
        return HexFormat.of().formatHex(digest.digest());
    }

    public static void guardManagedPath(Path path) throws IOException { guardAncestors(path); }

    /**
     * 只检查工作区根到目标之间的链接。根目录的上级，例如盘符或同步盘，不在这次检查里。
     */
    public static void guardInside(Path root, Path path) throws IOException {
        Path rootAbs = root.toAbsolutePath().normalize();
        Path current = path.toAbsolutePath().normalize();
        if (!current.startsWith(rootAbs)) throw new IOException("路径越界。");
        while (current != null && !current.equals(rootAbs)) {
            if (Files.exists(current, LinkOption.NOFOLLOW_LINKS)) {
                BasicFileAttributes attributes = Files.readAttributes(current, BasicFileAttributes.class, LinkOption.NOFOLLOW_LINKS);
                if (attributes.isSymbolicLink() || attributes.isOther()) throw new IOException("路径包含链接或特殊节点。");
            }
            current = current.getParent();
        }
    }

    public void removeVerifiedWorkspace(Path target, List<StoredFile> files, Duration timeout) throws IOException {
        verifyWorkspace(target, files, () -> true, timeout);
        cleanupDirectory(target);
    }

    private static void cleanupDirectory(Path directory) throws IOException {
        guardAncestors(directory);
        if (!Files.exists(directory, LinkOption.NOFOLLOW_LINKS)) return;
        Files.walkFileTree(directory, new SimpleFileVisitor<>() {
            @Override public FileVisitResult preVisitDirectory(Path path, BasicFileAttributes attributes) throws IOException {
                if (attributes.isOther() || attributes.isSymbolicLink()) throw new IOException("暂存清理遇到链接，已停止。");
                return FileVisitResult.CONTINUE;
            }
            @Override public FileVisitResult visitFile(Path file, BasicFileAttributes attributes) throws IOException {
                Files.delete(file);
                return FileVisitResult.CONTINUE;
            }
            @Override public FileVisitResult postVisitDirectory(Path path, IOException failure) throws IOException {
                if (failure != null) throw failure;
                Files.delete(path);
                return FileVisitResult.CONTINUE;
            }
        });
    }

    private Path directory(UUID taskId) {
        return root.resolve(Objects.requireNonNull(taskId).toString());
    }

    private Path payload(UUID taskId, int index) { return directory(taskId).resolve(index + ".bin"); }

    private static void guardAncestors(Path path) throws IOException {
        for (Path current = path.toAbsolutePath().normalize(); current != null; current = current.getParent()) {
            if (!Files.exists(current, LinkOption.NOFOLLOW_LINKS)) continue;
            BasicFileAttributes attributes = Files.readAttributes(current, BasicFileAttributes.class, LinkOption.NOFOLLOW_LINKS);
            if (attributes.isSymbolicLink() || attributes.isOther()) throw new IOException("暂存路径包含链接或特殊节点。");
        }
    }

    private static String transfer(InputStream input, OutputStream output, long expected, BooleanSupplier eligible,
                                   Duration timeout) throws IOException {
        MessageDigest digest = digest();
        long started = System.nanoTime();
        long deadline = timeout.toNanos();
        byte[] buffer = new byte[64 * 1024];
        long total = 0;
        while (true) {
            if (!eligible.getAsBoolean() || Thread.currentThread().isInterrupted()) throw new IOException("任务资格已撤销。");
            if (System.nanoTime() - started >= deadline) throw new IOException("文件接收超时。");
            int read = input.read(buffer);
            if (read < 0) break;
            if (read == 0) continue;
            if (read > expected - total) throw new IOException("实际文件长度与清单不一致。");
            output.write(buffer, 0, read);
            digest.update(buffer, 0, read);
            total += read;
        }
        if (!eligible.getAsBoolean() || Thread.currentThread().isInterrupted()) throw new IOException("任务资格已撤销。");
        if (System.nanoTime() - started >= deadline) throw new IOException("文件接收超时。");
        if (total != expected) throw new IOException("实际文件长度与清单不一致。");
        return HexFormat.of().formatHex(digest.digest());
    }

    private static MessageDigest digest() {
        try { return MessageDigest.getInstance("SHA-256"); }
        catch (NoSuchAlgorithmException e) { throw new IllegalStateException("运行环境缺少 SHA-256。", e); }
    }
}
