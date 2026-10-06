package com.dbts.glyahhaigeneratecode.projectimport;

import java.util.List;
import java.util.regex.Pattern;

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
            "\\b([A-Z0-9_]*(?:KEY|TOKEN|SECRET|PASSWORD|PASSWD|CREDENTIAL)[A-Z0-9_]*)\\s*=\\s*([^\\s\"']+)",
            Pattern.CASE_INSENSITIVE);

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
        masked = ENV_ASSIGN.matcher(masked).replaceAll("$1=***");
        return masked;
    }

    /**
     * 判断内容是否为合法 UTF-8 且可安全回写。
     *
     * 容器输出可能是任意字节；不是合法 UTF-8 时不做字符串往返，避免遮盖破坏原始内容。
     */
    static boolean roundTrips(byte[] raw) {
        String text = new String(raw, java.nio.charset.StandardCharsets.UTF_8);
        return java.util.Arrays.equals(text.getBytes(java.nio.charset.StandardCharsets.UTF_8), raw);
    }
}
