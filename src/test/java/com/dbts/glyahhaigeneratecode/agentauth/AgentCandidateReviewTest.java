package com.dbts.glyahhaigeneratecode.agentauth;

import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertNull;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/** 候选审阅的边界行为。 */
class AgentCandidateReviewTest {

    private AgentCandidateReviewService service(AgentChangeLedgerRepository ledger) {
        var sessions = mock(AgentEditSessionRepository.class);
        when(sessions.require(any(), anyLong())).thenReturn(
                new AgentEditSessionRepository.Session(UUID.randomUUID(), 1L, 2L, 3L,
                        "0".repeat(64), "WORKSPACE_WRITE", "MANUAL", 0,
                        UUID.randomUUID(), UUID.randomUUID(), "SUCCEEDED",
                        Instant.now().plusSeconds(600), true));
        return new AgentCandidateReviewService(mock(org.springframework.jdbc.core.JdbcTemplate.class),
                ledger, sessions, mock(AgentChangeRollbackService.class),
                mock(AgentModelsService.class), new com.fasterxml.jackson.databind.ObjectMapper());
    }

    @Test
    void noChangesSkipsWithoutCreatingReview() {
        var ledger = mock(AgentChangeLedgerRepository.class);
        when(ledger.list(any())).thenReturn(java.util.List.of());

        // 纯问答不产生变更，不能因此报错或造出空审阅。
        assertNull(service(ledger).prepareIfChanged(1L, UUID.randomUUID()));
    }
}