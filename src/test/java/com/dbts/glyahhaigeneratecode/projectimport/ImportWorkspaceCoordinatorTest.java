package com.dbts.glyahhaigeneratecode.projectimport;

import com.dbts.glyahhaigeneratecode.exception.MyException;
import org.junit.jupiter.api.Test;
import javax.sql.DataSource;
import java.sql.*;
import java.util.concurrent.atomic.AtomicBoolean;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class ImportWorkspaceCoordinatorTest {
    DataSource source=mock(DataSource.class);Connection connection=mock(Connection.class);
    PreparedStatement acquire=mock(PreparedStatement.class),release=mock(PreparedStatement.class);
    ImportWorkspaceCoordinator setup(boolean available,boolean released)throws Exception{
        when(source.getConnection()).thenReturn(connection);when(connection.isValid(2)).thenReturn(true);
        when(connection.prepareStatement("SELECT pg_try_advisory_lock(?)")).thenReturn(acquire);
        when(connection.prepareStatement("SELECT pg_advisory_unlock(?)")).thenReturn(release);
        for(var pair:new Object[][]{{acquire,available},{release,released}}){
            ResultSet result=mock(ResultSet.class);when(result.next()).thenReturn(true);
            when(result.getBoolean(1)).thenReturn((boolean)pair[1]);when(((PreparedStatement)pair[0]).executeQuery()).thenReturn(result);
        }
        return new ImportWorkspaceCoordinator(source);
    }
    @Test void successfulOperationUsesAppIdentityAndReleasesLock()throws Exception{
        var coordinator=setup(true,true);assertEquals("done",coordinator.withLock(42,()->"done"));
        verify(acquire).setLong(1,Long.MIN_VALUE^42L);verify(release).executeQuery();verify(connection).close();
    }
    @Test void busyApplicationDoesNotExecuteOrReleaseSomeoneElsesLock()throws Exception{
        var coordinator=setup(false,true);var ran=new AtomicBoolean();
        assertThrows(MyException.class,()->coordinator.withLock(42,()->{ran.set(true);return null;}));
        assertFalse(ran.get());verify(release,never()).executeQuery();
    }
    @Test void operationFailureStillReleasesLock()throws Exception{
        var coordinator=setup(true,true);var failure=new IllegalStateException("fixture");
        assertSame(failure,assertThrows(IllegalStateException.class,()->coordinator.withLock(42,()->{throw failure;})));
        verify(release).executeQuery();
    }
    @Test void lostConnectionAndUnconfirmedReleaseCannotReturnSuccess()throws Exception{
        var coordinator=setup(true,false);assertThrows(MyException.class,()->coordinator.withLock(42,()->"not returned"));
        var second=setup(true,true);when(connection.isValid(2)).thenReturn(false);
        assertThrows(MyException.class,()->second.withLock(42,()->"not returned"));
    }
    @Test void invalidIdentityIsRejectedBeforeDatabaseAccess()throws Exception{
        var coordinator=new ImportWorkspaceCoordinator(source);assertThrows(MyException.class,()->coordinator.withLock(0,()->null));
        verifyNoInteractions(source);
    }
}
