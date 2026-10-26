package com.dbts.glyahhaigeneratecode.projectimport;

import com.dbts.glyahhaigeneratecode.exception.ErrorCode;
import com.dbts.glyahhaigeneratecode.exception.MyException;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.*;

/** 隔离执行登记：执行者与容器身份先落库再运行；每次状态变化按 revision CAS，结束行只能补记清理完成。 */
@Repository
public class ImportExecutionLedger {
    public static final List<String> ACTIVE=List.of("PREPARING","RUNNING","STOPPING");
    public static final List<String> FINISHED=List.of("SUCCEEDED","FAILED","REFUSED","STOPPED","STOP_FAILED","INTERRUPTED");
    public record Row(UUID id,long ownerId,long appId,UUID planId,long executorPid,Instant executorStart,String containerName,String containerId,
                      String state,String reason,String cleanup,long revision){
        public boolean active(){return ACTIVE.contains(state);}
    }
    /** 对外查询需要的冻结执行摘要；不暴露命令原文、限额 JSON 或执行者内部身份。 */
    public record Detail(UUID id,long ownerId,long appId,UUID planId,String planRevision,String contentHash,String directory,
                         String slot,String network,String state,String reason,Integer exitCode,long outputBytes,String cleanup,
                         long revision,Instant createdAt,Instant updatedAt,Instant finishedAt,String containerId){
        public boolean active(){return ACTIVE.contains(state);}
    }
    public record Entry(UUID id,long ownerId,long appId,UUID planId,String planRevision,String contentHash,String directory,String commandHash,
                        ImportExecutionSandbox.Limits limits,long executorPid,Instant executorStart,String slot,String network){
        public Entry(UUID id,long ownerId,long appId,UUID planId,String planRevision,String contentHash,String directory,String commandHash,
                     ImportExecutionSandbox.Limits limits,long executorPid,Instant executorStart){this(id,ownerId,appId,planId,planRevision,contentHash,directory,commandHash,limits,executorPid,executorStart,"task","none");}
        public Entry{
            if(slot==null||!slot.matches("[A-Za-z0-9._:@+-]{1,160}")||network==null||!network.matches("none|bridge|glyahh-imp-egress"))throw new IllegalArgumentException("执行槽位无效。");
            if(id==null||ownerId<=0||appId<=0||planId==null||planRevision==null||!planRevision.matches("[0-9a-f]{64}")||contentHash==null||!contentHash.matches("[0-9a-f]{64}")
                ||directory==null||commandHash==null||!commandHash.matches("[0-9a-f]{64}")||limits==null||executorPid<=0||executorStart==null)throw new IllegalArgumentException("执行登记无效。");
        }
    }
    private final JdbcTemplate jdbc;
    private final ObjectMapper json;
    public ImportExecutionLedger(JdbcTemplate jdbc,ObjectMapper json){this.jdbc=jdbc;this.json=json;}

    /** 新建登记；同一应用的同一槽位已有未结束执行时由唯一索引拒绝。 */
    public Row open(Entry entry,String state,String reason){
        if(!"PREPARING".equals(state)&&!"REFUSED".equals(state))throw new IllegalArgumentException("初始状态无效。");
        if("REFUSED".equals(state)&&(reason==null||reason.isBlank()))throw new IllegalArgumentException("拒绝原因缺失。");
        try{
            jdbc.update("""
                INSERT INTO biz.project_import_startup_execution(execution_id,owner_id,app_id,plan_id,plan_revision,content_hash,directory,command_hash,limits_json,
                    executor_pid,executor_start,container_name,state,reason,cleanup,finished_at,slot)
                VALUES (?,?,?,?,?,?,?,?,CAST(? AS jsonb),?,?,?,?,?,?,CASE WHEN ?='REFUSED' THEN now() END,?)""",
                entry.id(),entry.ownerId(),entry.appId(),entry.planId(),entry.planRevision(),entry.contentHash(),entry.directory(),entry.commandHash(),limits(entry),
                entry.executorPid(),Timestamp.from(entry.executorStart()),ImportExecutionSandbox.PREFIX+entry.id(),state,reason,"REFUSED".equals(state)?"DONE":"PENDING",state,entry.slot());
        }catch(org.springframework.dao.DuplicateKeyException error){
            throw new MyException(ErrorCode.CONFLICT_ERROR,"该槽位已有未结束的执行。");
        }
        return require(entry.id());
    }
    /** 登记容器身份；必须在启动前成功，否则调用方删除容器。 */
    public Row container(Row row,String containerId){
        return cas(row,"UPDATE biz.project_import_startup_execution SET container_id=?,revision=revision+1,update_time=now() WHERE execution_id=? AND revision=? AND state IN ('PREPARING','STOPPING') AND container_id IS NULL",
            containerId,row.id(),row.revision());
    }
    public Row running(Row row,String started){
        return cas(row,"UPDATE biz.project_import_startup_execution SET state='RUNNING',container_started=?,revision=revision+1,update_time=now() WHERE execution_id=? AND revision=? AND state='PREPARING'",
            started,row.id(),row.revision());
    }
    /** 停止意图只改为 STOPPING；真正结束仍须由执行者确认容器消失后写入。 */
    public Row stopping(Row row){
        return cas(row,"UPDATE biz.project_import_startup_execution SET state='STOPPING',revision=revision+1,update_time=now() WHERE execution_id=? AND revision=? AND state IN ('PREPARING','RUNNING')",
            row.id(),row.revision());
    }
    public Row finish(Row row,String state,String reason,Integer exitCode,long outputBytes,String cleanup){
        if(!FINISHED.contains(state)||"REFUSED".equals(state)&&reason==null||!List.of("DONE","FAILED").contains(cleanup)||outputBytes<0)throw new IllegalArgumentException("结束状态无效。");
        return cas(row,"""
            UPDATE biz.project_import_startup_execution SET state=?,reason=?,exit_code=?,output_bytes=?,cleanup=?,finished_at=now(),revision=revision+1,update_time=now()
            WHERE execution_id=? AND revision=? AND finished_at IS NULL""",state,reason,exitCode,outputBytes,cleanup,row.id(),row.revision());
    }
    /** 重启核对确认残留资源已消失后，把结束行的清理失败补记为完成。 */
    public Row cleaned(Row row){
        return cas(row,"UPDATE biz.project_import_startup_execution SET cleanup='DONE',revision=revision+1,update_time=now() WHERE execution_id=? AND revision=? AND finished_at IS NOT NULL AND cleanup='FAILED'",
            row.id(),row.revision());
    }
    public Row find(UUID id){
        var rows=jdbc.query("SELECT * FROM biz.project_import_startup_execution WHERE execution_id=?",this::read,id);
        return rows.isEmpty()?null:rows.getFirst();
    }
    public Detail detail(UUID id){
        // 网络类型随执行限额保存在 limits_json；表本身没有独立 network 列。
        var rows=jdbc.query("SELECT execution_id,owner_id,app_id,plan_id,plan_revision,content_hash,directory,slot,limits_json,state,reason,exit_code,output_bytes,cleanup,revision,created_at,update_time,finished_at,container_id FROM biz.project_import_startup_execution WHERE execution_id=?",(rs,index)->new Detail(
            rs.getObject("execution_id",UUID.class),rs.getLong("owner_id"),rs.getLong("app_id"),rs.getObject("plan_id",UUID.class),
            rs.getString("plan_revision"),rs.getString("content_hash"),rs.getString("directory"),rs.getString("slot"),
            network(rs.getString("limits_json")),rs.getString("state"),rs.getString("reason"),
            (Integer)rs.getObject("exit_code"),rs.getLong("output_bytes"),rs.getString("cleanup"),rs.getLong("revision"),
            rs.getTimestamp("created_at").toInstant(),rs.getTimestamp("update_time").toInstant(),
            rs.getTimestamp("finished_at")==null?null:rs.getTimestamp("finished_at").toInstant(),rs.getString("container_id")),id);
        return rows.isEmpty()?null:rows.getFirst();
    }
    private String network(String limitsJson){
        try{return json.readTree(limitsJson).path("network").asText("none");}
        catch(Exception ignored){return "none";}
    }
    /** 需要核对的行：未结束，或结束但清理未完成。 */
    public List<Row> outstanding(){
        return jdbc.query("SELECT * FROM biz.project_import_startup_execution WHERE finished_at IS NULL OR cleanup<>'DONE' ORDER BY created_at",this::read);
    }
    public boolean unresolved(long appId){
        Integer count=jdbc.queryForObject("SELECT count(*) FROM biz.project_import_startup_execution WHERE app_id=? AND (finished_at IS NULL OR cleanup<>'DONE')",Integer.class,appId);
        return count==null||count>0;
    }
    /** 只挡住同一槽位上尚未结束或清理未完成的执行，其他服务可以继续。 */
    public boolean blocking(long appId,String slot){
        Integer count=jdbc.queryForObject("SELECT count(*) FROM biz.project_import_startup_execution WHERE app_id=? AND slot=? AND (finished_at IS NULL OR cleanup<>'DONE')",Integer.class,appId,slot);
        return count==null||count>0;
    }
    public List<Row> active(long appId){
        return jdbc.query("SELECT * FROM biz.project_import_startup_execution WHERE app_id=? AND finished_at IS NULL",this::read,appId);
    }
    /** 删除应用时读取该应用的全部执行行，包括已结束但容器或清理还没确认的行。 */
    public List<Row> forApp(long appId){
        return jdbc.query("SELECT * FROM biz.project_import_startup_execution WHERE app_id=? ORDER BY created_at",this::read,appId);
    }
    /** 该应用未结束或清理未确认的指定前缀槽位执行；停止必须覆盖结束后的残留。 */
    public List<UUID> activeSlots(long appId,String slotPrefix){
        return jdbc.query("SELECT execution_id FROM biz.project_import_startup_execution WHERE app_id=? AND (finished_at IS NULL OR cleanup<>'DONE') AND slot LIKE ?",
            (row,index)->row.getObject(1,UUID.class),appId,slotPrefix+"%");
    }
    /** 安装槽位的未结束或未确认清理行，覆盖任务关联前的崩溃窗口。 */
    public List<UUID> unresolvedInstall(long appId){
        return jdbc.query("SELECT execution_id FROM biz.project_import_startup_execution WHERE app_id=? AND slot LIKE 'install%' AND (finished_at IS NULL OR cleanup<>'DONE')",
            (row,index)->row.getObject(1,UUID.class),appId);
    }
    public List<UUID> activeOther(long appId,String planRevision){
        return jdbc.query("SELECT execution_id FROM biz.project_import_startup_execution WHERE app_id=? AND finished_at IS NULL AND plan_revision<>?",
            (row,index)->row.getObject(1,UUID.class),appId,planRevision);
    }

    private Row cas(Row row,String sql,Object... args){
        if(row==null)throw new IllegalArgumentException("执行登记无效。");
        if(jdbc.update(sql,args)!=1)throw new MyException(ErrorCode.CONFLICT_ERROR,"执行登记已变化。");
        return require(row.id());
    }
    private Row require(UUID id){
        var row=find(id);
        if(row==null)throw new MyException(ErrorCode.SYSTEM_ERROR,"执行登记丢失。");
        return row;
    }
    private String limits(Entry entry){
        var limits=entry.limits();
        try{return json.writeValueAsString(Map.of("cpus",limits.cpus(),"memoryBytes",limits.memoryBytes(),"processes",limits.processes(),"diskBytes",limits.diskBytes(),
            "tmpBytes",limits.tmpBytes(),"wallSeconds",limits.wall().toSeconds(),"outputBytes",limits.outputBytes(),"memory",limits.memory().name(),"network",entry.network()));}
        catch(com.fasterxml.jackson.core.JsonProcessingException error){throw new IllegalStateException("执行限额无法记录。");}
    }
    private Row read(ResultSet rs,int index)throws SQLException{
        return new Row(rs.getObject("execution_id",UUID.class),rs.getLong("owner_id"),rs.getLong("app_id"),rs.getObject("plan_id",UUID.class),rs.getLong("executor_pid"),
            rs.getTimestamp("executor_start").toInstant(),rs.getString("container_name"),rs.getString("container_id"),rs.getString("state"),rs.getString("reason"),
            rs.getString("cleanup"),rs.getLong("revision"));
    }
}
