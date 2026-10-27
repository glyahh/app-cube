package com.dbts.glyahhaigeneratecode.agentauth;

import com.dbts.glyahhaigeneratecode.ExternalTest;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;

import java.time.Duration;
import java.time.Instant;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.mockito.Mockito.mock;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 两轴判定的真实数据库行为。
 *
 * <p>需要 AGENT_POSTGRES_DSN 与 V44–V46 已应用；缺失时整类跳过，
 * 不把「没跑」记成「通过」。
 */
@ExternalTest
class AgentPermissionGateJdbcTest {
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
        // 环境里是 psycopg 用的 DSN：密码可能被百分号编码，且带 -csearch_path 参数。
        // 这里解析后按 JDBC 形态重建，避免驱动拿到未解码的密码。
        String raw = DSN.startsWith("jdbc:") ? DSN.substring(5) : DSN;
        java.net.URI uri = java.net.URI.create(raw);
        String userInfo = uri.getUserInfo() == null ? "" : uri.getUserInfo();
        String user = userInfo.contains(":") ? userInfo.substring(0, userInfo.indexOf(':')) : userInfo;
        String password = userInfo.contains(":") ? userInfo.substring(userInfo.indexOf(':') + 1) : "";
        var source = new DriverManagerDataSource();
        source.setDriverClassName("org.postgresql.Driver");
        source.setUrl("jdbc:postgresql://" + uri.getHost() + ":" + uri.getPort() + uri.getPath());
        source.setUsername(java.net.URLDecoder.decode(user, java.nio.charset.StandardCharsets.UTF_8));
        source.setPassword(java.net.URLDecoder.decode(password, java.nio.charset.StandardCharsets.UTF_8));
        return new JdbcTemplate(source);
    }

    private record Fixture(long ownerId, long appId, UUID sessionId) { }

    /**
     * 先回收所有未关闭会话。
     *
     * <p>同一张表的用例之间会互相挡住（唯一索引按 app 生效），
     * 需要各自清理，否则顺序一变就假失败。
     */
    private void reset(JdbcTemplate jdbc) {
        jdbc.update("UPDATE biz.agent_edit_session SET state='CANCELLED',closed_at=now() "
                + "WHERE closed_at IS NULL");
        jdbc.update("DELETE FROM biz.agent_tool_permission WHERE session_id IN "
                + "(SELECT session_id FROM biz.agent_edit_session WHERE closed_at IS NOT NULL)");
        jdbc.update("DELETE FROM biz.agent_change_ledger WHERE session_id IN "
                + "(SELECT session_id FROM biz.agent_edit_session WHERE closed_at IS NOT NULL)");
        jdbc.update("DELETE FROM biz.agent_candidate_review WHERE session_id IN "
                + "(SELECT session_id FROM biz.agent_edit_session WHERE closed_at IS NOT NULL)");
        jdbc.update("DELETE FROM biz.agent_edit_session WHERE closed_at IS NOT NULL");
    }

    private Fixture openFixture(JdbcTemplate jdbc, String approval, String sandbox) {
        reset(jdbc);
        var app = jdbc.queryForList("SELECT id, user_id FROM biz.app WHERE is_delete=0 LIMIT 1")
                .getFirst();
        long appId = ((Number) app.get("id")).longValue();
        long ownerId = ((Number) app.get("user_id")).longValue();
        UUID sessionId = UUID.randomUUID();
        jdbc.update("""
                INSERT INTO biz.agent_edit_session(session_id,owner_id,app_id,run_id,source_hash,
                    sandbox_mode,approval_mode,policy_revision,session_epoch,server_generation,
                    state,expires_at)
                VALUES(?,?,?,1,?,?,?,0,?,?,'EDITING',?)
                """, sessionId, ownerId, appId, "0".repeat(64), sandbox, approval,
                UUID.randomUUID(), UUID.randomUUID(),
                java.sql.Timestamp.from(Instant.now().plus(Duration.ofMinutes(30))));
        return new Fixture(ownerId, appId, sessionId);
    }

    private UUID insertTicket(JdbcTemplate jdbc, UUID sessionId, String risk) {
        UUID id = UUID.randomUUID();
        jdbc.update("""
                INSERT INTO biz.agent_tool_permission(permission_id,session_id,tool,action_hash,
                    action_json,risk,scope_hash,expires_at)
                VALUES(?,?,'write',?,'{}'::jsonb,?,?,now()+interval '5 minutes')
                """, id, sessionId, "a".repeat(64), risk, "b".repeat(64));
        return id;
    }

    private void cleanup(JdbcTemplate jdbc, UUID sessionId) {
        jdbc.update("UPDATE biz.agent_edit_session SET state='CANCELLED',closed_at=now() WHERE session_id=?",
                sessionId);
        jdbc.update("DELETE FROM biz.agent_tool_permission WHERE session_id=?", sessionId);
        // 账本与审阅都外键指向会话，不先清就删不掉会话。
        jdbc.update("DELETE FROM biz.agent_candidate_review WHERE session_id=?", sessionId);
        jdbc.update("DELETE FROM biz.agent_change_ledger WHERE session_id=?", sessionId);
        jdbc.update("DELETE FROM biz.agent_edit_session WHERE session_id=?", sessionId);
    }

    private void assumeReady() {
        org.junit.jupiter.api.Assumptions.assumeTrue(DSN != null, "未配置数据库连接");
        var jdbc = jdbc();
        Integer present = jdbc.queryForObject(
                "SELECT count(*) FROM information_schema.tables WHERE table_schema='biz' AND table_name='agent_edit_session'",
                Integer.class);
        org.junit.jupiter.api.Assumptions.assumeTrue(present != null && present > 0, "V44 迁移未应用");
    }

    @Test
    void manualTicketStartsPending() {
        assumeReady();
        var jdbc = jdbc();
        var fixture = openFixture(jdbc, "MANUAL", "WORKSPACE_WRITE");
        try {
            UUID ticket = insertTicket(jdbc, fixture.sessionId(), "ORDINARY");
            String status = jdbc.queryForObject(
                    "SELECT status FROM biz.agent_tool_permission WHERE permission_id=?", String.class, ticket);
            assertEquals("REQUESTED", status);
        } finally {
            cleanup(jdbc, fixture.sessionId());
        }
    }

    @Test
    void autoSourceRejectedInManualSession() {
        assumeReady();
        var jdbc = jdbc();
        var fixture = openFixture(jdbc, "MANUAL", "WORKSPACE_WRITE");
        try {
            UUID ticket = insertTicket(jdbc, fixture.sessionId(), "ORDINARY");
            assertThrows(org.springframework.dao.DataIntegrityViolationException.class, () ->
                    jdbc.update("UPDATE biz.agent_tool_permission SET status='APPROVED',"
                            + "decision_source='AUTO',revision=revision+1 WHERE permission_id=?", ticket));
        } finally {
            cleanup(jdbc, fixture.sessionId());
        }
    }

    @Test
    void userApprovalAcceptedInManualSession() {
        assumeReady();
        var jdbc = jdbc();
        var fixture = openFixture(jdbc, "MANUAL", "WORKSPACE_WRITE");
        try {
            UUID ticket = insertTicket(jdbc, fixture.sessionId(), "ORDINARY");
            jdbc.update("UPDATE biz.agent_tool_permission SET status='APPROVED',decision_source='USER',"
                    + "revision=revision+1 WHERE permission_id=?", ticket);
            var row = jdbc.queryForMap(
                    "SELECT status, decision_source FROM biz.agent_tool_permission WHERE permission_id=?", ticket);
            assertEquals("APPROVED", row.get("status"));
            assertEquals("USER", row.get("decision_source"));
        } finally {
            cleanup(jdbc, fixture.sessionId());
        }
    }

    @Test
    void expiredSessionHidesPendingTicket() {        assumeReady();
        var jdbc = jdbc();
        var fixture = openFixture(jdbc, "MANUAL", "WORKSPACE_WRITE");
        try {
            UUID ticket = insertTicket(jdbc, fixture.sessionId(), "ORDINARY");
            // 会话已过期但没关闭：执行侧早已超时，票据不能再出现在待决列表里。
            jdbc.update("UPDATE biz.agent_edit_session SET expires_at=now()-interval '1 minute' "
                    + "WHERE session_id=?", fixture.sessionId());

            var pending = new AgentToolPermissionRepository(jdbc)
                    .pendingByApp(fixture.ownerId(), fixture.appId());

            assertNull(pending, "过期会话的票据不该被前端看到");
            assertEquals("REQUESTED", jdbc.queryForObject(
                    "SELECT status FROM biz.agent_tool_permission WHERE permission_id=?",
                    String.class, ticket), "过期不等于自动批准或驳回");
        } finally {
            cleanup(jdbc, fixture.sessionId());
        }
    }

    @Test
    void highRiskRejectedByAiSourceInGuardedAuto() {
        assumeReady();
        var jdbc = jdbc();
        var fixture = openFixture(jdbc, "GUARDED_AUTO", "WORKSPACE_WRITE");
        try {
            // AI 只能批准 ORDINARY；高风险必须回到用户。
            UUID ticket = insertTicket(jdbc, fixture.sessionId(), "HIGH");
            assertThrows(org.springframework.dao.DataIntegrityViolationException.class, () ->
                    jdbc.update("UPDATE biz.agent_tool_permission SET status='APPROVED',"
                            + "decision_source='AI',revision=revision+1 WHERE permission_id=?", ticket));
        } finally {
            cleanup(jdbc, fixture.sessionId());
        }
    }

    @Test
    void openSessionIsUniquePerApp() {
        assumeReady();
        var jdbc = jdbc();
        var fixture = openFixture(jdbc, "MANUAL", "WORKSPACE_WRITE");
        try {
            assertThrows(org.springframework.dao.DataIntegrityViolationException.class, () ->
                    jdbc.update("""
                            INSERT INTO biz.agent_edit_session(session_id,owner_id,app_id,run_id,source_hash,
                                sandbox_mode,approval_mode,policy_revision,session_epoch,server_generation,
                                state,expires_at)
                            VALUES(?,?,?,2,?, 'WORKSPACE_WRITE','MANUAL',0,?,?,'EDITING',now()+interval '30 minutes')
                            """, UUID.randomUUID(), fixture.ownerId(), fixture.appId(),
                            "1".repeat(64), UUID.randomUUID(), UUID.randomUUID()));
        } finally {
            cleanup(jdbc, fixture.sessionId());
        }
    }

    @Test
    void closedSessionAllowsArchive() {
        assumeReady();
        var jdbc = jdbc();
        var fixture = openFixture(jdbc, "MANUAL", "WORKSPACE_WRITE");
        UUID ticket = insertTicket(jdbc, fixture.sessionId(), "ORDINARY");
        jdbc.update("UPDATE biz.agent_edit_session SET state='CANCELLED',closed_at=now() WHERE session_id=?",
                fixture.sessionId());
        // 已关闭会话的票据可随归档清理；未关闭时不可删。
        jdbc.update("DELETE FROM biz.agent_tool_permission WHERE session_id=?", fixture.sessionId());
        jdbc.update("DELETE FROM biz.agent_edit_session WHERE session_id=?", fixture.sessionId());
        assertNotNull(ticket);
        Integer left = jdbc.queryForObject(
                "SELECT count(*) FROM biz.agent_edit_session WHERE session_id=?", Integer.class,
                fixture.sessionId());
        assertEquals(0, left);
    }

    @Test
    void canonicalJsonMatchesPythonShape() throws Exception {
        var json = new ObjectMapper();
        String hash = AgentCanonicalJson.hash(json.readTree("{\"b\":2,\"a\":1}"));
        assertTrue(hash.matches("[0-9a-f]{64}"));
        assertEquals(hash, AgentCanonicalJson.hash(json.readTree("{\"a\":1,\"b\":2}")));
    }

    @Test
    void repeatedPrepareReturnsSameReview() {
        assumeReady();
        var jdbc = jdbc();
        var fixture = openFixture(jdbc, "MANUAL", "WORKSPACE_WRITE");
        try {
            jdbc.update("""
                    INSERT INTO biz.agent_change_ledger(session_id,path,change,before_hash,after_hash,
                        before_blob,after_blob,truncated) VALUES(?,'a.txt','ADDED',NULL,?,NULL,?,false)
                    """, fixture.sessionId(), "a".repeat(64), "x");
            var service = new AgentCandidateReviewService(jdbc,
                    new AgentChangeLedgerRepository(jdbc), new AgentEditSessionRepository(jdbc),
                    mock(AgentChangeRollbackService.class), mock(AgentModelsService.class), new ObjectMapper());

            // 前端重试或轮询会重复调用；第二次必须幂等，不能撞唯一约束变 500。
            var first = service.prepare(fixture.ownerId(), fixture.sessionId(), Instant.now().plusSeconds(600));
            var second = service.prepare(fixture.ownerId(), fixture.sessionId(), Instant.now().plusSeconds(600));

            assertEquals(first.path("reviewId").asText(), second.path("reviewId").asText());
            assertEquals(1, jdbc.queryForObject(
                    "SELECT count(*) FROM biz.agent_candidate_review WHERE session_id=?",
                    Integer.class, fixture.sessionId()));
        } finally {
            cleanup(jdbc, fixture.sessionId());
        }
    }

    @Test
    void latestReviewChecksAppVisibility() {
        assumeReady();
        var jdbc = jdbc();
        var fixture = openFixture(jdbc, "MANUAL", "WORKSPACE_WRITE");
        try {
            // 越权访问必须报错而不是返回 null：后者会让调用方以为「该应用没有审阅」，
            // 也和各接口统一的 40400 不一致。
            var models = mock(AgentModelsService.class);
            var denied = new com.dbts.glyahhaigeneratecode.exception.MyException(
                    com.dbts.glyahhaigeneratecode.exception.ErrorCode.NOT_FOUND_ERROR, "应用不存在。");
            org.mockito.Mockito.doThrow(denied).when(models).requireVisible(1L, 2L);
            var service = new AgentCandidateReviewService(jdbc,
                    new AgentChangeLedgerRepository(jdbc), new AgentEditSessionRepository(jdbc),
                    mock(AgentChangeRollbackService.class), models, new ObjectMapper());

            var error = org.junit.jupiter.api.Assertions.assertThrows(
                    com.dbts.glyahhaigeneratecode.exception.MyException.class,
                    () -> service.latest(1L, 2L));
            assertEquals(denied.getMessage(), error.getMessage());
        } finally {
            cleanup(jdbc, fixture.sessionId());
        }
    }

    @Test
    void consumeIsBoundToActionHashAndSingleUse() {
        assumeReady();
        var jdbc = jdbc();
        var fixture = openFixture(jdbc, "MANUAL", "WORKSPACE_WRITE");
        try {
            UUID ticket = insertTicket(jdbc, fixture.sessionId(), "ORDINARY");
            jdbc.update("UPDATE biz.agent_tool_permission SET status='APPROVED',decision_source='USER',"
                    + "revision=revision+1 WHERE permission_id=?", ticket);
            var repository = new AgentToolPermissionRepository(jdbc);
            String good = "a".repeat(64);

            // 批准后换成别的内容再消费：哈希对不上必须拒绝。
            assertThrows(com.dbts.glyahhaigeneratecode.exception.MyException.class,
                    () -> repository.consume(ticket, "c".repeat(64), UUID.randomUUID()));
            // 正确内容可消费一次。
            repository.consume(ticket, good, UUID.randomUUID());
            // 同一张票再消费：已 CONSUMED，不能再放行第二次副作用。
            assertThrows(com.dbts.glyahhaigeneratecode.exception.MyException.class,
                    () -> repository.consume(ticket, good, UUID.randomUUID()));
        } finally {
            cleanup(jdbc, fixture.sessionId());
        }
    }

    @Test
    void candidateDecisionIsSingleWinner() {
        assumeReady();
        var jdbc = jdbc();
        var fixture = openFixture(jdbc, "MANUAL", "WORKSPACE_WRITE");
        try {
            UUID reviewId = UUID.randomUUID();
            jdbc.update("""
                    INSERT INTO biz.agent_candidate_review(review_id,session_id,review_hash,candidate_hash,
                        changes_json,risk,expires_at) VALUES(?,?,?,?,'[]'::jsonb,'ORDINARY',now()+interval '20 minutes')
                    """, reviewId, fixture.sessionId(), "d".repeat(64), "e".repeat(64));

            // 与 Service.decide 相同的 CAS：并发下只允许一个决定生效，
            // 否则会出现「状态已批准、文件却被另一个拒绝请求删掉」。
            String sql = "UPDATE biz.agent_candidate_review SET status=?,decision_source='USER',"
                    + "revision=revision+1 WHERE review_id=? AND revision=0 AND status='WAITING'";
            int approve = jdbc.update(sql, "APPROVED", reviewId);
            int reject = jdbc.update(sql, "REJECTED", reviewId);

            assertEquals(1, approve + reject, "只能有一个决定成功");
            assertEquals("APPROVED", jdbc.queryForObject(
                    "SELECT status FROM biz.agent_candidate_review WHERE review_id=?",
                    String.class, reviewId));
        } finally {
            cleanup(jdbc, fixture.sessionId());
        }
    }
}
