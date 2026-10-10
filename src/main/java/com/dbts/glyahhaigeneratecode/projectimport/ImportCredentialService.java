package com.dbts.glyahhaigeneratecode.projectimport;

import com.dbts.glyahhaigeneratecode.exception.ErrorCode;
import com.dbts.glyahhaigeneratecode.exception.MyException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/** 凭据元数据与密文分开；变更先撤销资格，停止结果由执行协调器核验。 */
@Service
public class ImportCredentialService {
    public record View(String id, String provider, String name, String version, String revision, String state) { }
    public record Mutation(View credential, List<UUID> revokedTasks) { }
    private final JdbcTemplate jdbc;
    private final ImportCredentialKeyProvider keys;
    public ImportCredentialService(JdbcTemplate jdbc, ImportCredentialKeyProvider keys) { this.jdbc = jdbc; this.keys = keys; }

    public List<View> list(long owner) {
        if (owner <= 0) throw invalid();
        return jdbc.queryForList("SELECT credential_id,provider,name,current_version,record_revision,state FROM biz.project_import_credential WHERE owner_id=? AND state='ACTIVE' ORDER BY create_time,credential_id", owner)
                .stream().map(ImportCredentialService::view).toList();
    }

    @Transactional
    public View create(long owner, String provider, String name, String token) {
        if (owner <= 0) throw invalid();
        ImportProviderPolicy.requireSupported(provider);
        String note = name(name); UUID id = UUID.randomUUID();
        var secret = keys.cipher().encrypt(owner, provider, id, 1, token);
        // 1. 身份元数据与第一版密文在同一短事务中建立。
        changed(jdbc.update("INSERT INTO biz.project_import_credential(credential_id,owner_id,provider,name) VALUES (?,?,?,?)", id, owner, provider, note));
        insertSecret(id, 1, secret);
        return new View(id.toString(),provider,note,"1","0","ACTIVE");
    }

    @Transactional
    public View rename(long owner, UUID id, long revision, String name) {
        String note = name(name); var current = locked(owner,id,revision);
        ImportProviderPolicy.requireSupported(current.provider());
        changed(jdbc.update("UPDATE biz.project_import_credential SET name=?,record_revision=record_revision+1,update_time=now() WHERE credential_id=? AND owner_id=? AND record_revision=? AND state='ACTIVE'",note,id,owner,revision));
        return new View(id.toString(), current.provider(), note, current.version(), Long.toString(revision+1), "ACTIVE");
    }

    @Transactional
    public Mutation replace(long owner, UUID id, long revision, String token) {
        var current = locked(owner,id,revision); long version = Math.addExact(Long.parseLong(current.version()),1);
        ImportProviderPolicy.requireSupported(current.provider());
        var secret = keys.cipher().encrypt(owner,current.provider(),id,version,token);
        // 1. 与最终提交共用凭据行锁，旧版本不再签发或提交。
        changed(jdbc.update("UPDATE biz.project_import_credential SET current_version=?,record_revision=record_revision+1,update_time=now() WHERE credential_id=? AND owner_id=? AND record_revision=? AND state='ACTIVE'",version,id,owner,revision));
        jdbc.update("UPDATE biz.project_import_credential_version SET state='REVOKED' WHERE credential_id=? AND state='ACTIVE'",id);
        insertSecret(id,version,secret);
        // 2. 记录撤销后提交事务；资源停止不能在持锁期间执行。
        return new Mutation(new View(id.toString(),current.provider(),current.name(),Long.toString(version),Long.toString(revision+1),"ACTIVE"),revoke(id,owner));
    }

    @Transactional
    public Mutation delete(long owner, UUID id, long revision) {
        var current = locked(owner,id,revision);
        changed(jdbc.update("UPDATE biz.project_import_credential SET state='DELETED',record_revision=record_revision+1,update_time=now() WHERE credential_id=? AND owner_id=? AND record_revision=? AND state='ACTIVE'",id,owner,revision));
        jdbc.update("UPDATE biz.project_import_credential_version SET state='DELETED',key_id=NULL,nonce=NULL,ciphertext=NULL WHERE credential_id=?",id);
        return new Mutation(new View(id.toString(),current.provider(),current.name(),current.version(),Long.toString(revision+1),"DELETED"),revoke(id,owner));
    }

    private List<UUID> revoke(UUID id,long owner) {
        var tasks = jdbc.queryForList("""
                SELECT l.task_id FROM biz.project_import_credential_lease l JOIN biz.project_import_task t ON t.task_id=l.task_id
                WHERE l.credential_id=? AND l.owner_id=? AND t.owner_id=? AND t.status NOT IN ('SUCCEEDED','CANCELLED')
                UNION
                SELECT s.task_id FROM biz.project_import_source s WHERE s.credential_id=? AND s.owner_id=?
                    AND s.status NOT IN ('SUCCEEDED','CANCELLED')
                """,id,owner,owner,id,owner).stream().map(row -> (UUID)row.get("task_id")).toList();
        jdbc.update("UPDATE biz.project_import_credential_lease SET state='REVOKED' WHERE credential_id=? AND owner_id=?",id,owner);
        return tasks;
    }

    /** 只向当前受管来源租约解密，归属、版本、执行阶段和期限由数据库核对。 */
    public String readForSource(ImportSourceTask source) {
        ImportProviderPolicy.requireSupported(source.definition().source().provider());
        if(source.credentialId()==null || source.credentialVersion()==null)throw invalid();
        var rows=jdbc.queryForList("""
                SELECT v.key_id,v.nonce,v.ciphertext FROM biz.project_import_source s
                JOIN biz.project_import_credential c ON c.credential_id=s.credential_id
                JOIN biz.project_import_credential_version v ON v.credential_id=c.credential_id AND v.version=s.credential_version
                WHERE s.task_id=? AND s.owner_id=? AND s.revision=? AND s.status=? AND s.executor_id=? AND s.expires_at>now()
                    AND s.status IN ('RESOLVING_SOURCE','READING_CONTENT') AND s.credential_id=? AND s.credential_version=?
                    AND c.owner_id=s.owner_id AND c.provider=? AND c.provider=s.definition_json->'source'->>'provider'
                    AND c.state='ACTIVE' AND c.current_version=v.version AND v.state='ACTIVE'
                """,source.taskId(),source.ownerId(),source.revision(),source.status().name(),source.executorId(),
                source.credentialId(),source.credentialVersion(),source.definition().source().provider());
        if(rows.size()!=1)throw new MyException(ErrorCode.CONFLICT_ERROR,"凭据读取资格已失效。");
        var row=rows.getFirst();
        var secret=new ImportCredentialCipher.Encrypted((String)row.get("key_id"),(String)row.get("nonce"),(String)row.get("ciphertext"));
        return keys.cipher().decrypt(source.ownerId(),source.definition().source().provider(),source.credentialId(),source.credentialVersion(),secret);
    }

    private View locked(long owner,UUID id,long revision) {
        if (owner <= 0 || id == null || revision < 0 || revision == Long.MAX_VALUE) throw invalid();
        var rows = jdbc.queryForList("SELECT * FROM biz.project_import_credential WHERE credential_id=? AND owner_id=? AND state='ACTIVE' FOR UPDATE",id,owner);
        if (rows.isEmpty()) throw new MyException(ErrorCode.NOT_FOUND_ERROR,"凭据不存在。");
        var current = view(rows.getFirst());
        if (!Long.toString(revision).equals(current.revision())) throw new MyException(ErrorCode.CONFLICT_ERROR,"凭据已变化，请刷新后重试。");
        return current;
    }

    private void insertSecret(UUID id,long version,ImportCredentialCipher.Encrypted secret) {
        changed(jdbc.update("INSERT INTO biz.project_import_credential_version(credential_id,version,key_id,nonce,ciphertext,state) VALUES (?,?,?,?,?,'ACTIVE')",id,version,secret.keyId(),secret.nonce(),secret.ciphertext()));
    }

    private static View view(Map<String,Object> row) {
        return new View(row.get("credential_id").toString(),row.get("provider").toString(),row.get("name").toString(),row.get("current_version").toString(),row.get("record_revision").toString(),row.get("state").toString());
    }
    private static String name(String value) {
        if (value == null || value.isBlank() || value.length() > 40 || value.chars().anyMatch(Character::isISOControl)) throw invalid();
        return value.trim();
    }
    private static void changed(int count) { if (count != 1) throw new MyException(ErrorCode.CONFLICT_ERROR,"凭据已变化，请刷新后重试。"); }
    private static MyException invalid() { return new MyException(ErrorCode.PARAMS_ERROR,"凭据参数无效，备注限 40 字。"); }
}
