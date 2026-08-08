package com.dbts.glyahhaigeneratecode.core.rag.file;

import com.dbts.glyahhaigeneratecode.core.rag.RagFileHash;
import com.dbts.glyahhaigeneratecode.core.rag.model.RagSourceFile;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.InvalidPathException;
import java.nio.file.Path;
import java.util.Optional;

/**
 * 读取项目目录内的 UTF-8 源文件，并生成与磁盘内容一致的文件快照。
 * 无效、缺失或越界路径返回空结果，由调用方沿用原有索引清理策略。
 */
public class RagSourceFileReader {

    public Optional<RagSourceFile> read(Path root, String filePath) {
        // 1. 校验参数及规范化路径，先阻止词法层面的目录越界。
        if (root == null || filePath == null) {
            return Optional.empty();
        }
        try {
            Path normalizedRoot = root.toAbsolutePath().normalize();
            Path candidatePath = normalizedRoot.resolve(filePath).normalize();
            if (!candidatePath.startsWith(normalizedRoot)) {
                return Optional.empty();
            }

            // 2. 解析真实路径，阻止符号链接或目录联接逃逸项目根目录。
            Path realRoot = normalizedRoot.toRealPath();
            Path realFile = candidatePath.toRealPath();
            if (!realFile.startsWith(realRoot) || !Files.isRegularFile(realFile)) {
                return Optional.empty();
            }

            // 3. 一次读取文件字节，同时生成 UTF-8 内容和版本 hash。
            byte[] bytes = Files.readAllBytes(realFile);
            return Optional.of(new RagSourceFile(
                    realFile,
                    filePath,
                    new String(bytes, StandardCharsets.UTF_8),
                    RagFileHash.sha256Prefix(bytes)
            ));
        } catch (IOException | InvalidPathException | SecurityException e) {
            return Optional.empty();
        }
    }
}
