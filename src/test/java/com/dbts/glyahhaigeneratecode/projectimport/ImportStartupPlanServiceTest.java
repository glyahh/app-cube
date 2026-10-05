package com.dbts.glyahhaigeneratecode.projectimport;

import com.dbts.glyahhaigeneratecode.exception.ErrorCode;
import com.dbts.glyahhaigeneratecode.exception.MyException;
import com.dbts.glyahhaigeneratecode.model.Entity.User;
import org.junit.jupiter.api.Test;
import java.time.Instant;
import java.util.*;
import java.util.function.Function;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

/** 计划服务用替身识别与仓库，只验证确认/失效规则；真实 SQL 与接口见独立脚本。 */
class ImportStartupPlanServiceTest {
    ImportStartupService startup=mock(ImportStartupService.class);ImportStartupPlanRepository plans=mock(ImportStartupPlanRepository.class);
    ImportStartupPlanService service=new ImportStartupPlanService(startup,plans);
    User user=User.builder().id(7L).build();UUID scan=UUID.randomUUID(),task=UUID.randomUUID();
    ImportStartupScanner.Candidate web=new ImportStartupScanner.Candidate("web/package.json:dev","web","web","npm run dev",null,"STATIC","web/package.json#scripts.dev");
    ImportStartupService.PlanInput input(String script,ImportStartupScanner.Candidate... candidates){
        return new ImportStartupService.PlanInput(new ImportStartupService.Inspection(scan,"42",task,"a".repeat(64),".","2","3",
            new ImportStartupScanner.Result("b".repeat(64),List.of(candidates),List.of())),Map.of("web/package.json","{\"scripts\":{\"dev\":\""+script+"\"}}","web/package-lock.json","{}"),List.of());
    }
    @SuppressWarnings("unchecked")
    void current(ImportStartupService.PlanInput input){when(startup.withPlanInput(eq(user),eq(42L),eq(scan),any())).thenAnswer(call->((Function<ImportStartupService.PlanInput,Object>)call.getArgument(3)).apply(input));}
    ImportStartupPlanRepository.Plan row(ImportStartupService.PlanInput input,String state){
        var content=ImportStartupPlan.build(input.inspection(),input.files(),input.manual(),List.of(web.id()));
        return new ImportStartupPlanRepository.Plan(UUID.randomUUID(),7,42,ImportStartupPlan.revision(content),content,state,0,false,"CONFIRMED".equals(state)?Instant.now():null,
            "STALE".equals(state)?new ImportStartupPlanRepository.Stale("PLAN_CHANGED",List.of()):null,Instant.now());
    }
    void stored(ImportStartupPlanRepository.Plan plan){when(plans.find(7,42,plan.id())).thenReturn(plan);}

    @Test void staleInspectionOrInvalidSelectionCreatesNoPlan(){
        current(null);
        assertEquals(ErrorCode.CONFLICT_ERROR.getCode(),assertThrows(MyException.class,()->service.create(user,42,scan,List.of(web.id()))).getCode());
        current(input("vite",web));
        assertEquals(ErrorCode.PARAMS_ERROR.getCode(),assertThrows(MyException.class,()->service.create(user,42,scan,List.of("other"))).getCode());
        verify(plans,never()).create(anyLong(),any());
    }
    @Test void creationNeverConfirmsAndShowsDiffAgainstLastConfirmedPlan(){
        var before=row(input("vite",web),"STALE");var current=input("vite --host",web);current(current);
        var created=row(current,"DRAFT");when(plans.create(eq(7L),any())).thenReturn(created);when(plans.lastConfirmed(7,42,created.id())).thenReturn(before);
        var view=service.create(user,42,scan,List.of(web.id()));
        assertEquals("DRAFT",view.state());assertFalse(view.executable());assertEquals(before.id(),view.diffBase());
        assertTrue(view.diff().stream().anyMatch(change->change.path().equals("services[web/package.json:dev].expansion")));
        verify(plans,never()).confirm(any(),anyBoolean());
        // 读取草稿同样展示相对上次确认的差异，不依赖创建响应。
        stored(created);assertEquals(before.id(),service.get(user,42,created.id()).diffBase());
    }
    @Test void confirmationRequiresMatchingRevisionAndUnchangedContent(){
        var draft=row(input("vite",web),"DRAFT");stored(draft);when(startup.planRevoked(user,42,task)).thenReturn(null);current(input("vite",web));
        assertThrows(MyException.class,()->service.confirm(user,42,draft.id(),"c".repeat(64),false));verify(plans,never()).confirm(any(),anyBoolean());
        when(plans.confirm(draft,false)).thenReturn(row(input("vite",web),"CONFIRMED"));
        assertEquals("CONFIRMED",service.confirm(user,42,draft.id(),draft.planRevision(),false).state());
    }
    @Test void changedScriptInvalidatesConfirmationAndRecordsDiff(){
        var draft=row(input("vite",web),"DRAFT");stored(draft);current(input("vite --port 9000",web));
        var stale=org.mockito.ArgumentCaptor.forClass(ImportStartupPlanRepository.Stale.class);
        assertEquals(ErrorCode.CONFLICT_ERROR.getCode(),assertThrows(MyException.class,()->service.confirm(user,42,draft.id(),draft.planRevision(),false)).getCode());
        verify(plans).stale(eq(draft),stale.capture());verify(plans,never()).confirm(any(),anyBoolean());
        assertEquals("PLAN_CHANGED",stale.getValue().reason());
        assertEquals(List.of("services[web/package.json:dev].expansion"),stale.getValue().diff().stream().map(ImportStartupPlan.Change::path).toList());
    }
    @Test void newlyAvailableServiceAlsoInvalidatesAnAlreadyConfirmedPlan(){
        var confirmed=row(input("vite",web),"CONFIRMED");stored(confirmed);
        var agent=new ImportStartupScanner.Candidate("agent:x","api","api","node app.js",null,"AGENT","api/app.js");current(input("vite",web,agent));
        when(plans.stale(eq(confirmed),any())).thenReturn(new ImportStartupPlanRepository.Plan(confirmed.id(),7,42,confirmed.planRevision(),confirmed.content(),"STALE",1,false,confirmed.confirmedAt(),
            new ImportStartupPlanRepository.Stale("PLAN_CHANGED",List.of(new ImportStartupPlan.Change("available[agent:x]",null,"{}"))),confirmed.createdAt()));
        var view=service.get(user,42,confirmed.id());
        assertEquals("STALE",view.state());assertEquals("PLAN_CHANGED",view.staleReason());assertEquals("available[agent:x]",view.diff().getFirst().path());
    }
    @Test void vanishedSelectedServiceInvalidatesAndNamesIt(){
        var confirmed=row(input("vite",web),"CONFIRMED");stored(confirmed);
        current(input("vite",new ImportStartupScanner.Candidate("web/package.json:start","web","web","npm run start",null,"STATIC","web/package.json#scripts.start")));
        var stale=org.mockito.ArgumentCaptor.forClass(ImportStartupPlanRepository.Stale.class);
        when(plans.stale(eq(confirmed),stale.capture())).thenReturn(confirmed);service.get(user,42,confirmed.id());
        assertEquals("SELECTION_UNAVAILABLE",stale.getValue().reason());
        assertEquals(List.of(new ImportStartupPlan.Change("services[web/package.json:dev]","web",null)),stale.getValue().diff());
    }
    @Test void aggregateScriptNeedsExplicitAcknowledgementOfExpandedScope(){
        var start=new ImportStartupScanner.Candidate("web/package.json:start","web · start","web","npm run start",null,"STATIC","web/package.json#scripts.start");
        var current=new ImportStartupService.PlanInput(input("x",web,start).inspection(),Map.of("web/package.json","{\"scripts\":{\"dev\":\"concurrently npm:start npm:api\",\"start\":\"vite\",\"api\":\"node api.js\"}}"),List.of());
        current(current);
        var content=ImportStartupPlan.build(current.inspection(),current.files(),List.of(),List.of(web.id()));
        assertEquals(List.of("web/package.json:start"),content.aggregates().getFirst().unselected());
        var draft=new ImportStartupPlanRepository.Plan(UUID.randomUUID(),7,42,ImportStartupPlan.revision(content),content,"DRAFT",0,false,null,null,Instant.now());stored(draft);
        assertEquals(ErrorCode.PARAMS_ERROR.getCode(),assertThrows(MyException.class,()->service.confirm(user,42,draft.id(),draft.planRevision(),false)).getCode());
        verify(plans,never()).confirm(any(),anyBoolean());
        when(plans.confirm(draft,true)).thenReturn(new ImportStartupPlanRepository.Plan(draft.id(),7,42,draft.planRevision(),content,"CONFIRMED",1,true,Instant.now(),null,draft.createdAt()));
        assertTrue(service.confirm(user,42,draft.id(),draft.planRevision(),true).aggregateAcknowledged());verify(plans).confirm(draft,true);
    }
    @Test void replacedImportOrLostQualificationInvalidatesWithoutReadingWorkspace(){
        var confirmed=row(input("vite",web),"CONFIRMED");stored(confirmed);when(startup.planRevoked(user,42,task)).thenReturn("IMPORT_TASK_CHANGED");
        var revoked=new ImportStartupPlanRepository.Plan(confirmed.id(),7,42,confirmed.planRevision(),confirmed.content(),"STALE",1,false,confirmed.confirmedAt(),
            new ImportStartupPlanRepository.Stale("IMPORT_TASK_CHANGED",List.of()),confirmed.createdAt());
        when(plans.stale(eq(confirmed),any())).thenReturn(revoked);
        assertEquals("IMPORT_TASK_CHANGED",service.get(user,42,confirmed.id()).staleReason());
        assertThrows(MyException.class,()->service.confirm(user,42,confirmed.id(),confirmed.planRevision(),false));
        verify(startup,never()).withPlanInput(any(),anyLong(),any(),any());
    }
    @Test void staleInspectionInvalidatesPlanAndStalePlanCannotBeConfirmed(){
        var draft=row(input("vite",web),"DRAFT");stored(draft);current(null);
        assertThrows(MyException.class,()->service.confirm(user,42,draft.id(),draft.planRevision(),false));
        var stale=org.mockito.ArgumentCaptor.forClass(ImportStartupPlanRepository.Stale.class);verify(plans).stale(eq(draft),stale.capture());
        assertEquals("INSPECTION_STALE",stale.getValue().reason());
        var old=row(input("vite",web),"STALE");stored(old);clearInvocations(startup);
        assertThrows(MyException.class,()->service.confirm(user,42,old.id(),old.planRevision(),true));verify(startup,never()).withPlanInput(any(),anyLong(),any(),any());
        assertThrows(MyException.class,()->service.get(user,42,UUID.randomUUID()));
    }
}
