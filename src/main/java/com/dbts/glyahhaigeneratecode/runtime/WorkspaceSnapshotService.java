package com.dbts.glyahhaigeneratecode.runtime;

import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.extern.slf4j.Slf4j;

import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Instant;
import java.util.*;
import java.util.stream.Stream;

/**
 * 合入前后的文件级快照。
 *
 * 只处理调用方给出的相对路径。快照存放在工作区外的私有目录，避免随发布目录整体移动或被清理。
 *
 * <p>与 {@code ImportAgentPublication#recover()} 的分工：recover 负责目录移动的状态机，
 * 本服务只保留合入前每个文件的字节，供恢复与事后核对使用。
 */
@Slf4j
public class WorkspaceSnapshotService {

    private final ObjectMapper json = new ObjectMapper();
    private final Path root;
    private final int retain;

    public WorkspaceSnapshotService(Path root, int retain) {
        this.root = Objects.requireNonNull(root).toAbsolutePath().normalize();
        this.retain = Math.max(1, retain);
    }

    /** 记录合入前的内容；调用时合入尚未发生。 */
    public SnapshotRecord create(Path workspace, Collection<String> relativePaths, String reason) throws IOException {
        Files.createDirectories(root);
        String id = UUID.randomUUID().toString();
        Path directory = root.resolve(id);
        Files.createDirectory(directory);
        Path files = directory.resolve("files");
        Files.createDirectory(files);

        Map<String, String> before = new LinkedHashMap<>();
        for (String relative : new TreeSet<>(relativePaths)) {
            Path source = resolve(workspace, relative);
            // 候选新增的文件在合入前不存在，不纳入快照；目录级回退由发布恢复流程负责。
            if (!Files.isRegularFile(source, LinkOption.NOFOLLOW_LINKS)) {
                continue;
            }
            Path copy = inside(files, relative);
            Files.createDirectories(copy.getParent());
            Files.copy(source, copy, StandardCopyOption.COPY_ATTRIBUTES, StandardCopyOption.REPLACE_EXISTING);
            before.put(normalize(relative), sha256(source));
        }

        SnapshotRecord record = new SnapshotRecord(id, SnapshotRecord.Status.PENDING,
                Instant.now().toString(), workspace.toAbsolutePath().normalize().toString(), reason,
                before, Map.of(), Map.of());
        write(directory, record);
        cleanup(id);
        return record;
    }

    /** 合入成功后记录合入后的内容，作为再次恢复的比对基准。 */
    public void markCommitted(Path workspace, String id) throws IOException {
        Path directory = directory(id);
        SnapshotRecord record = read(directory);
        Map<String, String> after = new LinkedHashMap<>();
        for (String relative : record.beforeHashes().keySet()) {
            Path target = resolve(workspace, relative);
            // 合入删除文件是合法结果：缺失即不写入 afterHashes，恢复时按“应缺失”比较。
            if (Files.isRegularFile(target, LinkOption.NOFOLLOW_LINKS)) {
                after.put(relative, sha256(target));
            }
        }
        write(directory, new SnapshotRecord(record.id(), SnapshotRecord.Status.COMMITTED,
                record.createdAt(), record.workspace(), record.reason(),
                record.beforeHashes(), after, record.recoveryHashes()));
    }

    /** 合入失败；不修改工作区，恢复交给调用方或现有的发布恢复流程。 */
    public void markFailed(Path workspace, String id, String reason) throws IOException {
        Path directory = directory(id);
        SnapshotRecord record = read(directory);
        write(directory, new SnapshotRecord(record.id(), SnapshotRecord.Status.FAILED,
                record.createdAt(), record.workspace(), reason,
                record.beforeHashes(), record.afterHashes(), record.recoveryHashes()));
    }

    /**
     * 恢复到合入前的字节。
     *
     * @param force 为 false 时，COMMITTED 快照要求当前内容仍等于合入后内容；不一致则拒绝覆盖
     */
    public void restore(Path workspace, String id, boolean force) throws IOException {
        Path directory = directory(id);
        SnapshotRecord record = read(directory);
        Map<String, String> current = new LinkedHashMap<>();
        for (String relative : record.beforeHashes().keySet()) {
            Path target = resolve(workspace, relative);
            if (Files.isRegularFile(target, LinkOption.NOFOLLOW_LINKS)) {
                current.put(relative, sha256(target));
            }
        }
        if (record.status() == SnapshotRecord.Status.COMMITTED && !force) {
            List<String> changed = record.beforeHashes().keySet().stream()
                    .filter(relative -> !Objects.equals(current.get(relative), record.afterHashes().get(relative)))
                    .sorted().toList();
            if (!changed.isEmpty()) {
                throw new IOException("SNAPSHOT_CONTENT_CHANGED: " + String.join(",", changed));
            }
        }
        Path files = directory.resolve("files");
        for (String relative : record.beforeHashes().keySet()) {
            Path copy = inside(files, relative);
            Path target = resolve(workspace, relative);
            Files.createDirectories(target.getParent());
            Files.copy(copy, target, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.COPY_ATTRIBUTES);
        }
        write(directory, new SnapshotRecord(record.id(), record.status(), record.createdAt(),
                record.workspace(), record.reason(), record.beforeHashes(), record.afterHashes(), current));
    }

    public Optional<SnapshotRecord> find(String id) throws IOException {
        Path directory = directory(id);
        return Files.isDirectory(directory) ? Optional.of(read(directory)) : Optional.empty();
    }

    private void cleanup(String keep) throws IOException {
        List<Path> candidates;
        try (Stream<Path> stream = Files.list(root)) {
            candidates = stream.filter(Files::isDirectory)
                    .filter(path -> !path.getFileName().toString().equals(keep))
                    .filter(this::removable)
                    .sorted(Comparator.comparingLong(this::modified).reversed())
                    .toList();
        }
        for (int index = retain - 1; index < candidates.size(); index++) {
            deleteTree(candidates.get(index));
        }
    }

    private boolean removable(Path directory) {
        try {
            return read(directory).status() != SnapshotRecord.Status.PENDING;
        } catch (IOException error) {
            log.warn("快照清单不可读，保留不删 {}: {}", directory, error.getMessage());
            return false;
        }
    }

    private long modified(Path path) {
        try {
            return Files.getLastModifiedTime(path).toMillis();
        } catch (IOException error) {
            return 0L;
        }
    }

    private void deleteTree(Path directory) throws IOException {
        try (Stream<Path> stream = Files.walk(directory)) {
            for (Path path : stream.sorted(Comparator.reverseOrder()).toList()) {
                Files.deleteIfExists(path);
            }
        }
    }

    private Path directory(String id) throws IOException {
        Path directory = root.resolve(id).normalize();
        if (!directory.startsWith(root) || directory.equals(root)) {
            throw new IOException("SNAPSHOT_ID_INVALID: " + id);
        }
        return directory;
    }

    private Path inside(Path root, String relative) throws IOException {
        Path target = root.resolve(relative).normalize();
        if (!target.startsWith(root) || target.equals(root)) {
            throw new IOException("SNAPSHOT_PATH_INVALID: " + relative);
        }
        return target;
    }

    private Path resolve(Path workspace, String relative) throws IOException {
        Path root = workspace.toAbsolutePath().normalize();
        Path target = inside(root, relative);
        Path current = root;
        for (Path part : root.relativize(target)) {
            current = current.resolve(part);
            if (Files.isSymbolicLink(current)) {
                throw new IOException("SNAPSHOT_LINK_REJECTED: " + relative);
            }
        }
        return target;
    }

    private static String normalize(String relative) {
        return relative.replace('\\', '/');
    }

    private static String sha256(Path file) throws IOException {
        MessageDigest digest;
        try {
            digest = MessageDigest.getInstance("SHA-256");
        } catch (NoSuchAlgorithmException error) {
            throw new IllegalStateException("SHA-256 不可用", error);
        }
        try (InputStream input = Files.newInputStream(file)) {
            byte[] buffer = new byte[8192];
            int read;
            while ((read = input.read(buffer)) >= 0) {
                digest.update(buffer, 0, read);
            }
        }
        return HexFormat.of().formatHex(digest.digest());
    }

    private void write(Path directory, SnapshotRecord record) throws IOException {
        json.writerWithDefaultPrettyPrinter().writeValue(directory.resolve("manifest.json").toFile(), record);
    }

    private SnapshotRecord read(Path directory) throws IOException {
        return json.readValue(directory.resolve("manifest.json").toFile(), SnapshotRecord.class);
    }
}
