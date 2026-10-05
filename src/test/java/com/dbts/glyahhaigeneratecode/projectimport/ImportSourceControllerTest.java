package com.dbts.glyahhaigeneratecode.projectimport;

import com.dbts.glyahhaigeneratecode.controller.ImportSourceController;
import com.dbts.glyahhaigeneratecode.exception.GlobalExceptionHandler;
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

class ImportSourceControllerTest {
    final ImportSourceService sources=mock(ImportSourceService.class);
    final UserService users=mock(UserService.class);
    final ImportProperties properties=new ImportProperties();
    MockMvc mvc;MockHttpSession session;
    @BeforeEach void setup() {
        when(users.getUserInSession(any())).thenReturn(User.builder().id(12L).build());
        mvc=MockMvcBuilders.standaloneSetup(new ImportSourceController(sources,users)).setControllerAdvice(new GlobalExceptionHandler())
                .addFilters(new ImportRequestGuard(users,properties,new ObjectMapper())).build();
        session=new MockHttpSession();session.setAttribute(ImportRequestGuard.CSRF_SESSION_KEY,"csrf");
    }
    String body(String owner){return "{\"ownerId\":\""+owner+"\",\"appName\":\"fixture\",\"provider\":\"GitHub\",\"repositoryUrl\":\"https://github.com/octocat/Hello-World\"}";}
    @Test void sourceWriteRequiresCsrfOwnerAndBoundedMetadata()throws Exception {
        mvc.perform(post("/project/import/git/tasks").session(session).contentType("application/json").content(body("12"))).andExpect(status().isForbidden());
        mvc.perform(post("/project/import/git/tasks").session(session).header(ImportRequestGuard.CSRF_HEADER,"csrf").contentType("application/json").content(body("13"))).andExpect(jsonPath("$.code").value(40900));
        properties.setMaxMetadataBytes(2);
        mvc.perform(post("/project/import/git/tasks").session(session).header(ImportRequestGuard.CSRF_HEADER,"csrf").contentType("application/json").content(body("12"))).andExpect(status().isPayloadTooLarge());
        verifyNoInteractions(sources);
    }
    @Test void selectedCredentialCannotSilentlyUsePublicAnonymousPath()throws Exception {
        UUID selected=UUID.randomUUID();
        when(sources.prepare(eq(12L),anyString(),anyString(),anyString(),isNull(),eq(selected),eq(1L)))
                .thenThrow(new com.dbts.glyahhaigeneratecode.exception.MyException(com.dbts.glyahhaigeneratecode.exception.ErrorCode.OPERATION_ERROR,"认证未通过"));
        String body=body("12").replace("}",",\"credentialId\":\""+selected+"\",\"credentialVersion\":1}");
        mvc.perform(post("/project/import/git/tasks").session(session).header(ImportRequestGuard.CSRF_HEADER,"csrf").contentType("application/json").content(body)).andExpect(jsonPath("$.code").value(50001));
        verify(sources).prepare(eq(12L),anyString(),anyString(),anyString(),isNull(),eq(selected),eq(1L));
        verify(sources,never()).preparePublic(anyLong(),anyString(),anyString(),anyString(),any());
    }
    @Test void queryAndCancellationTakeOwnerOnlyFromSession()throws Exception {
        UUID id=UUID.randomUUID();
        mvc.perform(get("/project/import/git/tasks/"+id).session(session)).andExpect(header().string("Cache-Control","no-store"));
        verify(sources).get(12,id);
        mvc.perform(post("/project/import/git/tasks/"+id+"/cancel?revision=7").session(session).header(ImportRequestGuard.CSRF_HEADER,"csrf"));
        verify(sources).cancel(12,id,7);
    }
    @Test void selectionRequiresExplicitVersionAndCurrentOwner()throws Exception {
        UUID id=UUID.randomUUID();
        mvc.perform(post("/project/import/git/tasks/"+id+"/branch").session(session).header(ImportRequestGuard.CSRF_HEADER,"csrf")
                .contentType("application/json").content("{\"ownerId\":\"12\",\"branch\":\"master\"}")).andExpect(jsonPath("$.code").value(40000));
        mvc.perform(post("/project/import/git/tasks/"+id+"/branch").session(session).header(ImportRequestGuard.CSRF_HEADER,"csrf")
                .contentType("application/json").content("{\"ownerId\":\"13\",\"revision\":0,\"branch\":\"master\"}")).andExpect(jsonPath("$.code").value(40900));
        verifyNoInteractions(sources);
    }
    @Test void rangeRebuildRequiresCsrfOwnerAndRevision()throws Exception {
        UUID id=UUID.randomUUID();String route="/project/import/git/tasks/"+id+"/range";
        String body="{\"ownerId\":\"12\",\"revision\":\"7\",\"appName\":\"新名称\",\"options\":{\"skipDependencies\":true,\"skipBuildOutputs\":false,\"includeSensitive\":false}}";
        mvc.perform(post(route).session(session).contentType("application/json").content(body)).andExpect(status().isForbidden());
        mvc.perform(post(route).session(session).header(ImportRequestGuard.CSRF_HEADER,"csrf").contentType("application/json")
                .content(body.replace("\"12\"","\"13\""))).andExpect(jsonPath("$.code").value(40900));
        mvc.perform(post(route).session(session).header(ImportRequestGuard.CSRF_HEADER,"csrf").contentType("application/json")
                .content(body.replace("\"revision\":\"7\",",""))).andExpect(jsonPath("$.code").value(40000));
        verifyNoInteractions(sources);
        mvc.perform(post(route).session(session).header(ImportRequestGuard.CSRF_HEADER,"csrf").contentType("application/json").content(body));
        verify(sources).rebuild(eq(12L),eq(id),eq(7L),eq("新名称"),eq(new com.dbts.glyahhaigeneratecode.service.support.ProjectImportManifest.Options(true,false,false)));
    }
    @Test void opaqueRequestLookupUsesSessionOwnerAndCannotCreateAnything()throws Exception {
        UUID key=UUID.randomUUID();
        mvc.perform(get("/project/import/git/tasks/request/"+key+"?ownerId=13").session(session))
            .andExpect(header().string("Cache-Control","no-store"));
        verify(sources).getByRequest(12,key);
        verify(sources,never()).prepare(anyLong(),any(),any(),any(),any(),any(),any(),any());
        mvc.perform(get("/project/import/git/tasks/request/not-a-uuid").session(session)).andExpect(jsonPath("$.code").value(40000));
    }
    @Test void requestIdentifierIsPassedWithExactCreationPayload()throws Exception {
        UUID key=UUID.randomUUID();String payload=body("12").replace("}",",\"requestId\":\""+key+"\"}");
        mvc.perform(post("/project/import/git/tasks").session(session).header(ImportRequestGuard.CSRF_HEADER,"csrf").contentType("application/json").content(payload));
        verify(sources).prepare(eq(12L),eq("fixture"),eq("GitHub"),eq("https://github.com/octocat/Hello-World"),isNull(),isNull(),isNull(),eq(key));
    }
}
