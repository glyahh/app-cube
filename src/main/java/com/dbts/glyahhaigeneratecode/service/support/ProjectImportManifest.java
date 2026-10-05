package com.dbts.glyahhaigeneratecode.service.support;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.regex.Pattern;

/** 仅校验来源清单；不读取文件，不证明实际字节、内容版本或导入成功。 */
public final class ProjectImportManifest {
    private static final Set<String> DEPENDENCIES = Set.of("node_modules", ".venv", "venv");
    private static final Set<String> BUILD_OUTPUTS = Set.of("dist", "target", "build");
    private static final Pattern UNSAFE = Pattern.compile("[\\\\:<>\"|?*\\x00-\\x1f\\x7f]");
    private static final Pattern RESERVED = Pattern.compile("(?i)(?:con|prn|aux|nul|com[1-9¹²³]|lpt[1-9¹²³])(?:\\..*)?");
    private static final Pattern SENSITIVE = Pattern.compile(
            "application-local(?:-[\\w-]+)?\\.(?:ya?ml|properties)|.*\\.(?:pem|key|p12|pfx|jks|keystore)|"
                    + "id_rsa|id_ed25519|credentials(?:\\.json)?|\\.npmrc|\\.pypirc|\\.netrc");

    private ProjectImportManifest() { }

    public record FileEntry(String path, long size) { }
    public record Options(boolean skipDependencies, boolean skipBuildOutputs, boolean includeSensitive) {
        public static Options defaults() { return new Options(false, false, false); }
    }
    public record Limits(int maxFiles, long maxSourceBytes) {
        public Limits {
            if (maxFiles < 1 || maxSourceBytes < 0) throw new IllegalArgumentException("导入额度无效。");
        }
    }
    public enum ExclusionReason { SENSITIVE, DEPENDENCY, BUILD_OUTPUT }
    public record ExcludedFile(FileEntry file, ExclusionReason reason) { }
    public record Manifest(List<FileEntry> included, List<ExcludedFile> excluded, long totalBytes) {
        public Manifest {
            included = List.copyOf(included);
            excluded = List.copyOf(excluded);
        }
    }

    public static Manifest validate(List<FileEntry> files, Options options, Limits limits) {
        // 1. 全量校验来源，排除项也不能绕过路径或资源边界。
        if (files == null || files.isEmpty() || options == null || limits == null) {
            throw new IllegalArgumentException("文件清单或导入选项无效。");
        }
        if (files.size() > limits.maxFiles()) throw new IllegalArgumentException("文件数量超过导入额度，未截断清单。");
        Set<String> filePaths = new HashSet<>();
        Set<String> directoryPaths = new HashSet<>();
        Map<String, String> canonicalPaths = new HashMap<>();
        long sourceBytes = 0;
        for (FileEntry file : files) {
            if (file == null || file.size() < 0) throw new IllegalArgumentException("文件大小无效。");
            validatePath(file.path());
            if (file.size() > limits.maxSourceBytes() - sourceBytes) {
                throw new IllegalArgumentException("来源大小超过导入额度，未生成部分清单。");
            }
            sourceBytes += file.size();
            String[] parts = file.path().split("/");
            String prefix = "";
            for (int i = 0; i < parts.length; i++) {
                prefix = prefix.isEmpty() ? parts[i] : prefix + "/" + parts[i];
                String key = prefix.toLowerCase(Locale.ROOT);
                String previous = canonicalPaths.putIfAbsent(key, prefix);
                if (previous != null && !previous.equals(prefix)) {
                    throw new IllegalArgumentException("文件路径存在大小写冲突。");
                }
                if (i < parts.length - 1) directoryPaths.add(key);
                else if (!filePaths.add(key)) throw new IllegalArgumentException("文件路径重复。");
            }
        }
        if (filePaths.stream().anyMatch(directoryPaths::contains)) {
            throw new IllegalArgumentException("文件与目录路径冲突。");
        }

        // 2. 根据本次冻结选项生成只读清单，默认保留依赖和构建产物。
        List<FileEntry> included = new ArrayList<>();
        List<ExcludedFile> excluded = new ArrayList<>();
        long includedBytes = 0;
        for (FileEntry file : files) {
            ExclusionReason reason = exclusion(file.path(), options);
            if (reason == null) {
                included.add(file);
                includedBytes += file.size();
            } else {
                excluded.add(new ExcludedFile(file, reason));
            }
        }
        if (included.isEmpty()) throw new IllegalArgumentException("导入范围为空，请调整过滤选项。");
        return new Manifest(included, excluded, includedBytes);
    }

    private static void validatePath(String path) {
        if (path == null || path.isEmpty() || path.length() > 4096 || UNSAFE.matcher(path).find()) {
            throw new IllegalArgumentException("文件路径无效。");
        }
        for (String part : path.split("/", -1)) {
            if (part.isEmpty() || part.length() > 255 || part.equals(".") || part.equals("..") || part.endsWith(".") || part.endsWith(" ")
                    || RESERVED.matcher(part).matches()) {
                throw new IllegalArgumentException("文件路径无效。");
            }
        }
        for (int index = 0; index < path.length(); index++) {
            char value = path.charAt(index);
            if (Character.isHighSurrogate(value)) {
                if (++index >= path.length() || !Character.isLowSurrogate(path.charAt(index))) {
                    throw new IllegalArgumentException("文件路径编码无效。");
                }
            } else if (Character.isLowSurrogate(value)) {
                throw new IllegalArgumentException("文件路径编码无效。");
            }
        }
    }

    private static ExclusionReason exclusion(String path, Options options) {
        String[] parts = path.split("/");
        boolean sensitive = isSensitivePath(path);
        if (!options.includeSensitive() && sensitive) return ExclusionReason.SENSITIVE;
        for (int i = 0; i < parts.length - 1; i++) {
            if (options.skipDependencies() && DEPENDENCIES.contains(parts[i])) return ExclusionReason.DEPENDENCY;
        }
        for (int i = 0; i < parts.length - 1; i++) {
            if (options.skipBuildOutputs() && BUILD_OUTPUTS.contains(parts[i])) return ExclusionReason.BUILD_OUTPUT;
        }
        return null;
    }

    public static boolean isSensitivePath(String path) {
        String[] parts = path.split("/");
        String name = parts[parts.length - 1].toLowerCase(Locale.ROOT);
        return name.matches("\\.env(?:\\..*)?")
                ? !name.matches(".*\\.(?:example|sample|template)") : SENSITIVE.matcher(name).matches();
    }
}
