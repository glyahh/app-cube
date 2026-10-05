package com.dbts.glyahhaigeneratecode.projectimport;

import com.dbts.glyahhaigeneratecode.controller.ProjectImportController;
import com.dbts.glyahhaigeneratecode.exception.ErrorCode;
import com.dbts.glyahhaigeneratecode.exception.GlobalExceptionHandler;
import com.dbts.glyahhaigeneratecode.exception.MyException;
import com.dbts.glyahhaigeneratecode.model.Entity.User;
import com.dbts.glyahhaigeneratecode.service.UserService;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockHttpSession;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

import java.util.UUID;

import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

class ProjectImportControllerTest {
    ImportTaskService tasks;
    ImportPreferenceService preferences;
    UserService users;
    ImportProperties properties;
    MockMvc mvc;
    MockHttpSession session;
    String body = """
            {"ownerId":"12","appName":"x","files":[{"path":"a","size":1}],
            "options":{"skipDependencies":false,"skipBuildOutputs":false,"includeSensitive":false}}
            """;

    @BeforeEach
    void setup() {
        tasks = mock(ImportTaskService.class);
        preferences = mock(ImportPreferenceService.class);
        users = mock(UserService.class);
        properties = new ImportProperties();
        when(users.getUserInSession(any())).thenReturn(User.builder().id(12L).build());
        mvc = MockMvcBuilders.standaloneSetup(new ProjectImportController(tasks, users, preferences))
                .setControllerAdvice(new GlobalExceptionHandler())
                .addFilters(new ImportRequestGuard(users, properties, new ObjectMapper())).build();
        session = new MockHttpSession();
        session.setAttribute(ImportRequestGuard.CSRF_SESSION_KEY, "test-csrf");
    }

    @Test
    void preferencesAreSessionOwnedAndExplicitSaveRequiresCsrfAndCompleteFields() throws Exception {
        mvc.perform(get("/project/import/preferences").session(session)).andExpect(jsonPath("$.code").value(20000));
        verify(preferences).get(12);
        String valid = "{\"ownerId\":\"12\",\"skipDependencies\":true,\"skipBuildOutputs\":false,\"includeSensitive\":false}";
        mvc.perform(put("/project/import/preferences").session(session).contentType("application/json").content(valid))
                .andExpect(status().isForbidden());
        mvc.perform(put("/project/import/preferences").session(session).header(ImportRequestGuard.CSRF_HEADER, "test-csrf")
                        .contentType("application/json").content(valid.replace("\"12\"", "\"13\"")))
                .andExpect(jsonPath("$.code").value(40900));
        mvc.perform(put("/project/import/preferences").session(session).header(ImportRequestGuard.CSRF_HEADER, "test-csrf")
                        .contentType("application/json").content("{\"ownerId\":\"12\"}"))
                .andExpect(jsonPath("$.code").value(40000));
        mvc.perform(put("/project/import/preferences").session(session).header(ImportRequestGuard.CSRF_HEADER, "test-csrf")
                        .contentType("application/json").content(valid)).andExpect(jsonPath("$.code").value(20000));
        verify(preferences).save(12, new com.dbts.glyahhaigeneratecode.service.support.ProjectImportManifest.Options(true, false, false));
        verifyNoMoreInteractions(preferences);
    }

    @Test
    void anonymousRequestIsRejectedBeforeParsingBadBody() throws Exception {
        when(users.getUserInSession(any())).thenThrow(new MyException(ErrorCode.NOT_LOGIN_ERROR));
        mvc.perform(post("/project/import/tasks").contentType("application/json").content("not-json"))
                .andExpect(status().isUnauthorized()).andExpect(jsonPath("$.code").value(40100));
        verifyNoInteractions(tasks);
    }

    @Test
    void missingWrongCsrfAndForeignOriginsCannotTouchBusiness() throws Exception {
        mvc.perform(post("/project/import/tasks").session(session).contentType("application/json").content(body))
                .andExpect(status().isForbidden());
        mvc.perform(post("/project/import/tasks").session(session).header(ImportRequestGuard.CSRF_HEADER, "wrong")
                .contentType("application/json").content(body)).andExpect(status().isForbidden());
        mvc.perform(get("/project/import/csrf").session(session).header("Origin", "https://untrusted.invalid"))
                .andExpect(status().isForbidden());
        mvc.perform(get("/project/import/csrf").session(session).header("Sec-Fetch-Site", "cross-site"))
                .andExpect(status().isForbidden());
        verifyNoInteractions(tasks);
    }

    @Test
    void csrfEndpointIsSessionScopedAndNoStore() throws Exception {
        mvc.perform(get("/project/import/csrf").session(session).header("Origin", "http://localhost:5173"))
                .andExpect(status().isOk()).andExpect(header().string("Cache-Control", "no-store"))
                .andExpect(jsonPath("$.data").value("test-csrf"));
    }

    @Test
    void accountSwitchCannotCreateTaskForUnexpectedSessionOwner() throws Exception {
        mvc.perform(post("/project/import/tasks").session(session).header(ImportRequestGuard.CSRF_HEADER, "test-csrf")
                .contentType("application/json").content(body.replace("\"12\"", "\"13\"")))
                .andExpect(jsonPath("$.code").value(40900));
        verifyNoInteractions(tasks);
    }

    @Test
    void acceptedRequestUsesOnlyServerSessionIdentity() throws Exception {
        mvc.perform(post("/project/import/tasks").session(session).header(ImportRequestGuard.CSRF_HEADER, "test-csrf")
                .contentType("application/json").content(body))
                .andExpect(status().isOk()).andExpect(jsonPath("$.code").value(20000));
        verify(tasks).create(eq(12L), eq("x"), anyList(), any());
    }

    @Test
    void malformedOrOversizedMetadataDoesNotCreateTask() throws Exception {
        mvc.perform(post("/project/import/tasks").session(session).header(ImportRequestGuard.CSRF_HEADER, "test-csrf")
                .contentType("application/json").content("{"))
                .andExpect(jsonPath("$.code").value(40000));
        properties.setMaxMetadataBytes(2);
        mvc.perform(post("/project/import/tasks").session(session).header(ImportRequestGuard.CSRF_HEADER, "test-csrf")
                .contentType("application/json").content(body))
                .andExpect(status().isPayloadTooLarge());
        verifyNoInteractions(tasks);
    }

    @Test
    void taskLookupAndCancelDeriveOwnerAndPassVersion() throws Exception {
        UUID id = UUID.randomUUID();
        mvc.perform(get("/project/import/tasks/" + id).session(session)).andExpect(jsonPath("$.code").value(20000));
        mvc.perform(post("/project/import/tasks/" + id + "/cancel?revision=7").session(session)
                .header(ImportRequestGuard.CSRF_HEADER, "test-csrf")).andExpect(jsonPath("$.code").value(20000));
        verify(tasks).get(12, id);
        verify(tasks).cancel(12, id, 7);
    }

    @Test
    void binaryUploadPassesOnlyTaskIndexAndLeaseRevision() throws Exception {
        UUID id = UUID.randomUUID();
        mvc.perform(put("/project/import/tasks/" + id + "/files/0?revision=3").session(session)
                .header(ImportRequestGuard.CSRF_HEADER, "test-csrf").contentType("application/octet-stream").content(new byte[]{0, 1}))
                .andExpect(jsonPath("$.code").value(20000));
        verify(tasks).upload(eq(12L), eq(id), eq(3L), eq(0), any());
    }

    @Test
    void chunkedMetadataCannotBypassActualReadLimit() throws Exception {
        properties.setMaxMetadataBytes(2);
        var request = new org.springframework.mock.web.MockHttpServletRequest() {
            @Override public long getContentLengthLong() { return -1; }
        };
        request.setRequestURI("/project/import/tasks");
        request.setMethod("POST");
        request.setSession(session);
        request.addHeader(ImportRequestGuard.CSRF_HEADER, "test-csrf");
        request.setContent(body.getBytes(java.nio.charset.StandardCharsets.UTF_8));
        var guard = new ImportRequestGuard(users, properties, new ObjectMapper());
        org.junit.jupiter.api.Assertions.assertThrows(java.io.IOException.class, () -> guard.doFilter(request,
                new org.springframework.mock.web.MockHttpServletResponse(), (input, output) -> input.getInputStream().readAllBytes()));
        verifyNoInteractions(tasks);
    }
}
