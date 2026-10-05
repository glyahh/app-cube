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
import java.util.*;
import java.security.MessageDigest;
import java.nio.charset.StandardCharsets;

/** 持久记录用户本次识别决定；不执行模型，不把批准扩展为工程运行权限。 */
@Repository
public class ImportStartupApprovalRepository {
    public record Approval(UUID id,UUID requestId,long ownerId,long appId,UUID scanId,UUID importTaskId,String contentHash,
        long configRevision,long contextEpoch,String rulesVersion,List<String> directories,boolean approved,String state,long revision,Instant expiresAt,String result,String error,boolean cancelRequested){
        public Approval {
            if(id==null||requestId==null||ownerId<=0||appId<=0||scanId==null||importTaskId==null||contentHash==null||!contentHash.matches("[0-9a-f]{64}")
                ||configRevision<0||contextEpoch<0||rulesVersion==null||rulesVersion.isBlank()||directories==null||directories.isEmpty()||directories.size()>20
                ||directories.stream().anyMatch(Objects::isNull)||new HashSet<>(directories).size()!=directories.size()||revision<0||expiresAt==null
                ||!List.of("APPROVED","REJECTED","RUNNING","CANCELLING","CANCELLED","SUCCEEDED","FAILED","STALE","RECOVERY_REQUIRED").contains(state)
                ||approved=="REJECTED".equals(state)||("SUCCEEDED".equals(state)&&(result==null||cancelRequested))
                ||(List.of("CANCELLING","CANCELLED").contains(state)&&!cancelRequested)
                ||(cancelRequested&&!List.of("CANCELLING","CANCELLED","RECOVERY_REQUIRED","STALE").contains(state)))throw new IllegalArgumentException("识别批准记录无效。");
            directories=List.copyOf(directories);
        }
    }
    private static final String ACTIVE="('APPROVED','RUNNING','CANCELLING','RECOVERY_REQUIRED')";
    private static final long ADMISSION_LOCK=0x5354415254415050L;
    private final JdbcTemplate jdbc;
    private final ObjectMapper json;
    public ImportStartupApprovalRepository(JdbcTemplate jdbc,ObjectMapper json){this.jdbc=jdbc;this.json=json;}

    @Transactional
    public Approval create(long owner,UUID request,ImportStartupService.Inspection inspection,List<String> selected,boolean approved,int perUser,int global){
        if(owner<=0||request==null||perUser<1||global<1)throw new IllegalArgumentException("批准请求无效。");
        var directories=ImportStartupAgentPolicy.requireSelection(inspection,selected);
        var proposed=new Approval(UUID.randomUUID(),request,owner,Long.parseLong(inspection.appId()),inspection.scanId(),inspection.importTaskId(),inspection.contentHash(),
            Long.parseLong(inspection.configRevision()),Long.parseLong(inspection.contextEpoch()),ImportStartupInspectionRepository.RULES_VERSION,directories,approved,
            approved?"APPROVED":"REJECTED",0,Instant.now().plusSeconds(180),null,null,false);
        // 1. 请求幂等和跨实例准入在同一短事务；拒绝不占模型任务配额。
        jdbc.queryForList("SELECT pg_advisory_xact_lock(?)",ADMISSION_LOCK);
        var previous=byRequest(owner,request);
        if(previous!=null){if(!requestHash(previous).equals(requestHash(proposed)))throw conflict();return previous;}
        if(approved&&(pending(owner)>=perUser||pending(null)>=global))throw new MyException(ErrorCode.CONFLICT_ERROR,"识别补充任务数量已达上限。");
        changed(jdbc.update("""
            INSERT INTO biz.project_import_startup_approval(approval_id,request_id,owner_id,app_id,scan_id,import_task_id,content_hash,
                config_revision,context_epoch,rules_version,directories_json,request_hash,decision,state,expires_at)
            SELECT ?,?,?,?,?,?,?,?,?,?,CAST(? AS jsonb),?,?,?,?
            WHERE EXISTS(SELECT 1 FROM biz.project_import_startup_inspection i
                JOIN biz.project_import_startup_configuration c ON c.app_id=i.app_id AND c.owner_id=i.owner_id AND c.import_task_id=i.import_task_id
                JOIN biz.project_import_publication p ON p.app_id=i.app_id AND p.owner_id=i.owner_id AND p.task_id=i.import_task_id
                JOIN biz.app a ON a.id=i.app_id AND a.user_id=i.owner_id AND a.is_delete=0
                WHERE i.scan_id=? AND i.owner_id=? AND i.app_id=? AND i.import_task_id=? AND i.content_hash=?
                    AND i.config_revision=? AND i.context_epoch=? AND i.rules_version=? AND i.state='SUCCEEDED'
                    AND c.config_revision=i.config_revision AND c.context_epoch=i.context_epoch AND p.state='READY')
            """,proposed.id(),request,owner,proposed.appId(),proposed.scanId(),proposed.importTaskId(),proposed.contentHash(),proposed.configRevision(),proposed.contextEpoch(),
                proposed.rulesVersion(),encode(directories),requestHash(proposed),approved?"APPROVE":"REJECT",proposed.state(),Timestamp.from(proposed.expiresAt()),
                proposed.scanId(),owner,proposed.appId(),proposed.importTaskId(),proposed.contentHash(),proposed.configRevision(),proposed.contextEpoch(),proposed.rulesVersion()));
        return find(owner,proposed.id());
    }
    public Approval find(long owner,UUID id){var rows=jdbc.query("SELECT * FROM biz.project_import_startup_approval WHERE owner_id=? AND approval_id=?",this::read,owner,id);return rows.isEmpty()?null:rows.getFirst();}
    public Approval byRequest(long owner,UUID request){var rows=jdbc.query("SELECT * FROM biz.project_import_startup_approval WHERE owner_id=? AND request_id=?",this::read,owner,request);return rows.isEmpty()?null:rows.getFirst();}
    /** 仍占用配额的批准，供过期和重启对账读取；不返回终态记录。 */
    public record Key(long ownerId,UUID id){ }
    // 只取身份；逐条复核，单条冻结内容损坏不阻断其他批准的恢复。
    public List<Key> outstanding(){return jdbc.query("SELECT owner_id,approval_id FROM biz.project_import_startup_approval WHERE state IN "+ACTIVE+" ORDER BY created_at",
        (row,index)->new Key(row.getLong("owner_id"),row.getObject("approval_id",UUID.class)));}
    /** 本次识别已发布的补充结果身份；调用者逐条复核，坏记录不阻断其他结果。 */
    public List<UUID> published(long owner,long appId,UUID scanId){return jdbc.query("SELECT approval_id FROM biz.project_import_startup_approval WHERE owner_id=? AND app_id=? AND scan_id=? AND state='SUCCEEDED' ORDER BY update_time,approval_id",
        (row,index)->row.getObject("approval_id",UUID.class),owner,appId,scanId);}
    public int pending(Long owner){var count=owner==null?jdbc.queryForObject("SELECT count(*) FROM biz.project_import_startup_approval WHERE state IN "+ACTIVE,Integer.class)
        :jdbc.queryForObject("SELECT count(*) FROM biz.project_import_startup_approval WHERE state IN "+ACTIVE+" AND owner_id=?",Integer.class,owner);if(count==null)throw conflict();return count;}

    public Approval transition(Approval current,String next,String result,String error){
        boolean allowed=switch(current.state()){
            case "APPROVED" -> List.of("RUNNING","CANCELLED","FAILED","STALE","RECOVERY_REQUIRED").contains(next);
            case "RUNNING" -> List.of("SUCCEEDED","CANCELLING","FAILED","STALE","RECOVERY_REQUIRED").contains(next);
            case "CANCELLING" -> List.of("CANCELLED","RECOVERY_REQUIRED").contains(next);
            case "RECOVERY_REQUIRED" -> List.of("CANCELLED","FAILED","STALE").contains(next);
            case "SUCCEEDED" -> "STALE".equals(next);
            default -> false;
        };
        if(!allowed||("SUCCEEDED".equals(next)&&result==null)||(!"SUCCEEDED".equals(next)&&result!=null)
            ||(current.cancelRequested()&&List.of("RUNNING","SUCCEEDED","FAILED").contains(next)))throw new IllegalArgumentException("批准状态不能这样迁移。");
        // 1. 运行和发布成功时再次核对数据库资格，取消/版本变化撤销旧租约。
        changed(jdbc.update("""
            UPDATE biz.project_import_startup_approval s SET state=?,revision=revision+1,result_json=CAST(? AS jsonb),error=?,cancel_requested=cancel_requested OR ?,update_time=now()
            WHERE s.approval_id=? AND s.owner_id=? AND s.app_id=? AND s.scan_id=? AND s.revision=? AND s.state=?
              AND (? NOT IN ('RUNNING','SUCCEEDED') OR (s.expires_at>now() AND s.rules_version=? AND EXISTS(
                SELECT 1 FROM biz.project_import_startup_inspection i
                JOIN biz.project_import_startup_configuration c ON c.app_id=i.app_id AND c.owner_id=i.owner_id AND c.import_task_id=i.import_task_id
                JOIN biz.project_import_publication p ON p.app_id=i.app_id AND p.owner_id=i.owner_id AND p.task_id=i.import_task_id
                JOIN biz.app a ON a.id=i.app_id AND a.user_id=i.owner_id AND a.is_delete=0
                WHERE i.scan_id=s.scan_id AND i.app_id=s.app_id AND i.owner_id=s.owner_id AND i.import_task_id=s.import_task_id
                  AND i.content_hash=s.content_hash AND i.config_revision=s.config_revision AND i.context_epoch=s.context_epoch
                  AND i.rules_version=s.rules_version AND i.state='SUCCEEDED' AND p.state='READY'
                  AND c.config_revision=s.config_revision AND c.context_epoch=s.context_epoch)))
            """,next,result,error,List.of("CANCELLING","CANCELLED").contains(next),current.id(),current.ownerId(),current.appId(),current.scanId(),current.revision(),current.state(),next,ImportStartupInspectionRepository.RULES_VERSION));
        return find(current.ownerId(),current.id());
    }
    private Approval read(ResultSet row,int index)throws SQLException{
        try{var approval=new Approval(row.getObject("approval_id",UUID.class),row.getObject("request_id",UUID.class),row.getLong("owner_id"),row.getLong("app_id"),row.getObject("scan_id",UUID.class),row.getObject("import_task_id",UUID.class),
            row.getString("content_hash"),row.getLong("config_revision"),row.getLong("context_epoch"),row.getString("rules_version"),json.readValue(row.getString("directories_json"),new TypeReference<List<String>>(){ }),
            "APPROVE".equals(row.getString("decision")),row.getString("state"),row.getLong("revision"),row.getTimestamp("expires_at").toInstant(),row.getString("result_json"),row.getString("error"),row.getBoolean("cancel_requested"));
            if(!List.of("APPROVE","REJECT").contains(row.getString("decision"))||!requestHash(approval).equals(row.getString("request_hash")))
                throw new IllegalArgumentException("批准冻结内容不一致。");
            return approval;}
        catch(java.io.IOException|IllegalArgumentException error){throw new MyException(ErrorCode.SYSTEM_ERROR,"批准记录无法复核。");}
    }
    private String requestHash(Approval row){
        try{return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(encode(List.of(row.ownerId(),row.appId(),row.scanId(),row.importTaskId(),row.contentHash(),row.configRevision(),row.contextEpoch(),row.rulesVersion(),row.directories(),row.approved())).getBytes(StandardCharsets.UTF_8)));}
        catch(java.security.NoSuchAlgorithmException error){throw new IllegalStateException(error);}
    }
    private String encode(Object value){try{return json.writeValueAsString(value);}catch(java.io.IOException error){throw new IllegalArgumentException("批准内容无效。");}}
    private static void changed(int count){if(count!=1)throw conflict();}
    private static MyException conflict(){return new MyException(ErrorCode.CONFLICT_ERROR,"识别批准、内容或上下文已变化。");}
}
