package com.dbts.glyahhaigeneratecode.config;

import lombok.RequiredArgsConstructor;
import org.springframework.context.annotation.Configuration;
import org.springframework.web.servlet.config.annotation.CorsRegistry;
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer;

/**
 * 跨站配置。
 *
 * <p>允许来源来自 {@link SecurityProperties}，与 WebSocket 握手共用同一份白名单。
 * 未配置或配置为通配符时启动失败，不放宽为默认值。
 */
@Configuration
@RequiredArgsConstructor
public class CorsConfig implements WebMvcConfigurer {

    private final SecurityProperties securityProperties;

    @Override
    public void addCorsMappings(CorsRegistry registry) {
        // 先校验来源，配置缺失时立即失败。
        String[] origins = securityProperties.requireAllowedOriginPatterns();
        // 覆盖所有请求
        registry.addMapping("/**")
                // 允许发送 Cookie
                .allowCredentials(true)
                // 来源由部署配置决定，不用通配符
                .allowedOriginPatterns(origins)
                .allowedMethods("GET", "POST", "PUT", "DELETE", "OPTIONS")
                .allowedHeaders("*")
                .exposedHeaders("*");
    }
}
