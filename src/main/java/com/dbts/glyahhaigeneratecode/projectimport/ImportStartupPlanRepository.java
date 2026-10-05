package com.dbts.glyahhaigeneratecode.projectimport;

import com.dbts.glyahhaigeneratecode.exception.ErrorCode;
import com.dbts.glyahhaigeneratecode.exception.MyException;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Transactional;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Instant;
import java.util.List;
import java.util.Objects;
import java.util.UUID;

/** 持久保存冻结计划与本次确认；内容写入后不可修改，确认不授予安装、构建或运行。 */
@Repository
public class ImportStartupPlanRepository {
    public record Stale(String reason,List<ImportStartupPlan.Change> diff){
        public Stale{if(reason==null||reason.isBlank()||diff==null)throw new IllegalArgumentException("计划失效原因无效。");diff=List.copyOf(diff);}
    }
    public record Plan(UUID id,long ownerId,long appId,String planRevision,ImportStartupPlan.Content content,String state,long revision,
        boolean acknowledged,Instant confirmedAt,Stale stale,Instant createdAt){
        public Plan{
            if(id==null||ownerId<=0||appId<=0||planRevision==null||content==null||!List.of("DRAFT","CONFIRMED","STALE","SUPERSEDED").contains(state)||revision<0
                ||("CONFIRMED".equals(state)&&confirmedAt==null)||("DRAFT".equals(state)&&(confirmedAt!=null||acknowledged))||("STALE".equals(state)&&stale==null)
                ||!planRevision.equals(ImportStartupPlan.revision(content))||!Long.toString(appId).equals(content.appId()))throw new IllegalArgumentException("运行计划记录无效。");
        }
    }
    private final JdbcTemplate jdbc;
    private final ObjectMapper json;
    public ImportStartupPlanRepository(JdbcTemplate jdbc,ObjectMapper json){this.jdbc=jdbc;this.json=json;}

    /** 同一内容已有有效计划时直接返回；插入时数据库再次核对识别、配置、发布和应用资格。 */
    public Plan create(long owner,ImportStartupPlan.Content content){
        String revision=ImportStartupPlan.revision(content);long appId=Long.parseLong(content.appId());
        var existing=active(owner,appId,revision);if(existing!=null)return existing;
        jdbc.update("""
            INSERT INTO biz.project_import_startup_plan(plan_id,owner_id,app_id,scan_id,import_task_id,content_hash,config_revision,context_epoch,rules_version,plan_revision,plan_json,state)
            SELECT ?,?,?,?,?,?,?,?,?,?,CAST(? AS jsonb),'DRAFT'
            WHERE EXISTS(SELECT 1 FROM biz.project_import_startup_inspection i
                JOIN biz.project_import_startup_configuration c ON c.app_id=i.app_id AND c.owner_id=i.owner_id AND c.import_task_id=i.import_task_id
                JOIN biz.project_import_publication p ON p.app_id=i.app_id AND p.owner_id=i.owner_id AND p.task_id=i.import_task_id
                JOIN biz.app a ON a.id=i.app_id AND a.user_id=i.owner_id AND a.is_delete=0
                WHERE i.scan_id=? AND i.owner_id=? AND i.app_id=? AND i.import_task_id=? AND i.content_hash=?
                    AND i.config_revision=? AND i.context_epoch=? AND i.rules_version=? AND i.state='SUCCEEDED'
                    AND c.config_revision=i.config_revision AND c.context_epoch=i.context_epoch AND p.state='READY')
            ON CONFLICT (owner_id,app_id,plan_revision) WHERE state IN ('DRAFT','CONFIRMED') DO NOTHING
            """,UUID.randomUUID(),owner,appId,content.scanId(),content.importTaskId(),content.contentHash(),Long.parseLong(content.configRevision()),Long.parseLong(content.contextEpoch()),
                content.rulesVersion(),revision,ImportStartupPlan.json(content),
                content.scanId(),owner,appId,content.importTaskId(),content.contentHash(),Long.parseLong(content.configRevision()),Long.parseLong(content.contextEpoch()),content.rulesVersion());
        var created=active(owner,appId,revision);if(created==null)throw conflict();return created;
    }
    public Plan find(long owner,long appId,UUID id){var rows=jdbc.query("SELECT * FROM biz.project_import_startup_plan WHERE owner_id=? AND app_id=? AND plan_id=?",this::read,owner,appId,id);return rows.isEmpty()?null:rows.getFirst();}
    /** 当前优先已确认计划，其次最新草稿，再次最新失效记录（用于展示差异）。 */
    public Plan latest(long owner,long appId){var rows=jdbc.query("SELECT * FROM biz.project_import_startup_plan WHERE owner_id=? AND app_id=? AND state<>'SUPERSEDED' ORDER BY CASE state WHEN 'CONFIRMED' THEN 0 WHEN 'DRAFT' THEN 1 ELSE 2 END,created_at DESC LIMIT 1",this::read,owner,appId);return rows.isEmpty()?null:rows.getFirst();}
    /** 最近一次确认过的其他计划，作为新计划的差异基准；不恢复其授权。 */
    public Plan lastConfirmed(long owner,long appId,UUID exclude){var rows=jdbc.query("SELECT * FROM biz.project_import_startup_plan WHERE owner_id=? AND app_id=? AND plan_id<>? AND confirmed_at IS NOT NULL ORDER BY confirmed_at DESC LIMIT 1",this::read,owner,appId,exclude);return rows.isEmpty()?null:rows.getFirst();}
    private Plan active(long owner,long appId,String revision){var rows=jdbc.query("SELECT * FROM biz.project_import_startup_plan WHERE owner_id=? AND app_id=? AND plan_revision=? AND state IN ('DRAFT','CONFIRMED')",this::read,owner,appId,revision);return rows.isEmpty()?null:rows.getFirst();}

    /** 旧确认失效并保存差异；只能从草稿或已确认迁出，失效后不能恢复。 */
    public Plan stale(Plan current,Stale stale){
        if(!List.of("DRAFT","CONFIRMED").contains(current.state()))throw new IllegalArgumentException("计划状态不能这样迁移。");
        changed(jdbc.update("UPDATE biz.project_import_startup_plan SET state='STALE',stale_json=CAST(? AS jsonb),revision=revision+1,update_time=now() WHERE plan_id=? AND owner_id=? AND app_id=? AND revision=? AND state=?",
            encode(Objects.requireNonNull(stale)),current.id(),current.ownerId(),current.appId(),current.revision(),current.state()));
        return find(current.ownerId(),current.appId(),current.id());
    }

    /** 同一事务内替代旧确认并确认本计划；数据库再次核对识别、配置、发布和应用资格。 */
    @Transactional
    public Plan confirm(Plan current,boolean acknowledged){
        if(!"DRAFT".equals(current.state()))throw new IllegalArgumentException("计划状态不能这样迁移。");
        jdbc.update("UPDATE biz.project_import_startup_plan SET state='SUPERSEDED',revision=revision+1,update_time=now() WHERE app_id=? AND state='CONFIRMED' AND plan_id<>?",current.appId(),current.id());
        changed(jdbc.update("""
            UPDATE biz.project_import_startup_plan s SET state='CONFIRMED',aggregate_acknowledged=?,confirmed_at=now(),revision=revision+1,update_time=now()
            WHERE s.plan_id=? AND s.owner_id=? AND s.app_id=? AND s.revision=? AND s.state='DRAFT' AND s.plan_revision=? AND s.rules_version=?
              AND EXISTS(SELECT 1 FROM biz.project_import_startup_inspection i
                JOIN biz.project_import_startup_configuration c ON c.app_id=i.app_id AND c.owner_id=i.owner_id AND c.import_task_id=i.import_task_id
                JOIN biz.project_import_publication p ON p.app_id=i.app_id AND p.owner_id=i.owner_id AND p.task_id=i.import_task_id
                JOIN biz.app a ON a.id=i.app_id AND a.user_id=i.owner_id AND a.is_delete=0
                WHERE i.scan_id=s.scan_id AND i.app_id=s.app_id AND i.owner_id=s.owner_id AND i.import_task_id=s.import_task_id
                  AND i.content_hash=s.content_hash AND i.config_revision=s.config_revision AND i.context_epoch=s.context_epoch
                  AND i.rules_version=s.rules_version AND i.state='SUCCEEDED' AND p.state='READY'
                  AND c.config_revision=s.config_revision AND c.context_epoch=s.context_epoch)
            """,acknowledged,current.id(),current.ownerId(),current.appId(),current.revision(),current.planRevision(),ImportStartupInspectionRepository.RULES_VERSION));
        return find(current.ownerId(),current.appId(),current.id());
    }

    private Plan read(ResultSet row,int index)throws SQLException{
        try{
            var content=ImportStartupPlan.parse(row.getString("plan_json"));
            // 列与冻结内容双重绑定，被改写的记录不能作为当前计划返回。
            if(!Objects.equals(row.getObject("scan_id",UUID.class),content.scanId())||!Objects.equals(row.getObject("import_task_id",UUID.class),content.importTaskId())
                ||!Objects.equals(row.getString("content_hash"),content.contentHash())||!Long.toString(row.getLong("config_revision")).equals(content.configRevision())
                ||!Long.toString(row.getLong("context_epoch")).equals(content.contextEpoch())||!Objects.equals(row.getString("rules_version"),content.rulesVersion()))
                throw new IllegalArgumentException("计划冻结内容不一致。");
            String stale=row.getString("stale_json");var confirmed=row.getTimestamp("confirmed_at");
            return new Plan(row.getObject("plan_id",UUID.class),row.getLong("owner_id"),row.getLong("app_id"),row.getString("plan_revision"),content,row.getString("state"),
                row.getLong("revision"),row.getBoolean("aggregate_acknowledged"),confirmed==null?null:confirmed.toInstant(),stale==null?null:json.readValue(stale,Stale.class),
                row.getTimestamp("created_at").toInstant());
        }catch(java.io.IOException|IllegalArgumentException error){throw new MyException(ErrorCode.SYSTEM_ERROR,"运行计划记录无法复核。");}
    }
    private String encode(Object value){try{return json.writeValueAsString(value);}catch(java.io.IOException error){throw new IllegalArgumentException("计划内容无效。");}}
    private static void changed(int count){if(count!=1)throw conflict();}
    private static MyException conflict(){return new MyException(ErrorCode.CONFLICT_ERROR,"运行计划、识别内容或上下文已变化。");}
}
