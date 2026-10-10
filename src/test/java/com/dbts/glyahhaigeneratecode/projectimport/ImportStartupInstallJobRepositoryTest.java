package com.dbts.glyahhaigeneratecode.projectimport;

import com.dbts.glyahhaigeneratecode.exception.MyException;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

class ImportStartupInstallJobRepositoryTest {
    JdbcTemplate jdbc=mock(JdbcTemplate.class);
    ImportStartupInstallJobRepository repository=new ImportStartupInstallJobRepository(jdbc,new ObjectMapper());

    @Test void createReadsContentHashFromConfirmedPlanAndKeepsInstallQualification(){
        when(jdbc.query(anyString(),any(RowMapper.class),any(Object[].class))).thenReturn(List.of());
        when(jdbc.queryForObject(anyString(),eq(Integer.class))).thenReturn(0);
        when(jdbc.queryForObject(anyString(),eq(Integer.class),any(Object[].class))).thenReturn(0);
        assertThrows(MyException.class,()->repository.create(UUID.randomUUID(),UUID.randomUUID(),42,7,UUID.randomUUID(),
            "a".repeat(64),UUID.randomUUID(),1234L,Instant.now(),2,20));
        var sql=ArgumentCaptor.forClass(String.class);
        verify(jdbc).update(sql.capture(),any(Object[].class));
        // 受理阶段不经过会复制项目的计划视图：content_hash 与资格都在 SQL 里判定。
        assertTrue(sql.getValue().contains("p.content_hash"));
        assertTrue(sql.getValue().contains("p.state='CONFIRMED'"));
        assertTrue(sql.getValue().contains("pub.state='READY'"));
        assertTrue(sql.getValue().contains("a.is_delete=0"));
        assertFalse(sql.getValue().contains("plan_json"));
    }
    @Test void createRejectsInvalidPlanRevisionBeforeTouchingDatabase(){
        assertThrows(IllegalArgumentException.class,()->repository.create(UUID.randomUUID(),UUID.randomUUID(),42,7,UUID.randomUUID(),
            "not-a-revision",UUID.randomUUID(),1234L,Instant.now(),2,20));
        verifyNoInteractions(jdbc);
    }
}
