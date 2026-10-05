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
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.*;

/** 扫描任务的请求幂等、租约 CAS 和资源登记；不负责停止进程或执行工程。 */
@Repository
public class ImportStartupScanRepository {
    private static final long ADMISSION_LOCK=0x5354415254534341L;
    private static final String ACTIVE="('QUEUED','SCANNING','CANCELLING','STOP_FAILED','RECOVERY_REQUIRED')";
    private final JdbcTemplate jdbc;
    private final ObjectMapper json;
    public ImportStartupScanRepository(JdbcTemplate jdbc,ObjectMapper json){this.jdbc=jdbc;this.json=json;}
    private record Request(long appId,long ownerId,UUID importTaskId,long configRevision,long contextEpoch,String scope){ }

    @Transactional
    public ImportStartupScan create(ImportStartupScan proposed,int perUser,int global){
        if(proposed.status()!=ImportStartupScan.Status.QUEUED||proposed.revision()!=0||!proposed.resources().isEmpty()
            ||proposed.contentHash()!=null||proposed.cancelRequested()||!"PENDING".equals(proposed.cleanup())||perUser<1||global<1)throw new IllegalArgumentException("扫描初始状态或额度无效。");
        // 1. 全局短事务锁保证不同进程同时准入也不突破扫描任务数量。
        jdbc.queryForList("SELECT pg_advisory_xact_lock(?)",ADMISSION_LOCK);
        var previous=byRequest(proposed.ownerId(),proposed.requestId());
        if(previous!=null){if(!requestHash(previous).equals(requestHash(proposed)))throw conflict();return previous;}
        if(pending(proposed.ownerId())>=perUser||pending(null)>=global)throw new MyException(ErrorCode.CONFLICT_ERROR,"识别任务数量已达上限。");
        int inserted=jdbc.update("""
            INSERT INTO biz.project_import_startup_scan(scan_id,request_id,app_id,owner_id,import_task_id,config_revision,context_epoch,scope,
                request_hash,status,executor_id,executor_pid,executor_start,expires_at)
            SELECT ?,?,?,?,?,?,?,?,?,'QUEUED',?,?,?,?
            WHERE EXISTS(SELECT 1 FROM biz.project_import_startup_configuration c
                JOIN biz.project_import_publication p ON p.app_id=c.app_id AND p.task_id=c.import_task_id AND p.owner_id=c.owner_id
                JOIN biz.app a ON a.id=p.app_id AND a.user_id=p.owner_id AND a.is_delete=0
                WHERE c.app_id=? AND c.owner_id=? AND c.import_task_id=? AND c.config_revision=? AND c.context_epoch=? AND p.state='READY')
            """,proposed.scanId(),proposed.requestId(),proposed.appId(),proposed.ownerId(),proposed.importTaskId(),proposed.configRevision(),proposed.contextEpoch(),proposed.scope(),requestHash(proposed),
            proposed.executorId(),proposed.executorPid(),Timestamp.from(proposed.executorStart()),Timestamp.from(proposed.expiresAt()),
            proposed.appId(),proposed.ownerId(),proposed.importTaskId(),proposed.configRevision(),proposed.contextEpoch());
        requireChanged(inserted);return find(proposed.ownerId(),proposed.scanId());
    }

    public ImportStartupScan find(long owner,UUID id){
        var rows=jdbc.query("SELECT * FROM biz.project_import_startup_scan WHERE owner_id=? AND scan_id=?",this::read,owner,id);
        return rows.isEmpty()?null:rows.getFirst();
    }
    public ImportStartupScan byRequest(long owner,UUID request){
        var rows=jdbc.query("SELECT * FROM biz.project_import_startup_scan WHERE owner_id=? AND request_id=?",this::read,owner,request);
        return rows.isEmpty()?null:rows.getFirst();
    }
    public int pending(Long owner){
        String sql="SELECT count(*) FROM biz.project_import_startup_scan WHERE status IN "+ACTIVE+(owner==null?"":" AND owner_id=?");
        var count=owner==null?jdbc.queryForObject(sql,Integer.class):jdbc.queryForObject(sql,Integer.class,owner);
        if(count==null)throw conflict();return count;
    }
    public List<ImportStartupScan> outstanding(){return jdbc.query("SELECT * FROM biz.project_import_startup_scan WHERE status IN "+ACTIVE,this::read);}
    public boolean qualified(ImportStartupScan lease){
        if(lease.status()!=ImportStartupScan.Status.SCANNING)return false;
        var count=jdbc.queryForObject("""
            SELECT count(*) FROM biz.project_import_startup_scan s
            JOIN biz.project_import_startup_configuration c ON c.app_id=s.app_id AND c.owner_id=s.owner_id AND c.import_task_id=s.import_task_id
            JOIN biz.project_import_publication p ON p.app_id=s.app_id AND p.owner_id=s.owner_id AND p.task_id=s.import_task_id
            JOIN biz.app a ON a.id=s.app_id AND a.user_id=s.owner_id AND a.is_delete=0
            WHERE s.scan_id=? AND s.owner_id=? AND s.app_id=? AND s.revision=? AND s.executor_id=? AND s.status='SCANNING'
                AND s.expires_at>now() AND c.config_revision=s.config_revision AND c.context_epoch=s.context_epoch AND p.state='READY'
            """,Integer.class,lease.scanId(),lease.ownerId(),lease.appId(),lease.revision(),lease.executorId());
        return count!=null&&count==1;
    }
    public void resource(ImportStartupScan lease,ImportProcessRunner.Resource resource){
        ImportStartupScan.requireResource(resource);
        if(lease.status()!=ImportStartupScan.Status.SCANNING)throw conflict();
        requireChanged(jdbc.update("""
            UPDATE biz.project_import_startup_scan SET resources_json=resources_json || CAST(? AS jsonb),update_time=now()
            WHERE scan_id=? AND owner_id=? AND revision=? AND executor_id=? AND status='SCANNING' AND expires_at>now()
            """,encode(List.of(resource)),lease.scanId(),lease.ownerId(),lease.revision(),lease.executorId()));
    }
    public void recoveryResource(ImportStartupScan lease,ImportProcessRunner.Resource resource){
        ImportStartupScan.requireResource(resource);
        if(lease.status()!=ImportStartupScan.Status.RECOVERY_REQUIRED)throw conflict();
        requireChanged(jdbc.update("""
            UPDATE biz.project_import_startup_scan SET resources_json=resources_json || CAST(? AS jsonb),update_time=now()
            WHERE scan_id=? AND owner_id=? AND revision=? AND executor_id=? AND status='RECOVERY_REQUIRED'
            """,encode(List.of(resource)),lease.scanId(),lease.ownerId(),lease.revision(),lease.executorId()));
    }
    public ImportStartupScan transition(ImportStartupScan lease,ImportStartupScan.Status next,String hash,String cleanup,String error){
        lease.requireTransition(next,hash,cleanup);
        // 2. 取消或恢复后 revision 已变化，旧执行者不能再提交成功或追加资源。
        requireChanged(jdbc.update("""
            UPDATE biz.project_import_startup_scan SET status=?,revision=revision+1,content_hash=?,cleanup=?,error=?,cancel_requested=cancel_requested OR ?,update_time=now()
            WHERE scan_id=? AND owner_id=? AND app_id=? AND import_task_id=? AND revision=? AND executor_id=? AND status=? AND revision<9223372036854775807
                AND (? NOT IN ('SCANNING','SUCCEEDED') OR (expires_at>now() AND EXISTS(
                    SELECT 1 FROM biz.project_import_startup_configuration c
                    JOIN biz.project_import_publication p ON p.app_id=c.app_id AND p.owner_id=c.owner_id AND p.task_id=c.import_task_id
                    JOIN biz.app a ON a.id=p.app_id AND a.user_id=p.owner_id AND a.is_delete=0
                    WHERE c.app_id=project_import_startup_scan.app_id AND c.owner_id=project_import_startup_scan.owner_id
                        AND c.import_task_id=project_import_startup_scan.import_task_id AND c.config_revision=project_import_startup_scan.config_revision
                        AND c.context_epoch=project_import_startup_scan.context_epoch AND p.state='READY')))
            """,next.name(),hash,cleanup,error,next==ImportStartupScan.Status.CANCELLING,lease.scanId(),lease.ownerId(),lease.appId(),lease.importTaskId(),lease.revision(),lease.executorId(),lease.status().name(),next.name()));
        return find(lease.ownerId(),lease.scanId());
    }
    @Transactional
    public void complete(ImportStartupScan lease,ImportStartupService.Inspection result,ImportStartupInspectionRepository evidence){
        if(!lease.scanId().equals(result.scanId())||!Long.toString(lease.appId()).equals(result.appId())||!lease.importTaskId().equals(result.importTaskId())
            ||!Long.toString(lease.configRevision()).equals(result.configRevision())||!Long.toString(lease.contextEpoch()).equals(result.contextEpoch()))throw conflict();
        transition(lease,ImportStartupScan.Status.SUCCEEDED,result.contentHash(),"DONE",null);
        evidence.save(lease.ownerId(),result);
    }
    private ImportStartupScan read(ResultSet row,int index)throws SQLException{
        try{return new ImportStartupScan(row.getObject("scan_id",UUID.class),row.getObject("request_id",UUID.class),row.getLong("app_id"),row.getLong("owner_id"),row.getObject("import_task_id",UUID.class),
            row.getLong("config_revision"),row.getLong("context_epoch"),row.getString("scope"),ImportStartupScan.Status.valueOf(row.getString("status")),row.getLong("revision"),
            row.getObject("executor_id",UUID.class),row.getLong("executor_pid"),row.getTimestamp("executor_start").toInstant(),row.getTimestamp("expires_at").toInstant(),
            json.readValue(row.getString("resources_json"),new TypeReference<List<ImportProcessRunner.Resource>>(){}),row.getString("content_hash"),row.getString("cleanup"),row.getString("error"),row.getBoolean("cancel_requested"));}
        catch(java.io.IOException|IllegalArgumentException|NullPointerException error){throw new MyException(ErrorCode.SYSTEM_ERROR,"扫描任务记录无效。");}
    }
    private String requestHash(ImportStartupScan scan){
        try{return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(encode(new Request(scan.appId(),scan.ownerId(),scan.importTaskId(),scan.configRevision(),scan.contextEpoch(),scan.scope())).getBytes(StandardCharsets.UTF_8)));}
        catch(java.security.NoSuchAlgorithmException error){throw new IllegalStateException("扫描请求摘要不可用。");}
    }
    private String encode(Object value){try{return json.writeValueAsString(value);}catch(java.io.IOException error){throw new MyException(ErrorCode.SYSTEM_ERROR,"扫描任务记录失败。");}}
    private static void requireChanged(int rows){if(rows!=1)throw conflict();}
    private static MyException conflict(){return new MyException(ErrorCode.CONFLICT_ERROR,"扫描任务或上下文已变化。");}
}
