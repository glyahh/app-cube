package com.dbts.glyahhaigeneratecode.runtime;

import com.dbts.glyahhaigeneratecode.config.RuntimeProperties;
import com.dbts.glyahhaigeneratecode.exception.ErrorCode;
import com.dbts.glyahhaigeneratecode.exception.MyException;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.io.RandomAccessFile;
import java.nio.charset.StandardCharsets;
import java.nio.file.DirectoryStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;

/**
 * 运行日志的存放与读取。
 *
 * <p>按 {@code {appId}/{runNo}/console.log} 组织：同一项目每次运行的日志互不覆盖，
 * 排查时能直接说清「是第几次运行出的问题」。
 *
 * <p>容量控制分两层，缺一不可：
 * <ul>
 *   <li><b>单次运行封顶</b>：超过上限时只保留末尾一段，并在返回内容里明确标注被截断 ——
 *       不能悄悄丢掉开头，那会让用户以为日志本来就这么短。</li>
 *   <li><b>运行次数封顶</b>：只保留最近 N 次运行目录，更早的整目录删除。</li>
 * </ul>
 *
 * <p>写入侧由容器内的 runner 脚本负责（日志目录挂进了容器），平台只读不写，
 * 唯一的例外是停止运行后把超限文件裁到上限 —— 那时已经没人持有它的写入句柄了。
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class RuntimeLogService {

    private final RuntimeProperties props;

    private static final String CONSOLE_LOG = "console.log";

    /** 一次最多返回的字节数，避免把几十兆日志塞进一个响应。 */
    private static final int MAX_READ_BYTES = 256 * 1024;

    /** 本次运行的日志目录。 */
    public Path runLogDir(Long appId, int runNo) {
        return props.getLogRoot().resolve(String.valueOf(appId)).resolve(String.valueOf(runNo));
    }

    /** 日志文件路径。 */
    public Path consoleLogPath(Long appId, int runNo) {
        return runLogDir(appId, runNo).resolve(CONSOLE_LOG);
    }

    /** 准备本次运行的日志目录（容器启动前调用，挂载点必须存在）。 */
    public Path prepareRunDir(Long appId, int runNo) {
        Path dir = runLogDir(appId, runNo);
        try {
            Files.createDirectories(dir);
        } catch (IOException e) {
            throw new MyException(ErrorCode.SYSTEM_ERROR, "创建运行日志目录失败：" + e.getMessage());
        }
        return dir;
    }

    /**
     * 只保留最近 N 次运行的日志目录。
     *
     * <p>在每次启动新运行时调用：此时新目录已建好，删的是更早的。
     */
    public void pruneOldRuns(Long appId) {
        Path appDir = props.getLogRoot().resolve(String.valueOf(appId));
        if (!Files.isDirectory(appDir)) {
            return;
        }
        List<Path> runDirs = new ArrayList<>();
        try (DirectoryStream<Path> stream = Files.newDirectoryStream(appDir)) {
            for (Path entry : stream) {
                if (Files.isDirectory(entry)) {
                    runDirs.add(entry);
                }
            }
        } catch (IOException e) {
            log.warn("扫描运行日志目录失败 appId={}: {}", appId, e.getMessage());
            return;
        }

        // 目录名就是启动编号，按数值倒序排；非数字目录名（异常残留）排最后
        runDirs.sort(Comparator.comparingLong(RuntimeLogService::runNoOf).reversed());

        for (int i = props.getLogRetainRuns(); i < runDirs.size(); i++) {
            deleteRecursively(runDirs.get(i));
        }
    }

    private static long runNoOf(Path dir) {
        try {
            return Long.parseLong(dir.getFileName().toString());
        } catch (NumberFormatException e) {
            return -1L;
        }
    }

    private static void deleteRecursively(Path dir) {
        try (var walk = Files.walk(dir)) {
            walk.sorted(Comparator.reverseOrder()).forEach(p -> {
                try {
                    Files.deleteIfExists(p);
                } catch (IOException ignored) {
                    // 删不掉就留着，下次再试
                }
            });
        } catch (IOException e) {
            log.warn("删除旧运行日志目录失败 {}: {}", dir, e.getMessage());
        }
    }

    /** 读取结果：内容 + 是否被截断。 */
    public record LogSlice(String content, boolean truncated, long totalBytes) {
    }

    /**
     * 读取某次运行的日志末尾。
     *
     * @param appId    应用 id
     * @param runNo    启动编号
     * @param maxBytes 本次最多返回多少字节（上限 {@link #MAX_READ_BYTES}）
     */
    public LogSlice read(Long appId, int runNo, int maxBytes) {
        Path file = consoleLogPath(appId, runNo);
        if (!Files.isRegularFile(file)) {
            return new LogSlice("", false, 0L);
        }
        int limit = Math.max(1024, Math.min(maxBytes <= 0 ? MAX_READ_BYTES : maxBytes, MAX_READ_BYTES));
        try {
            long size = Files.size(file);
            long from = Math.max(0, size - limit);
            try (RandomAccessFile raf = new RandomAccessFile(file.toFile(), "r")) {
                raf.seek(from);
                byte[] buffer = new byte[(int) (size - from)];
                raf.readFully(buffer);
                String content = new String(buffer, StandardCharsets.UTF_8);
                // 从头截断时首个字符可能是半个多字节字符，丢掉这一行残片
                if (from > 0) {
                    int firstBreak = content.indexOf('\n');
                    content = firstBreak >= 0 ? content.substring(firstBreak + 1) : content;
                }
                return new LogSlice(content, from > 0, size);
            }
        } catch (IOException e) {
            log.warn("读取运行日志失败 appId={} runNo={}: {}", appId, runNo, e.getMessage());
            return new LogSlice("", false, 0L);
        }
    }

    /**
     * 把超限的日志裁到上限。
     *
     * <p>只在运行结束后调用：运行中容器持有该文件的写入句柄，
     * 此时截断会让后续写入落在稀疏偏移上，文件大小和内容对不上。
     * 保留末尾而不是开头 —— 出错信息总在最后。
     */
    public void trimIfOversized(Long appId, int runNo) {
        Path file = consoleLogPath(appId, runNo);
        if (!Files.isRegularFile(file)) {
            return;
        }
        try {
            long size = Files.size(file);
            long cap = props.getLogMaxBytes();
            if (size <= cap) {
                return;
            }
            long from = size - cap;
            byte[] tail;
            try (RandomAccessFile raf = new RandomAccessFile(file.toFile(), "r")) {
                raf.seek(from);
                tail = new byte[(int) cap];
                raf.readFully(tail);
            }
            String text = new String(tail, StandardCharsets.UTF_8);
            int firstBreak = text.indexOf('\n');
            if (firstBreak >= 0) {
                text = text.substring(firstBreak + 1);
            }
            String marker = String.format(
                    "[平台] 日志超过 %d MiB 上限，已只保留末尾部分（原始 %d 字节）%n",
                    cap / 1024 / 1024, size);
            Files.writeString(file, marker + text);
            log.info("运行日志已裁剪 appId={} runNo={} 原大小={} 保留={}", appId, runNo, size, cap);
        } catch (IOException e) {
            log.warn("裁剪运行日志失败 appId={} runNo={}: {}", appId, runNo, e.getMessage());
        }
    }

    /** 删除某次运行的日志目录（应用被删除时调用）。 */
    public void removeAppLogs(Long appId) {
        deleteRecursively(props.getLogRoot().resolve(String.valueOf(appId)));
    }
}
