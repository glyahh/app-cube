package com.dbts.glyahhaigeneratecode.config;

import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.context.annotation.Configuration;

import java.util.List;

/**
 * 跨站来源配置。
 *
 * <p>REST 与 WebSocket 共用同一份白名单。为空或含通配符时拒绝启动，
 * 避免生产环境忘记配置却静默使用本地来源。
 */
@Data
@Configuration
@ConfigurationProperties(prefix = "app.security")
public class SecurityProperties {

    /** 允许的来源模式，按 Spring 的 origin pattern 语法匹配。 */
    private List<String> allowedOriginPatterns = List.of();

    /**
     * 返回可用于 CORS 与 WebSocket 的来源数组。
     *
     * @throws IllegalStateException 未配置、全为空白或包含通配符
     */
    public String[] requireAllowedOriginPatterns() {
        List<String> patterns = allowedOriginPatterns == null ? List.of()
                : allowedOriginPatterns.stream()
                        .filter(pattern -> pattern != null && !pattern.isBlank())
                        .toList();
        if (patterns.isEmpty()) {
            throw new IllegalStateException("app.security.allowed-origin-patterns 未配置，拒绝启动");
        }
        if (patterns.contains("*")) {
            throw new IllegalStateException("app.security.allowed-origin-patterns 不允许使用通配符 *");
        }
        return patterns.toArray(String[]::new);
    }
}
