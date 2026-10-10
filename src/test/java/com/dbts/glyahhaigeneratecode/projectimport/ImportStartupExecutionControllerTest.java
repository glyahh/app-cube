package com.dbts.glyahhaigeneratecode.projectimport;

import com.dbts.glyahhaigeneratecode.controller.ImportStartupExecutionController;
import com.dbts.glyahhaigeneratecode.exception.MyException;
import com.dbts.glyahhaigeneratecode.model.Entity.User;
import com.dbts.glyahhaigeneratecode.service.UserService;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockHttpServletRequest;

import java.time.Instant;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.Mockito.*;

class ImportStartupExecutionControllerTest {
    private final UserService users = mock(UserService.class);
    private final ImportExecutionService executions = mock(ImportExecutionService.class);
    private final MockHttpServletRequest request = new MockHttpServletRequest();
    private final User user = User.builder().id(7L).build();
    private final ImportStartupExecutionController controller = new ImportStartupExecutionController(users, executions);
    private final UUID planId = UUID.randomUUID();
    private final UUID executionId = UUID.randomUUID();

    @Test
    void detailSerializesSnowflakeIdsAsStrings() {
        when(users.getUserInSession(request)).thenReturn(user);
        when(executions.detail(user, 922337203685477000L, planId, executionId)).thenReturn(detail(922337203685477000L));

        var response = controller.detail(922337203685477000L, executionId, planId, request);

        assertEquals("7", response.getData().ownerId());
        assertEquals("922337203685477000", response.getData().appId());
    }

    @Test
    void logRejectsInvalidLengthAndStopRequiresOwner() {
        when(users.getUserInSession(request)).thenReturn(user);
        when(executions.detail(user, 42L, planId, executionId)).thenReturn(detail(42L));
        assertThrows(MyException.class, () -> controller.log(42L, executionId, planId, 0, request));
        assertThrows(MyException.class, () -> controller.log(42L, executionId, planId, (1 << 20) + 1, request));
        assertThrows(MyException.class, () -> controller.stop(42L, executionId,
            new ImportStartupExecutionController.ExecutionRequest("8", planId), request));
        verify(executions, never()).stop(executionId);
    }

    private ImportExecutionLedger.Detail detail(long appId) {
        Instant now = Instant.now();
        return new ImportExecutionLedger.Detail(executionId, 7L, appId, planId, "a".repeat(64), "b".repeat(64), "web",
            "service:web", "none", "RUNNING", null, null, 0L, "PENDING", 3L, now, now, null, "container");
    }
}
