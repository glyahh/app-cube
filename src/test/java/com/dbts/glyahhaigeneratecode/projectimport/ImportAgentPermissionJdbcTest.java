package com.dbts.glyahhaigeneratecode.projectimport;

import com.dbts.glyahhaigeneratecode.exception.MyException;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.SingleConnectionDataSource;

import java.lang.reflect.InvocationTargetException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.Savepoint;
import java.sql.Statement;
import java.time.Instant;
import java.util.List;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;

/** 真实PG的策略、许可和发布反例；副作用与真实Docker另行验收。 */
@com.dbts.glyahhaigeneratecode.ExternalTest
class ImportAgentPermissionJdbcTest {
    @Test void realConstraintsAndCasRejectBypassAndKeepImmutableAudits() throws Exception {
        String schema = "permission_" + UUID.randomUUID().toString().replace("-", "");
        String prefix = '"' + schema + "\".";
        try (Connection connection = new ImportAgentEditJdbcTest().connect()) {
            connection.setAutoCommit(false);
            try {
                try (Statement statement = connection.createStatement()) {
                    statement.execute("CREATE SCHEMA \"" + schema + "\"; SET LOCAL search_path TO \"" + schema + "\"");
                    statement.execute("CREATE TABLE \"user\"(id bigint primary key); INSERT INTO \"user\" VALUES(7),(8)");
                    statement.execute("CREATE TABLE app(id bigint primary key,user_id bigint,is_delete int); INSERT INTO app VALUES(101,7,0)");
                    statement.execute("CREATE TABLE project_import_task(task_id uuid primary key,owner_id bigint,status text)");
                    statement.execute("CREATE TABLE project_import_publication(app_id bigint primary key,task_id uuid,owner_id bigint,state text)");
                    statement.execute("CREATE TABLE project_import_startup_configuration(app_id bigint,owner_id bigint,import_task_id uuid,config_revision bigint,context_epoch bigint)");
                    statement.execute("CREATE TABLE agent_run(status text,CONSTRAINT ck_agent_run_status CHECK(status IN ('RUNNING')))");
                    for (String migration : List.of("V30__import_agent_edit_ledger.sql", "V31__import_agent_edit_volume_guard.sql",
                            "V32__import_agent_edit_view.sql", "V33__import_agent_edit_candidate.sql", "V34__import_agent_edit_container.sql",
                            "V35__import_agent_worker.sql", "V36__import_agent_publication_guard.sql", "V37__import_agent_outputs.sql",
                            "V40__import_agent_permissions.sql")) {
                        statement.execute(Files.readString(Path.of("src/main/resources/db/migration", migration)).replace("biz.", prefix));
                    }
                }
                Connection isolated = (Connection) java.lang.reflect.Proxy.newProxyInstance(Connection.class.getClassLoader(),
                        new Class<?>[]{Connection.class}, (proxy, method, args) -> {
                            if (method.getName().equals("prepareStatement") && args != null && args[0] instanceof String sql) args[0] = sql.replace("biz.", prefix);
                            try { return method.invoke(connection, args); }
                            catch (InvocationTargetException error) { throw error.getCause(); }
                        });
                var jdbc = new JdbcTemplate(new SingleConnectionDataSource(isolated, true));
                var repository = new ImportAgentSettingsRepository(jdbc);
                var ledger = new ImportAgentEditLedger(jdbc);
                var json = new ObjectMapper();
                UUID task = UUID.randomUUID(), generation = UUID.randomUUID();
                jdbc.update("INSERT INTO project_import_task VALUES(?,7,'SUCCEEDED')", task);
                jdbc.update("INSERT INTO project_import_publication VALUES(101,?,7,'READY')", task);
                jdbc.update("INSERT INTO project_import_startup_configuration VALUES(101,7,?,0,0)", task);
                var settings = repository.restore(7, 101, "b".repeat(64), generation);
                assertEquals("MANUAL", settings.mode());
                assertEquals(settings, repository.restore(7, 101, "b".repeat(64), generation));
                var initial = settings;
                settings = repository.save(settings, "GUARDED_AUTO", "default", null);
                assertThrows(MyException.class, () -> repository.save(initial, "AUTO", "default", null));
                settings = repository.save(settings, "MANUAL", "default", null);
                rejected(connection, () -> repository.restore(8, 101, "b".repeat(64), generation));
                rejected(connection, () -> jdbc.update("UPDATE project_import_agent_settings SET thread_id='app:102',policy_revision=policy_revision+1"));
                rejected(connection, () -> jdbc.update("UPDATE project_import_agent_settings SET session_expires_at=session_expires_at+interval '1 hour',policy_revision=policy_revision+1"));
                var entry = new ImportAgentEditLedger.Entry(UUID.randomUUID(), 7, 101, task, "201", "a".repeat(64), 0, 0,
                        UUID.randomUUID(), Long.MAX_VALUE, Instant.now().minusSeconds(1), Instant.now().plusSeconds(300));
                rejected(connection, () -> ledger.open(entry));
                var row = ledger.open(entry, settings);
                var frozenSettings = settings;
                assertThrows(MyException.class, () -> repository.save(frozenSettings, "AUTO", "default", null));
                rejected(connection, () -> jdbc.update("UPDATE project_import_agent_edit SET permission_mode='AUTO',revision=revision+1"));
                row = ledger.planVolume(row, 64L << 20);
                row = ledger.volumeReady(row, json.createObjectNode().put("actualBytes", 64L << 20).put("reservedBytes", 64L << 20)
                        .put("loopDevice", "/dev/loop17").put("journal", true));
                ledger.planView(row);
                ledger.viewReady(row, json.createObjectNode().put("viewHash", "b".repeat(64)).put("viewSourceHash", "a".repeat(64)).put("viewStateHash", "c".repeat(64)));
                ledger.planContainer(row);
                ledger.containerCreated(row, json.createObjectNode().put("state", "CONTAINER_CREATED").put("containerId", "f".repeat(64)));
                row = ledger.beginEditing(row);
                UUID permission = UUID.randomUUID(), request = UUID.randomUUID(), proof = UUID.randomUUID();
                jdbc.update("""
                        INSERT INTO project_import_agent_permission(permission_id,edit_id,action_hash,action_json,tool,risk,scope_hash,expires_at)
                        VALUES(?,? ,?,'{}','edit','ORDINARY',?,now()+interval '60 seconds')
                        """, permission, entry.id(), "b".repeat(64), "c".repeat(64));
                rejected(connection, () -> jdbc.update("UPDATE project_import_agent_permission SET status='CONSUMED',consumption_proof=?,revision=revision+1", proof));
                rejected(connection, () -> jdbc.update("UPDATE project_import_agent_permission SET status='APPROVED',decision_source='AUTO',revision=revision+1"));
                rejected(connection, () -> jdbc.update("UPDATE project_import_agent_permission SET status='APPROVED',decision_source='SESSION',revision=revision+1"));
                rejected(connection, () -> jdbc.update("UPDATE project_import_agent_permission SET action_hash=?,revision=revision+1", "d".repeat(64)));
                jdbc.update("UPDATE project_import_agent_permission SET status='APPROVED',decision_source='USER',decision_request_id=?,approve_similar=true,revision=revision+1 WHERE revision=0", request);
                jdbc.update("""
                        INSERT INTO project_import_agent_grant(grant_id,owner_id,app_id,thread_id,session_epoch,server_generation,tool,risk,scope_hash,permission_id,expires_at)
                        SELECT ?,owner_id,app_id,thread_id,session_epoch,server_generation,'edit','ORDINARY',?,?,session_expires_at FROM project_import_agent_settings
                        """, UUID.randomUUID(), "c".repeat(64), permission);
                rejected(connection, () -> jdbc.update("DELETE FROM project_import_agent_grant"));
                rejected(connection, () -> {
                    repository.restore(7, 101, "d".repeat(64), generation);
                    jdbc.update("UPDATE project_import_agent_permission SET status='CONSUMED',consumption_proof=?,revision=revision+1", proof);
                });
                assertEquals(1, jdbc.update("UPDATE project_import_agent_permission SET status='CONSUMED',consumption_proof=?,consume_request_id=?,revision=revision+1 WHERE status='APPROVED' AND revision=1", proof,UUID.randomUUID()));
                assertEquals(0, jdbc.update("UPDATE project_import_agent_permission SET status='CONSUMED',consumption_proof=?,revision=revision+1 WHERE status='APPROVED' AND revision=1", UUID.randomUUID()));
                jdbc.update("UPDATE project_import_agent_permission SET status='RESULT_UNCONFIRMED',revision=revision+1");
                rejected(connection, () -> jdbc.update("UPDATE project_import_agent_permission SET status='APPROVED',revision=revision+1"));
                ledger.containerStopped(row, json.createObjectNode().put("state", "STOP_CONFIRMED")
                        .put("containerName", "glyahh-edit-" + entry.id()).put("containerId", "f".repeat(64)));
                row = ledger.beginSealing(row);
                String base = ImportDiskVolume.editPath(entry);
                ledger.planCandidate(row, "d".repeat(64));
                ledger.candidateReady(row, "d".repeat(64), json.createObjectNode().put("state", "CANDIDATE_SEALED")
                        .put("editId", entry.id().toString()).put("appId", "101").put("ownerId", "7").put("runId", "201")
                        .put("contentHash", entry.sourceHash()).put("formatVersion", 3).put("diskBytes", 64L << 20)
                        .put("authorizedStateHash", "d".repeat(64)).put("candidatePath", base + "/candidate")
                        .put("candidateManifestPath", base + "/candidate-manifest.json").put("candidateHash", "b".repeat(64)).put("candidateManifestHash", "c".repeat(64)));
                jdbc.update("""
                        UPDATE project_import_agent_edit_candidate SET phase='VERIFIED',verification_json=jsonb_build_object(
                        'sourceHash',?,'candidateHash',?,'candidateManifestHash',?,'authorizedStateHash',?,'retainedOutputs','{}'::jsonb)
                        """, entry.sourceHash(), "b".repeat(64), "c".repeat(64), "d".repeat(64));
                var sealing = row;
                var journal = json.createObjectNode().put("source", "source").put("folder", "folder").put("candidate", "candidate")
                        .put("backup", "backup").put("originalHash", entry.sourceHash());
                rejected(connection, () -> ledger.beginPublication(sealing, journal, List.of("src/greeting.txt")));
                jdbc.update("""
                        INSERT INTO project_import_agent_review(edit_id,review_hash,candidate_hash,manifest_hash,authorized_hash,source_hash,review_json,risk,expires_at)
                        SELECT edit_id,?,?,?,?,?,'{"authorized":["src/greeting.txt"]}','ORDINARY',expires_at FROM project_import_agent_edit
                        """, "e".repeat(64), "b".repeat(64), "c".repeat(64), "d".repeat(64), entry.sourceHash());
                jdbc.update("UPDATE project_import_agent_edit SET state='WAITING_REVIEW',permission_phase='WAITING_REVIEW',revision=revision+1");
                rejected(connection, () -> jdbc.update("UPDATE project_import_agent_review SET status='APPROVED',decision_source='AUTO',revision=revision+1"));
                rejected(connection, () -> jdbc.update("UPDATE project_import_agent_review SET review_hash=?,revision=revision+1", "f".repeat(64)));
                jdbc.update("UPDATE project_import_agent_review SET status='APPROVED',decision_source='USER',decision_request_id=?,revision=revision+1", UUID.randomUUID());
                rejected(connection, () -> {
                    repository.restore(7, 101, "d".repeat(64), UUID.randomUUID());
                    jdbc.update("UPDATE project_import_agent_edit SET state='COMMITTING',candidate_hash=?,authorized_json='[\"src/greeting.txt\"]',publish_phase='CANDIDATE_READY',recovery_json=CAST(? AS jsonb),revision=revision+1",
                            "b".repeat(64), journal.toString());
                });
                assertEquals(1, jdbc.update("UPDATE project_import_agent_edit SET state='COMMITTING',candidate_hash=?,authorized_json='[\"src/greeting.txt\"]',publish_phase='CANDIDATE_READY',recovery_json=CAST(? AS jsonb),revision=revision+1",
                        "b".repeat(64), journal.toString()));
                assertEquals(1, jdbc.queryForObject("SELECT count(*) FROM project_import_agent_review WHERE status='APPROVED'", Integer.class));
            } finally { connection.rollback(); }
            try (var statement = connection.prepareStatement("SELECT EXISTS(SELECT 1 FROM pg_namespace WHERE nspname=?)")) {
                statement.setString(1, schema);
                try (var result = statement.executeQuery()) { assertTrue(result.next()); assertFalse(result.getBoolean(1)); }
            }
        }
    }

    private static void rejected(Connection connection, Runnable action) throws Exception {
        Savepoint point = connection.setSavepoint();
        try {
            var error = assertThrows(RuntimeException.class, action::run);
            assertTrue(error instanceof DataIntegrityViolationException || error instanceof MyException, error.getClass().getName());
        } finally { connection.rollback(point); connection.releaseSavepoint(point); }
    }
}
