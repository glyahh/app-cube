package com.dbts.glyahhaigeneratecode.integration;

import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.Tag;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.utility.DockerImageName;

/** 每个测试 JVM 独立数据库和 Redis；Docker 不可用时测试失败，不记成跳过通过。 */
@Tag("integration")
public abstract class InfrastructureIntegrationTest {
    protected static final PostgreSQLContainer<?> POSTGRES = new PostgreSQLContainer<>(
            DockerImageName.parse("postgres:17-alpine"))
            .withDatabaseName("app_cube_test").withUsername("test").withPassword("test");
    protected static final GenericContainer<?> REDIS = new GenericContainer<>(
            DockerImageName.parse("redis:7-alpine")).withExposedPorts(6379);
    protected static JdbcTemplate jdbc;

    protected static synchronized void startInfrastructure() {
        if (!POSTGRES.isRunning()) POSTGRES.start();
        if (!REDIS.isRunning()) REDIS.start();
        if (jdbc != null) return;
        Flyway.configure().dataSource(POSTGRES.getJdbcUrl(), POSTGRES.getUsername(), POSTGRES.getPassword())
                .schemas("biz").defaultSchema("biz").createSchemas(true)
                .baselineOnMigrate(true).baselineVersion("2")
                .locations("classpath:db/migration").load().migrate();
        jdbc = new JdbcTemplate(new DriverManagerDataSource(POSTGRES.getJdbcUrl(),
                POSTGRES.getUsername(), POSTGRES.getPassword()));
    }

    @DynamicPropertySource
    static void infrastructureProperties(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", () -> {
            startInfrastructure();
            return POSTGRES.getJdbcUrl() + "&currentSchema=biz";
        });
        registry.add("spring.datasource.username", POSTGRES::getUsername);
        registry.add("spring.datasource.password", POSTGRES::getPassword);
        registry.add("spring.flyway.user", POSTGRES::getUsername);
        registry.add("spring.flyway.password", POSTGRES::getPassword);
        registry.add("spring.data.redis.host", () -> {
            startInfrastructure();
            return REDIS.getHost();
        });
        registry.add("spring.data.redis.port", () -> {
            startInfrastructure();
            return REDIS.getMappedPort(6379);
        });
        registry.add("agent.pool.core-size", () -> 0);
        registry.add("agent.runtime.python-executable", () -> "python");
    }
}
