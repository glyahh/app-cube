package com.dbts.glyahhaigeneratecode.projectimport;

import com.dbts.glyahhaigeneratecode.controller.ImportCredentialController;
import com.dbts.glyahhaigeneratecode.exception.GlobalExceptionHandler;
import com.dbts.glyahhaigeneratecode.model.Entity.User;
import com.dbts.glyahhaigeneratecode.service.UserService;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockHttpSession;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import java.util.List;
import java.util.UUID;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

class ImportCredentialControllerTest {
    final ImportCredentialService credentials=mock(ImportCredentialService.class);
    final ImportTaskService tasks=mock(ImportTaskService.class);
    final ImportSourceService sources=mock(ImportSourceService.class);
    final UserService users=mock(UserService.class);
    final ImportProperties properties=new ImportProperties();
    MockMvc mvc; MockHttpSession session;
    @BeforeEach void setup() {
        when(users.getUserInSession(any())).thenReturn(User.builder().id(12L).build());
        mvc=MockMvcBuilders.standaloneSetup(new ImportCredentialController(credentials,tasks,sources,users))
                .setControllerAdvice(new GlobalExceptionHandler()).addFilters(new ImportRequestGuard(users,properties,new ObjectMapper())).build();
        session=new MockHttpSession(); session.setAttribute(ImportRequestGuard.CSRF_SESSION_KEY,"csrf");
    }
    @Test void secretInputCannotSerializeOrAppearInToString() throws Exception {
        var input=new ImportCredentialController.Create("12","GitHub","x","TEST_SECRET_ONLY");
        assertFalse(new ObjectMapper().writeValueAsString(input).contains("TEST_SECRET_ONLY"));
        assertFalse(input.toString().contains("TEST_SECRET_ONLY"));
    }
    @Test void csrfIdentityAndBodyLimitsApplyBeforeCredentialMutation() throws Exception {
        String body="{\"ownerId\":\"13\",\"provider\":\"GitHub\",\"name\":\"x\",\"token\":\"TEST_SECRET_ONLY\"}";
        mvc.perform(post("/project/import/credentials").session(session).contentType("application/json").content(body)).andExpect(status().isForbidden());
        mvc.perform(post("/project/import/credentials").session(session).header(ImportRequestGuard.CSRF_HEADER,"csrf").contentType("application/json").content(body)).andExpect(jsonPath("$.code").value(40900));
        properties.setMaxMetadataBytes(2);
        mvc.perform(put("/project/import/credentials/"+UUID.randomUUID()+"/token").session(session).header(ImportRequestGuard.CSRF_HEADER,"csrf").contentType("application/json").content(body)).andExpect(status().isPayloadTooLarge());
        verifyNoInteractions(credentials,tasks);
    }
    @Test void replacementStopFailureIsReturnedRatherThanClaimingCancelled() throws Exception {
        UUID id=UUID.randomUUID(),task=UUID.randomUUID();
        var view=new ImportCredentialService.View(id.toString(),"GitHub","x","2","1","ACTIVE");
        when(credentials.replace(12,id,0,"TEST_SECRET_ONLY")).thenReturn(new ImportCredentialService.Mutation(view,List.of(task)));
        when(tasks.get(12,task)).thenThrow(new IllegalStateException("resource unavailable"));
        mvc.perform(put("/project/import/credentials/"+id+"/token").session(session).header(ImportRequestGuard.CSRF_HEADER,"csrf")
                .contentType("application/json").content("{\"ownerId\":\"12\",\"revision\":0,\"token\":\"TEST_SECRET_ONLY\"}"))
                .andExpect(jsonPath("$.code").value(20000)).andExpect(jsonPath("$.data.stops[0].status").value("STOP_FAILED"))
                .andExpect(jsonPath("$.data.stops[0].cleanup").value("UNVERIFIED"));
    }
    @Test void preparatorySourceCancellationDoesNotFallThroughToMainTask() throws Exception {
        UUID id=UUID.randomUUID(),task=UUID.randomUUID();
        var view=new ImportCredentialService.View(id.toString(),"GitHub","x","2","1","ACTIVE");
        when(credentials.replace(12,id,0,"TEST_SECRET_ONLY")).thenReturn(new ImportCredentialService.Mutation(view,List.of(task)));
        when(sources.cancelIfPresent(12,task)).thenReturn(new ImportSourceTask.View(task,null,ImportSourceTask.Status.CANCELLED,"3","1",null,null,null,List.of(),false,null,null,"DONE"));
        mvc.perform(put("/project/import/credentials/"+id+"/token").session(session).header(ImportRequestGuard.CSRF_HEADER,"csrf")
                .contentType("application/json").content("{\"ownerId\":\"12\",\"revision\":0,\"token\":\"TEST_SECRET_ONLY\"}"))
                .andExpect(jsonPath("$.data.stops[0].status").value("CANCELLED"))
                .andExpect(jsonPath("$.data.stops[0].cleanup").value("DONE"));
        verifyNoInteractions(tasks);
    }
    @Test void sourceStopFailureCannotFallBackToMainCancellation() throws Exception {
        UUID id=UUID.randomUUID(),task=UUID.randomUUID();
        var view=new ImportCredentialService.View(id.toString(),"GitHub","x","1","1","DELETED");
        when(credentials.delete(12,id,0)).thenReturn(new ImportCredentialService.Mutation(view,List.of(task)));
        when(sources.cancelIfPresent(12,task)).thenThrow(new IllegalStateException("source unknown"));
        mvc.perform(delete("/project/import/credentials/"+id).param("ownerId","12").param("revision","0").session(session).header(ImportRequestGuard.CSRF_HEADER,"csrf"))
                .andExpect(jsonPath("$.data.stops[0].status").value("STOP_FAILED"));
        verifyNoInteractions(tasks);
    }
    @Test void omittedRevisionCannotImplicitlyUseInitialVersion() throws Exception {
        mvc.perform(put("/project/import/credentials/"+UUID.randomUUID()+"/name").session(session).header(ImportRequestGuard.CSRF_HEADER,"csrf")
                .contentType("application/json").content("{\"ownerId\":\"12\",\"name\":\"x\"}"))
                .andExpect(jsonPath("$.code").value(40000));
        verifyNoInteractions(credentials,tasks);
    }
}
