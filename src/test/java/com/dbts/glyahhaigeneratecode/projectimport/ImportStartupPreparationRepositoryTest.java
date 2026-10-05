package com.dbts.glyahhaigeneratecode.projectimport;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import java.util.List;
import java.util.UUID;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

class ImportStartupPreparationRepositoryTest {
    JdbcTemplate jdbc=mock(JdbcTemplate.class);
    ImportStartupPreparationRepository repository=new ImportStartupPreparationRepository(jdbc,new ObjectMapper());

    @Test void insertRequiresConfirmedPlanAndUpdateAdvancesRevision(){
        var row=new ImportStartupPreparationRepository.Row(UUID.randomUUID(),7,42,UUID.randomUUID(),"a".repeat(64),"b".repeat(64),"INSTALL","APPROVED","REFUSED","MEMORY_LIMIT_UNENFORCEABLE",List.of(),0);
        when(jdbc.query(anyString(),any(RowMapper.class),any(Object[].class))).thenReturn(List.of());
        assertThrows(com.dbts.glyahhaigeneratecode.exception.MyException.class,()->repository.save(row));
        var sql=ArgumentCaptor.forClass(String.class);
        verify(jdbc).update(sql.capture(),any(Object[].class));
        assertTrue(sql.getValue().contains("p.state='CONFIRMED'"));
        assertTrue(sql.getValue().contains("INSERT INTO biz.project_import_startup_preparation"));
    }
}
