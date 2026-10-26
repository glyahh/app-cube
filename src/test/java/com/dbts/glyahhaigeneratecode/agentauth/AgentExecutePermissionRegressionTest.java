package com.dbts.glyahhaigeneratecode.agentauth;

import com.dbts.glyahhaigeneratecode.exception.MyException;
import com.dbts.glyahhaigeneratecode.projectimport.ImportAgentModelClient;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;

import java.time.Instant;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

class AgentExecutePermissionRegressionTest {
    private final ObjectMapper json = new ObjectMapper();
    private final AgentToolPermissionRepository permissions = mock(AgentToolPermissionRepository.class);
    private final AgentEditSessionRepository sessions = mock(AgentEditSessionRepository.class);
    private final ImportAgentModelClient reviewer = mock(ImportAgentModelClient.class);
    private final UUID sessionId = UUID.randomUUID();
    private final Instant expires = Instant.now().plusSeconds(600);
    private final AgentToolPermissionService service = new AgentToolPermissionService(permissions, sessions,
            reviewer, mock(AgentModelsService.class), json, mock(JdbcTemplate.class));

    private void session(String approval, String sandbox) {
        when(sessions.require(sessionId, 1L)).thenReturn(new AgentEditSessionRepository.Session(
                sessionId, 1, 2, 3, "a".repeat(64), sandbox, approval, 0,
                UUID.randomUUID(), UUID.randomUUID(), "EDITING", expires, false));
    }

    private JsonNode action(String command) {
        var action = json.createObjectNode().put("tool", "execute").put("cwd", "/workspace");
        action.putArray("argv").add("npm").add(command);
        return action;
    }

    private JsonNode frame(UUID id, JsonNode action) {
        return json.createObjectNode().put("permissionId", id.toString())
                .put("actionHash", AgentCanonicalJson.hash(action)).set("action", action);
    }

    private void ticket(UUID id, JsonNode action) {
        when(permissions.insert(eq(id), eq(sessionId), eq("execute"), anyString(), anyString(),
                anyString(), anyString(), anyString(), eq(expires))).thenReturn(id);
        when(permissions.require(id, 1L)).thenReturn(new AgentToolPermissionRepository.Row(
                id, sessionId, "execute", AgentCanonicalJson.hash(action), action.toString(), "HIGH",
                AgentCanonicalJson.hash(action), action.toString(), "REQUESTED", 0, null, null, expires));
    }

    @Test
    void oldBroadGrantCannotApproveDifferentCommandOrSameCommandWithChangedScript() throws Exception {
        session("MANUAL", "WORKSPACE_WRITE");
        when(permissions.granted(any(), anyString(), anyString(), anyString())).thenReturn(true);
        // Same argv can run changed package.json/script inputs: even an identical payload needs a new decision.
        for (String command : new String[]{"test", "build", "test"}) {
            UUID id = UUID.randomUUID();
            JsonNode action = action(command);
            ticket(id, action);
            assertEquals("REQUESTED", service.requested(1, sessionId, frame(id, action)).path("status").asText());
            verify(permissions).insert(eq(id), eq(sessionId), eq("execute"), anyString(), anyString(),
                    eq("HIGH"), eq(AgentCanonicalJson.hash(action)), anyString(), eq(expires));
        }
        verify(permissions, never()).granted(any(), anyString(), anyString(), anyString());
        verify(permissions, never()).decide(any(), anyString(), anyString(), any(), anyLong());
    }

    @Test
    void guardedAutoDoesNotClassifyMissingPathExecuteAsOrdinary() throws Exception {
        session("GUARDED_AUTO", "WORKSPACE_WRITE");
        UUID id = UUID.randomUUID();
        JsonNode action = action("test");
        ticket(id, action);
        assertTrue(service.requested(1, sessionId, frame(id, action)).path("highRisk").asBoolean());
        verifyNoInteractions(reviewer);
        verify(permissions, never()).decide(any(), anyString(), anyString(), any(), anyLong());
    }

    @Test
    void similarExecuteIsRejectedBeforeAnyDecisionOrGrantButApproveOnceWorks() {
        session("MANUAL", "WORKSPACE_WRITE");
        UUID id = UUID.randomUUID();
        JsonNode action = action("test");
        ticket(id, action);
        String hash = AgentCanonicalJson.hash(action);
        assertThrows(MyException.class, () -> service.decide(1, id,
                new AgentToolPermissionService.DecisionDTO(UUID.randomUUID(), hash, "APPROVE_SIMILAR", 0)));
        verify(permissions, never()).decide(any(), anyString(), anyString(), any(), anyLong());
        verify(permissions, never()).grant(any(), any(), anyString(), anyString(), anyString(), any());
        UUID request = UUID.randomUUID();
        service.decide(1, id, new AgentToolPermissionService.DecisionDTO(request, hash, "APPROVE_ONCE", 0));
        verify(permissions).decide(id, "APPROVED", "USER", request, 0);
    }

    @Test
    void explicitlySelectedAutoModeStillApprovesAndReadOnlyStillRejects() throws Exception {
        session("AUTO", "WORKSPACE_WRITE");
        UUID id = UUID.randomUUID();
        JsonNode action = action("test");
        ticket(id, action);
        service.requested(1, sessionId, frame(id, action));
        verify(permissions).decide(id, "APPROVED", "AUTO", null, 0);
        clearInvocations(permissions);
        session("AUTO", "READ_ONLY");
        assertThrows(MyException.class, () -> service.requested(1, sessionId, frame(id, action)));
        verifyNoInteractions(permissions);
    }
}
