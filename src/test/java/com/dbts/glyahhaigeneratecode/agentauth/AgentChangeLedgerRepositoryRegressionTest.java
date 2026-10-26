package com.dbts.glyahhaigeneratecode.agentauth;

import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.jdbc.core.JdbcTemplate;

import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

/** SQL contract checks; real PostgreSQL execution is separately required for database acceptance. */
class AgentChangeLedgerRepositoryRegressionTest {
    @Test
    void repeatedWritesMustNotLoseOriginalTruncationEvidence() {
        var jdbc = mock(JdbcTemplate.class);
        var repository = new AgentChangeLedgerRepository(jdbc);
        UUID session = UUID.randomUUID();
        repository.record(session, new AgentChangeLedgerRepository.Change("a.txt", "MODIFIED",
                "a".repeat(64), "b".repeat(64), "x".repeat(AgentChangeLedgerRepository.MAX_BLOB_CHARS + 1), "small", false));
        repository.record(session, new AgentChangeLedgerRepository.Change("a.txt", "MODIFIED",
                "b".repeat(64), "c".repeat(64), "small", "smaller", false));
        var sql = ArgumentCaptor.forClass(String.class);
        verify(jdbc, times(2)).update(sql.capture(), eq(session), eq("a.txt"), eq("MODIFIED"),
                anyString(), anyString(), nullable(String.class), nullable(String.class), anyBoolean());
        for (String statement : sql.getAllValues()) {
            String update = statement.substring(statement.indexOf("DO UPDATE SET"));
            assertTrue(update.contains("truncated=agent_change_ledger.truncated OR EXCLUDED.truncated"));
            assertFalse(update.contains("before_hash="));
            assertFalse(update.contains("before_blob="));
        }
        verify(jdbc).update(anyString(), eq(session), eq("a.txt"), eq("MODIFIED"),
                eq("a".repeat(64)), eq("b".repeat(64)), isNull(), isNull(), eq(true));
        verify(jdbc).update(anyString(), eq(session), eq("a.txt"), eq("MODIFIED"),
                eq("b".repeat(64)), eq("c".repeat(64)), eq("small"), eq("smaller"), eq(false));
    }

    @Test
    void rollbackProjectionRetainsOriginalHashToRecognizeLegacyMissingBefore() {
        var jdbc = mock(JdbcTemplate.class);
        UUID session = UUID.randomUUID();
        new AgentChangeLedgerRepository(jdbc).rollbackPlan(session);
        var sql = ArgumentCaptor.forClass(String.class);
        verify(jdbc).queryForList(sql.capture(), eq(session));
        assertTrue(sql.getValue().contains("before_hash,before_blob,truncated"));
    }
}
