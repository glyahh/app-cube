package com.dbts.glyahhaigeneratecode.agent;

import com.dbts.glyahhaigeneratecode.model.Entity.App;
import com.dbts.glyahhaigeneratecode.model.enums.CodeGenTypeEnum;
import com.dbts.glyahhaigeneratecode.projectimport.ImportTaskRepository;
import com.dbts.glyahhaigeneratecode.projectimport.ImportWorkspaceGate;
import com.dbts.glyahhaigeneratecode.service.AppService;
import com.dbts.glyahhaigeneratecode.service.UserService;
import com.dbts.glyahhaigeneratecode.service.support.WorkspacePathResolver;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import java.nio.file.Path;
import java.time.Instant;
import java.util.UUID;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class ImportAgentRequestIdentityTest {
    @Test void formalFactoryAndQueuedRefreshCarryCurrentImportIdentity()throws Exception {
        var apps=mock(AppService.class);var users=mock(UserService.class);var paths=mock(WorkspacePathResolver.class);
        var repository=mock(ImportTaskRepository.class);var gate=new ImportWorkspaceGate(repository);
        var settings=mock(com.dbts.glyahhaigeneratecode.projectimport.ImportAgentSettingsRepository.class);
        var policy=new com.dbts.glyahhaigeneratecode.projectimport.ImportAgentSettingsRepository.Settings(
                7,101,"app:101","MANUAL","default",null,2,UUID.randomUUID(),"session",UUID.randomUUID(),Instant.now().plusSeconds(60));
        when(settings.current(7,101)).thenReturn(policy);
        var factory=new AgentRunRequestFactory(apps,users,paths,new AgentProperties(),gate,settings,
                mock(com.dbts.glyahhaigeneratecode.agentauth.AgentModelPreferenceService.class),
                mock(com.dbts.glyahhaigeneratecode.agentauth.AgentEditSettingsService.class),
                mock(com.dbts.glyahhaigeneratecode.agentauth.AgentEditSessionService.class),
                mock(com.dbts.glyahhaigeneratecode.service.ChatHistoryService.class));
        var app=new App();app.setId(101L);app.setUserId(7L);app.setCodeGenType("auto");
        when(apps.getById(101L)).thenReturn(app);
        when(paths.outputDir(eq(101L),any(CodeGenTypeEnum.class))).thenReturn(Path.of("target","identity-test","project_101"));
        var old=UUID.randomUUID();var current=UUID.randomUUID();
        when(repository.publicationByApp(101)).thenReturn(new ImportTaskRepository.Publication(old,101,7,"READY","a".repeat(64),1,Instant.now()));
        var request=factory.build(101L,7L,"edit app.py",202L);
        assertEquals(old.toString(),request.getImportTaskId());
        assertEquals("2",request.getImportPolicyRevision());
        assertEquals(policy.sessionEpoch().toString(),request.getImportSessionEpoch());
        var mapper=new ObjectMapper();
        assertEquals(old.toString(),mapper.readTree(request.toFrameJson(mapper)).path("workspace").path("importTaskId").asText());
        when(repository.publicationByApp(101)).thenReturn(new ImportTaskRepository.Publication(current,101,7,"READY","b".repeat(64),1,Instant.now()));
        assertEquals(current.toString(),factory.refreshModelRoute(request).getImportTaskId());
        when(repository.publicationByApp(101)).thenReturn(new ImportTaskRepository.Publication(current,101,7,"PUBLISHING","b".repeat(64),1,Instant.now()));
        assertThrows(com.dbts.glyahhaigeneratecode.exception.MyException.class,()->factory.refreshModelRoute(request));
    }
    @Test void ordinaryRequestDoesNotInventImportIdentity()throws Exception {
        var request=AgentRunRequest.builder().runId("201").appId("101").workspaceRoot("target").workspaceName("project_101").build();
        assertFalse(new ObjectMapper().readTree(request.toFrameJson(new ObjectMapper())).path("workspace").has("importTaskId"));
    }
}
