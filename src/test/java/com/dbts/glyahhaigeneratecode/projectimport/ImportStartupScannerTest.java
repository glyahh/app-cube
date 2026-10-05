package com.dbts.glyahhaigeneratecode.projectimport;

import org.junit.jupiter.api.Test;
import java.util.Map;
import static org.junit.jupiter.api.Assertions.*;

class ImportStartupScannerTest {
    final ImportStartupScanner scanner=new ImportStartupScanner();
    Map<String,String> node(){return Map.of("frontend/package.json","{\"scripts\":{\"dev\":\"vite\",\"build\":\"vite build\"},\"packageManager\":\"npm@10.8.0\"}","frontend/src/main.ts","fixture");}
    @Test void nestedNodeUsesActualScriptAndDoesNotGuessRunningState(){
        var result=scanner.scan(node(),".");assertEquals(1,result.candidates().size());
        var service=result.candidates().getFirst();assertEquals("frontend",service.directory());assertEquals("npm run dev",service.command());
        assertEquals("npm run build",service.build());assertEquals("IDENTIFIED",result.scopes().getFirst().status());
        assertFalse(result.toString().contains("RUNNING"));
    }
    @Test void unrelatedStartTextDoesNotBecomeAScript(){
        var result=scanner.scan(Map.of("package.json","{\"description\":\"dev start\",\"packageManager\":\"npm@10\"}"),".");
        assertTrue(result.candidates().isEmpty());assertEquals("UNIDENTIFIED",result.scopes().getFirst().status());
    }
    @Test void malformedAndDuplicateConfigurationFailWithoutFallback(){
        for(String text:new String[]{"{","{\"scripts\":{},\"scripts\":{\"dev\":\"vite\"}}"}){
            var result=scanner.scan(Map.of("package.json",text,"index.html","valid fixture"),".");
            assertTrue(result.candidates().isEmpty());assertEquals("FAILED",result.scopes().getFirst().status());
        }
    }
    @Test void declaredConfigurationWinsAndInvalidDeclarationDoesNotFallBack(){
        var input=new java.util.HashMap<>(node());input.put(".glyahh/run.json","{\"processes\":[{\"name\":\"Web\",\"workDir\":\"frontend\",\"command\":\"npm run dev\"}]}");
        var result=scanner.scan(input,".");assertEquals(1,result.candidates().size());assertEquals("DECLARED",result.candidates().getFirst().source());
        input.put(".glyahh/run.json","{");result=scanner.scan(input,".");assertTrue(result.candidates().isEmpty());assertEquals("FAILED",result.scopes().getFirst().status());
    }
    @Test void foreignAndMissingDeclaredWorkingDirectoriesAreRejected(){
        for(String directory:new String[]{"../foreign","/etc","missing","frontend/../../other"}){
            var input=new java.util.HashMap<>(node());input.put(".glyahh/run.json","{\"processes\":[{\"name\":\"x\",\"workDir\":\""+directory+"\",\"command\":\"npm run dev\"}]}");
            var result=scanner.scan(input,".");assertTrue(result.candidates().isEmpty());assertEquals("FAILED",result.scopes().getFirst().status());
        }
    }
    @Test void dependencyBuildAndExampleProjectsAreNotUserServices(){
        var input=new java.util.HashMap<>(node());String text=input.get("frontend/package.json");
        for(String dir:new String[]{"node_modules/pkg",".venv/pkg","dist/sub","examples/demo","test/fixture",".glyahh/reports/old"})input.put(dir+"/package.json",text);
        assertEquals(1,scanner.scan(input,".").candidates().size());
    }
    @Test void conflictingLockFilesAndMultipleEntrypointsRemainAmbiguous(){
        var input=new java.util.HashMap<>(node());input.put("frontend/pnpm-lock.yaml","fixture");input.put("frontend/package-lock.json","fixture");
        var result=scanner.scan(input,".");assertTrue(result.candidates().isEmpty());assertEquals("AMBIGUOUS",result.scopes().getFirst().status());
        input.remove("frontend/pnpm-lock.yaml");input.put("frontend/package.json","{\"scripts\":{\"dev\":\"vite\",\"start\":\"node server.js\"},\"packageManager\":\"npm@10\"}");
        result=scanner.scan(input,".");assertEquals(2,result.candidates().size());assertEquals("AMBIGUOUS",result.scopes().getFirst().status());
    }
    @Test void selectedDirectoryDoesNotDiscardOtherInputOrIncludeOtherCandidates(){
        var input=new java.util.HashMap<>(node());input.put("backend/package.json",input.get("frontend/package.json"));
        assertEquals(2,scanner.scan(input,".").candidates().size());assertEquals(1,scanner.scan(input,"frontend").candidates().size());assertTrue(input.containsKey("backend/package.json"));
        assertThrows(IllegalArgumentException.class,()->scanner.scan(input,"../other"));assertThrows(IllegalArgumentException.class,()->scanner.scan(input,"missing"));
    }
    @Test void plainMavenLibraryDoesNotPretendToBeSpringBoot(){
        var result=scanner.scan(Map.of("pom.xml","<project><modelVersion>4.0.0</modelVersion><artifactId>library</artifactId></project>"),".");
        assertTrue(result.candidates().isEmpty());assertEquals("UNIDENTIFIED",result.scopes().getFirst().status());
    }
    @Test void xmlExternalEntitiesCannotReadFilesOrBecomeFallback(){
        String text="<!DOCTYPE project [<!ENTITY secret SYSTEM 'file:///not-allowed'>]><project><artifactId>&secret;</artifactId></project>";
        var result=scanner.scan(Map.of("pom.xml",text,"index.html","fixture"),".");assertTrue(result.candidates().isEmpty());assertEquals("FAILED",result.scopes().getFirst().status());
    }
    @Test void limitFailuresAreIncompleteNotUnidentifiedOrTruncatedSuccess(){
        var result=new ImportStartupScanner(1,128).scan(node(),".");assertTrue(result.candidates().isEmpty());assertEquals("INCOMPLETE",result.scopes().getFirst().status());
    }
    @Test void inputIdentityIsDeterministicAndDetectsSameLengthContentChanges(){
        var a=new java.util.LinkedHashMap<String,String>();a.put("a.txt","abcd");a.put("b.txt","efgh");
        var b=new java.util.LinkedHashMap<String,String>();b.put("b.txt","efgh");b.put("a.txt","abcd");
        assertEquals(scanner.scan(a,".").inputHash(),scanner.scan(b,".").inputHash());b.put("a.txt","abce");
        assertNotEquals(scanner.scan(a,".").inputHash(),scanner.scan(b,".").inputHash());
    }
    @Test void similarFilenamesDoNotInventStaticEntrypoints(){
        var result=scanner.scan(Map.of("evilpackage.json","{}","sub/notindex.html","fixture"),".");
        assertTrue(result.candidates().isEmpty());
    }
    @Test void springPluginAndParsedMainClassProduceCandidateWithoutCompiling(){
        var result=scanner.scan(spring(),".");
        assertEquals(1,result.candidates().size());assertEquals("mvn spring-boot:run",result.candidates().getFirst().command());
        assertEquals("HEURISTIC",result.candidates().getFirst().source());assertEquals("ALGORITHM",result.scopes().getFirst().phase());
    }
    @Test void commentsStringsAndTestsAreNotJavaEntrypoints(){
        var input=new java.util.HashMap<>(spring());
        input.put("src/main/java/demo/App.java","package demo; class App { String x=\"@SpringBootApplication public static void main(String[] args)\"; }");
        input.put("src/test/java/demo/Test.java",spring().get("src/main/java/demo/App.java"));
        assertTrue(scanner.scan(input,".").candidates().isEmpty());
    }
    @Test void pluginManagementDoesNotDeclareExecutablePlugin(){
        var input=new java.util.HashMap<>(spring());input.put("pom.xml",input.get("pom.xml").replace("<plugins>","<pluginManagement><plugins>").replace("</plugins>","</plugins></pluginManagement>"));
        assertTrue(scanner.scan(input,".").candidates().isEmpty());
    }
    @Test void multipleSpringMainClassesAreNotSilentlyReducedToOne(){
        var input=new java.util.HashMap<>(spring());input.put("src/main/java/demo/Other.java",input.get("src/main/java/demo/App.java").replace("class App","class Other"));
        var result=scanner.scan(input,".");assertEquals(2,result.candidates().size());assertEquals("AMBIGUOUS",result.scopes().getFirst().status());
        assertTrue(result.candidates().stream().allMatch(c->c.command().contains("-Dspring-boot.run.main-class=")));
    }
    @Test void invalidJavaSyntaxDoesNotBecomeUnidentified(){
        var input=new java.util.HashMap<>(spring());input.put("src/main/java/demo/App.java","class {");
        assertEquals("FAILED",scanner.scan(input,".").scopes().getFirst().status());
    }
    @Test void oversizedConfigurationAndUnimplementedPythonAreIncomplete(){
        assertEquals("INCOMPLETE",new ImportStartupScanner(100,8).scan(node(),".").scopes().getFirst().status());
        var result=scanner.scan(Map.of("requirements.txt","flask","app.py","print('not a service')"),".");
        assertEquals("INCOMPLETE",result.scopes().getFirst().status());assertTrue(result.candidates().isEmpty());
    }
    Map<String,String> spring(){return Map.of(
        "pom.xml","<project><modelVersion>4.0.0</modelVersion><build><plugins><plugin><groupId>org.springframework.boot</groupId><artifactId>spring-boot-maven-plugin</artifactId></plugin></plugins></build></project>",
        "src/main/java/demo/App.java","package demo; import org.springframework.boot.autoconfigure.SpringBootApplication; @SpringBootApplication public class App { public static void main(String[] args) {} }");}
    @Test void brokenNodeManifestDoesNotSuppressValidSpringCandidate(){
        var input=new java.util.HashMap<>(spring());input.put("package.json","broken JSON");
        var result=scanner.scan(input,".");
        assertEquals(1,result.candidates().size());assertTrue(result.candidates().getFirst().command().contains("spring-boot:run"));
        assertTrue(result.scopes().stream().anyMatch(scope->scope.status().equals("FAILED")));
        assertTrue(result.scopes().stream().anyMatch(scope->scope.status().equals("IDENTIFIED")));
    }
    @Test void declaredStaticNeedsItsActualEntryAndDoesNotTrustOldBuildOutputs(){
        var result=scanner.scan(Map.of(".glyahh/run.json","{\"kind\":\"STATIC\",\"processes\":[]}","index.html","fixture"),".");
        assertEquals("DECLARED",result.candidates().getFirst().source());
        result=scanner.scan(Map.of(".glyahh/run.json","{\"kind\":\"STATIC\"}","dist/index.html","old"),".");
        assertTrue(result.candidates().isEmpty());assertEquals("INCOMPLETE",result.scopes().getFirst().status());
    }
    @Test void declaredPortIsKeptOnlyWhenWrittenAsAnInteger(){
        var result=scanner.scan(Map.of(".glyahh/run.json","{\"processes\":[{\"name\":\"api\",\"command\":\"python3 app.py\",\"port\":8080}]}","app.py","print(1)"),".");
        assertEquals(8080,result.candidates().getFirst().port());
        result=scanner.scan(Map.of(".glyahh/run.json","{\"processes\":[{\"name\":\"api\",\"command\":\"python3 -m http.server 8080\"}]}","app.py","print(1)"),".");
        assertNull(result.candidates().getFirst().port());
        result=scanner.scan(Map.of(".glyahh/run.json","{\"processes\":[{\"name\":\"api\",\"command\":\"python3 app.py\",\"port\":\"8080\"}]}","app.py","print(1)"),".");
        assertEquals("FAILED",result.scopes().getFirst().status());
    }
    @Test void declaredBuildIsPreservedNotSilentlySkipped(){
        var result=scanner.scan(Map.of(".glyahh/run.json","{\"buildCommand\":\"npm run build\",\"processes\":[{\"name\":\"web\",\"command\":\"node server.js\"}]}","server.js","fixture"),".");
        assertEquals("npm run build",result.candidates().getFirst().build());
    }
    @Test void excessiveJsonDepthCannotReachAStaticFallback(){
        var text="{\"nested\":"+"[".repeat(100)+"0"+"]".repeat(100)+"}";
        var result=scanner.scan(Map.of("package.json",text,"index.html","fixture"),".");
        assertEquals("FAILED",result.scopes().getFirst().status());assertTrue(result.candidates().isEmpty());
    }
}
