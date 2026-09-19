package com.dbts.glyahhaigeneratecode.runtime;

import com.dbts.glyahhaigeneratecode.mapper.AppRuntimeIncidentMapper;
import com.dbts.glyahhaigeneratecode.model.Entity.AppRuntimeIncident;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.stereotype.Component;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * 运行异常检测。
 *
 * <p>判错刻意保守：Plan 明确「普通警告或仅写入 stderr 不直接判错」。所以只认三类信号 ——
 * 非零退出、未处理异常的标准特征行、明确的致命级别标记。识别不了的内容仍然留在日志里，
 * 只是不弹窗。
 *
 * <p>去重按 {@code (appId, runNo, fingerprint)}：错误行归一化后取摘要，
 * 所以同一处错误刷一百遍也只留一条记录、只弹一次框。数据库侧有唯一索引兜底，
 * 应用层的先查后插只是为了少一次异常开销，不作为正确性依据。
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class RuntimeIncidentDetector {

    private final AppRuntimeIncidentMapper incidentMapper;

    /** 启动失败窗口：在这个时长内退出算「启动异常」，之后算「运行异常」。 */
    private static final long STARTUP_WINDOW_SECONDS = 150;

    public static final String KIND_STARTUP = "STARTUP_FAILURE";
    public static final String KIND_RUNTIME = "RUNTIME_FAILURE";

    public static final String STATUS_OPEN = "OPEN";
    public static final String STATUS_DISMISSED = "DISMISSED";
    public static final String STATUS_REPAIRING = "REPAIRING";
    public static final String STATUS_REPAIRED = "REPAIRED";

    /**
     * 明确判错的日志特征。都要求是「未处理/致命」语义，不能用宽泛的 error 关键字 ——
     * 业务代码里打一句 error 级日志是常态，那不是崩溃。
     */
    private static final List<Pattern> FATAL_PATTERNS = List.of(
            Pattern.compile("Traceback \\(most recent call last\\)"),
            Pattern.compile("Exception in thread \"[^\"]*\""),
            Pattern.compile("UnhandledPromiseRejection", Pattern.CASE_INSENSITIVE),
            Pattern.compile("\\bUnhandled exception\\b", Pattern.CASE_INSENSITIVE),
            Pattern.compile("\\bFATAL\\b"),
            Pattern.compile("\\bpanic: "),
            Pattern.compile("\\bSegmentation fault\\b"),
            Pattern.compile("\\bOutOfMemoryError\\b"),
            Pattern.compile("\\bERR_[A-Z_]+\\b"),
            Pattern.compile("^\\s*Caused by: .+", Pattern.MULTILINE),
            Pattern.compile("Address already in use"),
            Pattern.compile("EADDRINUSE"),
            Pattern.compile("Cannot find module '"),
            Pattern.compile("ModuleNotFoundError: No module named")
    );

    /** 一条脱敏规则：命中后按 replacement 替换（可带 $1/$2 保留键名）。 */
    private record SecretRule(Pattern pattern, String replacement) {
    }

    /**
     * 敏感值：诊断包要把它们抹掉再交给模型或展示。
     *
     * <p><b>顺序有讲究。</b>对同一条文本从左到右依次应用，前面的规则会改变后面的输入：
     * <ol>
     *   <li>{@code Bearer} 必须排在「键值」之前。否则 {@code Authorization: Bearer sk-xxx}
     *       会先被键值规则匹配掉 {@code Bearer} 这个词（值部分只吃非空白），
     *       剩下的 token 反过来没规则认领，原样留在日志里 —— 这正是修复前的实际行为。</li>
     *   <li>连接串排在键值之前，先把整条 URI 抹掉，里面的口令才不会漏出来。</li>
     * </ol>
     */
    private static final List<SecretRule> SECRET_RULES = List.of(
            new SecretRule(Pattern.compile("(?i)\\bBearer\\s+[A-Za-z0-9._\\-]{8,}"),
                    "Bearer [已隐去]"),
            new SecretRule(Pattern.compile("(?i)\\bsk-[A-Za-z0-9_\\-]{8,}"),
                    "[已隐去]"),
            // 只写 postgres 会漏掉 postgresql —— 后者才是 JDBC 串里最常见的写法
            new SecretRule(Pattern.compile(
                    "(?i)\\b(postgres|postgresql|mysql|redis|mongodb|amqp|mssql)://[^\\s\"']+"),
                    "[已隐去]"),
            // 键值形态：键名含敏感词就抹值，键名与分隔符原样保留。
            // 前后各留一段 \w*，才盖得住 AWS_SECRET_ACCESS_KEY 这种敏感词在中间的名字。
            new SecretRule(Pattern.compile(
                    "(?i)([A-Za-z0-9_]*(?:password|passwd|pwd|secret|token|api[_-]?key"
                            + "|access[_-]?key|private[_-]?key|credential|authorization)[A-Za-z0-9_]*)"
                            + "(\\s*[=:]\\s*)(\\S+)"),
                    "$1$2[已隐去]")
    );

    /**
     * 记录一次异常退出。
     *
     * @param appId     应用 id
     * @param userId    用户 id
     * @param runNo     启动编号
     * @param exitCode  退出码
     * @param durationSeconds 本次运行已持续时长
     * @param logs      日志尾部（用于提取诊断片段）
     * @param command   启动命令（进诊断包）
     * @param sourceVersion 运行版本
     * @return 新建的事件；已存在同指纹记录时返回 null
     */
    public AppRuntimeIncident recordAbnormalExit(Long appId, Long userId, int runNo, Integer exitCode,
                                                 long durationSeconds, String logs, String command,
                                                 String sourceVersion) {
        String kind = durationSeconds <= STARTUP_WINDOW_SECONDS ? KIND_STARTUP : KIND_RUNTIME;
        String summary = "进程异常退出（退出码 " + (exitCode == null ? "未知" : exitCode) + "）";
        String detail = buildDiagnostic(logs, command, sourceVersion, exitCode, durationSeconds);
        String fingerprint = fingerprint("exit:" + exitCode + "|" + kind);
        return persist(appId, userId, runNo, fingerprint, kind, summary, detail, exitCode);
    }

    /**
     * 从日志里扫明确错误。返回本次新建的事件。
     *
     * <p>同一个 run 内同一指纹只建一条，所以重复扫描是安全的。
     */
    public List<AppRuntimeIncident> scanLogsForErrors(Long appId, Long userId, int runNo,
                                                      String logs, String command, String sourceVersion,
                                                      boolean startupPhase) {
        List<AppRuntimeIncident> created = new ArrayList<>();
        if (logs == null || logs.isBlank()) {
            return created;
        }
        for (String line : logs.split("\\R")) {
            if (line.isBlank() || !matchesFatal(line)) {
                continue;
            }
            String fingerprint = fingerprint(line);
            String summary = abbreviate(sanitize(line), 200);
            String detail = buildDiagnostic(logs, command, sourceVersion, null, null);
            AppRuntimeIncident incident = persist(appId, userId, runNo, fingerprint,
                    startupPhase ? KIND_STARTUP : KIND_RUNTIME, summary, detail, null);
            if (incident != null) {
                created.add(incident);
            }
        }
        return created;
    }

    private static boolean matchesFatal(String line) {
        for (Pattern pattern : FATAL_PATTERNS) {
            if (pattern.matcher(line).find()) {
                return true;
            }
        }
        return false;
    }

    private AppRuntimeIncident persist(Long appId, Long userId, int runNo, String fingerprint,
                                       String kind, String summary, String detail, Integer exitCode) {
        AppRuntimeIncident incident = AppRuntimeIncident.builder()
                .appId(appId)
                .userId(userId)
                .runNo(runNo)
                .fingerprint(fingerprint)
                .kind(kind)
                .status(STATUS_OPEN)
                .summary(summary)
                .detail(detail)
                .exitCode(exitCode)
                .detectedAt(LocalDateTime.now())
                .createTime(LocalDateTime.now())
                .updateTime(LocalDateTime.now())
                .isDelete(0)
                .build();
        try {
            incidentMapper.insertSelective(incident);
            return incident;
        } catch (DuplicateKeyException e) {
            // 唯一索引挡住了重复：这正是不重复弹框的实现方式，不是错误
            return null;
        }
    }

    /**
     * 生成脱敏诊断内容。
     *
     * <p>内容用于两处：展示给用户、以及交给模型做修复。所以既要够定位问题，
     * 又不能带凭据 —— 统一在这里过一遍脱敏。
     */
    public String buildDiagnostic(String logs, String command, String sourceVersion,
                                  Integer exitCode, Long durationSeconds) {
        StringBuilder sb = new StringBuilder();
        sb.append("运行环境：Docker 沙箱（Node.js / Python / Java / SQLite）\n");
        sb.append("启动命令：").append(command == null ? "（未记录）" : sanitize(command)).append('\n');
        sb.append("运行版本：").append(sourceVersion == null ? "（未记录）" : sourceVersion).append('\n');
        if (exitCode != null) {
            sb.append("退出码：").append(exitCode).append('\n');
        }
        if (durationSeconds != null) {
            sb.append("持续时长：").append(durationSeconds).append(" 秒\n");
        }
        if (logs != null && !logs.isBlank()) {
            sb.append("\n日志片段（末尾 200 行）：\n");
            String tail = tailLines(logs, 200);
            sb.append(sanitize(tail));
        }
        return sb.toString();
    }

    private static String tailLines(String text, int count) {
        String[] lines = text.split("\\R");
        if (lines.length <= count) {
            return text;
        }
        StringBuilder sb = new StringBuilder();
        for (int i = lines.length - count; i < lines.length; i++) {
            sb.append(lines[i]).append('\n');
        }
        return sb.toString();
    }

    /** 抹掉疑似凭据的值。 */
    public static String sanitize(String text) {
        if (text == null || text.isEmpty()) {
            return text;
        }
        String result = text;
        for (SecretRule rule : SECRET_RULES) {
            result = rule.pattern().matcher(result).replaceAll(rule.replacement());
        }
        return result;
    }

    private static String abbreviate(String text, int max) {
        if (text == null) {
            return "";
        }
        return text.length() <= max ? text : text.substring(0, max) + "…";
    }

    /**
     * 错误指纹：先把易变部分抹平（时间戳、数字、十六进制、路径），再取摘要。
     *
     * <p>不这样做的话，同一处错误每行都因为时间戳不同而变成不同指纹，去重就失效了。
     */
    public static String fingerprint(String rawLine) {
        String normalized = sanitize(rawLine == null ? "" : rawLine)
                .replaceAll("\\d{4}-\\d{2}-\\d{2}[T ]\\d{2}:\\d{2}:\\d{2}(\\.\\d+)?", "<ts>")
                .replaceAll("\\b\\d{2}:\\d{2}:\\d{2}\\b", "<time>")
                .replaceAll("0x[0-9a-fA-F]+", "<hex>")
                .replaceAll("\\b\\d+\\b", "<n>")
                .replaceAll("[A-Za-z]:\\\\[^\\s\"']+", "<path>")
                .replaceAll("/(?:[\\w.\\-]+/)+[\\w.\\-]+", "<path>")
                .replaceAll("\\s+", " ")
                .trim();
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            byte[] hash = digest.digest(normalized.getBytes(StandardCharsets.UTF_8));
            return HexFormat.of().formatHex(hash).substring(0, 32);
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 不可用", e);
        }
    }
}
