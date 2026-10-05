package com.dbts.glyahhaigeneratecode.projectimport;

import com.dbts.glyahhaigeneratecode.exception.MyException;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import javax.crypto.spec.SecretKeySpec;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

class ImportCredentialServiceTest {
    private final JdbcTemplate jdbc = mock(JdbcTemplate.class);
    private final ImportCredentialKeyProvider keys = mock(ImportCredentialKeyProvider.class);
    private final UUID id = UUID.randomUUID();
    private ImportCredentialService service;
    private Map<String,Object> row() { return Map.of("credential_id", id, "owner_id", 12L, "provider", "GitHub", "name", "读取", "current_version", 1L, "record_revision", 2L, "state", "ACTIVE"); }
    @BeforeEach void setup() {
        when(keys.cipher()).thenReturn(new ImportCredentialCipher("v1", Map.of("v1", new SecretKeySpec(new byte[32], "AES"))));
        when(jdbc.update(anyString(), any(Object[].class))).thenReturn(1);
        when(jdbc.queryForList(anyString(), any(Object[].class))).thenReturn(List.of(row()));
        service = new ImportCredentialService(jdbc, keys);
    }
    @Test void createProducesMetadataOnlyAndIndependentServerIds() {
        var one = service.create(12,"GitHub","一个","TEST_SECRET_ONLY");
        var two = service.create(12,"GitHub","另一个","TEST_SECRET_ONLY");
        assertNotEquals(one.id(),two.id()); assertEquals("1",one.version());
        assertFalse(one.toString().contains("TEST_SECRET_ONLY"));
    }
    @Test void foreignOwnerAndStaleRevisionCannotMutateOrEncrypt() {
        when(jdbc.queryForList(anyString(), any(Object[].class))).thenReturn(List.of());
        assertThrows(MyException.class,()->service.replace(13,id,2,"TEST_SECRET_ONLY"));
        when(jdbc.queryForList(anyString(), any(Object[].class))).thenReturn(List.of(row()));
        assertThrows(MyException.class,()->service.replace(12,id,1,"TEST_SECRET_ONLY"));
        verify(keys,never()).cipher(); verify(jdbc,never()).update(anyString(),any(Object[].class));
    }
    @Test void renameKeepsSemanticVersionAndDoesNotRevoke() {
        var renamed = service.rename(12,id,2,"新备注");
        assertEquals("1",renamed.version()); assertEquals("3",renamed.revision());
        verify(keys,never()).cipher();
        verify(jdbc,never()).update(contains("credential_lease"),any(Object[].class));
    }
    @Test void replacementChangesVersionAndReturnsOnlyRelatedUnfinishedTaskIds() {
        UUID task = UUID.randomUUID();
        when(jdbc.queryForList(contains("SELECT l.task_id"), any(Object[].class))).thenReturn(List.of(Map.of("task_id",task)));
        var replaced = service.replace(12,id,2,"NEW_TEST_SECRET_ONLY");
        assertEquals("2",replaced.credential().version()); assertEquals(List.of(task),replaced.revokedTasks());
        assertFalse(replaced.toString().contains("NEW_TEST_SECRET_ONLY"));
        verify(jdbc).update(contains("credential_lease SET state='REVOKED'"),any(Object[].class));
    }
    @Test void deletionRemovesAllSecretMaterialsAndKeepsAuditMetadata() {
        when(jdbc.queryForList(contains("SELECT l.task_id"),any(Object[].class))).thenReturn(List.of());
        var deleted = service.delete(12,id,2);
        assertEquals("DELETED",deleted.credential().state());
        verify(jdbc).update(contains("key_id=NULL,nonce=NULL,ciphertext=NULL"),any(Object[].class));
    }
    @Test void revocationIncludesPreparatorySourcesAndDeduplicatesLinkedTasks() {
        when(jdbc.queryForList(contains("SELECT l.task_id"),any(Object[].class))).thenReturn(List.of());
        service.delete(12,id,2);
        var sql=org.mockito.ArgumentCaptor.forClass(String.class);
        verify(jdbc).queryForList(sql.capture(),eq(id),eq(12L),eq(12L),eq(id),eq(12L));
        assertTrue(sql.getValue().contains("UNION"));
        assertFalse(sql.getValue().contains("UNION ALL"));
        assertTrue(sql.getValue().contains("s.credential_id=? AND s.owner_id=?"));
        assertTrue(sql.getValue().contains("s.status NOT IN ('SUCCEEDED','CANCELLED')"));
    }
    @Test void validationAndMissingKeyFailBeforePersistence() {
        assertThrows(MyException.class,()->service.create(12,"Other","x","secret"));
        assertThrows(MyException.class,()->service.create(12,"GitHub"," ","secret"));
        assertThrows(MyException.class,()->service.create(12,"GitHub","x".repeat(41),"secret"));
        when(keys.cipher()).thenThrow(new MyException(com.dbts.glyahhaigeneratecode.exception.ErrorCode.OPERATION_ERROR,"密钥不可用"));
        assertThrows(MyException.class,()->service.create(12,"GitHub","x","secret"));
        verify(jdbc,never()).update(anyString(),any(Object[].class));
    }
    ImportSourceTask lease() {
        return new ImportSourceTask(UUID.randomUUID(),12,new ImportSourceTask.Definition("工程",ImportRepositorySource.parse("GitHub","https://github.com/octocat/Hello-World"),
                new com.dbts.glyahhaigeneratecode.service.support.ProjectImportManifest.Options(false,false,false)),ImportSourceTask.Status.RESOLVING_SOURCE,0,id,1L,
                null,null,List.of(),List.of(),false,null,UUID.randomUUID(),123,java.time.Instant.now(),java.time.Instant.now().plusSeconds(600),null,"NONE");
    }
    @Test void sourceSecretReadRequiresDatabaseQualificationBeforeDecryption() {
        var lease=lease();
        when(jdbc.queryForList(contains("SELECT v.key_id"),any(Object[].class))).thenReturn(List.of());
        assertThrows(MyException.class,()->service.readForSource(lease));
        verify(keys,never()).cipher();
        var sql=org.mockito.ArgumentCaptor.forClass(String.class);
        verify(jdbc).queryForList(sql.capture(),eq(lease.taskId()),eq(12L),eq(0L),eq("RESOLVING_SOURCE"),eq(lease.executorId()),eq(id),eq(1L),eq("GitHub"));
        assertTrue(sql.getValue().contains("s.expires_at>now()"));assertTrue(sql.getValue().contains("c.current_version=v.version"));
        assertTrue(sql.getValue().contains("c.owner_id=s.owner_id AND c.provider=?"));
    }
    @Test void sourceDecryptionUsesExactOwnerPlatformAndImmutableVersion() {
        var cipher=keys.cipher();var encrypted=cipher.encrypt(12,"GitHub",id,1,"FAKE_SOURCE_READ_SECRET");
        when(jdbc.queryForList(contains("SELECT v.key_id"),any(Object[].class))).thenReturn(List.of(Map.of("key_id",encrypted.keyId(),"nonce",encrypted.nonce(),"ciphertext",encrypted.ciphertext())));
        assertEquals("FAKE_SOURCE_READ_SECRET",service.readForSource(lease()));
        var changed=cipher.encrypt(13,"GitHub",id,1,"FAKE_FOREIGN_SECRET");
        when(jdbc.queryForList(contains("SELECT v.key_id"),any(Object[].class))).thenReturn(List.of(Map.of("key_id",changed.keyId(),"nonce",changed.nonce(),"ciphertext",changed.ciphertext())));
        assertThrows(MyException.class,()->service.readForSource(lease()));
    }
}
