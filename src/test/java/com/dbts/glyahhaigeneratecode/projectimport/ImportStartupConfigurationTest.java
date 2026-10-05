package com.dbts.glyahhaigeneratecode.projectimport;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import java.nio.file.*;
import java.util.List;
import java.util.UUID;
import static org.junit.jupiter.api.Assertions.*;

class ImportStartupConfigurationTest {
    @TempDir Path root;
    ImportStartupConfiguration.Manual service(UUID id,String dir,String command){return new ImportStartupConfiguration.Manual(id,"API",dir,command,null,null);}
    @Test void assignsIdentityAndPreservesCommandsWithoutExecuting()throws Exception{
        var result=ImportStartupConfiguration.validate(root,List.of(service(null,".","unknown --fixture")),List.of());
        assertNotNull(result.getFirst().id());assertEquals("unknown --fixture",result.getFirst().command());
        try(var files=Files.list(root)){assertEquals(0,files.count());}
        assertThrows(UnsupportedOperationException.class,()->result.clear());
    }
    @Test void currentIdentityCanBeRetainedButForeignAndDuplicateIdsAreRejected()throws Exception{
        var old=service(UUID.randomUUID(),".","old");
        assertEquals(old.id(),ImportStartupConfiguration.validate(root,List.of(service(old.id(),".","new")),List.of(old)).getFirst().id());
        assertThrows(IllegalArgumentException.class,()->ImportStartupConfiguration.validate(root,List.of(service(UUID.randomUUID(),".","new")),List.of(old)));
        assertThrows(IllegalArgumentException.class,()->ImportStartupConfiguration.validate(root,List.of(old,old),List.of(old)));
    }
    @Test void invalidDirectoriesAndProtectedInfrastructureAreRejected()throws Exception{
        Files.createDirectory(root.resolve(".glyahh"));Files.createDirectory(root.resolve("node_modules"));
        for(String dir:new String[]{"../other","/etc","missing",".glyahh"})
            assertThrows(IllegalArgumentException.class,()->ImportStartupConfiguration.validate(root,List.of(service(null,dir,"command")),List.of()));
        assertEquals("node_modules",ImportStartupConfiguration.validate(root,List.of(service(null,"node_modules","command")),List.of()).getFirst().directory());
    }
    @Test void duplicateNamesEmptyCommandsAndControlCharactersAreRejected(){
        for(String command:new String[]{"", " \t", "run\nother", "x".repeat(1025)})
            assertThrows(IllegalArgumentException.class,()->ImportStartupConfiguration.validate(root,List.of(service(null,".",command)),List.of()));
        assertThrows(IllegalArgumentException.class,()->ImportStartupConfiguration.validate(root,List.of(service(null,".","a"),service(null,".","b")),List.of()));
    }
    @Test void emptyListRemovesManualEntriesAndVersionIdentityRemainsExact()throws Exception{
        assertTrue(ImportStartupConfiguration.validate(root,List.of(),List.of()).isEmpty());
        var state=new ImportStartupConfiguration.State(42,7,UUID.randomUUID(),9007199254740993L,9007199254740994L,".",List.of());
        assertEquals("9007199254740993",state.view().configRevision());assertEquals("9007199254740994",state.view().contextEpoch());
    }
}
