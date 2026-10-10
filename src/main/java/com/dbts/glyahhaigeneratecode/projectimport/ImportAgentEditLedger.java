package com.dbts.glyahhaigeneratecode.projectimport;

import com.dbts.glyahhaigeneratecode.exception.ErrorCode;
import com.dbts.glyahhaigeneratecode.exception.MyException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.UUID;

/** 冻结编辑身份与撤销资格；不提供执行、候选授权或文件发布捷径。 */
@Repository
public class ImportAgentEditLedger {
    private final com.fasterxml.jackson.databind.ObjectMapper json=new com.fasterxml.jackson.databind.ObjectMapper();
    public record Entry(UUID id,long ownerId,long appId,UUID importTaskId,String runId,String sourceHash,
                        long configRevision,long contextEpoch,UUID executorId,long executorPid,
                        Instant executorStart,Instant expiresAt) {
        public Entry {
            if(id==null||ownerId<=0||appId<=0||importTaskId==null||executorId==null||executorPid<=0
                ||runId==null||!runId.matches("[1-9][0-9]{0,18}")||sourceHash==null||!sourceHash.matches("[0-9a-f]{64}")
                ||configRevision<0||contextEpoch<0||executorStart==null||expiresAt==null||!expiresAt.isAfter(executorStart))
                throw new IllegalArgumentException("编辑身份或内容版本无效。");
            try { Long.parseLong(runId); }
            catch(NumberFormatException error){throw new IllegalArgumentException("编辑运行ID无效。");}
        }
    }
    public record Row(Entry identity,String state,long revision,boolean cancelRequested,
                      String resources,String cleanup,String publishPhase,boolean stopConfirmed) { }

    private final JdbcTemplate jdbc;
    public ImportAgentEditLedger(JdbcTemplate jdbc){this.jdbc=jdbc;}

    public java.util.Map<String,Object> workerInfo(Row row){
        var rows=jdbc.queryForList("SELECT * FROM biz.project_import_agent_edit_worker WHERE edit_id=?",row.identity().id());
        return rows.isEmpty()?java.util.Map.of():rows.getFirst();
    }

    public void workerLaunching(Row row,UUID lease,long pid,Instant started){
        if(pid<=0||started==null)throw conflict();
        if(jdbc.update("UPDATE biz.project_import_agent_edit_worker SET windows_pid=?,windows_start=? WHERE edit_id=? AND lease_id=? AND phase='PLANNED' AND windows_pid IS NULL",
                pid,Timestamp.from(started.truncatedTo(java.time.temporal.ChronoUnit.MICROS)),row.identity().id(),lease)!=1)throw conflict();
    }

    public UUID planWorker(Row row){
        if(!qualified(row)||!"PREPARING".equals(row.state()))throw conflict();
        UUID lease=UUID.randomUUID();var e=row.identity();
        if(jdbc.update("""
            INSERT INTO biz.project_import_agent_edit_worker(edit_id,lease_id,phase)
            SELECT edit_id,?,'PLANNED' FROM biz.project_import_agent_edit
            WHERE edit_id=? AND owner_id=? AND revision=? AND state='PREPARING' AND NOT cancel_requested
            """,lease,e.id(),e.ownerId(),row.revision())!=1)throw conflict();
        return lease;
    }

    public void workerRunning(Row row,UUID lease,long windowsPid,Instant windowsStart,String nativePid,String nativeTicks){
        if(!qualified(row)||windowsPid<=0||windowsStart==null||nativePid==null||nativeTicks==null
                ||!nativePid.matches("[1-9][0-9]{0,18}")||!nativeTicks.matches("[1-9][0-9]{0,19}"))throw conflict();
        var e=row.identity();
        if(jdbc.update("""
            UPDATE biz.project_import_agent_edit_worker w SET phase='RUNNING',windows_pid=?,windows_start=?,native_pid=?,native_start_ticks=?
            FROM biz.project_import_agent_edit e WHERE w.edit_id=e.edit_id AND e.edit_id=? AND e.owner_id=? AND e.revision=?
              AND w.lease_id=? AND w.phase='PLANNED' AND e.state='PREPARING' AND NOT e.cancel_requested AND e.expires_at>now()
            """,windowsPid,Timestamp.from(windowsStart),Long.parseLong(nativePid),nativeTicks,e.id(),e.ownerId(),row.revision(),lease)!=1)throw conflict();
    }

    public void workerStopped(Row row,UUID lease,boolean nativeStopped,boolean launcherStopped){
        if(!nativeStopped||!launcherStopped)throw conflict();var e=row.identity();
        int changed=jdbc.update("""
            UPDATE biz.project_import_agent_edit_worker w SET phase='STOPPED',stop_proof='{"nativeStopped":true,"launcherStopped":true}'::jsonb
            FROM biz.project_import_agent_edit e WHERE w.edit_id=e.edit_id AND e.edit_id=? AND e.owner_id=? AND e.revision=?
              AND w.lease_id=? AND w.phase IN ('PLANNED','RUNNING','STOPPING') AND e.closed_at IS NULL
            """,e.id(),e.ownerId(),row.revision(),lease);
        if(changed!=1&&!Boolean.TRUE.equals(jdbc.queryForObject("SELECT EXISTS(SELECT 1 FROM biz.project_import_agent_edit_worker WHERE edit_id=? AND lease_id=? AND phase='STOPPED')",Boolean.class,e.id(),lease)))throw conflict();
    }

    /** 仅供本轮确认未创建进程且已建立停止墓碑的启动失败链路收口。 */
    public void workerNotStarted(Row row,UUID lease){
        var e=row.identity();
        if(jdbc.update("""
            UPDATE biz.project_import_agent_edit_worker w SET phase='STOPPED',stop_proof='{"nativeStopped":true,"launcherStopped":true}'::jsonb
            FROM biz.project_import_agent_edit e WHERE w.edit_id=e.edit_id AND e.edit_id=? AND e.owner_id=? AND e.revision=?
              AND w.lease_id=? AND w.phase='PLANNED' AND w.windows_pid IS NULL AND w.windows_start IS NULL
              AND w.native_pid IS NULL AND w.native_start_ticks IS NULL AND e.closed_at IS NULL
            """,e.id(),e.ownerId(),row.revision(),lease)!=1)throw conflict();
    }

    /** 调用者先持有应用锁并建立完整快照；数据库另核对当前归属、来源和上下文。 */
    public Row open(Entry entry) {
        try {
            jdbc.update("""
                INSERT INTO biz.project_import_agent_edit(edit_id,owner_id,app_id,import_task_id,run_id,source_hash,
                    config_revision,context_epoch,executor_id,executor_pid,executor_start,expires_at)
                VALUES(?,?,?,?,?,?,?,?,?,?,?,?)
                """,entry.id(),entry.ownerId(),entry.appId(),entry.importTaskId(),Long.parseLong(entry.runId()),entry.sourceHash(),
                entry.configRevision(),entry.contextEpoch(),entry.executorId(),entry.executorPid(),
                Timestamp.from(entry.executorStart()),Timestamp.from(entry.expiresAt()));
        } catch(org.springframework.dao.DuplicateKeyException error){throw conflict();}
        catch(org.springframework.dao.DataIntegrityViolationException error){throw conflict();}
        return require(entry.id(),entry.ownerId());
    }

    public Row require(UUID id,long ownerId) {
        var rows=jdbc.query("SELECT * FROM biz.project_import_agent_edit WHERE edit_id=? AND owner_id=?",
                (row,index)->read(row),id,ownerId);
        if(rows.size()!=1)throw new MyException(ErrorCode.NOT_FOUND_ERROR,"编辑任务不存在。");
        return rows.getFirst();
    }

    /** 按固定页读取未收口任务；恢复时仍在应用锁内重新读取和CAS撤销。 */
    public java.util.List<Row> openAfter(UUID cursor){
        return jdbc.query("""
            SELECT * FROM biz.project_import_agent_edit WHERE closed_at IS NULL
                AND (CAST(? AS uuid) IS NULL OR edit_id>CAST(? AS uuid)) ORDER BY edit_id LIMIT 100
            """,(row,index)->read(row),cursor,cursor);
    }

    /** 未决发布必须保留候选/备份；只有没有发布意图的任务可释放编辑资源。 */
    public boolean discardable(Row row){
        var e=row.identity();
        return Boolean.TRUE.equals(jdbc.queryForObject("""
            SELECT EXISTS(SELECT 1 FROM biz.project_import_agent_edit WHERE edit_id=? AND owner_id=? AND revision=?
                AND state='RECOVERY_REQUIRED' AND cancel_requested AND closed_at IS NULL
                AND publish_phase='NONE' AND candidate_hash IS NULL AND recovery_json='{}'::jsonb)
            """,Boolean.class,e.id(),e.ownerId(),row.revision()));
    }

    /** 只检查准备/编辑资格；不能用它代替容器限额、停止确认或最终内容校验。 */
    public boolean qualified(Row row) {
        return qualified(row,false);
    }

    /** 封存/验证资格独立检查；不允许用准备资格或停止自述直接发布。 */
    public boolean qualifiedSealing(Row row){return qualified(row,true);}

    private boolean qualified(Row row,boolean sealing){
        var entry=row.identity();
        return Boolean.TRUE.equals(jdbc.queryForObject("""
            SELECT EXISTS(SELECT 1 FROM biz.project_import_agent_edit e
                JOIN biz.project_import_publication p ON p.app_id=e.app_id AND p.owner_id=e.owner_id AND p.task_id=e.import_task_id
                JOIN biz.app a ON a.id=e.app_id AND a.user_id=e.owner_id AND a.is_delete=0
                JOIN biz.project_import_task t ON t.task_id=e.import_task_id AND t.owner_id=e.owner_id AND t.status='SUCCEEDED'
                JOIN biz.project_import_startup_configuration c ON c.app_id=e.app_id AND c.owner_id=e.owner_id AND c.import_task_id=e.import_task_id
                WHERE e.edit_id=? AND e.owner_id=? AND e.app_id=? AND e.run_id=? AND e.revision=?
                  AND e.source_hash=? AND e.executor_id=? AND e.closed_at IS NULL AND NOT e.cancel_requested
                  AND %s AND e.expires_at>now() AND p.state='READY'
                  AND c.config_revision=e.config_revision AND c.context_epoch=e.context_epoch)
            """.formatted(sealing?"e.state='SEALING' AND e.stop_confirmed AND e.resources_json->0->>'state'='READY'":"e.state IN ('PREPARING','EDITING')"),Boolean.class,entry.id(),entry.ownerId(),entry.appId(),Long.parseLong(entry.runId()),row.revision(),
                entry.sourceHash(),entry.executorId()));
    }

    /** 只有已绑定真实容器的准备任务可进入编辑；这不表示模型已经执行。 */
    public Row beginEditing(Row row) {
        if(!qualified(row))throw conflict();
        return cas(row,"""
            UPDATE biz.project_import_agent_edit e SET state='EDITING',revision=revision+1,update_time=now()
            WHERE edit_id=? AND owner_id=? AND revision=? AND state='PREPARING' AND NOT cancel_requested
              AND closed_at IS NULL AND expires_at>now() AND resources_json->0->>'state'='READY'
              AND EXISTS(SELECT 1 FROM biz.project_import_agent_edit_view v WHERE v.edit_id=e.edit_id AND v.phase='SEALED')
              AND EXISTS(SELECT 1 FROM biz.project_import_agent_edit_container c WHERE c.edit_id=e.edit_id
                AND c.phase='CREATED' AND c.container_id IS NOT NULL)
            """);
    }

    /** 先持久停止证明，再CAS封存；模型结束事件不能直接设置停止标志。 */
    public Row beginSealing(Row row) {
        if(!qualified(row))throw conflict();
        return cas(row,"""
            UPDATE biz.project_import_agent_edit e SET state='SEALING',stop_confirmed=true,revision=revision+1,update_time=now()
            WHERE edit_id=? AND owner_id=? AND revision=? AND state='EDITING' AND NOT cancel_requested
              AND closed_at IS NULL AND expires_at>now() AND resources_json->0->>'state'='READY'
              AND EXISTS(SELECT 1 FROM biz.project_import_agent_edit_container c WHERE c.edit_id=e.edit_id
                AND c.phase='REMOVED' AND c.container_id IS NOT NULL AND c.receipt_json->>'stopConfirmed'='true')
              AND EXISTS(SELECT 1 FROM biz.project_import_publication p
                JOIN biz.app a ON a.id=p.app_id AND a.user_id=p.owner_id AND a.is_delete=0
                JOIN biz.project_import_task t ON t.task_id=p.task_id AND t.owner_id=p.owner_id AND t.status='SUCCEEDED'
                JOIN biz.project_import_startup_configuration s ON s.app_id=p.app_id AND s.owner_id=p.owner_id AND s.import_task_id=p.task_id
                WHERE p.app_id=e.app_id AND p.owner_id=e.owner_id AND p.task_id=e.import_task_id AND p.state='READY'
                  AND s.config_revision=e.config_revision AND s.context_epoch=e.context_epoch)
            """);
    }

    public Row cancel(Row row) {
        return cas(row,"""
            UPDATE biz.project_import_agent_edit SET cancel_requested=true,state='CANCELLING',revision=revision+1,update_time=now()
            WHERE edit_id=? AND owner_id=? AND revision=? AND state IN ('PREPARING','EDITING','SEALING') AND NOT cancel_requested
            """);
    }

    /** 先落创建意图，再允许平台调用卷助手；实际路径仅由冻结编辑身份派生。 */
    public Row planVolume(Row row,long bytes){
        if(bytes<16L<<20||bytes>8L<<30||bytes%4096!=0)throw new IllegalArgumentException("编辑磁盘额度无效。");
        var e=row.identity();var resource=json.createObjectNode();
        resource.put("kind","VOLUME").put("resourceId",e.id().toString()).put("editId",e.id().toString())
                .put("ownerId",Long.toString(e.ownerId())).put("appId",Long.toString(e.appId())).put("runId",e.runId())
                .put("contentHash",e.sourceHash()).put("diskBytes",bytes).put("formatVersion",3)
                .put("mountPath",ImportDiskVolume.editPath(e)).put("state","PLANNED");
        return replaceVolume(row,"[]",json.createArrayNode().add(resource).toString());
    }

    public Row volumeReady(Row row,com.fasterxml.jackson.databind.JsonNode receipt){
        var resource=volume(row);
        for(String key:java.util.List.of("actualBytes","reservedBytes","loopDevice","journal"))resource.set(key,receipt.get(key));
        resource.put("state","READY");
        return replaceVolume(row,row.resources(),json.createArrayNode().add(resource).toString());
    }

    public Row volumeReleased(Row row){
        var resource=volume(row);resource.put("state","RELEASED");
        return replaceVolume(row,row.resources(),json.createArrayNode().add(resource).toString());
    }

    /** 先登记同卷中的视图和平台私有映射路径，再允许实际复制。 */
    public void planView(Row row){
        var e=row.identity();String base=ImportDiskVolume.editPath(e);
        int changed=jdbc.update("""
            INSERT INTO biz.project_import_agent_edit_view(edit_id,phase,view_path,private_state_path)
            SELECT edit_id,'PLANNED',?,? FROM biz.project_import_agent_edit
            WHERE edit_id=? AND owner_id=? AND revision=? AND state='PREPARING' AND NOT cancel_requested AND closed_at IS NULL
            """,base+"/view",base+"/private-state.json",e.id(),e.ownerId(),row.revision());
        if(changed!=1)throw conflict();
    }

    public void viewReady(Row row,com.fasterxml.jackson.databind.JsonNode receipt){
        var safe=json.createObjectNode();
        for(String key:java.util.List.of("viewHash","viewSourceHash","viewStateHash"))safe.set(key,receipt.get(key));
        var e=row.identity();
        int changed=jdbc.update("""
            UPDATE biz.project_import_agent_edit_view v SET phase='SEALED',receipt_json=CAST(? AS jsonb)
            FROM biz.project_import_agent_edit e WHERE v.edit_id=e.edit_id AND e.edit_id=? AND e.owner_id=?
              AND e.revision=? AND v.phase='PLANNED'
            """,safe.toString(),e.id(),e.ownerId(),row.revision());
        if(changed!=1)throw conflict();
    }

    /** 实际卷释放已记入父账本后，再确认其中的视图和私有映射已一并消失。 */
    public void viewReleased(Row row){
        var e=row.identity();
        jdbc.update("""
            UPDATE biz.project_import_agent_edit_view v SET phase='RELEASED'
            FROM biz.project_import_agent_edit e WHERE v.edit_id=e.edit_id AND e.edit_id=? AND e.owner_id=?
              AND e.revision=? AND v.phase IN ('PLANNED','SEALED')
            """,e.id(),e.ownerId(),row.revision());
        // 无视图登记的早期失败也可清理；仍有未释放登记时绝不能关闭父任务。
        if(Boolean.TRUE.equals(jdbc.queryForObject("SELECT EXISTS(SELECT 1 FROM biz.project_import_agent_edit_view WHERE edit_id=? AND phase<>'RELEASED')",Boolean.class,e.id())))throw conflict();
    }

    private com.fasterxml.jackson.databind.node.ObjectNode volume(Row row){
        try{
            var list=json.readTree(row.resources());
            if(!list.isArray()||list.size()!=1||!list.get(0).isObject())throw conflict();
            return (com.fasterxml.jackson.databind.node.ObjectNode)list.get(0).deepCopy();
        }catch(java.io.IOException error){throw conflict();}
    }

    private Row replaceVolume(Row row,String expected,String updated){
        var e=row.identity();
        int changed=jdbc.update("""
            UPDATE biz.project_import_agent_edit SET resources_json=CAST(? AS jsonb),revision=revision+1,update_time=now()
            WHERE edit_id=? AND owner_id=? AND revision=? AND resources_json=CAST(? AS jsonb)
                AND state IN ('PREPARING','CANCELLING','COMMITTING','RECOVERY_REQUIRED') AND closed_at IS NULL
            """,updated,e.id(),e.ownerId(),row.revision(),expected);
        if(changed!=1)throw conflict();return require(e.id(),e.ownerId());
    }

    /** 卷助手实际确认释放后才能终结，历史资源身份保留用于回放。 */
    public Row finishReleasedVolume(Row row,String reason){
        if(reason==null||!reason.matches("[A-Z][A-Z0-9_]{0,79}"))throw new IllegalArgumentException("编辑失败原因无效。");
        var e=row.identity();
        int changed=jdbc.update("""
            UPDATE biz.project_import_agent_edit SET state=CASE WHEN cancel_requested THEN 'CANCELLED' ELSE 'FAILED' END,
                reason=?,stop_confirmed=true,cleanup='DONE',closed_at=now(),revision=revision+1,update_time=now()
            WHERE edit_id=? AND owner_id=? AND revision=? AND state IN ('PREPARING','CANCELLING','RECOVERY_REQUIRED')
                AND jsonb_array_length(resources_json)=1 AND resources_json->0->>'state'='RELEASED'
                AND candidate_hash IS NULL AND publish_phase='NONE' AND recovery_json='{}'::jsonb
            """,reason,e.id(),e.ownerId(),row.revision());
        if(changed!=1)throw conflict();return require(e.id(),e.ownerId());
    }

    /** 实际停止确认后、创建候选前登记；回执与Java验证另记，不能据此进入发布。 */
    public void planCandidate(Row row,String stateHash){
        if(stateHash==null||!stateHash.matches("[0-9a-f]{64}"))throw new IllegalArgumentException("候选授权摘要无效。");
        var e=row.identity();String base=ImportDiskVolume.editPath(e);
        int changed=jdbc.update("""
            INSERT INTO biz.project_import_agent_edit_candidate(edit_id,phase,candidate_path,manifest_path,authorized_state_hash)
            SELECT edit_id,'PLANNED',?,?,? FROM biz.project_import_agent_edit
            WHERE edit_id=? AND owner_id=? AND revision=? AND state='SEALING' AND stop_confirmed
              AND NOT cancel_requested AND closed_at IS NULL
            """,base+"/candidate",base+"/candidate-manifest.json",stateHash,e.id(),e.ownerId(),row.revision());
        if(changed!=1)throw conflict();
    }

    /** 平台封存回执只持久保留摘要；不伪造独立内容校验结果。 */
    public void candidateReady(Row row,String stateHash,com.fasterxml.jackson.databind.JsonNode receipt){
        var e=row.identity();String base=ImportDiskVolume.editPath(e);
        if(receipt==null||!receipt.isObject()||stateHash==null||!stateHash.matches("[0-9a-f]{64}"))throw conflict();
        if(!receipt.path("formatVersion").isIntegralNumber()||!receipt.path("formatVersion").canConvertToInt()||receipt.path("formatVersion").asInt()!=3
                ||!receipt.path("diskBytes").isIntegralNumber()||!receipt.path("diskBytes").canConvertToLong()
                ||receipt.path("diskBytes").asLong()!=volume(row).path("diskBytes").asLong())throw conflict();
        var expected=java.util.Map.of("appId",Long.toString(e.appId()),"ownerId",Long.toString(e.ownerId()),
                "runId",e.runId(),"editId",e.id().toString(),"contentHash",e.sourceHash(),"state","CANDIDATE_SEALED",
                "candidatePath",base+"/candidate","candidateManifestPath",base+"/candidate-manifest.json","authorizedStateHash",stateHash);
        for(var item:expected.entrySet())
            if(!receipt.path(item.getKey()).isTextual()||!item.getValue().equals(receipt.path(item.getKey()).asText()))throw conflict();
        var safe=json.createObjectNode();
        for(String key:java.util.List.of("candidateHash","candidateManifestHash")){
            if(!receipt.path(key).isTextual()||!receipt.path(key).asText().matches("[0-9a-f]{64}"))throw conflict();
            safe.set(key,receipt.get(key));
        }
        int changed=jdbc.update("""
            UPDATE biz.project_import_agent_edit_candidate c SET phase='SEALED',receipt_json=CAST(? AS jsonb)
            FROM biz.project_import_agent_edit e WHERE c.edit_id=e.edit_id AND e.edit_id=? AND e.owner_id=?
              AND e.revision=? AND c.phase='PLANNED' AND c.authorized_state_hash=?
            """,safe.toString(),e.id(),e.ownerId(),row.revision(),stateHash);
        if(changed!=1)throw conflict();
    }

    /** 获取已持久封存的原库存摘要；不能从模型或候选manifest自报填充。 */
    public String sealedViewSourceHash(Row row){
        var e=row.identity();
        var hashes=jdbc.queryForList("""
            SELECT v.receipt_json->>'viewSourceHash' FROM biz.project_import_agent_edit_view v
            JOIN biz.project_import_agent_edit e ON e.edit_id=v.edit_id
            WHERE e.edit_id=? AND e.owner_id=? AND e.revision=? AND v.phase='SEALED'
            """,String.class,e.id(),e.ownerId(),row.revision());
        if(hashes.size()!=1||hashes.getFirst()==null||!hashes.getFirst().matches("[0-9a-f]{64}"))throw conflict();
        return hashes.getFirst();
    }

    /** Java独立核验完成后持久验证摘要；数据库再次核对资格和封存回执，不发布文件。 */
    public void candidateVerified(Row row,ImportAgentCandidateVerifier.Frozen frozen,ImportAgentCandidateVerifier.Verified verified){
        var e=row.identity();
        if(!"SEALING".equals(row.state())||!row.stopConfirmed()||row.cancelRequested()||frozen==null||verified==null
                ||verified.files()<0||verified.bytes()<0||verified.candidateHash()==null||verified.manifestHash()==null
                ||!verified.candidateHash().matches("[0-9a-f]{64}")||!verified.manifestHash().matches("[0-9a-f]{64}")
                ||frozen.authorizedStateHash()==null||!frozen.authorizedStateHash().matches("[0-9a-f]{64}"))throw conflict();
        if(!qualifiedSealing(row))throw conflict();
        var proof=json.createObjectNode().put("sourceHash",e.sourceHash()).put("candidateHash",verified.candidateHash())
                .put("candidateManifestHash",verified.manifestHash()).put("authorizedStateHash",frozen.authorizedStateHash());
        var outputs=proof.putObject("retainedOutputs");
        frozen.outputs().forEach((name,stamp)->outputs.putArray(name).add(stamp.sha256()).add(stamp.bytes()).add(stamp.mode()));
        try{
            int changed=jdbc.update("""
                UPDATE biz.project_import_agent_edit_candidate c SET phase='VERIFIED',verification_json=CAST(? AS jsonb)
                FROM biz.project_import_agent_edit e WHERE c.edit_id=e.edit_id AND e.edit_id=? AND e.owner_id=? AND e.revision=?
                  AND e.state='SEALING' AND e.stop_confirmed AND NOT e.cancel_requested AND e.closed_at IS NULL AND e.expires_at>now()
                  AND e.source_hash=? AND e.executor_id=? AND c.phase='SEALED' AND c.authorized_state_hash=?
                  AND c.receipt_json->>'candidateHash'=? AND c.receipt_json->>'candidateManifestHash'=?
                """,proof.toString(),e.id(),e.ownerId(),row.revision(),e.sourceHash(),e.executorId(),frozen.authorizedStateHash(),
                    verified.candidateHash(),verified.manifestHash());
            if(changed!=1)throw conflict();
        }catch(org.springframework.dao.DataIntegrityViolationException error){throw conflict();}
    }

    /** 只能在父账本已记录真实卷释放后解除候选占用，保留回执和验证审计。 */
    public void candidateReleased(Row row){
        var e=row.identity();
        jdbc.update("""
            UPDATE biz.project_import_agent_edit_candidate c SET phase='RELEASED'
            FROM biz.project_import_agent_edit e WHERE c.edit_id=e.edit_id AND e.edit_id=? AND e.owner_id=?
              AND e.revision=? AND e.resources_json->0->>'state'='RELEASED' AND c.phase<>'RELEASED'
            """,e.id(),e.ownerId(),row.revision());
        if(Boolean.TRUE.equals(jdbc.queryForObject("SELECT EXISTS(SELECT 1 FROM biz.project_import_agent_edit_candidate WHERE edit_id=? AND phase<>'RELEASED')",Boolean.class,e.id())))throw conflict();
    }

    public com.fasterxml.jackson.databind.JsonNode candidateProof(Row row){
        var values=jdbc.queryForList("""
            SELECT verification_json::text FROM biz.project_import_agent_edit_candidate c
            JOIN biz.project_import_agent_edit e ON e.edit_id=c.edit_id
            WHERE e.edit_id=? AND e.owner_id=? AND e.revision=? AND c.phase IN ('VERIFIED','RELEASED')
            """,String.class,row.identity().id(),row.identity().ownerId(),row.revision());
        if(values.size()!=1)throw conflict();
        try{return json.readTree(values.getFirst());}catch(java.io.IOException error){throw conflict();}
    }

    public com.fasterxml.jackson.databind.JsonNode publicationJournal(Row row){
        String text=jdbc.queryForObject("SELECT recovery_json::text FROM biz.project_import_agent_edit WHERE edit_id=? AND owner_id=? AND revision=?",
                String.class,row.identity().id(),row.identity().ownerId(),row.revision());
        try{return json.readTree(text);}catch(java.io.IOException error){throw conflict();}
    }

    /** 串行登记产物占用；累计硬上限防止反复修复无限增长。 */
    public void planOutputs(Row row,String path,com.fasterxml.jackson.databind.JsonNode files){
        long bytes=4096;var directories=new java.util.HashSet<String>();
        var names=files.fieldNames();while(names.hasNext()){
            String name=names.next();long size=files.get(name).get(1).asLong();
            bytes=Math.addExact(bytes,Math.max(4096,Math.multiplyExact(Math.floorDiv(Math.addExact(size,4095),4096),4096)));
            for(int slash=name.lastIndexOf('/');slash>=0;slash=name.lastIndexOf('/',slash-1))directories.add(name.substring(0,slash));
        }
        bytes=Math.addExact(bytes,Math.multiplyExact(directories.size(),4096L));
        final long reserved=bytes;var e=row.identity();
        var transaction=new org.springframework.transaction.support.TransactionTemplate(
                new org.springframework.jdbc.datasource.DataSourceTransactionManager(java.util.Objects.requireNonNull(jdbc.getDataSource())));
        transaction.execute(status->{
            jdbc.execute("SELECT pg_advisory_xact_lock(71493371037)");
            long occupied=jdbc.queryForObject("SELECT COALESCE(sum(reserved_bytes),0) FROM biz.project_import_agent_edit_outputs WHERE phase<>'RELEASED'",Long.class);
            if(reserved>(8L<<30)-occupied)throw conflict();
            long pending=jdbc.queryForObject("SELECT COALESCE(sum(reserved_bytes),0) FROM biz.project_import_agent_edit_outputs WHERE phase='PLANNED'",Long.class);
            try{
                long available=java.nio.file.Files.getFileStore(java.nio.file.Path.of(path).getParent()).getUsableSpace();
                if(reserved>available-pending-(256L<<20))throw conflict();
            }catch(java.io.IOException error){throw conflict();}
            jdbc.update("INSERT INTO biz.project_import_agent_edit_outputs(edit_id,owner_id,app_id,run_id,source_hash,resource_path,files_json,reserved_bytes) VALUES(?,?,?,?,?,?,CAST(? AS jsonb),?)",
                    e.id(),e.ownerId(),e.appId(),Long.parseLong(e.runId()),e.sourceHash(),path,files.toString(),reserved);
            return null;
        });
    }

    public java.util.List<java.util.Map<String,Object>> outputs(long appId){
        return jdbc.queryForList("SELECT edit_id,resource_path,files_json::text AS files,phase FROM biz.project_import_agent_edit_outputs WHERE app_id=? AND phase<>'RELEASED'",appId);
    }

    public void outputsPhase(UUID editId,String phase){
        if(!java.util.Set.of("READY","RELEASED").contains(phase)
                ||jdbc.update("UPDATE biz.project_import_agent_edit_outputs SET phase=?,updated_at=now() WHERE edit_id=? AND phase<>? AND phase<>'RELEASED'",phase,editId,phase)!=1)throw conflict();
    }

    public Row beginPublication(Row row,com.fasterxml.jackson.databind.JsonNode journal,java.util.List<String> authorized){
        if(!qualifiedSealing(row))throw conflict();var proof=candidateProof(row);var e=row.identity();
        try{
            if(jdbc.update("""
                UPDATE biz.project_import_agent_edit SET state='COMMITTING',publish_phase='CANDIDATE_READY',candidate_hash=?,
                    authorized_json=CAST(? AS jsonb),recovery_json=CAST(? AS jsonb),revision=revision+1,update_time=now()
                WHERE edit_id=? AND owner_id=? AND revision=? AND state='SEALING' AND NOT cancel_requested AND publish_phase='NONE'
                """,proof.path("candidateHash").asText(),json.writeValueAsString(authorized),journal.toString(),e.id(),e.ownerId(),row.revision())!=1)throw conflict();
            return require(e.id(),e.ownerId());
        }catch(java.io.IOException error){throw conflict();}
    }

    public Row publicationPhase(Row row,String previous,String next,com.fasterxml.jackson.databind.JsonNode journal){
        var e=row.identity();
        if(!java.util.Set.of("CANDIDATE_READY","OLD_MOVED","NEW_MOVED").contains(next))throw conflict();
        if(jdbc.update("""
            UPDATE biz.project_import_agent_edit SET publish_phase=?,recovery_json=CAST(? AS jsonb),revision=revision+1,update_time=now()
            WHERE edit_id=? AND owner_id=? AND revision=? AND state='COMMITTING' AND publish_phase=? AND NOT cancel_requested
            """,next,journal.toString(),e.id(),e.ownerId(),row.revision(),previous)!=1)throw conflict();
        return require(e.id(),e.ownerId());
    }

    public Row commitPublication(Row row){
        var transaction=new org.springframework.transaction.support.TransactionTemplate(
                new org.springframework.jdbc.datasource.DataSourceTransactionManager(java.util.Objects.requireNonNull(jdbc.getDataSource())));
        return transaction.execute(status->{
            var e=row.identity();
            if(jdbc.update("""
                UPDATE biz.project_import_agent_edit SET publish_phase='COMMITTED',revision=revision+1,update_time=now()
                WHERE edit_id=? AND owner_id=? AND revision=? AND state='COMMITTING' AND publish_phase='NEW_MOVED'
                  AND NOT cancel_requested AND expires_at>now()
                """,e.id(),e.ownerId(),row.revision())!=1)throw conflict();
            if(jdbc.update("""
                UPDATE biz.project_import_startup_configuration SET context_epoch=context_epoch+1,update_time=now()
                WHERE app_id=? AND owner_id=? AND import_task_id=? AND config_revision=? AND context_epoch=?
                  AND context_epoch<9223372036854775807
                """,e.appId(),e.ownerId(),e.importTaskId(),e.configRevision(),e.contextEpoch())!=1)throw conflict();
            return require(e.id(),e.ownerId());
        });
    }

    public Row finishPublication(Row row,boolean committed){
        var e=row.identity();
        if(jdbc.update("""
            UPDATE biz.project_import_agent_edit SET state=?,cleanup='DONE',closed_at=now(),revision=revision+1,update_time=now()
            WHERE edit_id=? AND owner_id=? AND revision=? AND closed_at IS NULL AND stop_confirmed
              AND state IN ('COMMITTING','RECOVERY_REQUIRED') AND resources_json->0->>'state'='RELEASED'
              AND ((? AND publish_phase='COMMITTED' AND NOT cancel_requested) OR (NOT ? AND publish_phase<>'COMMITTED'))
            """,committed?"SUCCEEDED":"CANCELLED",e.id(),e.ownerId(),row.revision(),committed,committed)!=1)throw conflict();
        return require(e.id(),e.ownerId());
    }

    public Row publicationRecovery(Row row){
        var e=row.identity();
        if(jdbc.update("""
            UPDATE biz.project_import_agent_edit SET state='RECOVERY_REQUIRED',
                cancel_requested=CASE WHEN publish_phase='COMMITTED' THEN cancel_requested ELSE true END,
                revision=revision+1,update_time=now()
            WHERE edit_id=? AND owner_id=? AND revision=? AND closed_at IS NULL AND publish_phase<>'NONE'
            """,e.id(),e.ownerId(),row.revision())!=1)throw conflict();
        return require(e.id(),e.ownerId());
    }

    /** 实际创建前先登记业务审计，平台失败也不删除创建意图。 */
    public void planContainer(Row row){
        var e=row.identity();
        if(!qualified(row))throw conflict();
        if(jdbc.update("""
            INSERT INTO biz.project_import_agent_edit_container(edit_id,container_name,phase)
            SELECT edit_id,?,'PLANNED' FROM biz.project_import_agent_edit
            WHERE edit_id=? AND owner_id=? AND revision=? AND state IN ('PREPARING','EDITING') AND NOT cancel_requested AND closed_at IS NULL
            ""","glyahh-edit-"+e.id(),e.id(),e.ownerId(),row.revision())!=1)throw conflict();
    }

    public boolean hasContainer(Row row){
        return Boolean.TRUE.equals(jdbc.queryForObject("SELECT EXISTS(SELECT 1 FROM biz.project_import_agent_edit_container WHERE edit_id=?)",Boolean.class,row.identity().id()));
    }

    public String createdContainer(Row row){
        var e=row.identity();
        var ids=jdbc.queryForList("""
            SELECT c.container_id FROM biz.project_import_agent_edit_container c JOIN biz.project_import_agent_edit e ON e.edit_id=c.edit_id
            WHERE e.edit_id=? AND e.owner_id=? AND e.revision=? AND e.state='PREPARING' AND NOT e.cancel_requested
              AND e.closed_at IS NULL AND c.phase='CREATED' AND c.container_id IS NOT NULL
            """,String.class,e.id(),e.ownerId(),row.revision());
        if(ids.size()!=1)throw conflict();return ids.getFirst();
    }

    public void containerCreated(Row row,com.fasterxml.jackson.databind.JsonNode receipt){
        var e=row.identity();
        if(receipt==null||!"CONTAINER_CREATED".equals(receipt.path("state").asText())||!receipt.path("containerId").isTextual()
                ||!receipt.path("containerId").asText().matches("[0-9a-f]{64}")||!qualified(row))throw conflict();
        if(jdbc.update("""
            UPDATE biz.project_import_agent_edit_container c SET phase='CREATED',container_id=?
            FROM biz.project_import_agent_edit e WHERE c.edit_id=e.edit_id AND e.edit_id=? AND e.owner_id=? AND e.revision=?
              AND c.phase='PLANNED' AND e.closed_at IS NULL AND NOT e.cancel_requested
            """,receipt.path("containerId").asText(),e.id(),e.ownerId(),row.revision())!=1)throw conflict();
    }

    /** 平台确认真实停止/删除后先保存证据，再允许卷释放；不从晚到回执补造创建意图。 */
    public void containerStopped(Row row,com.fasterxml.jackson.databind.JsonNode receipt){
        var e=row.identity();String name="glyahh-edit-"+e.id();
        if(receipt==null||!"STOP_CONFIRMED".equals(receipt.path("state").asText())||!name.equals(receipt.path("containerName").asText())
                ||!receipt.has("containerId")||!receipt.path("containerId").isNull()&&(!receipt.path("containerId").isTextual()
                    ||!receipt.path("containerId").asText().matches("[0-9a-f]{64}")))throw conflict();
        String id=receipt.path("containerId").isNull()?null:receipt.path("containerId").asText();
        var rows=jdbc.queryForList("SELECT phase,container_id,receipt_json::text AS receipt FROM biz.project_import_agent_edit_container WHERE edit_id=?",e.id());
        if(rows.isEmpty()){if(id!=null)throw conflict();return;}
        var proof=json.createObjectNode().put("stopConfirmed",true).put("containerName",name);
        if(id==null)proof.putNull("containerId");else proof.put("containerId",id);
        var current=rows.getFirst();
        if("REMOVED".equals(current.get("phase"))){
            try{if(!proof.equals(json.readTree((String)current.get("receipt"))))throw conflict();}
            catch(java.io.IOException error){throw conflict();}
            return;
        }
        if(jdbc.update("""
            UPDATE biz.project_import_agent_edit_container c SET phase='REMOVED',container_id=COALESCE(c.container_id,?),receipt_json=CAST(? AS jsonb)
            FROM biz.project_import_agent_edit e WHERE c.edit_id=e.edit_id AND e.edit_id=? AND e.owner_id=? AND e.revision=?
              AND e.closed_at IS NULL AND c.phase IN ('PLANNED','CREATED') AND (c.container_id IS NULL OR c.container_id=?)
            """,id,proof.toString(),e.id(),e.ownerId(),row.revision(),id)!=1)throw conflict();
    }

    /** 重启核实旧执行者后撤销资格，保留所有资源与发布阶段，不自动续跑。 */
    public Row recoveryRequired(Row row) {
        return cas(row,"""
            UPDATE biz.project_import_agent_edit SET cancel_requested=true,state='RECOVERY_REQUIRED',revision=revision+1,update_time=now()
            WHERE edit_id=? AND owner_id=? AND revision=? AND closed_at IS NULL
            """);
    }

    /** 尚未分配资源的准备失败可直接收口；已登记资源或进入发布的任务必须走确认恢复。 */
    public Row finishWithoutResources(Row row,String reason) {
        if(reason==null||!reason.matches("[A-Z][A-Z0-9_]{0,79}"))throw new IllegalArgumentException("编辑失败原因无效。");
        var entry=row.identity();
        int changed=jdbc.update("""
            UPDATE biz.project_import_agent_edit SET state=CASE WHEN cancel_requested THEN 'CANCELLED' ELSE 'FAILED' END,
                reason=?,stop_confirmed=true,cleanup='DONE',closed_at=now(),revision=revision+1,update_time=now()
            WHERE edit_id=? AND owner_id=? AND revision=? AND state IN ('PREPARING','CANCELLING','RECOVERY_REQUIRED')
                AND resources_json='[]'::jsonb AND candidate_hash IS NULL AND publish_phase='NONE' AND recovery_json='{}'::jsonb
            """,reason,entry.id(),entry.ownerId(),row.revision());
        if(changed!=1)throw conflict();return require(entry.id(),entry.ownerId());
    }

    private Row cas(Row row,String sql) {
        var entry=row.identity();
        if(jdbc.update(sql,entry.id(),entry.ownerId(),row.revision())!=1)throw conflict();
        return require(entry.id(),entry.ownerId());
    }
    private static Row read(ResultSet row)throws SQLException {
        var identity=new Entry(row.getObject("edit_id",UUID.class),row.getLong("owner_id"),row.getLong("app_id"),
            row.getObject("import_task_id",UUID.class),Long.toString(row.getLong("run_id")),row.getString("source_hash"),
            row.getLong("config_revision"),row.getLong("context_epoch"),row.getObject("executor_id",UUID.class),row.getLong("executor_pid"),
            row.getTimestamp("executor_start").toInstant(),row.getTimestamp("expires_at").toInstant());
        return new Row(identity,row.getString("state"),row.getLong("revision"),row.getBoolean("cancel_requested"),
            row.getString("resources_json"),row.getString("cleanup"),row.getString("publish_phase"),row.getBoolean("stop_confirmed"));
    }
    private static MyException conflict(){return new MyException(ErrorCode.CONFLICT_ERROR,"编辑任务资格或版本已变化，请重新确认。");}
}
