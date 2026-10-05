package com.dbts.glyahhaigeneratecode.projectimport;

import com.dbts.glyahhaigeneratecode.exception.ErrorCode;
import com.dbts.glyahhaigeneratecode.exception.MyException;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Transactional;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.util.List;
import java.util.UUID;

/** 来源阶段版本、归属及资源记录；网络读取不持有数据库事务。 */
@Repository
public class ImportSourceRepository {
    private final JdbcTemplate jdbc;
    private final ObjectMapper json;
    public ImportSourceRepository(JdbcTemplate jdbc,ObjectMapper json){this.jdbc=jdbc;this.json=json;}

    @Transactional
    public void insert(ImportSourceTask task) {
        insert(task,null);
    }

    @Transactional
    public void insert(ImportSourceTask task,UUID requestId) {
        if(task.status()!=ImportSourceTask.Status.RESOLVING_SOURCE || task.revision()!=0 || task.mainTaskCreated()
                || !task.branches().isEmpty() || !task.resources().isEmpty() || task.commit()!=null || task.contentHash()!=null)
            throw new IllegalArgumentException("来源初始状态无效。");
        if(task.credentialId()!=null) {
            var selected=jdbc.queryForList("SELECT provider,current_version,state FROM biz.project_import_credential WHERE credential_id=? AND owner_id=? FOR UPDATE",task.credentialId(),task.ownerId());
            if(selected.size()!=1 || !task.definition().source().provider().equals(selected.getFirst().get("provider"))
                    || !"ACTIVE".equals(selected.getFirst().get("state")) || task.credentialVersion()==null
                    || ((Number)selected.getFirst().get("current_version")).longValue()!=task.credentialVersion())requireChanged(0);
            Integer active=jdbc.queryForObject("SELECT count(*) FROM biz.project_import_credential_version WHERE credential_id=? AND version=? AND state='ACTIVE'",Integer.class,task.credentialId(),task.credentialVersion());
            if(active==null || active!=1)requireChanged(0);
        }
        jdbc.update("""
                INSERT INTO biz.project_import_source(task_id,owner_id,definition_json,status,credential_id,credential_version,
                    executor_id,executor_pid,executor_start,expires_at,client_request_id,client_request_hash)
                VALUES (?,?,CAST(? AS jsonb),'RESOLVING_SOURCE',?,?,?,?,?,?,?,?)
                """,task.taskId(),task.ownerId(),encode(task.definition()),task.credentialId(),task.credentialVersion(),
                task.executorId(),task.executorPid(),Timestamp.from(task.executorStart()),Timestamp.from(task.expiresAt()),requestId,
                requestId==null?null:requestHash(task.definition(),task.credentialId(),task.credentialVersion()));
    }

    public ImportSourceTask findByRequest(long owner,UUID requestId) {
        var rows=jdbc.query("SELECT * FROM biz.project_import_source WHERE owner_id=? AND client_request_id=?",this::read,owner,requestId);
        return rows.isEmpty()?null:rows.getFirst();
    }

    public boolean requestMatches(long owner,UUID requestId,ImportSourceTask.Definition definition,UUID credentialId,Long version) {
        Integer count=jdbc.queryForObject("SELECT count(*) FROM biz.project_import_source WHERE owner_id=? AND client_request_id=? AND client_request_hash=?",
                Integer.class,owner,requestId,requestHash(definition,credentialId,version));
        return count!=null && count==1;
    }
    private record FrozenRequest(ImportSourceTask.Definition definition,UUID credentialId,Long credentialVersion){ }
    private String requestHash(ImportSourceTask.Definition definition,UUID credentialId,Long version) {
        try{return java.util.HexFormat.of().formatHex(java.security.MessageDigest.getInstance("SHA-256")
                .digest(encode(new FrozenRequest(definition,credentialId,version)).getBytes(java.nio.charset.StandardCharsets.UTF_8)));}
        catch(java.security.NoSuchAlgorithmException error){throw new IllegalStateException("来源请求摘要不可用。");}
    }

    public ImportSourceTask find(long owner,UUID id) {
        var rows=jdbc.query("SELECT * FROM biz.project_import_source WHERE task_id=? AND owner_id=?",this::read,id,owner);
        return rows.isEmpty()?null:rows.getFirst();
    }

    public List<ImportSourceTask> expired() {
        return jdbc.query("SELECT * FROM biz.project_import_source WHERE expires_at<=now() AND status IN ('RESOLVING_SOURCE','WAITING_BRANCH','READING_CONTENT','WAITING_CONFIRMATION')",this::read);
    }

    public boolean qualified(ImportSourceTask lease) {
        Integer valid=jdbc.queryForObject("""
                SELECT count(*) FROM biz.project_import_source s
                LEFT JOIN biz.project_import_credential c ON c.credential_id=s.credential_id
                LEFT JOIN biz.project_import_credential_version v ON v.credential_id=s.credential_id AND v.version=s.credential_version
                WHERE s.task_id=? AND s.owner_id=? AND s.revision=? AND s.status=? AND s.executor_id=? AND s.expires_at>now()
                    AND (s.credential_id IS NULL OR (c.owner_id=s.owner_id AND c.provider=s.definition_json->'source'->>'provider'
                        AND c.state='ACTIVE' AND v.state='ACTIVE' AND c.current_version=s.credential_version))
                """,Integer.class,lease.taskId(),lease.ownerId(),lease.revision(),lease.status().name(),lease.executorId());
        return valid!=null && valid==1;
    }

    public void resource(ImportSourceTask lease,ImportProcessRunner.Resource resource) {
        if(resource.pid()<=0 || resource.started()==null)throw new IllegalArgumentException("进程身份无法核实。");
        // 资源登记不改变业务 revision；取消条件仍能撤销本轮读取。
        int changed=jdbc.update("""
                UPDATE biz.project_import_source SET resources_json=resources_json || CAST(? AS jsonb),update_time=now()
                WHERE task_id=? AND owner_id=? AND revision=? AND status=? AND executor_id=?
                """,encode(List.of(resource)),lease.taskId(),lease.ownerId(),lease.revision(),lease.status().name(),lease.executorId());
        requireChanged(changed);
    }

    @Transactional
    public void branchesReady(ImportSourceTask lease,List<ImportGitMetadata.Branch> branches) {
        if(lease.status()!=ImportSourceTask.Status.RESOLVING_SOURCE || branches==null || branches.isEmpty())throw new IllegalArgumentException("仓库无可导入分支。");
        // 与替换/删除共享凭据行锁，资格撤销先提交时不能发布晚到分支结果。
        if(lease.credentialId()!=null) {
            var locked=jdbc.queryForList("SELECT credential_id FROM biz.project_import_credential WHERE credential_id=? AND owner_id=? FOR UPDATE",
                    lease.credentialId(),lease.ownerId());
            if(locked.isEmpty())requireChanged(0);
        }
        if(!qualified(lease))requireChanged(0);
        requireChanged(jdbc.update("""
                UPDATE biz.project_import_source SET status='WAITING_BRANCH',revision=revision+1,branches_json=CAST(? AS jsonb),update_time=now()
                WHERE task_id=? AND owner_id=? AND revision=? AND status='RESOLVING_SOURCE' AND executor_id=? AND expires_at>now()
                """,encode(branches),lease.taskId(),lease.ownerId(),lease.revision(),lease.executorId()));
    }

    public boolean transition(ImportSourceTask previous,ImportSourceTask.Status next,String error,String cleanup) {
        return jdbc.update("""
                UPDATE biz.project_import_source SET status=?,revision=revision+1,error_code=?,cleanup_state=?,update_time=now()
                WHERE task_id=? AND owner_id=? AND revision=? AND status=? AND executor_id=?
                """,next.name(),error,cleanup,previous.taskId(),previous.ownerId(),previous.revision(),previous.status().name(),previous.executorId())==1;
    }

    @Transactional
    public ImportSourceTask select(ImportSourceTask previous,String branch,String commit,UUID executor,long pid,java.time.Instant started,long reservedBytes) {
        if(previous.status()!=ImportSourceTask.Status.WAITING_BRANCH || !ImportRepositorySource.branch(branch).equals(branch)
                || !commit.matches("[0-9a-f]{40}") || reservedBytes<=0)throw new IllegalArgumentException("来源选择无效。");
        if(previous.credentialId()!=null) {
            var locked=jdbc.queryForList("SELECT credential_id FROM biz.project_import_credential WHERE credential_id=? AND owner_id=? FOR UPDATE",previous.credentialId(),previous.ownerId());
            if(locked.isEmpty() || !qualified(previous))requireChanged(0);
        }
        requireChanged(jdbc.update("""
                UPDATE biz.project_import_source SET status='READING_CONTENT',revision=revision+1,branch_name=?,commit_id=?,
                    executor_id=?,executor_pid=?,executor_start=?,reserved_bytes=?,update_time=now()
                WHERE task_id=? AND owner_id=? AND revision=? AND status='WAITING_BRANCH' AND expires_at>now()
                """,branch,commit,executor,pid,Timestamp.from(started),reservedBytes,previous.taskId(),previous.ownerId(),previous.revision()));
        return find(previous.ownerId(),previous.taskId());
    }

    @Transactional
    public void contentReady(ImportSourceTask lease,String contentHash) {
        if(lease.status()!=ImportSourceTask.Status.READING_CONTENT || contentHash==null || !contentHash.matches("[0-9a-f]{64}"))throw new IllegalArgumentException("来源封存版本无效。");
        if(lease.credentialId()!=null) {
            var locked=jdbc.queryForList("SELECT credential_id FROM biz.project_import_credential WHERE credential_id=? AND owner_id=? FOR UPDATE",lease.credentialId(),lease.ownerId());
            if(locked.isEmpty() || !qualified(lease))requireChanged(0);
        }
        requireChanged(jdbc.update("""
                UPDATE biz.project_import_source s SET status='WAITING_CONFIRMATION',revision=s.revision+1,content_hash=?,update_time=now()
                FROM biz.project_import_task t WHERE s.task_id=t.task_id AND s.task_id=? AND s.owner_id=? AND s.revision=?
                    AND s.status='READING_CONTENT' AND s.executor_id=? AND s.expires_at>now() AND s.main_task_created=true
                    AND t.owner_id=s.owner_id AND t.status='WAITING_CONFIRMATION' AND t.content_hash=?
                """,contentHash,lease.taskId(),lease.ownerId(),lease.revision(),lease.executorId(),contentHash));
    }

    public ImportGitReader.Snapshot snapshot(ImportSourceTask lease) {
        String stored=jdbc.queryForObject("SELECT manifest_json::text FROM biz.project_import_source WHERE task_id=? AND owner_id=? AND revision=? AND status='READING_CONTENT' AND executor_id=?",
                String.class,lease.taskId(),lease.ownerId(),lease.revision(),lease.executorId());
        try{return json.readValue(stored,ImportGitReader.Snapshot.class);}
        catch(Exception error){throw new MyException(ErrorCode.CONFLICT_ERROR,"固定内容清单无法核实。");}
    }

    @Transactional
    public ImportSourceTask rebuild(ImportSourceTask previous,ImportSourceTask.Definition definition,UUID executor,long pid,java.time.Instant started) {
        if(previous.status()!=ImportSourceTask.Status.WAITING_CONFIRMATION || !previous.mainTaskCreated())requireChanged(0);
        if(previous.credentialId()!=null) {
            var locked=jdbc.queryForList("SELECT credential_id FROM biz.project_import_credential WHERE credential_id=? AND owner_id=? FOR UPDATE",previous.credentialId(),previous.ownerId());
            if(locked.isEmpty())requireChanged(0);
        }
        if(!qualified(previous))requireChanged(0);
        // 先撤销旧提交资格；此后发生清理或重读失败也不能提交旧清单。
        requireChanged(jdbc.update("""
                UPDATE biz.project_import_source s SET status='READING_CONTENT',revision=s.revision+1,definition_json=CAST(? AS jsonb),
                    content_hash=NULL,executor_id=?,executor_pid=?,executor_start=?,update_time=now()
                WHERE s.task_id=? AND s.owner_id=? AND s.revision=? AND s.status='WAITING_CONFIRMATION' AND s.expires_at>now()
                    AND s.main_task_created=true AND s.manifest_json IS NOT NULL
                    AND EXISTS(SELECT 1 FROM biz.project_import_task t WHERE t.task_id=s.task_id AND t.owner_id=s.owner_id
                        AND t.status='WAITING_CONFIRMATION' AND t.source_kind='GIT')
                    AND NOT EXISTS(SELECT 1 FROM biz.project_import_publication p WHERE p.task_id=s.task_id)
                """,encode(definition),executor,pid,Timestamp.from(started),previous.taskId(),previous.ownerId(),previous.revision()));
        return find(previous.ownerId(),previous.taskId());
    }

    private ImportSourceTask read(ResultSet row,int index)throws SQLException {
        try {
            return new ImportSourceTask(row.getObject("task_id",UUID.class),row.getLong("owner_id"),
                    json.readValue(row.getString("definition_json"),ImportSourceTask.Definition.class),
                    ImportSourceTask.Status.valueOf(row.getString("status")),row.getLong("revision"),row.getObject("credential_id",UUID.class),
                    row.getObject("credential_version",Long.class),row.getString("branch_name"),row.getString("commit_id"),
                    json.readValue(row.getString("branches_json"),new TypeReference<>(){}),json.readValue(row.getString("resources_json"),new TypeReference<>(){}),
                    row.getBoolean("main_task_created"),row.getString("content_hash"),row.getObject("executor_id",UUID.class),row.getLong("executor_pid"),
                    row.getTimestamp("executor_start").toInstant(),row.getTimestamp("expires_at").toInstant(),row.getString("error_code"),row.getString("cleanup_state"));
        } catch(Exception error){throw new SQLException("来源任务记录无效。");}
    }
    private String encode(Object value){try{return json.writeValueAsString(value);}catch(Exception error){throw new IllegalStateException("来源记录无法保存。");}}
    private static void requireChanged(int count){if(count!=1)throw new MyException(ErrorCode.CONFLICT_ERROR,"来源任务已变化，请刷新。");}
}
