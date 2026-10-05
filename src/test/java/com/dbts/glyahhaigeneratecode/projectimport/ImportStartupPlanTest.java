package com.dbts.glyahhaigeneratecode.projectimport;

import org.junit.jupiter.api.Test;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;

class ImportStartupPlanTest {
    @Test void declaredHealthPathIsFrozenAndChangesPlanRevision(){
        String declaration="{\"processes\":[{\"name\":\"api\",\"command\":\"python app.py\",\"port\":8080,\"healthPath\":\"/health\"}]}";
        var files=Map.of(".glyahh/run.json",declaration,"app.py","print('fixture')");
        var result=new ImportStartupScanner().scan(files,".");
        assertEquals(1,result.candidates().size());
        var plan=ImportStartupPlan.build(inspection("a".repeat(64),result.candidates(),result.scopes()),files,List.of(),List.of("declared:api"));
        assertEquals("/health",plan.services().getFirst().readiness().path());
        var changedFiles=Map.of(".glyahh/run.json",declaration.replace("/health","/ready"),"app.py","print('fixture')");
        var changedResult=new ImportStartupScanner().scan(changedFiles,".");
        var changed=ImportStartupPlan.build(inspection("a".repeat(64),changedResult.candidates(),changedResult.scopes()),changedFiles,List.of(),List.of("declared:api"));
        assertNotEquals(ImportStartupPlan.revision(plan),ImportStartupPlan.revision(changed));
        for(String invalid:List.of("//example.com","/health?token=x","/a/../health")){
            var bad=new ImportStartupScanner().scan(Map.of(".glyahh/run.json",declaration.replace("/health",invalid),"app.py","fixture"),".");
            assertTrue(bad.candidates().isEmpty());assertEquals("DECLARATION_INVALID",bad.scopes().getFirst().diagnostic());
        }
        var noPort=new ImportStartupScanner().scan(Map.of(".glyahh/run.json",declaration.replace("\"port\":8080,",""),"app.py","fixture"),".");
        assertTrue(noPort.candidates().isEmpty());
    }
    UUID scan=UUID.randomUUID(),task=UUID.randomUUID();
    ImportStartupScanner.Candidate node(String dir,String key,String build){return new ImportStartupScanner.Candidate(dir+"/package.json:"+key,dir+" · "+key,dir,"npm run "+key,build,"STATIC",dir+"/package.json#scripts."+key);}
    ImportStartupService.Inspection inspection(String hash,List<ImportStartupScanner.Candidate> candidates,List<ImportStartupScanner.Scope> scopes){
        return new ImportStartupService.Inspection(scan,"42",task,hash,".","2","3",new ImportStartupScanner.Result("b".repeat(64),candidates,scopes));
    }
    Map<String,String> files(String scripts){return Map.of("web/package.json","{\"scripts\":"+scripts+"}","web/package-lock.json","{}");}

    @Test void invalidSelectionNeverFallsBackToAnotherCandidate(){
        var current=inspection("a".repeat(64),List.of(node("web","dev",null)),List.of());var files=files("{\"dev\":\"vite\"}");
        for(List<String> selected:List.of(List.<String>of(),List.of("web/package.json:dev","web/package.json:dev"),List.of("other")))
            assertThrows(IllegalArgumentException.class,()->ImportStartupPlan.build(current,files,List.of(),selected));
        var many=new ArrayList<String>();for(int index=0;index<21;index++)many.add("id"+index);
        assertThrows(IllegalArgumentException.class,()->ImportStartupPlan.build(current,files,List.of(),many));
    }
    @Test void planListsCommandBuildDependencyReadinessAndIsDeterministic(){
        var current=inspection("a".repeat(64),List.of(node("web","dev","npm run build")),List.of(new ImportStartupScanner.Scope("web","FIXED","IDENTIFIED",null)));
        var files=files("{\"dev\":\"vite\",\"build\":\"vite build\",\"prebuild\":\"node gen.js\",\"postinstall\":\"node patch.js\"}");
        var plan=ImportStartupPlan.build(current,files,List.of(),List.of("web/package.json:dev"));
        var service=plan.services().getFirst();
        assertEquals("npm run dev",service.command());assertEquals(List.of("scripts.dev: vite"),service.expansion());
        assertEquals("PROCESS_ALIVE",service.readiness().method());assertTrue(service.unknowns().contains("READINESS_PORT_UNDECLARED"));
        var build=plan.builds().getFirst();assertEquals(service.build(),build.id());assertEquals(List.of("scripts.prebuild"),build.lifecycle());
        var dependency=plan.dependencies().getFirst();assertEquals("UNKNOWN",dependency.status());assertEquals("npm",dependency.manager());
        assertEquals(List.of("web/package-lock.json","web/package.json"),dependency.declarations());assertEquals(List.of("scripts.postinstall"),dependency.lifecycle());
        assertTrue(plan.aggregates().isEmpty());assertTrue(plan.shared().isEmpty());
        assertEquals(ImportStartupPlan.revision(plan),ImportStartupPlan.revision(ImportStartupPlan.build(current,files,List.of(),List.of("web/package.json:dev"))));
        assertEquals(ImportStartupPlan.revision(plan),ImportStartupPlan.revision(ImportStartupPlan.parse(ImportStartupPlan.json(plan))));
    }
    @Test void aggregateScriptIsExpandedAndNamesUnselectedServices(){
        var manual=new ImportStartupConfiguration.Manual(UUID.randomUUID(),"api","backend","node server.js","npm ci",null);
        var current=inspection("a".repeat(64),List.of(node("web","dev",null),node("web","start",null),
            new ImportStartupScanner.Candidate("manual:"+manual.id(),"api","backend","node server.js",null,"MANUAL","startup configuration revision 2")),
            List.of(new ImportStartupScanner.Scope("web","FIXED","AMBIGUOUS","MULTIPLE_START_SCRIPTS")));
        var files=new HashMap<>(files("{\"dev\":\"concurrently \\\"npm:ui\\\" \\\"npm:api\\\"\",\"ui\":\"vite\",\"api\":\"node ../backend/server.js\",\"start\":\"vite preview\",\"predev\":\"node check.js\"}"));
        files.put("backend/package.json","{}");
        var plan=ImportStartupPlan.build(current,files,List.of(manual),List.of("web/package.json:dev"));
        var aggregate=plan.aggregates().getFirst();
        assertEquals("service:web/package.json:dev",aggregate.id());assertTrue(aggregate.reasons().containsAll(List.of("MULTI_SCRIPT_RUNNER","REFERENCES_UNSELECTED")));
        assertEquals(List.of("manual:"+manual.id()),aggregate.unselected());
        var service=plan.services().getFirst();
        assertEquals(List.of("scripts.dev: concurrently \"npm:ui\" \"npm:api\"","scripts.predev: node check.js","scripts.ui: vite","scripts.api: node ../backend/server.js"),service.expansion());
        assertEquals(List.of("scripts.predev"),service.lifecycle());assertTrue(service.unknowns().contains("SCOPE_FIXED_AMBIGUOUS_MULTIPLE_START_SCRIPTS"));
        // 同包被引用的另一启动脚本也算带上未选服务。
        for(String body:List.of("npm run start","npm start")){
            var chained=ImportStartupPlan.build(current,files("{\"dev\":\""+body+"\",\"start\":\"vite preview\"}"),List.of(manual),List.of("web/package.json:dev"));
            assertEquals(List.of("web/package.json:start"),chained.aggregates().getFirst().unselected(),body);
            assertEquals(List.of("REFERENCES_UNSELECTED"),chained.aggregates().getFirst().reasons());
        }
    }
    @Test void shellOperatorsAreClassifiedWithoutFlaggingSequentialOrRedirect(){
        var current=inspection("a".repeat(64),List.of(node("web","dev",null)),List.of());
        assertTrue(ImportStartupPlan.build(current,files("{\"dev\":\"vite build && vite 2>&1\"}"),List.of(),List.of("web/package.json:dev")).aggregates().isEmpty());
        assertTrue(ImportStartupPlan.build(current,files("{\"dev\":\"node -r dotenv/config server.js\"}"),List.of(),List.of("web/package.json:dev")).aggregates().isEmpty());
        for(var entry:Map.of("node a.js & node b.js","BACKGROUND_PROCESS","cd ../api && node a.js","DIRECTORY_CHANGE","turbo run dev","WORKSPACE_RUNNER",
                "npm run dev --workspaces","WORKSPACE_RUNNER","docker compose up","COMPOSE","pnpm -r dev","WORKSPACE_RUNNER").entrySet())
            assertTrue(ImportStartupPlan.build(current,files("{\"dev\":\""+entry.getKey()+"\"}"),List.of(),List.of("web/package.json:dev")).aggregates().getFirst().reasons().contains(entry.getValue()),entry.getKey());
    }
    @Test void sharedPreparationIsListedOnceWithEveryAffectedService(){
        var current=inspection("a".repeat(64),List.of(node("web","dev","npm run build"),node("web","start","npm run build")),List.of());
        var plan=ImportStartupPlan.build(current,files("{\"dev\":\"vite\",\"start\":\"vite preview\",\"build\":\"vite build\"}"),List.of(),List.of("web/package.json:dev","web/package.json:start"));
        assertEquals(1,plan.builds().size());assertEquals(2,plan.builds().getFirst().services().size());
        assertEquals(List.of(plan.builds().getFirst().id(),"dependency:node:web").stream().sorted().toList(),plan.shared());
    }
    @Test void mavenReactorAndParentScopeAreListed(){
        String parent="<project><modules><module>api</module></modules></project>";
        String child="<project><parent><groupId>g</groupId></parent></project>";
        var root=new ImportStartupScanner.Candidate("pom.xml:demo.App","工程",".","sh ./mvnw spring-boot:run","sh ./mvnw package","HEURISTIC","pom.xml + demo.App");
        var api=new ImportStartupScanner.Candidate("api/pom.xml:demo.Api","api","api","mvn spring-boot:run","mvn package","HEURISTIC","api/pom.xml + demo.Api");
        var current=inspection("a".repeat(64),List.of(root,api),List.of());var files=Map.of("pom.xml",parent,"mvnw","#!/bin/sh","api/pom.xml",child);
        var plan=ImportStartupPlan.build(current,files,List.of(),List.of("pom.xml:demo.App"));
        assertTrue(plan.aggregates().stream().allMatch(row->row.reasons().contains("MAVEN_REACTOR")&&row.unselected().equals(List.of("api/pom.xml:demo.Api"))));
        assertEquals(2,plan.aggregates().size());assertTrue(plan.services().getFirst().expansion().contains("modules: api"));
        var module=ImportStartupPlan.build(current,files,List.of(),List.of("api/pom.xml:demo.Api"));
        assertEquals(List.of("api/pom.xml","pom.xml"),module.dependencies().getFirst().declarations());assertTrue(module.aggregates().isEmpty());
    }
    @Test void manualAgentAndStaticSourcesStayUnverifiedAndNeedTheirOwnConfirmation(){
        var manual=new ImportStartupConfiguration.Manual(UUID.randomUUID(),"api","web","npm run dev","npm ci",null);
        var current=inspection("a".repeat(64),List.of(new ImportStartupScanner.Candidate("manual:"+manual.id(),"api","web","npm run dev",null,"MANUAL","cfg"),
            new ImportStartupScanner.Candidate("agent:x","ai","web","node app.js",null,"AGENT","web/app.js"),
            new ImportStartupScanner.Candidate("site/index.html","site","site",null,null,"STATIC","site/index.html")),List.of());
        var plan=ImportStartupPlan.build(current,files("{\"dev\":\"vite\"}"),List.of(manual),List.of("manual:"+manual.id(),"agent:x","site/index.html"));
        var byId=new HashMap<String,ImportStartupPlan.Service>();plan.services().forEach(row->byId.put(row.id(),row));
        assertTrue(byId.get("manual:"+manual.id()).unknowns().contains("MANUAL_COMMAND_UNVERIFIED"));assertTrue(byId.get("agent:x").unknowns().contains("AGENT_INFERRED"));
        assertEquals("STATIC_ENTRY",byId.get("site/index.html").readiness().method());assertTrue(byId.get("site/index.html").dependencies().isEmpty());
        assertEquals(List.of("npm ci"),plan.dependencies().getFirst().installs());
        assertThrows(IllegalArgumentException.class,()->ImportStartupPlan.build(current,files("{\"dev\":\"vite\"}"),List.of(),List.of("manual:"+manual.id())));
    }
    @Test void contentSelectionOrAvailableServiceChangesRevisionAndDiffNamesFields(){
        var current=inspection("a".repeat(64),List.of(node("web","dev",null)),List.of());var files=files("{\"dev\":\"vite\"}");
        var before=ImportStartupPlan.build(current,files,List.of(),List.of("web/package.json:dev"));
        var changed=ImportStartupPlan.build(inspection("c".repeat(64),List.of(node("web","dev",null)),List.of()),files("{\"dev\":\"vite --host\"}"),List.of(),List.of("web/package.json:dev"));
        assertNotEquals(ImportStartupPlan.revision(before),ImportStartupPlan.revision(changed));
        var paths=ImportStartupPlan.diff(before,changed).stream().map(ImportStartupPlan.Change::path).toList();
        assertTrue(paths.contains("contentHash"));assertTrue(paths.contains("services[web/package.json:dev].expansion"));
        var added=ImportStartupPlan.build(inspection("a".repeat(64),List.of(node("web","dev",null),new ImportStartupScanner.Candidate("agent:y","y","api","node y.js",null,"AGENT","api/y.js")),List.of()),files,List.of(),List.of("web/package.json:dev"));
        var diff=ImportStartupPlan.diff(before,added);
        assertEquals(1,diff.size());assertEquals("available[agent:y]",diff.getFirst().path());assertNull(diff.getFirst().before());
        assertTrue(ImportStartupPlan.diff(before,before).isEmpty());
    }
    @Test void unreadableOrMissingScriptIsUnknownNotGuessed(){
        var current=inspection("a".repeat(64),List.of(node("web","dev",null)),List.of());
        var missing=ImportStartupPlan.build(current,files("{\"start\":\"vite\"}"),List.of(),List.of("web/package.json:dev"));
        assertTrue(missing.services().getFirst().unknowns().contains("SCRIPT_NOT_FOUND"));assertTrue(missing.services().getFirst().expansion().isEmpty());
        var broken=ImportStartupPlan.build(current,Map.of("web/package.json","{broken"),List.of(),List.of("web/package.json:dev"));
        assertTrue(broken.services().getFirst().unknowns().contains("PACKAGE_JSON_UNREADABLE"));
    }
}
