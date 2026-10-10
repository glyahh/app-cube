package com.dbts.glyahhaigeneratecode.projectimport;

import com.dbts.glyahhaigeneratecode.exception.MyException;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.SingleConnectionDataSource;
import java.lang.reflect.*;
import java.net.*;
import java.nio.file.*;
import java.sql.*;
import java.time.Instant;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;

/** 实际PostgreSQL及Java持久方法；仅操作本轮事务内schema，不修改正式业务表。 */
@EnabledIfEnvironmentVariable(named="AGENT_TEST_REAL_EDIT_JDBC",matches="1")
class ImportAgentEditJdbcTest {
    @TempDir Path temporary;
    Connection connect()throws Exception{
        String dsn=Files.readAllLines(Path.of(".env")).stream().filter(line->line.startsWith("AGENT_POSTGRES_DSN="))
                .map(line->line.substring(line.indexOf('=')+1).strip()).findFirst().orElseThrow();
        if(dsn.startsWith("\"")||dsn.startsWith("'"))dsn=dsn.substring(1,dsn.length()-1);
        URI uri=URI.create(dsn);var credentials=new Properties();
        String[] identity=uri.getRawUserInfo().split(":",2);
        credentials.setProperty("user",URLDecoder.decode(identity[0],java.nio.charset.StandardCharsets.UTF_8));
        if(identity.length==2)credentials.setProperty("password",URLDecoder.decode(identity[1],java.nio.charset.StandardCharsets.UTF_8));
        credentials.setProperty("connectTimeout","5");credentials.setProperty("socketTimeout","30");
        String url="jdbc:postgresql://"+uri.getHost()+":"+(uri.getPort()<0?5432:uri.getPort())+uri.getPath();
        return DriverManager.getConnection(url,credentials);
    }
    @Test void realJavaVerifiedWriteRejectsMismatchesRevocationRaceAndDuplicate()throws Exception{
        String schema="edit_jdbc_"+UUID.randomUUID().toString().replace("-","");String prefix='"'+schema+"\".";
        var helper=new ImportAgentCandidateVerifierTest();var fixture=helper.fixture();var verified=helper.verify(fixture,fixture.access());
        var old=fixture.entry();var process=ProcessHandle.current();
        var entry=new ImportAgentEditLedger.Entry(old.id(),7,101,old.importTaskId(),"201",old.sourceHash(),0,0,old.executorId(),
                Long.MAX_VALUE,process.info().startInstant().orElseThrow(),Instant.now().plusSeconds(300));
        try(Connection connection=connect()){
            connection.setAutoCommit(false);
            try{
                try(Statement statement=connection.createStatement()){
                    statement.execute("CREATE SCHEMA \""+schema+"\"");statement.execute("SET LOCAL search_path TO \""+schema+"\"");
                    statement.execute("CREATE TABLE \"user\"(id bigint primary key); INSERT INTO \"user\" VALUES(7),(8)");
                    statement.execute("CREATE TABLE app(id bigint primary key,user_id bigint,is_delete int); INSERT INTO app VALUES(101,7,0)");
                    statement.execute("CREATE TABLE project_import_task(task_id uuid primary key,owner_id bigint,status text)");
                    statement.execute("CREATE TABLE project_import_publication(app_id bigint primary key,task_id uuid,owner_id bigint,state text)");
                    statement.execute("CREATE TABLE project_import_startup_configuration(app_id bigint,owner_id bigint,import_task_id uuid,config_revision bigint,context_epoch bigint)");
                    for(String migration:List.of("V30__import_agent_edit_ledger.sql","V31__import_agent_edit_volume_guard.sql","V32__import_agent_edit_view.sql","V33__import_agent_edit_candidate.sql","V34__import_agent_edit_container.sql","V35__import_agent_worker.sql","V36__import_agent_publication_guard.sql","V37__import_agent_outputs.sql"))
                        statement.execute(Files.readString(Path.of("src/main/resources/db/migration",migration)).replace("biz.",prefix));
                }
                // 只替换测试事务的schema，SQL及生产Java账本方法保持原样。
                Connection isolated=(Connection)java.lang.reflect.Proxy.newProxyInstance(Connection.class.getClassLoader(),new Class<?>[]{Connection.class},(proxy,method,args)->{
                    if(method.getName().equals("prepareStatement")&&args!=null&&args.length>0&&args[0] instanceof String sql)args[0]=sql.replace("biz.",prefix);
                    try{return method.invoke(connection,args);}catch(InvocationTargetException error){throw error.getCause();}
                });
                var jdbc=new JdbcTemplate(new SingleConnectionDataSource(isolated,true));var ledger=new ImportAgentEditLedger(jdbc);var json=new ObjectMapper();
                Savepoint emptyDomain=connection.setSavepoint();
                jdbc.update("INSERT INTO project_import_task VALUES(?,7,'SUCCEEDED')",entry.importTaskId());
                jdbc.update("INSERT INTO project_import_publication VALUES(101,?,7,'READY')",entry.importTaskId());
                jdbc.update("INSERT INTO project_import_startup_configuration VALUES(101,7,?,0,0)",entry.importTaskId());
                var row=ledger.open(entry);row=ledger.planVolume(row,64L<<20);
                row=ledger.volumeReady(row,json.createObjectNode().put("actualBytes",64L<<20).put("reservedBytes",64L<<20).put("loopDevice","/dev/loop17").put("journal",true));
                ledger.planView(row);ledger.viewReady(row,json.createObjectNode().put("viewHash","d".repeat(64)).put("viewSourceHash",fixture.frozen().viewSourceHash()).put("viewStateHash",fixture.frozen().authorizedStateHash()));
                var workerLease=ledger.planWorker(row);
                Savepoint unstartedWorker=connection.setSavepoint();
                ledger.workerNotStarted(row,workerLease);
                assertEquals("STOPPED",jdbc.queryForObject("SELECT phase FROM project_import_agent_edit_worker WHERE edit_id=?",String.class,entry.id()));
                assertTrue(jdbc.queryForObject("SELECT windows_pid IS NULL AND native_pid IS NULL FROM project_import_agent_edit_worker WHERE edit_id=?",Boolean.class,entry.id()));
                connection.rollback(unstartedWorker);connection.releaseSavepoint(unstartedWorker);
                var launcherStart=process.info().startInstant().orElseThrow().truncatedTo(java.time.temporal.ChronoUnit.MICROS);
                ledger.workerLaunching(row,workerLease,process.pid(),launcherStart);
                var launchedRow=row;assertThrows(MyException.class,()->ledger.workerNotStarted(launchedRow,workerLease));
                ledger.workerRunning(row,workerLease,process.pid(),launcherStart,"123","42");
                for(String mutation:List.of("UPDATE project_import_agent_edit_worker SET windows_pid=999",
                        "UPDATE project_import_agent_edit_worker SET native_start_ticks='43'",
                        "UPDATE project_import_agent_edit_worker SET lease_id='"+UUID.randomUUID()+"'",
                        "UPDATE project_import_agent_edit_worker SET phase='STOPPED'",
                        "DELETE FROM project_import_agent_edit_worker")){
                    Savepoint point=connection.setSavepoint();
                    try{assertThrows(org.springframework.dao.DataIntegrityViolationException.class,()->jdbc.update(mutation));}
                    finally{connection.rollback(point);connection.releaseSavepoint(point);}
                }
                var notCreated=row;assertThrows(MyException.class,()->ledger.beginEditing(notCreated));
                ledger.planContainer(row);assertTrue(ledger.hasContainer(row));
                String containerId="f".repeat(64);
                ledger.containerCreated(row,json.createObjectNode().put("state","CONTAINER_CREATED").put("containerId",containerId));
                var stopReceipt=json.createObjectNode().put("state","STOP_CONFIRMED").put("containerName","glyahh-edit-"+entry.id()).put("containerId",containerId);
                for(String mutation:List.of("DELETE FROM project_import_agent_edit_container","UPDATE project_import_agent_edit_container SET container_id='"+"e".repeat(64)+"'",
                        "UPDATE project_import_agent_edit SET state='EDITING',revision=revision+1; UPDATE project_import_agent_edit SET state='SEALING',stop_confirmed=true,revision=revision+1")){
                    Savepoint point=connection.setSavepoint();
                    try{assertThrows(org.springframework.dao.DataIntegrityViolationException.class,()->jdbc.update(mutation));}
                    finally{connection.rollback(point);connection.releaseSavepoint(point);}
                }
                var oldRevision=row;row=ledger.beginEditing(row);
                assertThrows(MyException.class,()->ledger.beginEditing(oldRevision));
                var notStopped=row;assertThrows(MyException.class,()->ledger.beginSealing(notStopped));
                ledger.containerStopped(row,stopReceipt);ledger.containerStopped(row,stopReceipt);
                Savepoint workerAlive=connection.setSavepoint();var waitingForWorker=row;
                try{assertThrows(org.springframework.dao.DataIntegrityViolationException.class,()->ledger.beginSealing(waitingForWorker));}
                finally{connection.rollback(workerAlive);connection.releaseSavepoint(workerAlive);}
                ledger.workerStopped(row,workerLease,true,true);
                row=ledger.beginSealing(row);
                row=ledger.require(entry.id(),7);assertTrue(ledger.qualifiedSealing(row));
                assertEquals(fixture.frozen().viewSourceHash(),ledger.sealedViewSourceHash(row));
                String auth=fixture.frozen().authorizedStateHash(),base=ImportDiskVolume.editPath(entry);
                ledger.planCandidate(row,auth);
                var receipt=json.createObjectNode().put("appId","101").put("ownerId","7").put("runId","201").put("editId",entry.id().toString())
                        .put("contentHash",entry.sourceHash()).put("formatVersion",3).put("diskBytes",64L<<20).put("state","CANDIDATE_SEALED")
                        .put("authorizedStateHash",auth).put("candidatePath",base+"/candidate").put("candidateManifestPath",base+"/candidate-manifest.json")
                        .put("candidateHash",verified.candidateHash()).put("candidateManifestHash",verified.manifestHash());
                ledger.candidateReady(row,auth,receipt);var frozenRow=row;
                assertThrows(MyException.class,()->ledger.candidateVerified(frozenRow,fixture.frozen(),new ImportAgentCandidateVerifier.Verified("e".repeat(64),verified.manifestHash(),1,1)));
                var stale=new ImportAgentEditLedger.Row(entry,row.state(),row.revision()+1,false,row.resources(),row.cleanup(),row.publishPhase(),true);
                assertThrows(MyException.class,()->ledger.candidateVerified(stale,fixture.frozen(),verified));
                for(String mutation:List.of("UPDATE project_import_startup_configuration SET context_epoch=1","UPDATE app SET user_id=8")){
                    Savepoint point=connection.setSavepoint();
                    try{jdbc.update(mutation);assertThrows(MyException.class,()->ledger.candidateVerified(frozenRow,fixture.frozen(),verified));}
                    finally{connection.rollback(point);connection.releaseSavepoint(point);}
                }
                assertEquals("SEALED",jdbc.queryForObject("SELECT phase FROM project_import_agent_edit_candidate WHERE edit_id=?",String.class,entry.id()));
                ledger.candidateVerified(row,fixture.frozen(),verified);
                assertEquals("VERIFIED",jdbc.queryForObject("SELECT phase FROM project_import_agent_edit_candidate WHERE edit_id=?",String.class,entry.id()));
                var proof=json.readTree(jdbc.queryForObject("SELECT verification_json::text FROM project_import_agent_edit_candidate WHERE edit_id=?",String.class,entry.id()));
                assertEquals(entry.sourceHash(),proof.path("sourceHash").asText());assertEquals(verified.candidateHash(),proof.path("candidateHash").asText());
                assertEquals(verified.manifestHash(),proof.path("candidateManifestHash").asText());assertEquals(auth,proof.path("authorizedStateHash").asText());assertEquals(5,proof.size());
                assertTrue(proof.path("retainedOutputs").isObject());
                assertThrows(MyException.class,()->ledger.candidateVerified(frozenRow,fixture.frozen(),verified));
                assertEquals("VERIFIED",jdbc.queryForObject("SELECT phase FROM project_import_agent_edit_candidate WHERE edit_id=?",String.class,entry.id()));
                var coordinator=new ImportWorkspaceCoordinator(jdbc.getDataSource());
                var platform=org.mockito.Mockito.mock(ImportDiskVolume.class);
                org.mockito.Mockito.when(platform.stopEditContainers(org.mockito.ArgumentMatchers.any(),org.mockito.ArgumentMatchers.anyLong())).thenReturn(stopReceipt);
                var recovery=new ImportAgentEditRecovery(ledger,coordinator,platform,org.mockito.Mockito.mock(ImportAgentPublication.class));
                assertEquals(1,recovery.reconcile().recovered());
                assertEquals("CANCELLED",ledger.require(entry.id(),7).state());
                assertEquals("DONE",ledger.require(entry.id(),7).cleanup());
                assertEquals("RELEASED",jdbc.queryForObject("SELECT phase FROM project_import_agent_edit_candidate WHERE edit_id=?",String.class,entry.id()));
                assertEquals("RELEASED",jdbc.queryForObject("SELECT phase FROM project_import_agent_edit_view WHERE edit_id=?",String.class,entry.id()));
                assertEquals(containerId,jdbc.queryForObject("SELECT container_id FROM project_import_agent_edit_container WHERE edit_id=?",String.class,entry.id()));
                assertEquals("REMOVED",jdbc.queryForObject("SELECT phase FROM project_import_agent_edit_container WHERE edit_id=?",String.class,entry.id()));
                assertTrue(recovery.reconcile().unresolved().isEmpty());
                assertEquals(0,jdbc.queryForObject("SELECT count(*) FROM pg_locks WHERE locktype='advisory' AND pid=pg_backend_pid()",Integer.class));
                var emptyIdentity=new ImportAgentEditLedger.Entry(UUID.randomUUID(),7,101,entry.importTaskId(),"202",entry.sourceHash(),0,0,
                        entry.executorId(),Long.MAX_VALUE,entry.executorStart(),entry.expiresAt());
                ledger.open(emptyIdentity);assertEquals(1,recovery.reconcile().recovered());
                assertEquals("CANCELLED",ledger.require(emptyIdentity.id(),7).state());
                assertEquals("DONE",ledger.require(emptyIdentity.id(),7).cleanup());
                // 重新准备干净身份，用真实Java账本贯通服务；平台字节仍为明确标注的受控夹具。
                connection.rollback(emptyDomain);connection.releaseSavepoint(emptyDomain);
                var serviceTest=new ImportAgentCandidateVerificationTest();serviceTest.temporary=temporary;
                var serviceResult=serviceTest.validServiceFlow(ledger,(identity,viewSourceHash)->{
                    jdbc.update("INSERT INTO project_import_task VALUES(?,7,'SUCCEEDED')",identity.importTaskId());
                    jdbc.update("INSERT INTO project_import_publication VALUES(101,?,7,'READY')",identity.importTaskId());
                    jdbc.update("INSERT INTO project_import_startup_configuration VALUES(101,7,?,0,0)",identity.importTaskId());
                    var opened=ledger.planVolume(ledger.open(identity),64L<<20);
                    opened=ledger.volumeReady(opened,json.createObjectNode().put("actualBytes",64L<<20).put("reservedBytes",64L<<20).put("loopDevice","/dev/loop17").put("journal",true));
                    ledger.planView(opened);ledger.viewReady(opened,json.createObjectNode().put("viewHash","d".repeat(64)).put("viewSourceHash",viewSourceHash).put("viewStateHash","d".repeat(64)));
                    jdbc.update("UPDATE project_import_agent_edit SET state='EDITING',revision=revision+1 WHERE edit_id=?",identity.id());
                    jdbc.update("UPDATE project_import_agent_edit SET state='SEALING',stop_confirmed=true,revision=revision+1 WHERE edit_id=?",identity.id());
                    var persisted=ledger.require(identity.id(),7);
                    assertEquals(identity.executorStart(),persisted.identity().executorStart());
                    return persisted;
                });
                assertEquals("VERIFIED",jdbc.queryForObject("SELECT phase FROM project_import_agent_edit_candidate",String.class));
                var serviceProof=json.readTree(jdbc.queryForObject("SELECT verification_json::text FROM project_import_agent_edit_candidate",String.class));
                assertEquals(serviceResult.candidateHash(),serviceProof.path("candidateHash").asText());
                assertEquals(serviceResult.manifestHash(),serviceProof.path("candidateManifestHash").asText());
                assertEquals("SEALING",jdbc.queryForObject("SELECT state FROM project_import_agent_edit",String.class));
                assertEquals("NONE",jdbc.queryForObject("SELECT publish_phase FROM project_import_agent_edit",String.class));
            }finally{connection.rollback();}
            try(PreparedStatement statement=connection.prepareStatement("SELECT EXISTS(SELECT 1 FROM pg_namespace WHERE nspname=?)")){
                statement.setString(1,schema);try(ResultSet result=statement.executeQuery()){assertTrue(result.next());assertFalse(result.getBoolean(1));}
            }
        }
    }
}
