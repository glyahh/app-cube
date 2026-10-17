package com.dbts.glyahhaigeneratecode.projectimport;

import com.dbts.glyahhaigeneratecode.exception.MyException;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class ImportAgentCandidateReviewServiceTest {
    private final ImportAgentEditLedger ledger = mock(ImportAgentEditLedger.class);
    private final ImportAgentCandidateReviewRepository repository = mock(ImportAgentCandidateReviewRepository.class);
    private final ImportAgentPermissionServiceImpl permissions = mock(ImportAgentPermissionServiceImpl.class);
    private final ImportAgentCandidateReviewService service = new ImportAgentCandidateReviewService(
            ledger, repository, permissions, new ObjectMapper(), mock(ImportDiskVolume.class),
            mock(ImportAgentModelClient.class), mock(ImportAgentSettingsRepository.class), mock(ImportAgentPublication.class),
            mock(ImportWorkspaceCoordinator.class), mock(ImportAgentEditPreparation.class), mock(org.springframework.jdbc.core.JdbcTemplate.class));

    @Test
    void malformedDecisionNeverReadsOrMutatesLedger() {
        assertThrows(MyException.class, () -> service.decide(1, UUID.randomUUID(), null, "session"));
        assertThrows(MyException.class, () -> service.decide(1, UUID.randomUUID(),
                new ImportAgentCandidateReviewService.DecisionDTO(UUID.randomUUID(), "a".repeat(64), null, 0), "session"));
        assertThrows(MyException.class, () -> service.decide(1, UUID.randomUUID(),
                new ImportAgentCandidateReviewService.DecisionDTO(UUID.randomUUID(), "a".repeat(64), "AUTO", 0), "session"));
        verifyNoInteractions(ledger, repository, permissions);
    }

    @Test
    void expiredSessionCannotRegisterDecision() {
        var edit = UUID.randomUUID();
        var entry = new ImportAgentEditLedger.Entry(edit, 1, 2, UUID.randomUUID(), "3", "a".repeat(64),
                0, 0, UUID.randomUUID(), 1, Instant.now().minusSeconds(10), Instant.now().plusSeconds(100));
        var row = new ImportAgentEditLedger.Row(entry, "WAITING_REVIEW", 1, false, "[]", "{}", "NONE", true);
        when(ledger.require(edit, 1)).thenReturn(row);
        doThrow(new MyException(com.dbts.glyahhaigeneratecode.exception.ErrorCode.CONFLICT_ERROR, "session expired"))
                .when(permissions).requireSession(1, 2, "expired");
        assertThrows(MyException.class, () -> service.decide(1, edit,
                new ImportAgentCandidateReviewService.DecisionDTO(UUID.randomUUID(), "a".repeat(64), "APPROVE", 0), "expired"));
        verifyNoInteractions(repository);
    }
}
