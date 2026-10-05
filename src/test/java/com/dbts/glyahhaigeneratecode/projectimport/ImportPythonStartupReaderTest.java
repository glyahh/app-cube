package com.dbts.glyahhaigeneratecode.projectimport;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import java.nio.file.*;
import java.util.Map;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;
import static org.mockito.ArgumentMatchers.*;

class ImportPythonStartupReaderTest {
    @TempDir Path root;
    Map<String,String> input(){return Map.of("backend/requirements.txt","fastapi\nuvicorn","backend/app.py","raise RuntimeError('must not execute')\nfrom fastapi import FastAPI\napp=FastAPI()");}
    ImportProperties properties(){var props=new ImportProperties();props.setStagingRoot(root.resolve("imports"));return props;}
    @Test void actualManagedParserReturnsBoundCandidatesWithoutImportingProject()throws Exception{
        var reader=new ImportPythonStartupReader(properties());var files=input();var original=new ImportStartupScanner().scan(files,".");
        var result=reader.supplement(original,files,()->true);assertEquals(original.inputHash(),result.inputHash());
        assertEquals("backend",result.candidates().getFirst().directory());assertEquals("python -m uvicorn app:app --host 0.0.0.0",result.candidates().getFirst().command());
        assertEquals("UNIDENTIFIED",result.scopes().getFirst().status());assertEquals("IDENTIFIED",result.scopes().getLast().status());
        try(var jobs=Files.list(root.resolve("project_startup_python"))){assertEquals(0,jobs.count());}
    }
    @Test void missingParserCannotFallBackToFakePythonCandidate()throws Exception{
        var properties=properties();properties.setCredentialPython(root.resolve("missing.exe"));var reader=new ImportPythonStartupReader(properties);
        assertEquals(root.resolve("missing.exe"),properties.getCredentialPython());assertFalse(Files.exists(properties.getCredentialPython()));
        assertEquals(properties.getCredentialPython().toAbsolutePath().normalize(),org.springframework.test.util.ReflectionTestUtils.getField(reader,"python"));
        var files=input();var original=new ImportStartupScanner().scan(files,".");
        assertEquals("PYTHON_RULES_NOT_IMPLEMENTED",original.scopes().getFirst().diagnostic());
        assertThrows(java.io.IOException.class,()->reader.supplement(original,files,()->true));
    }
    @Test void revocationDoesNotReturnAResultAndCleansStoppedJob()throws Exception{
        var reader=new ImportPythonStartupReader(properties());var files=input();
        assertThrows(java.io.IOException.class,()->reader.supplement(new ImportStartupScanner().scan(files,"."),files,()->false));
        try(var jobs=Files.list(root.resolve("project_startup_python"))){assertEquals(0,jobs.count());}
    }
    @Test void nodeAndDeclaredScopesDoNotNeedPythonEnvironment()throws Exception{
        var properties=properties();properties.setCredentialPython(root.resolve("missing.exe"));var reader=new ImportPythonStartupReader(properties);
        var files=Map.of("index.html","fixture");var result=new ImportStartupScanner().scan(files,".");assertSame(result,reader.supplement(result,files,()->true));
    }
    @Test void mixedNodeAndPythonInOneDirectoryKeepBothServices()throws Exception{
        var files=new java.util.HashMap<>(input());files.put("backend/package.json","{\"packageManager\":\"npm@10\",\"scripts\":{\"dev\":\"node server.js\"}}");
        var reader=new ImportPythonStartupReader(properties());var result=reader.supplement(new ImportStartupScanner().scan(files,"."),files,()->true);
        assertEquals(2,result.candidates().size());assertTrue(result.candidates().stream().anyMatch(candidate->candidate.command().equals("npm run dev")));
        assertTrue(result.candidates().stream().anyMatch(candidate->candidate.command().startsWith("python -m uvicorn")));
    }
    @Test void foreignTransportInputIdentityCannotReplaceCapturedInput()throws Exception{
        var reader=new ImportPythonStartupReader(properties());var files=input();var original=new ImportStartupScanner().scan(files,".");
        try(var runner=mockStatic(ImportProcessRunner.class)){
            runner.when(()->ImportProcessRunner.run(anyList(),any(Path.class),anyMap(),any(java.time.Duration.class),anyLong(),anyLong(),any(),any(),any()))
                .thenReturn(new ImportProcessRunner.Result<>(new ImportPythonStartupReader.Response("foreign-input",Map.of()),0,0,0));
            assertThrows(java.io.IOException.class,()->reader.supplement(original,files,()->true));
        }
        try(var jobs=Files.list(root.resolve("project_startup_python"))){assertEquals(0,jobs.count());}
    }
}
