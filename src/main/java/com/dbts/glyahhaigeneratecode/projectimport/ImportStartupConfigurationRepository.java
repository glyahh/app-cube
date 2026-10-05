package com.dbts.glyahhaigeneratecode.projectimport;

import com.dbts.glyahhaigeneratecode.exception.ErrorCode;
import com.dbts.glyahhaigeneratecode.exception.MyException;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;
import javax.sql.DataSource;
import java.util.List;
import java.util.UUID;
import static com.dbts.glyahhaigeneratecode.projectimport.ImportStartupConfiguration.*;

@Repository
public class ImportStartupConfigurationRepository {
    private final JdbcTemplate jdbc;
    private final ObjectMapper json;
    public ImportStartupConfigurationRepository(DataSource source,ObjectMapper json){this.jdbc=new JdbcTemplate(source);this.json=json;}
    public State current(long appId,long ownerId,UUID taskId){
        jdbc.update("""
            INSERT INTO biz.project_import_startup_configuration(app_id,owner_id,import_task_id)
            SELECT app_id,owner_id,task_id FROM biz.project_import_publication WHERE app_id=? AND owner_id=? AND task_id=? AND state='READY'
            ON CONFLICT(app_id) DO NOTHING
            """,appId,ownerId,taskId);
        return read(appId,ownerId,taskId);
    }
    public State read(long appId,long ownerId,UUID taskId){
        var rows=jdbc.query("SELECT * FROM biz.project_import_startup_configuration WHERE app_id=? AND owner_id=? AND import_task_id=?",(row,index)->{
            try{return new State(row.getLong("app_id"),row.getLong("owner_id"),row.getObject("import_task_id",UUID.class),row.getLong("config_revision"),row.getLong("context_epoch"),row.getString("scope"),json.readValue(row.getString("services_json"),new TypeReference<List<Manual>>(){}));}
            catch(java.io.IOException error){throw new MyException(ErrorCode.SYSTEM_ERROR,"启动配置读取失败。");}
        },appId,ownerId,taskId);
        if(rows.size()!=1)throw conflict();return rows.getFirst();
    }
    public State save(State previous,String scope,List<Manual> services){
        String body;try{body=json.writeValueAsString(services);}catch(java.io.IOException error){throw new MyException(ErrorCode.PARAMS_ERROR,"启动配置无效。");}
        int changed=jdbc.update("""
            UPDATE biz.project_import_startup_configuration SET scope=?,services_json=CAST(? AS jsonb),config_revision=config_revision+1,
                context_epoch=context_epoch+1,update_time=now()
            WHERE app_id=? AND owner_id=? AND import_task_id=? AND config_revision=? AND context_epoch=?
                AND config_revision<9223372036854775807 AND context_epoch<9223372036854775807
            """,scope,body,previous.appId(),previous.ownerId(),previous.importTaskId(),previous.configRevision(),previous.contextEpoch());
        if(changed!=1)throw conflict();return read(previous.appId(),previous.ownerId(),previous.importTaskId());
    }
    public void revokeForWrite(long appId){
        int changed=jdbc.update("UPDATE biz.project_import_startup_configuration SET context_epoch=context_epoch+1,update_time=now() WHERE app_id=? AND context_epoch<9223372036854775807",appId);
        if(changed==0&&Boolean.TRUE.equals(jdbc.queryForObject("SELECT EXISTS(SELECT 1 FROM biz.project_import_startup_configuration WHERE app_id=?)",Boolean.class,appId)))throw conflict();
    }
    private static MyException conflict(){return new MyException(ErrorCode.CONFLICT_ERROR,"启动配置或上下文已变化，请重新读取。");}
}
