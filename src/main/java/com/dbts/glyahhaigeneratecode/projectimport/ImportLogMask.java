package com.dbts.glyahhaigeneratecode.projectimport;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.regex.Pattern;
import java.util.regex.Matcher;

/**
 * 执行日志落盘前的秘密遮盖。
 *
 * 导入工程在安装/运行时可能把自己的密钥打印到 stdout/stderr，这些输出会落到执行日志文件。
 * 这里按固定规则遮盖常见密钥形态，保留其余内容用于定位问题；不是"输出已安全"的证明，
 * 泄漏结论仍以专用虚构哨兵的整链路验收为准。
 */
final class ImportLogMask {

    /** 平台自身环境变量：命中值逐字替换，避免平台密钥经日志外泄。 */
    private static final List<String> PLATFORM_SECRET_NAMES = List.of(
            "AGENT_MODEL_API_KEY", "AGENT_SUMMARIZER_API_KEY", "DEEPSEEK_API_KEY", "DASHSCOPE_API_KEY",
            "ZHIPU_API_KEY", "ALIYUN_OSS_ACCESS_KEY_ID", "ALIYUN_OSS_ACCESS_KEY_SECRET", "GITEE_TOKEN", "SCM_GITEE_TOKEN");

    private static final Pattern GENERIC_KEY = Pattern.compile("\\b(?:sk-[A-Za-z0-9_\\-]{8,}|LTAI[A-Za-z0-9]{8,})\\b");
    private static final Pattern GIT_TOKEN = Pattern.compile("\\b(?:gh[pousr]_[A-Za-z0-9]{20,}|github_pat_[A-Za-z0-9_]{20,}|glpat-[A-Za-z0-9_\\-]{16,})\\b");
    private static final Pattern BEARER = Pattern.compile("(Bearer\\s+)[A-Za-z0-9._\\-]{8,}", Pattern.CASE_INSENSITIVE);
    private static final Pattern ENV_ASSIGN = Pattern.compile(
            "\\b([A-Z0-9_]+)[\"']?\\s*[:=]\\s*(?:\"[^\"\\r\\n]*(?:\"|$)|'[^'\\r\\n]*(?:'|$)|[^\\s,;}]+)",
            Pattern.CASE_INSENSITIVE);
    private static final Pattern SECRET_NAME = Pattern.compile("KEY|TOKEN|SECRET|PASSWORD|PASSWD|CREDENTIAL", Pattern.CASE_INSENSITIVE);

    private ImportLogMask() {
    }

    /**
     * 遮盖一段文本里的密钥形态。只做替换，不改变行结构，便于继续排查失败原因。
     */
    static String mask(String text) {
        if (text == null || text.isEmpty()) {
            return text;
        }
        // 1. 平台自身环境变量值优先逐字替换，避免正则漏配导致真实密钥外泄。
        String masked = text;
        for (String name : PLATFORM_SECRET_NAMES) {
            String value = System.getenv(name);
            if (value != null && value.length() >= 6) {
                masked = masked.replace(value, "***");
            }
        }
        // 2. 通用密钥形态与赋值形式。
        masked = GENERIC_KEY.matcher(masked).replaceAll("***");
        masked = GIT_TOKEN.matcher(masked).replaceAll("***");
        masked = BEARER.matcher(masked).replaceAll("$1***");
        // 先识别赋值，再判断键名；避免两个重叠的贪婪匹配在长日志上反复回溯。
        Matcher assignments = ENV_ASSIGN.matcher(masked);
        var result = new StringBuilder();
        while (assignments.find()) {
            if (SECRET_NAME.matcher(assignments.group(1)).find()) {
                assignments.appendReplacement(result, Matcher.quoteReplacement(assignments.group(1) + "=***"));
            } else {
                // 非敏感赋值必须原样保留，不能因为扫描过就把匹配前的日志片段丢掉。
                assignments.appendReplacement(result, Matcher.quoteReplacement(assignments.group()));
            }
        }
        assignments.appendTail(result);
        return result.toString();
    }

    /**
     * 每条流独立拼行，完整行遮盖后才交给落盘出口；关闭时处理最后一行。
     * 非 UTF-8 字节用替换字符呈现，不能因此跳过秘密保护。原始工程文件不受影响。
     */
    static OutputStream lines(OutputStream output) {
        return new OutputStream() {
            // 总输入由 reader 按执行输出限额控制；不完整行只占用本次限额内的内存，不落原文。
            private final ByteArrayOutputStream pending = new ByteArrayOutputStream();
            private boolean closed;

            @Override public synchronized void write(int value) throws IOException {
                write(new byte[]{(byte) value}, 0, 1);
            }

            @Override public synchronized void write(byte[] bytes, int offset, int length) throws IOException {
                java.util.Objects.checkFromIndexSize(offset, length, bytes.length);
                if (closed) throw new IOException("日志流已关闭。");
                // 1. UTF-8 和秘密可以跨读取分块；只在真正的换行处分开。
                int start = offset;
                for (int index = offset; index < offset + length; index++) {
                    if (bytes[index] != '\n') continue;
                    pending.write(bytes, start, index - start + 1);
                    emit();
                    start = index + 1;
                }
                pending.write(bytes, start, offset + length - start);
            }

            private void emit() throws IOException {
                // 2. 无论是否包含非法字节，落盘出口只接收遮盖后的文本。
                output.write(mask(pending.toString(StandardCharsets.UTF_8)).getBytes(StandardCharsets.UTF_8));
                output.flush();
                pending.reset();
            }

            @Override public synchronized void close() throws IOException {
                if (closed) return;
                closed = true;
                if (pending.size() > 0) emit();
                // 共享文件由执行生命周期关闭，不由单条 stdout/stderr 提前关闭。
            }
        };
    }
}
