package com.dbts.glyahhaigeneratecode.config;

import org.junit.jupiter.api.Test;
import org.springframework.web.cors.CorsConfiguration;
import org.springframework.web.servlet.config.annotation.CorsRegistry;

import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

class CorsConfigTest {

    /** CorsRegistry#getCorsConfigurations 为 protected，测试子类只用于读取已注册配置。 */
    private static class CapturingCorsRegistry extends CorsRegistry {
        CorsConfiguration configuration(String path) {
            return getCorsConfigurations().get(path);
        }
    }

    @Test
    void usesConfiguredOriginsAndKeepsCredentials() {
        SecurityProperties properties = new SecurityProperties();
        properties.setAllowedOriginPatterns(List.of("http://127.0.0.1:5173"));

        CapturingCorsRegistry registry = new CapturingCorsRegistry();
        new CorsConfig(properties).addCorsMappings(registry);

        CorsConfiguration configuration = registry.configuration("/**");
        assertEquals(List.of("http://127.0.0.1:5173"), configuration.getAllowedOriginPatterns());
        assertEquals(Boolean.TRUE, configuration.getAllowCredentials());
    }

    @Test
    void refusesEmptyOrigins() {
        SecurityProperties properties = new SecurityProperties();
        CorsRegistry registry = new CorsRegistry();

        assertThrows(IllegalStateException.class, () -> new CorsConfig(properties).addCorsMappings(registry));
    }

    @Test
    void refusesWildcardOrigin() {
        SecurityProperties properties = new SecurityProperties();
        properties.setAllowedOriginPatterns(List.of("*"));
        CorsRegistry registry = new CorsRegistry();

        assertThrows(IllegalStateException.class, () -> new CorsConfig(properties).addCorsMappings(registry));
    }

    @Test
    void ignoresBlankEntriesAndStillRefusesWhenNothingRemains() {
        SecurityProperties properties = new SecurityProperties();
        properties.setAllowedOriginPatterns(List.of("", "   "));
        CorsRegistry registry = new CorsRegistry();

        assertThrows(IllegalStateException.class, () -> new CorsConfig(properties).addCorsMappings(registry));
    }
}
