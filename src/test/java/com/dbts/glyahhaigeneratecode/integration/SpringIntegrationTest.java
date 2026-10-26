package com.dbts.glyahhaigeneratecode.integration;

import com.dbts.glyahhaigeneratecode.GlyahhAiGenerateCodeApplication;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;

import static org.junit.jupiter.api.Assertions.assertNotNull;

@Tag("integration")
@SpringBootTest(classes = GlyahhAiGenerateCodeApplication.class,
        webEnvironment = SpringBootTest.WebEnvironment.NONE)
@ActiveProfiles("integration")
class SpringIntegrationTest extends InfrastructureIntegrationTest {
    @BeforeAll
    static void startContainers() {
        startInfrastructure();
    }

    @Test
    void applicationContextStartsWithoutLocalSecrets() {
        assertNotNull(jdbc.queryForObject("SELECT 1", Integer.class));
    }
}
