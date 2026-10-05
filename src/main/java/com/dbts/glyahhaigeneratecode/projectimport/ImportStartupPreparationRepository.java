package com.dbts.glyahhaigeneratecode.projectimport;

import com.dbts.glyahhaigeneratecode.exception.ErrorCode;
import com.dbts.glyahhaigeneratecode.exception.MyException;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.List;
import java.util.UUID;

/** 安装确认与运行结果登记。同一计划版本只有一行，重启只读取，不在这里重新执行。 */
@Repository
public class ImportStartupPreparationRepository {
    public record Row(UUID id,long ownerId,long appId,UUID planId,String planRevision,String contentHash,String kind,String decision,String state,String reason,
                      List<ImportStartupPreparation.ServiceView> services,long revision){ }
    private static final TypeReference<List<ImportStartupPreparation.ServiceView>> SERVICES=new TypeReference<>(){ };
    private final JdbcTemplate jdbc;
    private final ObjectMapper json;
    public ImportStartupPreparationRepository(JdbcTemplate jdbc,ObjectMapper json){this.jdbc=jdbc;this.json=json;}

    public Row find(long ownerId,long appId,UUID planId,String planRevision,String kind){
        var rows=jdbc.query("""
            SELECT * FROM biz.project_import_startup_preparation
            WHERE owner_id=? AND app_id=? AND plan_id=? AND plan_revision=? AND kind=?""",this::read,ownerId,appId,planId,planRevision,kind);
        return rows.isEmpty()?null:rows.getFirst();
    }

    /** 新建或按版本推进。身份列不更新。 */
    public Row save(Row row){
        try{
            if(row.revision()==0)jdbc.update("""
                INSERT INTO biz.project_import_startup_preparation(preparation_id,owner_id,app_id,plan_id,plan_revision,content_hash,kind,decision,state,reason,services_json)
                SELECT ?,?,?,?,?,?,?,?,?,?,CAST(? AS jsonb) FROM biz.project_import_startup_plan p
                WHERE p.plan_id=? AND p.owner_id=? AND p.app_id=? AND p.plan_revision=? AND p.content_hash=? AND p.state='CONFIRMED'""",
                row.id(),row.ownerId(),row.appId(),row.planId(),row.planRevision(),row.contentHash(),row.kind(),row.decision(),row.state(),row.reason(),services(row),
                row.planId(),row.ownerId(),row.appId(),row.planRevision(),row.contentHash());
            else jdbc.update("""
                UPDATE biz.project_import_startup_preparation SET decision=?,state=?,reason=?,services_json=CAST(? AS jsonb),revision=revision+1,update_time=now()
                WHERE preparation_id=? AND owner_id=? AND app_id=? AND revision=?""",
                row.decision(),row.state(),row.reason(),services(row),row.id(),row.ownerId(),row.appId(),row.revision()-1);
        }catch(IllegalArgumentException error){throw new MyException(ErrorCode.OPERATION_ERROR,"安装或运行结果无法保存。");}
        var saved=find(row.ownerId(),row.appId(),row.planId(),row.planRevision(),row.kind());
        if(saved==null||!saved.id().equals(row.id())||saved.revision()!=row.revision()||!saved.state().equals(row.state()))
            throw new MyException(ErrorCode.CONFLICT_ERROR,"安装或运行记录已变化。");
        return saved;
    }

    private String services(Row row){
        try{return json.writeValueAsString(row.services());}
        catch(com.fasterxml.jackson.core.JsonProcessingException error){throw new IllegalArgumentException("服务结果无效。");}
    }
    private Row read(ResultSet row,int index)throws SQLException{
        try{return new Row(row.getObject("preparation_id",UUID.class),row.getLong("owner_id"),row.getLong("app_id"),row.getObject("plan_id",UUID.class),
            row.getString("plan_revision"),row.getString("content_hash"),row.getString("kind"),row.getString("decision"),row.getString("state"),row.getString("reason"),
            json.readValue(row.getString("services_json"),SERVICES),row.getLong("revision"));}
        catch(com.fasterxml.jackson.core.JsonProcessingException error){throw new SQLException("服务结果无法读取。");}
    }
}
