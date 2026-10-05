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
import java.time.Instant;
import java.util.List;
import java.util.UUID;

/** 批准状态和独占执行身份原子绑定；资源登记不改变批准版本。 */
@Repository
public class ImportStartupModelLeaseRepository {
    private final JdbcTemplate jdbc;
    private final ObjectMapper json;
    private final ImportStartupApprovalRepository approvals;
    public ImportStartupModelLeaseRepository(JdbcTemplate jdbc,ObjectMapper json,ImportStartupApprovalRepository approvals){this.jdbc=jdbc;this.json=json;this.approvals=approvals;}

    @Transactional
    public ImportStartupModelLease claim(ImportStartupApprovalRepository.Approval approved,UUID executor,long pid,Instant started){
        if(!"APPROVED".equals(approved.state())||!approved.approved()||approved.cancelRequested()||!approved.expiresAt().isAfter(Instant.now()))throw conflict();
        // 1. 先验证执行者输入；无效参数不能使批准变成 RUNNING。
        new ImportStartupModelLease(approved.id(),approved.ownerId(),approved.appId(),approved.revision()+1,executor,pid,started,0,List.of(),"PENDING");
        var running=approvals.transition(approved,"RUNNING",null,null);
        changed(jdbc.update("""
            INSERT INTO biz.project_import_startup_model_execution(approval_id,owner_id,app_id,approval_revision,executor_id,executor_pid,executor_start)
            SELECT approval_id,owner_id,app_id,revision,?,?,? FROM biz.project_import_startup_approval
            WHERE approval_id=? AND owner_id=? AND app_id=? AND revision=? AND state='RUNNING' AND NOT cancel_requested AND expires_at>now()
            """,executor,pid,Timestamp.from(started),running.id(),running.ownerId(),running.appId(),running.revision()));
        return find(running.ownerId(),running.id());
    }
    public ImportStartupModelLease find(long owner,UUID id){
        var rows=jdbc.query("SELECT * FROM biz.project_import_startup_model_execution WHERE owner_id=? AND approval_id=?",this::read,owner,id);
        return rows.isEmpty()?null:rows.getFirst();
    }
    public List<ImportStartupModelLease> outstanding(){return jdbc.query("SELECT * FROM biz.project_import_startup_model_execution WHERE cleanup<>'DONE'",this::read);}
    public boolean qualified(ImportStartupModelLease lease){
        if(!"PENDING".equals(lease.cleanup()))return false;
        var count=jdbc.queryForObject("""
            SELECT count(*) FROM biz.project_import_startup_model_execution e
            JOIN biz.project_import_startup_approval s ON s.approval_id=e.approval_id AND s.owner_id=e.owner_id AND s.app_id=e.app_id
            JOIN biz.project_import_startup_inspection i ON i.scan_id=s.scan_id AND i.owner_id=s.owner_id AND i.app_id=s.app_id AND i.import_task_id=s.import_task_id
            JOIN biz.project_import_startup_configuration c ON c.app_id=s.app_id AND c.owner_id=s.owner_id AND c.import_task_id=s.import_task_id
            JOIN biz.project_import_publication p ON p.app_id=s.app_id AND p.owner_id=s.owner_id AND p.task_id=s.import_task_id
            JOIN biz.app a ON a.id=s.app_id AND a.user_id=s.owner_id AND a.is_delete=0
            WHERE e.approval_id=? AND e.owner_id=? AND e.app_id=? AND e.revision=? AND e.executor_id=? AND e.executor_pid=? AND e.executor_start=?
                AND e.cleanup='PENDING' AND s.revision=e.approval_revision AND s.state='RUNNING' AND NOT s.cancel_requested AND s.expires_at>now()
                AND s.rules_version=? AND i.rules_version=s.rules_version AND i.state='SUCCEEDED' AND i.content_hash=s.content_hash
                AND i.config_revision=s.config_revision AND i.context_epoch=s.context_epoch
                AND c.config_revision=s.config_revision AND c.context_epoch=s.context_epoch AND p.state='READY'
            """,Integer.class,lease.approvalId(),lease.ownerId(),lease.appId(),lease.revision(),lease.executorId(),lease.executorPid(),Timestamp.from(lease.executorStart()),ImportStartupInspectionRepository.RULES_VERSION);
        return count!=null&&count==1;
    }
    public void resource(ImportStartupModelLease lease,ImportProcessRunner.Resource resource){
        ImportStartupScan.requireResource(resource);
        // 取消/到期后仍登记已存在的资源，不能借撤销资格抹掉清理责任。
        changed(jdbc.update("""
            UPDATE biz.project_import_startup_model_execution e SET resources_json=resources_json || CAST(? AS jsonb),update_time=now()
            WHERE e.approval_id=? AND e.owner_id=? AND e.app_id=? AND e.revision=? AND e.executor_id=? AND e.executor_pid=? AND e.executor_start=?
                AND e.cleanup<>'DONE' AND jsonb_array_length(e.resources_json)<128
            """,encode(List.of(resource)),lease.approvalId(),lease.ownerId(),lease.appId(),lease.revision(),lease.executorId(),lease.executorPid(),Timestamp.from(lease.executorStart())));
    }
    /** 调用者先核对独占执行者、全部资源和私有输入；本方法只提交清理 CAS。 */
    public ImportStartupModelLease cleanup(ImportStartupModelLease lease,String next){
        if("DONE".equals(lease.cleanup())||!List.of("DONE","UNVERIFIED","FAILED").contains(next))throw conflict();
        changed(jdbc.update("""
            UPDATE biz.project_import_startup_model_execution SET cleanup=?,revision=revision+1,update_time=now()
            WHERE approval_id=? AND owner_id=? AND app_id=? AND revision=? AND executor_id=? AND executor_pid=? AND executor_start=? AND cleanup=?
            """,next,lease.approvalId(),lease.ownerId(),lease.appId(),lease.revision(),lease.executorId(),lease.executorPid(),Timestamp.from(lease.executorStart()),lease.cleanup()));
        return find(lease.ownerId(),lease.approvalId());
    }
    private ImportStartupModelLease read(ResultSet row,int index)throws SQLException{
        try{return new ImportStartupModelLease(row.getObject("approval_id",UUID.class),row.getLong("owner_id"),row.getLong("app_id"),row.getLong("approval_revision"),
            row.getObject("executor_id",UUID.class),row.getLong("executor_pid"),row.getTimestamp("executor_start").toInstant(),row.getLong("revision"),
            json.readValue(row.getString("resources_json"),new TypeReference<List<ImportProcessRunner.Resource>>(){}),row.getString("cleanup"));}
        catch(java.io.IOException|IllegalArgumentException failure){throw new MyException(ErrorCode.SYSTEM_ERROR,"模型执行资源无法复核。");}
    }
    private String encode(Object value){try{return json.writeValueAsString(value);}catch(java.io.IOException failure){throw new IllegalArgumentException("模型资源无法登记。");}}
    private static void changed(int rows){if(rows!=1)throw conflict();}
    private static MyException conflict(){return new MyException(ErrorCode.CONFLICT_ERROR,"模型执行资格或资源版本已变化。");}
}
