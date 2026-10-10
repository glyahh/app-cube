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
        var factory=new AgentRunRequestFactory(apps,users,paths,new AgentProperties(),gate);
        var app=new App();app.setId(101L);app.setUserId(7L);app.setCodeGenType("auto");
        when(apps.getById(101L)).thenReturn(app);
        when(paths.outputDir(eq(101L),any(CodeGenTypeEnum.class))).thenReturn(Path.of("target","identity-test","project_101"));
        var old=UUID.randomUUID();var current=UUID.randomUUID();
        when(repository.publicationByApp(101)).thenReturn(new ImportTaskRepository.Publication(old,101,7,"READY","a".repeat(64),1,Instant.now()));
        var request=factory.build(101L,7L,"edit app.py",202L);
        assertEquals(old.toString(),request.getImportTaskId());
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
