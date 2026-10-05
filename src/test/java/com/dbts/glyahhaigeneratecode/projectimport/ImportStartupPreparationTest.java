package com.dbts.glyahhaigeneratecode.projectimport;

import com.dbts.glyahhaigeneratecode.exception.MyException;
import org.junit.jupiter.api.Test;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import static org.junit.jupiter.api.Assertions.*;

class ImportDiskAdmissionTest {
    @Test void lowOrUnknownSpaceStopsWithoutTruncating(){
        var error=assertThrows(MyException.class,()->ImportDiskAdmission.requireUsable(10,256));
        assertTrue(error.getMessage().contains("未截断"));
        assertThrows(MyException.class,()->ImportDiskAdmission.requireUsable(-1,0));
        assertDoesNotThrow(()->ImportDiskAdmission.requireUsable(256,256));
    }
}

class ImportStartupPreparationTest {
    @Test void directoryPresenceDoesNotMakeDependenciesReadyAndInstallCommandsStayDeclared(){
        var node=ImportStartupPlan.build(inspection(new ImportStartupScanner.Candidate("web","web",".","npm run dev",null,"STATIC","package.json")),
            Map.of("package.json","{\"name\":\"a\"}"),List.of(),List.of("web"));
        var assessed=ImportStartupPreparation.assess(node);
        assertEquals("UNKNOWN",assessed.dependencies().getFirst().status());
        assertEquals("UNCHECKED",assessed.dependencies().getFirst().reason());
        assertTrue(assessed.installRequired());
        assertTrue(assessed.installCommands().isEmpty());
        UUID id=UUID.randomUUID();
        var manual=ImportStartupPlan.build(inspection(new ImportStartupScanner.Candidate("manual:"+id,"web",".","npm run dev",null,"MANUAL","manual")),
            Map.of("package.json","{\"name\":\"a\"}"),List.of(new ImportStartupConfiguration.Manual(id,"web",".","npm run dev","npm ci",null)),List.of("manual:"+id));
        assertEquals(List.of("npm ci"),ImportStartupPreparation.assess(manual).installCommands());
    }
    @Test void retryKeepsOnlyReadyServices(){
        var plan=twoServices();
        var previous=List.of(new ImportStartupPreparation.ServiceView("web","READY","READY",null,UUID.randomUUID()),
            new ImportStartupPreparation.ServiceView("api","START","FAILED","EXIT_NONZERO",UUID.randomUUID()));
        var kept=ImportStartupPreparation.keepReady(previous,plan);
        assertEquals(List.of("web"),kept.stream().map(ImportStartupPreparation.ServiceView::serviceId).toList());
        assertEquals("PARTIAL",ImportStartupPreparation.aggregate(List.of(kept.getFirst(),new ImportStartupPreparation.ServiceView("api","START","FAILED","EXIT_NONZERO",null))));
        assertEquals("NONE_READY",ImportStartupPreparation.aggregate(ImportStartupPreparation.notStarted(plan,"MEMORY_LIMIT_UNENFORCEABLE")));
    }
    private static ImportStartupPlan.Content twoServices(){
        return ImportStartupPlan.build(new ImportStartupService.Inspection(UUID.randomUUID(),"42",UUID.randomUUID(),"a".repeat(64),".","1","1",
            new ImportStartupScanner.Result("b".repeat(64),List.of(
                new ImportStartupScanner.Candidate("web","web","web","node server.js",null,"STATIC","web/server.js"),
                new ImportStartupScanner.Candidate("api","api","api","node app.js",null,"STATIC","api/app.js")),List.of())),
            Map.of("web/server.js","", "api/app.js",""),List.of(),List.of("web","api"));
    }
    private static ImportStartupService.Inspection inspection(ImportStartupScanner.Candidate candidate){
        return new ImportStartupService.Inspection(UUID.randomUUID(),"42",UUID.randomUUID(),"a".repeat(64),".","1","1",
            new ImportStartupScanner.Result("b".repeat(64),List.of(candidate),List.of()));
    }
}
