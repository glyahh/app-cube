package com.dbts.glyahhaigeneratecode.projectimport;

import com.dbts.glyahhaigeneratecode.exception.ErrorCode;
import com.dbts.glyahhaigeneratecode.exception.MyException;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;
import javax.sql.DataSource;
import java.util.Objects;
import java.util.UUID;

/** 持久保存完成的识别证据；读取结果仍须核对当前应用与内容资格。 */
@Repository
public class ImportStartupInspectionRepository {
    public static final String RULES_VERSION="import-startup-2026-10-06-v3";
    public record Stored(String state,String rulesVersion,ImportStartupService.Inspection inspection){ }
    private final JdbcTemplate jdbc;
    private final ObjectMapper json;
    public ImportStartupInspectionRepository(DataSource source,ObjectMapper json){this.jdbc=new JdbcTemplate(source);this.json=json;}

    public void save(long ownerId,ImportStartupService.Inspection result){
        String body;
        try{body=json.writeValueAsString(result);}catch(java.io.IOException error){throw invalid();}
        jdbc.update("""
            INSERT INTO biz.project_import_startup_inspection
                (scan_id,app_id,owner_id,import_task_id,config_revision,context_epoch,content_hash,scope,rules_version,inspection_json)
            VALUES(?,?,?,?,?,?,?,?,?,CAST(? AS jsonb))
            """,result.scanId(),Long.parseLong(result.appId()),ownerId,result.importTaskId(),Long.parseLong(result.configRevision()),
            Long.parseLong(result.contextEpoch()),result.contentHash(),result.scope(),RULES_VERSION,body);
    }

    public Stored find(long appId,long ownerId,UUID importTaskId,UUID scanId){
        var rows=jdbc.query("""
            SELECT * FROM biz.project_import_startup_inspection
            WHERE scan_id=? AND app_id=? AND owner_id=? AND import_task_id=?
            """,(row,index)->{
                try{
                    var result=json.readValue(row.getString("inspection_json"),ImportStartupService.Inspection.class);
                    // JSON 与查询条件双重绑定，坏记录不能借另一应用的身份返回候选。
                    if(result==null||!Objects.equals(scanId,result.scanId())||!Long.toString(appId).equals(result.appId())
                        ||!Objects.equals(importTaskId,result.importTaskId())||!Objects.equals(row.getString("content_hash"),result.contentHash())
                        ||!Long.toString(row.getLong("config_revision")).equals(result.configRevision())
                        ||!Long.toString(row.getLong("context_epoch")).equals(result.contextEpoch())
                        ||!Objects.equals(row.getString("scope"),result.scope())||result.result()==null)throw invalid();
                    return new Stored(row.getString("state"),row.getString("rules_version"),result);
                }catch(java.io.IOException error){throw invalid();}
            },scanId,appId,ownerId,importTaskId);
        if(rows.size()!=1)throw new MyException(ErrorCode.NOT_FOUND_ERROR,"识别记录不存在。");
        return rows.getFirst();
    }

    public void stale(long appId,long ownerId,UUID scanId){
        int changed=jdbc.update("UPDATE biz.project_import_startup_inspection SET state='STALE' WHERE scan_id=? AND app_id=? AND owner_id=?",scanId,appId,ownerId);
        if(changed!=1)throw invalid();
    }
    private static MyException invalid(){return new MyException(ErrorCode.SYSTEM_ERROR,"识别记录无法复核，请重新识别。");}
}
