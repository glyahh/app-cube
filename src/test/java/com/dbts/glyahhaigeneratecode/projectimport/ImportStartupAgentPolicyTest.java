package com.dbts.glyahhaigeneratecode.projectimport;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;

class ImportStartupAgentPolicyTest {
    ImportStartupService.Inspection inspection(List<ImportStartupScanner.Candidate> candidates,List<ImportStartupScanner.Scope> scopes){
        return new ImportStartupService.Inspection(UUID.randomUUID(),"42",UUID.randomUUID(),"a".repeat(64),".","2","3",new ImportStartupScanner.Result("b".repeat(64),candidates,scopes));
    }
    ImportStartupScanner.Scope unknown(String directory){return new ImportStartupScanner.Scope(directory,"ALGORITHM","UNIDENTIFIED","NO_ENTRY");}
    @Test void partialUnknownIsEligibleWithoutOverridingKnownService(){
        var known=new ImportStartupScanner.Candidate("front","前端","frontend","npm run dev",null,"STATIC","frontend/package.json");
        var input=inspection(List.of(known),List.of(new ImportStartupScanner.Scope("frontend","FIXED","IDENTIFIED",null),unknown("backend")));
        assertEquals(List.of("backend"),ImportStartupAgentPolicy.requireSelection(input,List.of("backend")));
        assertThrows(IllegalArgumentException.class,()->ImportStartupAgentPolicy.requireSelection(input,List.of("frontend")));
    }
    @ParameterizedTest @ValueSource(strings={"FAILED","INCOMPLETE","AMBIGUOUS"})
    void conflictingDiagnosticCannotAuthorize(String status){
        var input=inspection(List.of(),List.of(unknown("backend"),new ImportStartupScanner.Scope("backend","FIXED",status,"reason")));
        assertTrue(ImportStartupAgentPolicy.eligibleDirectories(input).isEmpty());
    }
    @ParameterizedTest @ValueSource(strings={"FAILED","INCOMPLETE","AMBIGUOUS"})
    void FailedParentDoesNotAuthorizeDescendant(String status){
        var input=inspection(List.of(),List.of(unknown("backend/api"),new ImportStartupScanner.Scope(".","DISCOVERY",status,"reason")));
        assertTrue(ImportStartupAgentPolicy.eligibleDirectories(input).isEmpty());
    }
    @Test void manualCandidateKeepsPriority(){
        var manual=new ImportStartupScanner.Candidate("manual","后端","backend","custom",null,"MANUAL","configuration");
        assertTrue(ImportStartupAgentPolicy.eligibleDirectories(inspection(List.of(manual),List.of(unknown("backend")))).isEmpty());
    }
    @Test void emptyDuplicateAndUnobservedRequestsAreRejected(){
        var input=inspection(List.of(),List.of(unknown("backend")));
        for(var selection:List.of(List.<String>of(),List.of("backend","backend"),List.of("../backend"),List.of("backend/other")))
            assertThrows(IllegalArgumentException.class,()->ImportStartupAgentPolicy.requireSelection(input,selection));
    }
    @Test void similarlyNamedParentDoesNotBlockAnotherDirectory(){
        var input=inspection(List.of(),List.of(unknown("backend"),new ImportStartupScanner.Scope("back","FIXED","FAILED","invalid")));
        assertEquals(List.of("backend"),ImportStartupAgentPolicy.eligibleDirectories(input));
    }
    @Test void identifiedRangeInSameDirectoryKeepsUnknownRangeIndependent(){
        var node=new ImportStartupScanner.Candidate("app/package.json:dev","app","app","npm run dev",null,"STATIC","app/package.json#scripts.dev");
        var maven=new ImportStartupScanner.Scope("app","ALGORITHM","UNIDENTIFIED","NO_SUPPORTED_JAVA_PLUGIN");
        var input=inspection(List.of(node),List.of(new ImportStartupScanner.Scope("app","FIXED","IDENTIFIED",null),maven));
        assertEquals(List.of("app"),ImportStartupAgentPolicy.eligibleDirectories(input));
        assertEquals(List.of(maven),ImportStartupAgentPolicy.unresolvedRanges(input,List.of("app")));
        assertEquals(List.of(node),ImportStartupAgentPolicy.knownCandidates(input,List.of("app")));
    }
    @Test void multipleUnknownRangesInSameDirectoryAreListedSeparately(){
        var node=new ImportStartupScanner.Scope("app","FIXED","UNIDENTIFIED","PACKAGE_MANAGER_UNKNOWN");var maven=new ImportStartupScanner.Scope("app","ALGORITHM","UNIDENTIFIED","NO_SUPPORTED_JAVA_PLUGIN");
        var input=inspection(List.of(),List.of(node,maven,unknown("other")));
        assertEquals(List.of("app","other"),ImportStartupAgentPolicy.eligibleDirectories(input));
        assertEquals(List.of(node,maven),ImportStartupAgentPolicy.unresolvedRanges(input,List.of("app")));
        assertThrows(IllegalArgumentException.class,()->ImportStartupAgentPolicy.unresolvedRanges(input,List.of("app","missing")));
    }
    @Test void publishedSupplementCannotBeRequestedAgain(){
        var agent=new ImportStartupScanner.Candidate("agent:x","后端","backend","python app.py",null,"AGENT","backend/app.py");
        assertTrue(ImportStartupAgentPolicy.eligibleDirectories(inspection(List.of(agent),List.of(unknown("backend")))).isEmpty());
        var noEntry=new ImportStartupScanner.Scope("backend",ImportStartupAgentPolicy.AGENT_PHASE,"UNIDENTIFIED","AGENT_NO_ENTRY");
        assertTrue(ImportStartupAgentPolicy.eligibleDirectories(inspection(List.of(),List.of(unknown("backend"),noEntry))).isEmpty());
    }
}
