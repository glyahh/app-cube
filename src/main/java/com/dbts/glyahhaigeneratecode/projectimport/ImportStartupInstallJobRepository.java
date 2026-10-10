package com.dbts.glyahhaigeneratecode.projectimport;

import com.dbts.glyahhaigeneratecode.exception.ErrorCode;
import com.dbts.glyahhaigeneratecode.exception.MyException;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Transactional;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.util.*;

/**
 * 安装任务的请求幂等、状态 CAS、资源与结果登记。不负责执行安装命令，也不停止进程。
 * 终态行只作审计；同一应用未完成的任务由部分唯一索引在数据库层兜底。
 */
@Repository
public class ImportStartupInstallJobRepository {
    private static final long ADMISSION_LOCK=0x494E53544A4F4231L;
    static final String ACTIVE="('PENDING','PREPARING','INSTALLING','VERIFYING','CANCELLING','STOP_FAILED','RECOVERY_REQUIRED')";
    private final JdbcTemplate jdbc;
    private final ObjectMapper json;
    public ImportStartupInstallJobRepository(JdbcTemplate jdbc,ObjectMapper json){this.jdbc=jdbc;this.json=json;}
    private record Request(long appId,long ownerId,UUID planId,String planRevision,boolean approved){ }

    /**
     * 受理安装任务。content_hash 直接从计划表取，受理阶段不读取会复制项目的计划视图。
     * 计划未确认、发布资格未就绪或应用已删除时插入 0 行，按冲突返回。
     */
    @Transactional
    public ImportStartupInstallJob create(UUID jobId,UUID requestId,long appId,long ownerId,UUID planId,String planRevision,
                                          UUID executorId,long executorPid,java.time.Instant executorStart,int perUser,int global){return create(jobId,requestId,appId,ownerId,planId,planRevision,executorId,executorPid,executorStart,perUser,global,true);}

    @Transactional
    public ImportStartupInstallJob create(UUID jobId,UUID requestId,long appId,long ownerId,UUID planId,String planRevision,
        UUID executorId,long executorPid,java.time.Instant executorStart,int perUser,int global,boolean approved){
        if(jobId==null||requestId==null||appId<=0||ownerId<=0||planId==null||executorId==null||executorPid<=0
            ||executorStart==null||planRevision==null||!planRevision.matches("[0-9a-f]{64}")||perUser<1||global<1)
            throw new IllegalArgumentException("安装任务初始状态或额度无效。");
        // 1. 全局短事务锁保证不同进程同时受理也不突破安装任务数量。
        jdbc.queryForList("SELECT pg_advisory_xact_lock(?)",ADMISSION_LOCK);
        var previous=byRequest(ownerId,requestId);
        if(previous!=null){if(previous.appId()!=appId||!previous.planId().equals(planId)||!previous.planRevision().equals(planRevision)||!Objects.equals(previous.approved(),approved))throw conflict();return previous;}
        if(pending(ownerId)>=perUser||pending(null)>=global)throw new MyException(ErrorCode.CONFLICT_ERROR,"安装任务数量已达上限。");
        int inserted=jdbc.update("""
            INSERT INTO biz.project_import_startup_install_job(job_id,request_id,app_id,owner_id,plan_id,plan_revision,content_hash,request_hash,
                state,executor_id,executor_pid,executor_start,approved)
            SELECT ?,?,?,?,?,?,p.content_hash,?,'PENDING',?,?,?,?
            FROM biz.project_import_startup_plan p
            JOIN biz.project_import_publication pub ON pub.app_id=p.app_id AND pub.owner_id=p.owner_id
            JOIN biz.app a ON a.id=p.app_id AND a.user_id=p.owner_id AND a.is_delete=0
            WHERE p.plan_id=? AND p.owner_id=? AND p.app_id=? AND p.plan_revision=? AND p.state='CONFIRMED' AND pub.state='READY'
            """,jobId,requestId,appId,ownerId,planId,planRevision,requestHash(appId,ownerId,planId,planRevision,approved),
            executorId,executorPid,Timestamp.from(executorStart),approved,
            planId,ownerId,appId,planRevision);
        requireChanged(inserted);return find(ownerId,jobId);
    }

    public ImportStartupInstallJob find(long owner,UUID id){
        var rows=jdbc.query("SELECT * FROM biz.project_import_startup_install_job WHERE owner_id=? AND job_id=?",this::read,owner,id);
        return rows.isEmpty()?null:rows.getFirst();
    }
    public ImportStartupInstallJob byRequest(long owner,UUID request){
        var rows=jdbc.query("SELECT * FROM biz.project_import_startup_install_job WHERE owner_id=? AND request_id=?",this::read,owner,request);
        return rows.isEmpty()?null:rows.getFirst();
    }
    /** 页面刷新恢复：该应用该计划下最近一次安装任务，含终态。 */
    public ImportStartupInstallJob current(long owner,long appId,UUID planId){
        var rows=jdbc.query("""
            SELECT * FROM biz.project_import_startup_install_job WHERE owner_id=? AND app_id=? AND plan_id=?
            ORDER BY created_at DESC LIMIT 1""",this::read,owner,appId,planId);
        return rows.isEmpty()?null:rows.getFirst();
    }
    /** 删除保护与冲突检查：该应用当前未完成或资源未核清的任务。 */
    public ImportStartupInstallJob activeByApp(long appId){
        var rows=jdbc.query("SELECT * FROM biz.project_import_startup_install_job WHERE app_id=? AND state IN "+ACTIVE,this::read,appId);
        return rows.isEmpty()?null:rows.getFirst();
    }
    public int pending(Long owner){
        String sql="SELECT count(*) FROM biz.project_import_startup_install_job WHERE state IN "+ACTIVE+(owner==null?"":" AND owner_id=?");
        var count=owner==null?jdbc.queryForObject(sql,Integer.class):jdbc.queryForObject(sql,Integer.class,owner);
        if(count==null)throw conflict();return count;
    }
    public List<ImportStartupInstallJob> outstanding(){return jdbc.query("SELECT * FROM biz.project_import_startup_install_job WHERE state IN "+ACTIVE,this::read);}

    /** 后台重新读取的安装资格：计划仍确认且版本一致、发布资格就绪、应用未删除。 */
    public boolean qualified(ImportStartupInstallJob lease){
        if(ImportStartupInstallJob.isTerminal(lease.state()))return false;
        var count=jdbc.queryForObject("""
            SELECT count(*) FROM biz.project_import_startup_install_job j
            JOIN biz.project_import_startup_plan p ON p.plan_id=j.plan_id AND p.owner_id=j.owner_id AND p.app_id=j.app_id
            JOIN biz.project_import_publication pub ON pub.app_id=j.app_id AND pub.owner_id=j.owner_id
            JOIN biz.app a ON a.id=j.app_id AND a.user_id=j.owner_id AND a.is_delete=0
            WHERE j.job_id=? AND j.owner_id=? AND j.app_id=? AND j.revision=? AND j.executor_id=? AND j.state=?
                AND p.state='CONFIRMED' AND p.plan_revision=j.plan_revision AND p.content_hash=j.content_hash AND pub.state='READY'
            """,Integer.class,lease.jobId(),lease.ownerId(),lease.appId(),lease.revision(),lease.executorId(),lease.state().name());
        return count!=null&&count==1;
    }

    /** 中间状态推进。revision 不匹配说明被取消或抢占，调用方必须停下。 */
    public ImportStartupInstallJob transition(ImportStartupInstallJob lease,ImportStartupInstallJob.State next,String failureStage,String reason,String cleanup){
        lease.requireTransition(next,reason);
        if(!List.of("PENDING","DONE","UNVERIFIED","FAILED").contains(cleanup))throw new IllegalArgumentException("安装清理状态无效。");
        requireChanged(jdbc.update("""
            UPDATE biz.project_import_startup_install_job
            SET state=?,revision=revision+1,failure_stage=?,failure_reason=?,cleanup=?,
                cancel_requested=cancel_requested OR ?,
                started_at=COALESCE(started_at,CASE WHEN ?='PREPARING' THEN now() END),
                finished_at=NULL,update_time=now()
            WHERE job_id=? AND owner_id=? AND app_id=? AND revision=? AND executor_id=? AND state=?
            """,next.name(),failureStage,reason,cleanup,next==ImportStartupInstallJob.State.CANCELLING,
            next.name(),lease.jobId(),lease.ownerId(),lease.appId(),lease.revision(),lease.executorId(),lease.state().name()));
        return find(lease.ownerId(),lease.jobId());
    }

    /** 终态收口：状态、阶段摘要、关联执行、preparation 身份在一次更新里写入，不能分步留下半个终态。 */
    @Transactional
    public ImportStartupInstallJob finish(ImportStartupInstallJob lease,ImportStartupInstallJob.State next,List<ImportStartupInstallJob.Stage> stages,
                                          List<UUID> executionIds,UUID preparationId,String failureStage,String reason,String cleanup){
        lease.requireTransition(next,reason);
        if(!ImportStartupInstallJob.isTerminal(next))throw new IllegalArgumentException("安装收口必须是终态。");
        if(!List.of("PENDING","DONE","UNVERIFIED","FAILED").contains(cleanup))throw new IllegalArgumentException("安装清理状态无效。");
        requireChanged(jdbc.update("""
            UPDATE biz.project_import_startup_install_job
            SET state=?,revision=revision+1,stages_json=stages_json || CAST(? AS jsonb),execution_ids=execution_ids || CAST(? AS jsonb),
                preparation_id=COALESCE(preparation_id,?),failure_stage=?,failure_reason=?,cleanup=?,
                cancel_requested=cancel_requested OR ?,finished_at=now(),update_time=now()
            WHERE job_id=? AND owner_id=? AND app_id=? AND revision=? AND executor_id=? AND state=?
            """,next.name(),encode(stages==null?List.of():stages),encode(executionIds==null?List.of():executionIds.stream().map(UUID::toString).toList()),
            preparationId,failureStage,reason,cleanup,next==ImportStartupInstallJob.State.CANCELLING,
            lease.jobId(),lease.ownerId(),lease.appId(),lease.revision(),lease.executorId(),lease.state().name()));
        return find(lease.ownerId(),lease.jobId());
    }

    /** 取消意图先落库并转入 CANCELLING；推进 revision 使执行线程的下一次 CAS 失败并停下。 */
    public ImportStartupInstallJob requestCancel(long owner,UUID jobId,long revision){
        var job=find(owner,jobId);
        if(job==null||job.revision()!=revision)throw conflict();
        job.requireTransition(ImportStartupInstallJob.State.CANCELLING,null);
        requireChanged(jdbc.update("""
            UPDATE biz.project_import_startup_install_job
            SET state='CANCELLING',cancel_requested=true,revision=revision+1,update_time=now()
            WHERE job_id=? AND owner_id=? AND revision=? AND state NOT IN ('SUCCEEDED','FAILED','REJECTED','CANCELLED')
            """,jobId,owner,revision));
        return find(owner,jobId);
    }

    /** 登记准入或创建阶段打算使用的资源；创建结果不明时按此意图核对，不凭相似名称删除。 */
    public void resource(ImportStartupInstallJob lease,ImportProcessRunner.Resource resource){
        ImportStartupInstallJob.requireResource(resource);
        if(ImportStartupInstallJob.isTerminal(lease.state()))throw conflict();
        requireChanged(jdbc.update("""
            UPDATE biz.project_import_startup_install_job SET resources_json=resources_json || CAST(? AS jsonb),revision=revision+1,update_time=now()
            WHERE job_id=? AND owner_id=? AND revision=? AND executor_id=? AND state=?
            """,encode(List.of(resource)),lease.jobId(),lease.ownerId(),lease.revision(),lease.executorId(),lease.state().name()));
    }
    public void recoveryResource(ImportStartupInstallJob lease,ImportProcessRunner.Resource resource){
        ImportStartupInstallJob.requireResource(resource);
        if(lease.state()!=ImportStartupInstallJob.State.RECOVERY_REQUIRED)throw conflict();
        requireChanged(jdbc.update("""
            UPDATE biz.project_import_startup_install_job SET resources_json=resources_json || CAST(? AS jsonb),revision=revision+1,update_time=now()
            WHERE job_id=? AND owner_id=? AND revision=? AND executor_id=? AND state='RECOVERY_REQUIRED'
            """,encode(List.of(resource)),lease.jobId(),lease.ownerId(),lease.revision(),lease.executorId()));
    }


    public void stage(long owner,UUID id,ImportStartupInstallJob.Stage stage){
        jdbc.update("UPDATE biz.project_import_startup_install_job SET stages_json=stages_json || CAST(? AS jsonb),update_time=now() WHERE job_id=? AND owner_id=? AND state IN "+ACTIVE,
            encode(List.of(stage)),id,owner);
    }
    public void execution(long owner,UUID id,UUID execution){
        requireChanged(jdbc.update("UPDATE biz.project_import_startup_install_job SET execution_ids=execution_ids || CAST(? AS jsonb) WHERE job_id=? AND owner_id=? AND NOT cancel_requested AND state IN "+ACTIVE,
            encode(List.of(execution.toString())),id,owner));
    }
    public void probe(long owner,UUID id,String name,String container){
        new ImportStartupInstallJob.Probe(name,container);
        requireChanged(jdbc.update("UPDATE biz.project_import_startup_install_job SET probes_json=probes_json || CAST(? AS jsonb) WHERE job_id=? AND owner_id=? AND NOT cancel_requested AND state IN "+ACTIVE,
            encode(List.of(new ImportStartupInstallJob.Probe(name,container))),id,owner));
    }
    /** 结果保存和任务成功在同一事务内提交；取消持有同一行锁，不能越过这里。 */
    @Transactional
    public ImportStartupInstallJob complete(ImportStartupInstallJob lease,java.util.function.Supplier<ImportStartupPreparationService.View> save){
        jdbc.queryForList("SELECT job_id FROM biz.project_import_startup_install_job WHERE job_id=? FOR UPDATE",lease.jobId());
        var current=find(lease.ownerId(),lease.jobId());
        if(current==null||current.cancelRequested()||current.revision()!=lease.revision()||!qualified(current))throw conflict();
        var result=save.get();
        var next=switch(result.state()){
            case "SUCCEEDED","NOT_REQUIRED"->ImportStartupInstallJob.State.SUCCEEDED;
            case "REJECTED"->ImportStartupInstallJob.State.REJECTED;
            default->ImportStartupInstallJob.State.FAILED;
        };
        String stage=null;
        if(next==ImportStartupInstallJob.State.FAILED)stage=current.stages().stream().filter(s->List.of("FAILED","REFUSED").contains(s.status())).map(ImportStartupInstallJob.Stage::stage).findFirst().orElse(null);
        return finish(current,next,List.of(),List.of(),result.preparationId(),stage,result.reason(),"DONE");
    }

    private ImportStartupInstallJob read(ResultSet row,int index)throws SQLException{
        try{return new ImportStartupInstallJob(
            row.getObject("job_id",UUID.class),row.getObject("request_id",UUID.class),row.getLong("app_id"),row.getLong("owner_id"),
            row.getObject("plan_id",UUID.class),row.getString("plan_revision"),row.getString("content_hash"),row.getString("request_hash"),
            ImportStartupInstallJob.State.valueOf(row.getString("state")),row.getLong("revision"),row.getBoolean("cancel_requested"),
            row.getString("failure_stage"),row.getString("failure_reason"),
            row.getObject("executor_id",UUID.class),row.getLong("executor_pid"),row.getTimestamp("executor_start").toInstant(),
            row.getTimestamp("started_at")==null?null:row.getTimestamp("started_at").toInstant(),
            row.getTimestamp("finished_at")==null?null:row.getTimestamp("finished_at").toInstant(),
            json.readValue(row.getString("execution_ids"),new TypeReference<List<UUID>>(){ }),
            json.readValue(row.getString("resources_json"),new TypeReference<List<ImportProcessRunner.Resource>>(){ }),
            json.readValue(row.getString("stages_json"),new TypeReference<List<ImportStartupInstallJob.Stage>>(){ }),
            row.getObject("preparation_id",UUID.class),row.getString("cleanup"),row.getObject("approved",Boolean.class),
            json.readValue(row.getString("probes_json"),new TypeReference<List<ImportStartupInstallJob.Probe>>(){ }),row.getTimestamp("created_at").toInstant());}
        catch(java.io.IOException|IllegalArgumentException|NullPointerException error){throw new MyException(ErrorCode.SYSTEM_ERROR,"安装任务记录无效。");}
    }
    private String requestHash(ImportStartupInstallJob job){return requestHash(job.appId(),job.ownerId(),job.planId(),job.planRevision(),Boolean.TRUE.equals(job.approved()));}
    private String requestHash(long appId,long ownerId,UUID planId,String planRevision,boolean approved){
        try{return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(
            encode(new Request(appId,ownerId,planId,planRevision,approved)).getBytes(StandardCharsets.UTF_8)));}
        catch(java.security.NoSuchAlgorithmException error){throw new IllegalStateException("安装请求摘要不可用。");}
    }
    private String encode(Object value){try{return json.writeValueAsString(value);}catch(java.io.IOException error){throw new MyException(ErrorCode.SYSTEM_ERROR,"安装任务记录失败。");}}
    private static void requireChanged(int rows){if(rows!=1)throw conflict();}
    private static MyException conflict(){return new MyException(ErrorCode.CONFLICT_ERROR,"安装任务或上下文已变化。");}
}
