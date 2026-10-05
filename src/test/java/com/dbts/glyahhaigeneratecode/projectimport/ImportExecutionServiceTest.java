package com.dbts.glyahhaigeneratecode.projectimport;

import com.dbts.glyahhaigeneratecode.config.RuntimeProperties;
import com.dbts.glyahhaigeneratecode.exception.MyException;
import com.dbts.glyahhaigeneratecode.model.Entity.User;
import com.dbts.glyahhaigeneratecode.runtime.DockerCli;
import com.dbts.glyahhaigeneratecode.service.AppService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import java.nio.file.Path;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

class ImportExecutionServiceTest {
    @TempDir Path temp;
    ImportStartupPlanService plans=mock(ImportStartupPlanService.class);
    AppService apps=mock(AppService.class);
    ImportExecutionLedger ledger=mock(ImportExecutionLedger.class);
    DockerCli docker=mock(DockerCli.class);
    User user=mock(User.class);
    UUID planId=UUID.randomUUID();
    ImportExecutionService service;

    @BeforeEach void setUp()throws Exception{
        var properties=new ImportProperties();properties.setStagingRoot(temp.resolve("private"));
        var runtime=mock(RuntimeProperties.class);when(runtime.getImage()).thenReturn("img");
        service=new ImportExecutionService(plans,apps,ledger,mock(ImportWorkspaceCoordinator.class),mock(ImportStartupResourceGuard.class),docker,runtime,properties);
        when(user.getId()).thenReturn(1L);
    }
    ImportStartupPlanService.View view(String state){
        var service=new ImportStartupPlan.Service("index.html","site",".","FIXED","index.html",null,List.of(),List.of(),null,List.of(),new ImportStartupPlan.Readiness("STATIC_ENTRY","x"),List.of());
        var content=new ImportStartupPlan.Content(1,"7",UUID.randomUUID(),UUID.randomUUID(),"a".repeat(64),"0","0","r",List.of(service),List.of(),List.of(),List.of(),List.of(),List.of());
        return new ImportStartupPlanService.View(planId,"7",content.scanId(),"b".repeat(64),state,"1",false,Instant.now(),false,content,null,List.of(),null);
    }

    @Test void productionPathNeverAcceptsUnguaranteedMemory(){
        var limits=new ImportExecutionSandbox.Limits(0.5,128L<<20,32,32L<<20,8L<<20,Duration.ofSeconds(20),4096,ImportExecutionSandbox.Memory.VERIFICATION_ONLY_UNGUARANTEED);
        assertThrows(MyException.class,()->service.execute(user,7,planId,".","sh ok.sh",limits));
        verifyNoInteractions(plans,apps,ledger,docker);
    }
    @Test void onlyCurrentlyConfirmedPlanAndItsDirectoriesCanExecute(){
        when(plans.get(user,7,planId)).thenReturn(view("STALE"));
        assertThrows(MyException.class,()->service.execute(user,7,planId,".","sh ok.sh",ImportExecutionSandbox.Limits.defaults()));
        when(plans.get(user,7,planId)).thenReturn(view("CONFIRMED"));
        assertThrows(MyException.class,()->service.execute(user,7,planId,"other","sh ok.sh",ImportExecutionSandbox.Limits.defaults()));
        verifyNoInteractions(apps,ledger,docker);
    }
    @Test void serviceExposesNoControllerOrUserEndpoint(){
        assertNull(ImportExecutionService.class.getAnnotation(org.springframework.web.bind.annotation.RestController.class));
        assertNull(ImportExecutionService.class.getAnnotation(org.springframework.web.bind.annotation.RequestMapping.class));
    }
    @Test void servingCannotBorrowAnotherServicesDirectoryPortOrCommand(){
        var original=view("CONFIRMED");var content=original.plan();
        var api=new ImportStartupPlan.Service("api","api",".","DECLARED",".glyahh/run.json","python3 app.py",List.of(),List.of(),null,List.of(),
            new ImportStartupPlan.Readiness("HTTP","/health","/health"),List.of(),8080);
        var plan=new ImportStartupPlan.Content(content.schema(),content.appId(),content.scanId(),content.importTaskId(),content.contentHash(),content.configRevision(),content.contextEpoch(),
            content.rulesVersion(),List.of(api),List.of(),List.of(),List.of(),List.of(),List.of());
        when(plans.get(user,7,planId)).thenReturn(new ImportStartupPlanService.View(planId,"7",plan.scanId(),"b".repeat(64),"CONFIRMED","1",false,Instant.now(),false,plan,null,List.of(),null));
        String slot="service:"+ImportExecutionRunner.sha("api").substring(0,32);
        assertThrows(MyException.class,()->service.execute(user,7,planId,".","python3 different.py",ImportExecutionSandbox.Limits.defaults(),slot,
            ImportExecutionSandbox.Network.LOOPBACK,ImportExecutionSandbox.Lifetime.SERVE,8080));
        assertThrows(MyException.class,()->service.execute(user,7,planId,".","python3 app.py",ImportExecutionSandbox.Limits.defaults(),"service:other",
            ImportExecutionSandbox.Network.LOOPBACK,ImportExecutionSandbox.Lifetime.SERVE,8080));
        verifyNoInteractions(apps,ledger,docker);
    }
}
