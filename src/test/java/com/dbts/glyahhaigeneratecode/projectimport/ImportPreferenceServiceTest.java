package com.dbts.glyahhaigeneratecode.projectimport;

import com.dbts.glyahhaigeneratecode.exception.MyException;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;

import static com.dbts.glyahhaigeneratecode.service.support.ProjectImportManifest.Options;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

class ImportPreferenceServiceTest {
    @Test void absentPreferencesUseConfirmedDefaults() {
        var jdbc = mock(JdbcTemplate.class);
        when(jdbc.queryForList(anyString(), any(Object[].class))).thenReturn(java.util.List.of());
        assertEquals(Options.defaults(), new ImportPreferenceService(jdbc).get(12));
    }

    @Test void saveRequiresValidOwnerAndCompleteOptions() {
        var jdbc = mock(JdbcTemplate.class);
        var service = new ImportPreferenceService(jdbc);
        assertThrows(MyException.class, () -> service.save(0, Options.defaults()));
        assertThrows(MyException.class, () -> service.get(-1));
        assertThrows(MyException.class, () -> service.save(12, null));
        verifyNoInteractions(jdbc);
    }

    @Test void failedDatabaseWriteIsNotSuccessfulSave() {
        var service = new ImportPreferenceService(mock(JdbcTemplate.class));
        assertThrows(MyException.class, () -> service.save(12, Options.defaults()));
    }

    @Test void saveUpdatesOnlyItsOwnerAndReturnsExactExplicitOptions() {
        var jdbc = mock(JdbcTemplate.class);
        when(jdbc.update(anyString(), eq(12L), eq(true), eq(false), eq(true))).thenReturn(1);
        var options = new Options(true, false, true);
        assertEquals(options, new ImportPreferenceService(jdbc).save(12, options));
        verify(jdbc).update(contains("ON CONFLICT (owner_id)"), eq(12L), eq(true), eq(false), eq(true));
        verifyNoMoreInteractions(jdbc);
    }
}
