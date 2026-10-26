package com.dbts.glyahhaigeneratecode.integration;

import com.dbts.glyahhaigeneratecode.agentauth.AgentEditSessionRepository;
import com.dbts.glyahhaigeneratecode.exception.MyException;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.springframework.dao.DataIntegrityViolationException;

import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;

@Tag("integration")
class AgentEditSessionRepositoryIntegrationTest extends InfrastructureIntegrationTest {
    @BeforeAll
    static void startContainers() {
        startInfrastructure();
    }

    @Test
    void expiredSessionIsReclaimedAndLiveSessionIsUniquePerApp() {
        jdbc.update("INSERT INTO biz.\"user\"(id,user_account,user_password) VALUES(7001,'integration-owner','irreversible-test-hash')");
        jdbc.update("INSERT INTO biz.app(id,app_name,init_prompt,code_gen_type,user_id) VALUES(9001,'integration','','auto',7001)");
        var repository = new AgentEditSessionRepository(jdbc);
        UUID stale = UUID.randomUUID();
        jdbc.update("""
                INSERT INTO biz.agent_edit_session(session_id,owner_id,app_id,run_id,source_hash,
                    sandbox_mode,approval_mode,policy_revision,session_epoch,server_generation,state,expires_at)
                VALUES(?,7001,9001,1,?,'WORKSPACE_WRITE','MANUAL',0,?,?,'EDITING',?)
                """, stale, "0".repeat(64), UUID.randomUUID(), UUID.randomUUID(),
                java.sql.Timestamp.from(Instant.now().minusSeconds(60)));

        var opened = repository.open(7001, 9001, 2, "a".repeat(64), "WORKSPACE_WRITE", "MANUAL",
                0, UUID.randomUUID(), UUID.randomUUID(), Instant.now().plus(5, ChronoUnit.MINUTES));
        assertNotNull(opened.id());
        assertEquals("FAILED", repository.require(stale, 7001).state());
        assertThrows(DataIntegrityViolationException.class, () -> repository.open(7001, 9001, 3,
                "b".repeat(64), "WORKSPACE_WRITE", "MANUAL", 0, UUID.randomUUID(), UUID.randomUUID(),
                Instant.now().plusSeconds(300)));
        repository.close(opened.id(), 7001, "SUCCEEDED");
        assertEquals("SUCCEEDED", repository.require(opened.id(), 7001).state());
        assertThrows(MyException.class, () -> repository.require(opened.id(), 7002));
    }
}
