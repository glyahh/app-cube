package com.dbts.glyahhaigeneratecode.projectimport;

import com.dbts.glyahhaigeneratecode.exception.MyException;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import java.sql.ResultSet;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

class ImportStartupPlanRepositoryTest {
    JdbcTemplate jdbc=mock(JdbcTemplate.class);ImportStartupPlanRepository repository=new ImportStartupPlanRepository(jdbc,new ObjectMapper());
    UUID scan=UUID.randomUUID(),task=UUID.randomUUID();
    ImportStartupPlan.Content content=ImportStartupPlan.build(new ImportStartupService.Inspection(scan,"42",task,"a".repeat(64),".","2","3",
        new ImportStartupScanner.Result("b".repeat(64),List.of(new ImportStartupScanner.Candidate("site/index.html","site","site",null,null,"STATIC","site/index.html")),List.of())),
        Map.of(),List.of(),List.of("site/index.html"));
    ImportStartupPlanRepository.Plan row(String state){return new ImportStartupPlanRepository.Plan(UUID.randomUUID(),7,42,ImportStartupPlan.revision(content),content,state,0,false,
        "CONFIRMED".equals(state)?Instant.now():null,"STALE".equals(state)?new ImportStartupPlanRepository.Stale("PLAN_CHANGED",List.of()):null,Instant.now());}

    @Test void identicalActivePlanIsReturnedWithoutInsert(){
        var existing=row("DRAFT");when(jdbc.query(anyString(),any(RowMapper.class),any(Object[].class))).thenReturn(List.of(existing));
        assertEquals(existing,repository.create(7,content));verify(jdbc,never()).update(anyString(),any(Object[].class));
    }
    @Test void insertRechecksDatabaseQualificationAndRejectsWhenNoRowAppears(){
        when(jdbc.query(anyString(),any(RowMapper.class),any(Object[].class))).thenReturn(List.of());
        assertThrows(MyException.class,()->repository.create(7,content));
        var sql=ArgumentCaptor.forClass(String.class);verify(jdbc).update(sql.capture(),any(Object[].class));
        for(String guard:List.of("i.state='SUCCEEDED'","c.context_epoch=i.context_epoch","p.state='READY'","a.is_delete=0","ON CONFLICT (owner_id,app_id,plan_revision) WHERE state IN ('DRAFT','CONFIRMED')"))
            assertTrue(sql.getValue().contains(guard),guard);
    }
    @Test void confirmationSupersedesOldPlanAndChecksRevisionAndQualification(){
        var draft=row("DRAFT");when(jdbc.update(anyString(),any(Object[].class))).thenReturn(1,0);
        assertThrows(MyException.class,()->repository.confirm(draft,false));
        var sql=ArgumentCaptor.forClass(String.class);verify(jdbc,times(2)).update(sql.capture(),any(Object[].class));
        assertTrue(sql.getAllValues().getFirst().contains("SET state='SUPERSEDED'"));
        for(String guard:List.of("s.revision=?","s.state='DRAFT'","s.plan_revision=?","i.content_hash=s.content_hash","c.context_epoch=s.context_epoch","p.state='READY'"))
            assertTrue(sql.getAllValues().get(1).contains(guard),guard);
    }
    @Test void staleAndSupersededPlansCannotMoveAgain(){
        for(String state:List.of("STALE","SUPERSEDED")){
            var plan=row(state);
            assertThrows(IllegalArgumentException.class,()->repository.stale(plan,new ImportStartupPlanRepository.Stale("PLAN_CHANGED",List.of())));
            assertThrows(IllegalArgumentException.class,()->repository.confirm(plan,true));
        }
        assertThrows(IllegalArgumentException.class,()->repository.confirm(row("CONFIRMED"),true));verifyNoInteractions(jdbc);
    }
    @Test void tamperedFrozenContentIsRejectedOnRead()throws Exception{
        var stored=mock(ResultSet.class);var captor=ArgumentCaptor.forClass(RowMapper.class);
        when(jdbc.query(anyString(),captor.capture(),any(Object[].class))).thenReturn(List.of());repository.find(7,42,UUID.randomUUID());
        when(stored.getString("plan_json")).thenReturn(ImportStartupPlan.json(content).replace("\"name\":\"site\"","\"name\":\"evil\""));
        when(stored.getObject("scan_id",UUID.class)).thenReturn(scan);when(stored.getObject("import_task_id",UUID.class)).thenReturn(task);
        when(stored.getString("content_hash")).thenReturn("a".repeat(64));when(stored.getLong("config_revision")).thenReturn(2L);when(stored.getLong("context_epoch")).thenReturn(3L);
        when(stored.getString("rules_version")).thenReturn(ImportStartupInspectionRepository.RULES_VERSION);when(stored.getObject("plan_id",UUID.class)).thenReturn(UUID.randomUUID());
        when(stored.getLong("owner_id")).thenReturn(7L);when(stored.getLong("app_id")).thenReturn(42L);when(stored.getString("plan_revision")).thenReturn(ImportStartupPlan.revision(content));
        when(stored.getString("state")).thenReturn("DRAFT");when(stored.getTimestamp("created_at")).thenReturn(Timestamp.from(Instant.now()));
        assertThrows(MyException.class,()->captor.getValue().mapRow(stored,0));
        when(stored.getString("plan_json")).thenReturn(ImportStartupPlan.json(content));
        assertEquals("DRAFT",((ImportStartupPlanRepository.Plan)captor.getValue().mapRow(stored,0)).state());
        when(stored.getLong("context_epoch")).thenReturn(4L);assertThrows(MyException.class,()->captor.getValue().mapRow(stored,0));
    }
}
