package com.dbts.glyahhaigeneratecode.projectimport;

import com.dbts.glyahhaigeneratecode.service.support.ProjectImportStaging.StoredFile;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Transactional;
import com.dbts.glyahhaigeneratecode.exception.ErrorCode;
import com.dbts.glyahhaigeneratecode.exception.MyException;
import java.time.Instant;
import java.sql.Timestamp;

import javax.sql.DataSource;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.List;
import java.util.UUID;

@Repository
public class ImportTaskRepository {
    public record Publication(UUID taskId, long appId, long ownerId, String state, String contentHash, long executorPid, Instant executorStart) { }
    private final JdbcTemplate jdbc;
    private final ObjectMapper json;
    public ImportTaskRepository(DataSource source, ObjectMapper json) {
        this.jdbc = new JdbcTemplate(source);
        this.json = json;
    }

    public void insert(ImportTask task) {
        jdbc.update("""
                INSERT INTO biz.project_import_task(task_id, owner_id, definition_json, status, revision,
                    next_file, receipts_json, source_bytes, cleanup_state)
                VALUES (?, ?, CAST(? AS jsonb), ?, 0, 0, '[]'::jsonb, ?, 'NONE')
                """, task.taskId(), task.ownerId(), encode(task.definition()), task.status().name(), task.definition().manifest().totalBytes());
    }

    @Transactional
    public void insertFromSource(ImportTask task,ImportSourceTask source,ImportGitReader.Snapshot snapshot) {
        ImportProviderPolicy.requireSupported(source.definition().source().provider());
        if(!task.taskId().equals(source.taskId()) || task.ownerId()!=source.ownerId())
            throw publicationConflict();
        if(source.credentialId()!=null) {
            var selected=jdbc.queryForList("""
                    SELECT c.credential_id FROM biz.project_import_credential c
                    JOIN biz.project_import_credential_version v ON v.credential_id=c.credential_id AND v.version=c.current_version
                    WHERE c.credential_id=? AND c.owner_id=? AND c.provider=? AND c.current_version=?
                        AND c.state='ACTIVE' AND v.state='ACTIVE' FOR UPDATE OF c
                    """,source.credentialId(),source.ownerId(),source.definition().source().provider(),source.credentialVersion());
            if(selected.size()!=1)throw publicationConflict();
        }
        var rows=jdbc.queryForList("SELECT * FROM biz.project_import_source WHERE task_id=? AND owner_id=? FOR UPDATE",source.taskId(),source.ownerId());
        if(rows.isEmpty())throw publicationConflict();
        var row=rows.getFirst();
        if(!"READING_CONTENT".equals(row.get("status")) || !source.executorId().equals(row.get("executor_id"))
                || ((Number)row.get("revision")).longValue()!=source.revision() || Boolean.TRUE.equals(row.get("main_task_created"))
                || !snapshot.commit().equals(row.get("commit_id")) || !snapshot.branch().equals(row.get("branch_name"))
                || !java.util.Objects.equals(source.credentialId(),row.get("credential_id")) || !java.util.Objects.equals(source.credentialVersion(),row.get("credential_version"))
                || ((Timestamp)row.get("expires_at")).toInstant().isBefore(Instant.now()))throw publicationConflict();
        insert(task);
        if(source.credentialId()!=null && jdbc.update("""
                INSERT INTO biz.project_import_credential_lease(task_id,credential_id,credential_version,owner_id,repository_url)
                VALUES (?,?,?,?,?)
                """,task.taskId(),source.credentialId(),source.credentialVersion(),source.ownerId(),source.definition().source().cloneUrl())!=1)throw publicationConflict();
        if(jdbc.update("UPDATE biz.project_import_task SET source_kind='GIT' WHERE task_id=? AND owner_id=?",task.taskId(),task.ownerId())!=1)throw publicationConflict();
        if(jdbc.update("UPDATE biz.project_import_source SET main_task_created=true,manifest_json=CAST(? AS jsonb),update_time=now() WHERE task_id=? AND owner_id=? AND revision=?",
                encode(snapshot),source.taskId(),source.ownerId(),source.revision())!=1)throw publicationConflict();
    }

    public ImportTask find(UUID id, long ownerId) {
        List<ImportTask> rows = jdbc.query("SELECT * FROM biz.project_import_task WHERE task_id = ? AND owner_id = ?",
                this::read, id, ownerId);
        return rows.isEmpty() ? null : rows.getFirst();
    }

    @Transactional
    public ImportTask resetFromSource(ImportTask previous,ImportTask.Definition definition,ImportSourceTask source,ImportGitReader.Snapshot snapshot) {
        ImportProviderPolicy.requireSupported(source.definition().source().provider());
        requireCredentialLease(previous);
        // 固定快照、来源租约与主任务必须同时匹配，不能覆盖已提交或已取消任务。
        Integer valid=jdbc.queryForObject("""
                SELECT count(*) FROM biz.project_import_source WHERE task_id=? AND owner_id=? AND revision=?
                    AND status='READING_CONTENT' AND executor_id=? AND expires_at>now() AND main_task_created=true
                    AND commit_id=? AND branch_name=? AND manifest_json=CAST(? AS jsonb) AND definition_json=CAST(? AS jsonb)
                """,Integer.class,source.taskId(),source.ownerId(),source.revision(),source.executorId(),snapshot.commit(),snapshot.branch(),encode(snapshot),encode(source.definition()));
        if(valid==null || valid!=1 || !previous.taskId().equals(source.taskId()) || previous.ownerId()!=source.ownerId())throw publicationConflict();
        if(jdbc.update("""
                UPDATE biz.project_import_task t SET definition_json=CAST(? AS jsonb),status='READING_CONTENT',revision=revision+1,
                    next_file=0,receipts_json='[]'::jsonb,source_bytes=?,uploaded_bytes=0,content_hash=NULL,
                    executor_id=NULL,error_code=NULL,cleanup_state='NONE',update_time=now()
                WHERE t.task_id=? AND t.owner_id=? AND t.revision=? AND t.status='WAITING_CONFIRMATION' AND t.source_kind='GIT'
                    AND NOT EXISTS(SELECT 1 FROM biz.project_import_publication p WHERE p.task_id=t.task_id)
                """,encode(definition),definition.manifest().totalBytes(),previous.taskId(),previous.ownerId(),previous.revision())!=1)throw publicationConflict();
        return find(previous.taskId(),previous.ownerId());
    }
    public boolean isGit(UUID id,long owner){return "GIT".equals(jdbc.queryForObject("SELECT source_kind FROM biz.project_import_task WHERE task_id=? AND owner_id=?",String.class,id,owner));}

    public int pending(Long ownerId) {
        String condition = ownerId == null ? "" : " AND owner_id = ?";
        String sql = "SELECT CAST((SELECT count(*) FROM biz.project_import_task WHERE status NOT IN ('CANCELLED','FAILED','SUCCEEDED')"+condition+") + "
                + "(SELECT count(*) FROM biz.project_import_source WHERE main_task_created=false AND status NOT IN ('CANCELLED','SUCCEEDED') "
                + "AND NOT(status='FAILED' AND cleanup_state='DONE')"+condition+") AS integer)";
        return ownerId == null ? jdbc.queryForObject(sql, Integer.class) : jdbc.queryForObject(sql, Integer.class, ownerId,ownerId);
    }

    public long reservedBytes() {
        return jdbc.queryForObject("""
                SELECT COALESCE(sum(source_bytes - uploaded_bytes), 0) FROM biz.project_import_task
                WHERE status NOT IN ('CANCELLED','FAILED','SUCCEEDED')
                """, Long.class);
    }

    public Publication publicationByApp(long appId) {
        var rows = jdbc.query("""
                SELECT p.*, CASE WHEN p.state='READY' AND (t.status<>'SUCCEEDED' OR a.id IS NULL OR a.is_delete<>0 OR a.user_id<>p.owner_id)
                    THEN 'UNAVAILABLE' ELSE p.state END AS effective_state
                FROM biz.project_import_publication p JOIN biz.project_import_task t ON t.task_id=p.task_id
                LEFT JOIN biz.app a ON a.id=p.app_id WHERE p.app_id=?
                """, (row, index) -> publication(row, row.getString("effective_state")), appId);
        return rows.isEmpty() ? null : rows.getFirst();
    }

    public Publication publicationByTask(UUID taskId, long ownerId) {
        var rows = jdbc.query("SELECT * FROM biz.project_import_publication WHERE task_id=? AND owner_id=?",
                (row, index) -> publication(row, row.getString("state")), taskId, ownerId);
        return rows.isEmpty() ? null : rows.getFirst();
    }

    @Transactional
    public ImportTask reservePublication(ImportTask task, long appId, String executorId, long pid, Instant started) {
        requireCredentialLease(task);
        requireSource(task);
        ImportTask lease = transition(task, ImportTask.Status.COMMITTING, task.nextFile(), task.receipts(), task.contentHash(), null, "NONE", executorId);
        if (lease == null) throw publicationConflict();
        if (jdbc.queryForObject("SELECT count(*) FROM biz.app WHERE id=?", Integer.class, appId) != 0) throw publicationConflict();
        jdbc.update("""
                INSERT INTO biz.project_import_publication(task_id,app_id,owner_id,state,content_hash,executor_pid,executor_start)
                VALUES (?,?,?,'PUBLISHING',?,?,?)
                """, task.taskId(), appId, task.ownerId(), task.contentHash(), pid, Timestamp.from(started));
        return lease;
    }

    @Transactional
    public void filesReady(ImportTask lease) {
        int updated = jdbc.update("""
                UPDATE biz.project_import_publication p SET state='FILES_READY',update_time=now()
                FROM biz.project_import_task t WHERE p.task_id=t.task_id AND p.task_id=? AND p.owner_id=?
                    AND p.state IN ('PUBLISHING','FILES_READY') AND t.revision=? AND t.status='COMMITTING'
                """, lease.taskId(), lease.ownerId(), lease.revision());
        if (updated != 1) throw publicationConflict();
    }

    @Transactional
    public ImportTask finishPublication(ImportTask lease) {
        requireCredentialLease(lease);
        boolean gitSource=requireSource(lease);
        // 1. 同一短事务核对资格、创建 App、发布 journal，取消和迟到结果不能越过行版本。
        ImportTask completed = transition(lease, ImportTask.Status.SUCCEEDED, lease.nextFile(), lease.receipts(), lease.contentHash(), null, "NONE", null);
        if (completed == null) throw publicationConflict();
        Publication publication = publicationByTask(lease.taskId(), lease.ownerId());
        if (publication == null || !publication.state().equals("FILES_READY") || !publication.contentHash().equals(lease.contentHash())) throw publicationConflict();
        jdbc.update("INSERT INTO biz.app(id,app_name,init_prompt,code_gen_type,user_id) VALUES (?,?,'','auto',?)",
                publication.appId(), lease.definition().appName(), lease.ownerId());
        if (jdbc.update("UPDATE biz.project_import_publication SET state='READY',update_time=now() WHERE task_id=? AND state='FILES_READY'",
                lease.taskId()) != 1) throw publicationConflict();
        if(gitSource && jdbc.update("UPDATE biz.project_import_source SET status='SUCCEEDED',revision=revision+1,update_time=now() WHERE task_id=? AND owner_id=? AND status='WAITING_CONFIRMATION' AND content_hash=?",
                lease.taskId(),lease.ownerId(),lease.contentHash())!=1)throw publicationConflict();
        return completed;
    }

    @Transactional
    public ImportTask reclaimPublication(ImportTask task, String executorId, long pid, Instant started) {
        requireCredentialLease(task);
        requireSource(task);
        ImportTask lease = transition(task, ImportTask.Status.COMMITTING, task.nextFile(), task.receipts(), task.contentHash(), null, "NONE", executorId);
        if (lease == null) throw publicationConflict();
        if (jdbc.update("""
                UPDATE biz.project_import_publication SET executor_pid=?,executor_start=?,update_time=now()
                WHERE task_id=? AND owner_id=? AND state IN ('PUBLISHING','FILES_READY')
                """, pid, Timestamp.from(started), task.taskId(), task.ownerId()) != 1) throw publicationConflict();
        return lease;
    }

    /** 应用记录已经删除后，把仍是 READY 的提交资格改为已中止。没有导入记录时不报错，也不删除子表。 */
    public void retirePublication(long appId) {
        jdbc.update("UPDATE biz.project_import_publication SET state='ABORTED',update_time=now() WHERE app_id=? AND state='READY'", appId);
    }

    public void abortPublication(UUID taskId, long ownerId) {
        if (jdbc.update("UPDATE biz.project_import_publication SET state='ABORTED',update_time=now() WHERE task_id=? AND owner_id=? AND state<>'READY'",
                taskId, ownerId) != 1) throw publicationConflict();
    }

    private static Publication publication(ResultSet row, String state) throws SQLException {
        return new Publication(row.getObject("task_id", UUID.class), row.getLong("app_id"), row.getLong("owner_id"), state,
                row.getString("content_hash"), row.getLong("executor_pid"), row.getTimestamp("executor_start").toInstant());
    }

    private static MyException publicationConflict() { return new MyException(ErrorCode.CONFLICT_ERROR, "导入提交资格已变化，请刷新任务。"); }

    private void requireCredentialLease(ImportTask task) {
        // 1. 文件夹任务没有凭据租约；Git 任务必须与凭据变更共用同一行锁。
        var references = jdbc.queryForList("SELECT credential_id FROM biz.project_import_credential_lease WHERE task_id=?",task.taskId());
        if (references.isEmpty()) return;
        UUID credentialId = (UUID) references.getFirst().get("credential_id");
        var credentials = jdbc.queryForList("SELECT credential_id FROM biz.project_import_credential WHERE credential_id=? FOR UPDATE",credentialId);
        if (credentials.isEmpty()) throw publicationConflict();
        // 2. 获得锁后重新读取租约和版本，撤销先提交则不能发布。
        Integer valid = jdbc.queryForObject("""
                SELECT count(*) FROM biz.project_import_credential_lease l
                JOIN biz.project_import_credential c ON c.credential_id=l.credential_id
                JOIN biz.project_import_credential_version v ON v.credential_id=c.credential_id AND v.version=l.credential_version
                WHERE l.task_id=? AND l.owner_id=? AND c.owner_id=? AND l.state='ACTIVE'
                    AND c.state='ACTIVE' AND v.state='ACTIVE' AND c.current_version=l.credential_version
                """,Integer.class,task.taskId(),task.ownerId(),task.ownerId());
        if (valid == null || valid != 1) throw publicationConflict();
    }

    private boolean requireSource(ImportTask task) {
        String kind=jdbc.queryForObject("SELECT source_kind FROM biz.project_import_task WHERE task_id=? AND owner_id=?",String.class,task.taskId(),task.ownerId());
        if("FOLDER".equals(kind))return false;
        if(!"GIT".equals(kind))throw publicationConflict();
        var rows=jdbc.queryForList("SELECT * FROM biz.project_import_source WHERE task_id=? AND owner_id=? AND expires_at>now() FOR UPDATE",task.taskId(),task.ownerId());
        if(rows.isEmpty())throw publicationConflict();
        var source=rows.getFirst();
        if(source.get("credential_id")!=null) {
            Integer valid=jdbc.queryForObject("""
                    SELECT count(*) FROM biz.project_import_credential_lease l
                    JOIN biz.project_import_credential c ON c.credential_id=l.credential_id
                    JOIN biz.project_import_credential_version v ON v.credential_id=l.credential_id AND v.version=l.credential_version
                    JOIN biz.project_import_source s ON s.task_id=l.task_id
                    WHERE l.task_id=? AND l.owner_id=? AND l.credential_id=? AND l.credential_version=? AND l.state='ACTIVE'
                        AND c.owner_id=l.owner_id AND c.current_version=l.credential_version AND c.state='ACTIVE' AND v.state='ACTIVE'
                        AND c.provider=s.definition_json->'source'->>'provider'
                    """,Integer.class,task.taskId(),task.ownerId(),source.get("credential_id"),source.get("credential_version"));
            if(valid==null || valid!=1)throw publicationConflict();
        }
        if(!"WAITING_CONFIRMATION".equals(source.get("status")) || !Boolean.TRUE.equals(source.get("main_task_created"))
                || !task.contentHash().equals(source.get("content_hash")) || source.get("commit_id")==null || source.get("manifest_json")==null)
            throw publicationConflict();
        try {
            var snapshot=json.readValue(source.get("manifest_json").toString(),ImportGitReader.Snapshot.class);
            var definition=json.readValue(source.get("definition_json").toString(),ImportSourceTask.Definition.class);
            ImportProviderPolicy.requireSupported(definition.source().provider());
            var files=snapshot.blobs().stream().map(blob->new com.dbts.glyahhaigeneratecode.service.support.ProjectImportManifest.FileEntry(blob.path(),blob.size())).toList();
            if(!snapshot.commit().equals(source.get("commit_id")) || !snapshot.branch().equals(source.get("branch_name"))
                    || !task.definition().files().equals(files) || !task.definition().appName().equals(definition.appName())
                    || !task.definition().options().equals(definition.options()))throw publicationConflict();
        }catch(Exception error){throw publicationConflict();}
        return true;
    }

    /** 同一状态和版本只允许一次转换，晚到上传不能覆盖取消。 */
    public ImportTask transition(ImportTask previous, ImportTask.Status status, int nextFile,
                                 List<StoredFile> receipts, String hash, String error, String cleanup, String executorId) {
        long uploaded = receipts.stream().mapToLong(StoredFile::size).sum();
        int changed = jdbc.update("""
                UPDATE biz.project_import_task SET status=?, revision=revision+1, next_file=?, receipts_json=CAST(? AS jsonb),
                    uploaded_bytes=?, content_hash=?, error_code=?, cleanup_state=?, executor_id=?, update_time=now()
                WHERE task_id=? AND owner_id=? AND revision=? AND status=?
                """, status.name(), nextFile, encode(receipts), uploaded, hash, error, cleanup, executorId,
                previous.taskId(), previous.ownerId(), previous.revision(), previous.status().name());
        if (changed != 1) return null;
        return new ImportTask(previous.taskId(), previous.ownerId(), previous.definition(), status, previous.revision() + 1,
                nextFile, receipts, hash, error, cleanup, executorId);
    }

    private ImportTask read(ResultSet row, int index) throws SQLException {
        try {
            return new ImportTask(row.getObject("task_id", UUID.class), row.getLong("owner_id"),
                    json.readValue(row.getString("definition_json"), ImportTask.Definition.class),
                    ImportTask.Status.valueOf(row.getString("status")), row.getLong("revision"), row.getInt("next_file"),
                    json.readValue(row.getString("receipts_json"), new TypeReference<List<StoredFile>>() { }),
                    row.getString("content_hash"), row.getString("error_code"), row.getString("cleanup_state"), row.getString("executor_id"));
        } catch (Exception e) { throw new SQLException("导入任务记录损坏，已阻止继续执行。"); }
    }

    private String encode(Object value) {
        try { return json.writeValueAsString(value); }
        catch (Exception e) { throw new IllegalStateException("导入任务记录无法保存。"); }
    }
}
