package com.dbts.glyahhaigeneratecode.config;

import org.junit.jupiter.api.Test;
import org.springframework.boot.context.properties.bind.Binder;
import org.springframework.mock.env.MockEnvironment;

import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

class SecurityPropertiesTest {

    @Test
    void bindsCommaSeparatedOrigins() {
        MockEnvironment environment = new MockEnvironment()
                .withProperty("app.security.allowed-origin-patterns",
                        "http://127.0.0.1:5173,http://localhost:5173");

        SecurityProperties properties = Binder.get(environment)
                .bind("app.security", SecurityProperties.class).get();

        assertEquals(List.of("http://127.0.0.1:5173", "http://localhost:5173"),
                properties.getAllowedOriginPatterns());
    }

    @Test
    void emptyValueBindsAndThenFailsRequirement() {
        MockEnvironment environment = new MockEnvironment()
                .withProperty("app.security.allowed-origin-patterns", "");

        SecurityProperties properties = Binder.get(environment)
                .bind("app.security", SecurityProperties.class).get();

        assertThrows(IllegalStateException.class, properties::requireAllowedOriginPatterns);
    }

    @Test
    void rejectsWildcard() {
        SecurityProperties properties = new SecurityProperties();
        properties.setAllowedOriginPatterns(List.of("*"));

        assertThrows(IllegalStateException.class, properties::requireAllowedOriginPatterns);
    }
}
