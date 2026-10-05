package com.dbts.glyahhaigeneratecode.projectimport;

import com.dbts.glyahhaigeneratecode.exception.MyException;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import javax.sql.DataSource;
import java.sql.*;
import java.util.List;
import java.util.UUID;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

class ImportStartupInspectionRepositoryTest {
    DataSource source=mock(DataSource.class);Connection connection=mock(Connection.class);
    PreparedStatement statement=mock(PreparedStatement.class);ResultSet row=mock(ResultSet.class);
    ObjectMapper json=new ObjectMapper();UUID scan=UUID.randomUUID(),task=UUID.randomUUID();
    ImportStartupService.Inspection result=new ImportStartupService.Inspection(scan,"42",task,"a".repeat(64),".","1","2",new ImportStartupScanner.Result("input",List.of(),List.of()));
    ImportStartupInspectionRepository repository;
    @BeforeEach void setup()throws Exception{
        when(source.getConnection()).thenReturn(connection);when(connection.prepareStatement(anyString())).thenReturn(statement);
        when(statement.executeQuery()).thenReturn(row);when(row.next()).thenReturn(true,false);
        when(row.getString("inspection_json")).thenReturn(json.writeValueAsString(result));
        when(row.getString("content_hash")).thenReturn(result.contentHash());when(row.getString("scope")).thenReturn(".");
        when(row.getString("state")).thenReturn("SUCCEEDED");when(row.getString("rules_version")).thenReturn(ImportStartupInspectionRepository.RULES_VERSION);
        when(row.getLong("config_revision")).thenReturn(1L);when(row.getLong("context_epoch")).thenReturn(2L);
        repository=new ImportStartupInspectionRepository(source,json);
    }
    @Test void validRecordRoundTripsAndQueryBindsAllIdentities(){
        var stored=repository.find(42,7,task,scan);assertEquals(result,stored.inspection());
        try{verify(statement).setObject(1,scan);verify(statement).setObject(4,task);}catch(SQLException error){fail(error);}
    }
    @Test void jsonFromAnotherAppCannotBorrowRowIdentity()throws Exception{
        when(row.getString("inspection_json")).thenReturn(json.writeValueAsString(new ImportStartupService.Inspection(scan,"43",task,result.contentHash(),".","1","2",result.result())));
        assertThrows(MyException.class,()->repository.find(42,7,task,scan));
    }
    @Test void mismatchedContentAndRevisionCannotReturnCandidates()throws Exception{
        when(row.getString("content_hash")).thenReturn("b".repeat(64));assertThrows(MyException.class,()->repository.find(42,7,task,scan));
        when(row.next()).thenReturn(true,false);when(row.getString("content_hash")).thenReturn(result.contentHash());when(row.getLong("context_epoch")).thenReturn(3L);
        assertThrows(MyException.class,()->repository.find(42,7,task,scan));
    }
    @Test void malformedAndMissingRecordsAreExplicitErrors()throws Exception{
        when(row.getString("inspection_json")).thenReturn("{}");assertThrows(MyException.class,()->repository.find(42,7,task,scan));
        when(row.next()).thenReturn(false);assertThrows(MyException.class,()->repository.find(42,7,task,scan));
    }
}
