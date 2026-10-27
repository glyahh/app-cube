package com.dbts.glyahhaigeneratecode.agentauth;

import com.dbts.glyahhaigeneratecode.ExternalTest;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;

import java.time.Duration;
import java.time.Instant;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;

/**
 * 会话生命周期：正常关闭、过期回收、崩溃残留不永久挡路。
 *
 * <p>需要 AGENT_POSTGRES_DSN 与 V44–V47；缺失时整类跳过。
 */
@ExternalTest
class AgentEditSessionLifecycleTest {
    private static final String DSN = dsn();

    private static String dsn() {
        String value = System.getenv("AGENT_POSTGRES_DSN");
        if (value != null && !value.isBlank()) return value;
        try {
            var env = java.nio.file.Path.of(".env");
            if (!java.nio.file.Files.exists(env)) return null;
            for (String line : java.nio.file.Files.readAllLines(env)) {
                line = line.trim();
                if (line.startsWith("AGENT_POSTGRES_DSN=")) {
                    return line.substring("AGENT_POSTGRES_DSN=".length()).trim().replaceAll("^\"|\"$", "");
                }
            }
        } catch (Exception ignored) {
            // 读不到就跳过，不伪造通过。
        }
        return null;
    }

    private static JdbcTemplate jdbc() {
        String raw = DSN.startsWith("jdbc:") ? DSN.substring(5) : DSN;
        java.net.URI uri = java.net.URI.create(raw);
        String info = uri.getUserInfo() == null ? "" : uri.getUserInfo();
        String user = info.contains(":") ? info.substring(0, info.indexOf(':')) : info;
        String password = info.contains(":") ? info.substring(info.indexOf(':') + 1) : "";
        var source = new DriverManagerDataSource();
        source.setDriverClassName("org.postgresql.Driver");
        source.setUrl("jdbc:postgresql://" + uri.getHost() + ":" + uri.getPort() + uri.getPath());
        source.setUsername(java.net.URLDecoder.decode(user, java.nio.charset.StandardCharsets.UTF_8));
        source.setPassword(java.net.URLDecoder.decode(password, java.nio.charset.StandardCharsets.UTF_8));
        return new JdbcTemplate(source);
    }

    private JdbcTemplate ready() {
        org.junit.jupiter.api.Assumptions.assumeTrue(DSN != null, "未配置数据库连接");
        var jdbc = jdbc();
        Integer present = jdbc.queryForObject(
                "SELECT count(*) FROM information_schema.tables "
                        + "WHERE table_schema='biz' AND table_name='agent_edit_session'", Integer.class);
        org.junit.jupiter.api.Assumptions.assumeTrue(present != null && present > 0, "V44 迁移未应用");
        return jdbc;
    }

    private record Fixture(long ownerId, long appId) { }

    private Fixture ownerApp(JdbcTemplate jdbc) {
        var app = jdbc.queryForList("SELECT id, user_id FROM biz.app WHERE is_delete=0 LIMIT 1").getFirst();
        return new Fixture(((Number) app.get("user_id")).longValue(), ((Number) app.get("id")).longValue());
    }

    private UUID insert(JdbcTemplate jdbc, Fixture fixture, Instant expiresAt) {
        UUID id = UUID.randomUUID();
        jdbc.update("""
                INSERT INTO biz.agent_edit_session(session_id,owner_id,app_id,run_id,source_hash,
                    sandbox_mode,approval_mode,policy_revision,session_epoch,server_generation,
                    state,expires_at)
                VALUES(?,?,?,1,?, 'WORKSPACE_WRITE','MANUAL',0,?,?,'EDITING',?)
                """, id, fixture.ownerId(), fixture.appId(), "0".repeat(64),
                UUID.randomUUID(), UUID.randomUUID(), java.sql.Timestamp.from(expiresAt));
        return id;
    }

    /** 清理所有未关闭会话与附属记录，保证用例之间互不影响。 */
    private void reset(JdbcTemplate jdbc) {
        jdbc.update("UPDATE biz.agent_edit_session SET state='CANCELLED',closed_at=now() "
                + "WHERE closed_at IS NULL");
        clean(jdbc);
    }

    private void clean(JdbcTemplate jdbc) {
        jdbc.update("DELETE FROM biz.agent_tool_permission WHERE session_id IN "
                + "(SELECT session_id FROM biz.agent_edit_session WHERE closed_at IS NOT NULL)");
        jdbc.update("DELETE FROM biz.agent_change_ledger WHERE session_id IN "
                + "(SELECT session_id FROM biz.agent_edit_session WHERE closed_at IS NOT NULL)");
        jdbc.update("DELETE FROM biz.agent_candidate_review WHERE session_id IN "
                + "(SELECT session_id FROM biz.agent_edit_session WHERE closed_at IS NOT NULL)");
        jdbc.update("DELETE FROM biz.agent_edit_session WHERE closed_at IS NOT NULL");
    }

    @Test
    void expiredSessionIsReclaimedBeforeOpeningNew() {
        var jdbc = ready();
        reset(jdbc);
        var fixture = ownerApp(jdbc);
        try {
            // 造一个已过期但未关闭的会话，模拟进程被杀留下的残留。
            UUID stale = insert(jdbc, fixture, Instant.now().minus(Duration.ofMinutes(1)));
            assertNotNull(stale);

            var repository = new AgentEditSessionRepository(jdbc);
            var fresh = repository.open(fixture.ownerId(), fixture.appId(), 2L, "1".repeat(64),
                    AgentSandboxPolicy.WORKSPACE_WRITE, "MANUAL", 0, UUID.randomUUID(),
                    UUID.randomUUID(), Instant.now().plus(Duration.ofMinutes(30)));

            assertEquals("EDITING", fresh.state());
            String staleState = jdbc.queryForObject(
                    "SELECT state FROM biz.agent_edit_session WHERE session_id=?", String.class, stale);
            assertEquals("FAILED", staleState, "过期会话必须被回收，否则会永久挡住新请求");
        } finally {
            clean(jdbc);
        }
    }

    @Test
    void openSessionBlocksSecondUnexpired() {
        var jdbc = ready();
        reset(jdbc);
        var fixture = ownerApp(jdbc);
        try {
            insert(jdbc, fixture, Instant.now().plus(Duration.ofMinutes(30)));
            var repository = new AgentEditSessionRepository(jdbc);
            // 未过期的并发会话必须被拒，不能静默复用。
            org.junit.jupiter.api.Assertions.assertThrows(
                    org.springframework.dao.DataIntegrityViolationException.class,
                    () -> repository.open(fixture.ownerId(), fixture.appId(), 3L, "2".repeat(64),
                            AgentSandboxPolicy.WORKSPACE_WRITE, "MANUAL", 0, UUID.randomUUID(),
                            UUID.randomUUID(), Instant.now().plus(Duration.ofMinutes(30))));
        } finally {
            clean(jdbc);
        }
    }
}