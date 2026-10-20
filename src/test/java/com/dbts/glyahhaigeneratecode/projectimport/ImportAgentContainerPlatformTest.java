package com.dbts.glyahhaigeneratecode.projectimport;

import com.dbts.glyahhaigeneratecode.agent.AgentRunRequest;
import com.dbts.glyahhaigeneratecode.config.WorkspaceProperties;
import com.dbts.glyahhaigeneratecode.model.enums.CodeGenTypeEnum;
import com.dbts.glyahhaigeneratecode.runtime.DockerCli;
import com.dbts.glyahhaigeneratecode.config.RuntimeProperties;
import com.dbts.glyahhaigeneratecode.service.support.WorkspacePathResolver;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.SingleConnectionDataSource;
import java.nio.file.*;
import java.util.UUID;
import static org.junit.jupiter.api.Assertions.*;

/** 专用真实导入应用；生产准备/创建与真实数据库/Docker，脚本负责重启恢复和清理。 */
@EnabledIfEnvironmentVariable(named="AGENT_TEST_CONTAINER_LIFECYCLE_FIXTURE",matches=".+")
class ImportAgentContainerPlatformTest {
    @Test void realProductionPreparationAndCreationBindActualContainerBeforeWorker()throws Exception{
        var json=new ObjectMapper();Path input=Path.of(System.getenv("AGENT_TEST_CONTAINER_LIFECYCLE_FIXTURE"));
        var fixture=json.readTree(Files.readAllBytes(input));long owner=Long.parseLong(fixture.path("ownerId").asText()),app=Long.parseLong(fixture.path("appId").asText());
        UUID task=UUID.fromString(fixture.path("taskId").asText());
        try(var connection=new ImportAgentEditJdbcTest().connect()){
            var source=new SingleConnectionDataSource(connection,true);var jdbc=new JdbcTemplate(source);
            assertTrue(Boolean.TRUE.equals(jdbc.queryForObject("""
                SELECT EXISTS(SELECT 1 FROM biz.app a JOIN biz."user" u ON u.id=a.user_id
                  JOIN biz.project_import_publication p ON p.app_id=a.id AND p.owner_id=u.id
                  WHERE a.id=? AND u.id=? AND p.task_id=? AND p.state='READY' AND a.is_delete=0
                    AND u.user_account LIKE 'import_test_edit_%')
                """,Boolean.class,app,owner,task)));
            var ledger=new ImportAgentEditLedger(jdbc);var coordinator=new ImportWorkspaceCoordinator(source);
            var gate=new ImportWorkspaceGate(new ImportTaskRepository(source,json));
            var paths=new WorkspacePathResolver(new WorkspaceProperties(),gate);
            Path directory=paths.outputDir(app,CodeGenTypeEnum.AUTO);
            var volumes=new ImportDiskVolume(ImportExecutionSandbox.docker(new DockerCli(new RuntimeProperties())),Path.of("scripts/import_disk_volume.py"));
            var properties=new ImportProperties();
            var permissions=new ImportAgentPermissionServiceImpl(gate,coordinator,new ImportAgentSettingsRepository(jdbc),
                    new ImportAgentModelClient(new com.dbts.glyahhaigeneratecode.agent.AgentProperties(),json,
                            org.mockito.Mockito.mock(ImportAgentReviewPool.class)),
                    org.mockito.Mockito.mock(com.dbts.glyahhaigeneratecode.agentauth.AgentModelsService.class));
            var policy=permissions.settings(owner,app,"container-fixture-"+fixture.path("runId").asText());
            var preparation=new ImportAgentEditPreparation(coordinator,gate,new ImportStartupConfigurationRepository(source,json),ledger,properties,volumes,permissions);
            var request=AgentRunRequest.builder().appId(Long.toString(app)).userId(Long.toString(owner)).importTaskId(task.toString())
                    .runId(fixture.path("runId").asText()).workspaceRoot(directory.getParent().toString()).workspaceName(directory.getFileName().toString())
                    .deadlineMs(300000L).importPolicyRevision(policy.policyRevision()).importSessionEpoch(policy.sessionEpoch()).build();
            var prepared=preparation.prepare(request);var entry=prepared.row().identity();
            Path output=input.resolveSibling("java-lifecycle-receipt.json");
            var receipt=json.createObjectNode().put("editId",entry.id().toString()).put("runId",entry.runId())
                    .put("appId",Long.toString(app)).put("ownerId",Long.toString(owner)).put("sourceHash",entry.sourceHash());
            receipt.set("resources",json.readTree(prepared.row().resources()));
            Files.writeString(output,json.writeValueAsString(receipt));
            var lifecycle=new ImportAgentContainerLifecycle(ledger,coordinator,volumes);
            String id=lifecycle.create(entry.id(),owner);
            assertEquals(id,jdbc.queryForObject("SELECT container_id FROM biz.project_import_agent_edit_container WHERE edit_id=?",String.class,entry.id()));
            assertEquals("CREATED",jdbc.queryForObject("SELECT phase FROM biz.project_import_agent_edit_container WHERE edit_id=?",String.class,entry.id()));
            assertThrows(com.dbts.glyahhaigeneratecode.exception.MyException.class,()->lifecycle.create(entry.id(),owner));
            assertTrue(ledger.qualified(prepared.row()));assertEquals("PREPARING",ledger.require(entry.id(),owner).state());
            assertFalse(ledger.require(entry.id(),owner).cancelRequested());
            if(fixture.path("verifySealing").asBoolean()){
                var docker=new DockerCli(new RuntimeProperties());
                lifecycle.beginEditing(entry.id(),owner);
                assertEquals(id,jdbc.queryForObject("SELECT container_id FROM biz.project_import_agent_edit_container WHERE edit_id=?",String.class,entry.id()));
                assertEquals("0",docker.run(java.util.List.of("exec",id,"cat","/sys/fs/cgroup/memory.swap.max"),30).stdout().strip());
                var kernel=receipt.putObject("actualKernelLimits");
                for(String file:java.util.List.of("memory.max","memory.swap.max","cpu.max","pids.max")){
                    var read=docker.run(java.util.List.of("exec",id,"cat","/sys/fs/cgroup/"+file),30);assertTrue(read.ok());kernel.put(file,read.stdout().strip());
                }
                assertThrows(com.dbts.glyahhaigeneratecode.exception.MyException.class,()->ledger.beginSealing(ledger.require(entry.id(),owner)));
                assertThrows(com.dbts.glyahhaigeneratecode.exception.MyException.class,()->lifecycle.seal(entry.id(),owner+1));
                assertEquals("true",docker.run(java.util.List.of("inspect","--format","{{.State.Running}}",id),30).stdout().strip());
                var sealed=lifecycle.seal(entry.id(),owner);
                assertEquals("SEALING",sealed.state());assertTrue(sealed.stopConfirmed());assertFalse(sealed.cancelRequested());
                assertFalse(docker.run(java.util.List.of("inspect",id),30).ok());
                assertEquals("REMOVED",jdbc.queryForObject("SELECT phase FROM biz.project_import_agent_edit_container WHERE edit_id=?",String.class,entry.id()));
                var verification=new ImportAgentCandidateVerification(ledger,coordinator,gate,paths,volumes,properties);
                var verified=verification.verify(entry.id(),owner,prepared.request().getImportViewStateHash());
                assertEquals("VERIFIED",jdbc.queryForObject("SELECT phase FROM biz.project_import_agent_edit_candidate WHERE edit_id=?",String.class,entry.id()));
                assertEquals("NONE",ledger.require(entry.id(),owner).publishPhase());
                assertThrows(com.dbts.glyahhaigeneratecode.exception.MyException.class,()->lifecycle.seal(entry.id(),owner));
                assertEquals("SEALING",ledger.require(entry.id(),owner).state());
                receipt.put("sealedAndVerified",true).put("candidateHash",verified.candidateHash()).put("manifestHash",verified.manifestHash());
            }
            receipt.put("containerId",id).put("status","PASS");Files.writeString(output,json.writeValueAsString(receipt));
            assertEquals(0,jdbc.queryForObject("SELECT count(*) FROM pg_locks WHERE locktype='advisory' AND pid=pg_backend_pid()",Integer.class));
        }
    }
}
