package com.dbts.glyahhaigeneratecode.projectimport;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import java.nio.file.*;
import java.nio.file.attribute.FileTime;
import java.util.List;
import java.util.UUID;
import static org.junit.jupiter.api.Assertions.*;

class ImportStartupSnapshotStoreTest {
    @TempDir Path root;
    Path workspace() throws Exception {return Files.createDirectory(root.resolve("workspace"));}
    ImportStartupSnapshotStore store(Path workspace) throws Exception {
        return new ImportStartupSnapshotStore(root.resolve("snapshots"),List.of(workspace),100,1024*1024);
    }
    @Test void capturesFullBinaryAndSensitiveContentButDoesNotExposeSecretsToParser() throws Exception {
        Path work=workspace();Files.write(work.resolve("image.bin"),new byte[]{0,1,-1});
        Files.writeString(work.resolve(".env"),"fixture-private");Files.createDirectories(work.resolve(".glyahh"));
        Files.writeString(work.resolve(".glyahh/run.json"),"{}");
        var store=store(work);var snapshot=store.capture(UUID.randomUUID(),work,()->true);
        assertEquals(3,snapshot.files().size());assertEquals(java.util.Map.of(".glyahh/run.json","{}"),store.parserInput(snapshot));
        assertTrue(Files.exists(work.resolve("image.bin")));assertEquals("fixture-private",Files.readString(work.resolve(".env")));
    }
    @Test void agentProjectionReadsSealedSourcesAndReadmeButNotProtectedOrHistoricalFiles()throws Exception{
        Path work=workspace();Files.writeString(work.resolve("README.md"),"project instructions are data");Files.createDirectories(work.resolve("backend"));Files.writeString(work.resolve("backend/app.js"),"console.log('source')");
        Files.writeString(work.resolve(".env.example"),"must not send");Files.createDirectories(work.resolve("secrets"));Files.writeString(work.resolve("secrets/config.json"),"must not send");
        Files.createDirectories(work.resolve("logs"));Files.writeString(work.resolve("logs/report.txt"),"old result");
        var store=store(work);var snapshot=store.capture(UUID.randomUUID(),work,()->true);
        var input=store.agentInput(snapshot,java.util.List.of("."));assertEquals(java.util.Map.of("README.md","project instructions are data","backend/app.js","console.log('source')"),input);
        Files.writeString(work.resolve("backend/app.js"),"changed");assertEquals("console.log('source')",store.agentInput(snapshot,java.util.List.of(".")).get("backend/app.js"));
        assertThrows(UnsupportedOperationException.class,()->input.clear());
    }
    @Test void agentProjectionOnlyReadsApprovedDirectories()throws Exception{
        Path work=workspace();Files.writeString(work.resolve("README.md"),"root notes");
        for(String dir:new String[]{"backend","backend2","frontend"}){Files.createDirectories(work.resolve(dir));Files.writeString(work.resolve(dir+"/app.js"),dir);}
        Files.writeString(work.resolve("backend/.env"),"TEST_ONLY=secret");
        var store=store(work);var snapshot=store.capture(UUID.randomUUID(),work,()->true);
        assertEquals(java.util.Map.of("backend/app.js","backend"),store.agentInput(snapshot,java.util.List.of("backend")));
        assertEquals(java.util.Map.of("backend/app.js","backend","frontend/app.js","frontend"),store.agentInput(snapshot,java.util.List.of("backend","frontend")));
        assertThrows(IllegalArgumentException.class,()->store.agentInput(snapshot,java.util.List.of()));
    }
    @Test void agentProjectionLimitsDoNotReturnTruncatedSource()throws Exception{
        Path work=workspace();Files.writeString(work.resolve("app.js"),"x".repeat(16*1024+1));var store=store(work);var snapshot=store.capture(UUID.randomUUID(),work,()->true);
        assertThrows(java.io.IOException.class,()->store.agentInput(snapshot,java.util.List.of(".")));
    }
    @Test void corruptedAndNonUtf8AgentSourceIsRejected()throws Exception{
        Path work=workspace();Files.write(work.resolve("app.js"),new byte[]{-1});var store=store(work);UUID id=UUID.randomUUID();var snapshot=store.capture(id,work,()->true);
        assertThrows(java.io.IOException.class,()->store.agentInput(snapshot,java.util.List.of(".")));Files.write(root.resolve("snapshots").resolve(id.toString()).resolve("0.bin"),new byte[]{1});
        assertThrows(java.io.IOException.class,()->store.agentInput(snapshot,java.util.List.of(".")));
    }
    @Test void sameSizeAndTimestampChangesInvalidateFullContentIdentity() throws Exception {
        Path work=workspace();Path file=work.resolve("index.html");Files.writeString(file,"aaaa");FileTime time=Files.getLastModifiedTime(file);
        var store=store(work);var first=store.capture(UUID.randomUUID(),work,()->true);
        Files.writeString(file,"bbbb");Files.setLastModifiedTime(file,time);
        var second=store.capture(UUID.randomUUID(),work,()->true);
        assertNotEquals(first.contentHash(),second.contentHash());assertThrows(java.io.IOException.class,()->store.verify(first,work,()->true));
        assertEquals("aaaa",store.parserInput(first).get("index.html"));
    }
    @Test void revocationDoesNotPublishPartialSnapshot() throws Exception {
        Path work=workspace();Files.writeString(work.resolve("index.html"),"fixture");var store=store(work);UUID id=UUID.randomUUID();
        assertThrows(java.io.IOException.class,()->store.capture(id,work,()->false));
        assertFalse(Files.exists(root.resolve("snapshots").resolve(id.toString())));
    }
    @Test void limitsRejectWholeInputRatherThanDroppingFiles() throws Exception {
        Path work=workspace();Files.writeString(work.resolve("a.txt"),"a");Files.writeString(work.resolve("b.txt"),"b");
        var store=new ImportStartupSnapshotStore(root.resolve("snapshots"),List.of(work),1,100);UUID id=UUID.randomUUID();
        assertThrows(java.io.IOException.class,()->store.capture(id,work,()->true));
        assertFalse(Files.exists(root.resolve("snapshots").resolve(id.toString())));
    }
    @Test void sourceAndSnapshotCannotOverlap() throws Exception {
        Path work=workspace();assertThrows(IllegalArgumentException.class,()->new ImportStartupSnapshotStore(work.resolve("snapshots"),List.of(work),100,100));
    }
    @Test void extraFilesAndMissingFilesInvalidateSnapshot() throws Exception {
        Path work=workspace();Files.writeString(work.resolve("index.html"),"fixture");var store=store(work);
        var snapshot=store.capture(UUID.randomUUID(),work,()->true);assertDoesNotThrow(()->store.verify(snapshot,work,()->true));
        Files.writeString(work.resolve("extra.txt"),"x");assertThrows(java.io.IOException.class,()->store.verify(snapshot,work,()->true));
        Files.delete(work.resolve("extra.txt"));Files.delete(work.resolve("index.html"));assertThrows(java.io.IOException.class,()->store.verify(snapshot,work,()->true));
    }
    @Test void invalidUtf8ConfigurationFailsInsteadOfReturningPartialParserInput() throws Exception {
        Path work=workspace();Files.write(work.resolve("package.json"),new byte[]{-1});var store=store(work);
        var snapshot=store.capture(UUID.randomUUID(),work,()->true);
        assertThrows(java.io.IOException.class,()->store.parserInput(snapshot));
    }
    @Test void dependenciesAreInFullIdentityButNotParserInput() throws Exception {
        Path work=workspace();Files.writeString(work.resolve("index.html"),"fixture");Files.createDirectories(work.resolve("node_modules/pkg"));
        Files.writeString(work.resolve("node_modules/pkg/package.json"),"{}");var store=store(work);
        var first=store.capture(UUID.randomUUID(),work,()->true);assertEquals(2,first.files().size());
        assertEquals(java.util.Map.of("index.html","fixture"),store.parserInput(first));
        Files.writeString(work.resolve("node_modules/pkg/package.json"),"[]");assertThrows(java.io.IOException.class,()->store.verify(first,work,()->true));
    }
    @Test void corruptedPrivateCopyCannotProduceAParserInput() throws Exception {
        Path work=workspace();Files.writeString(work.resolve("index.html"),"fixture");var store=store(work);UUID id=UUID.randomUUID();
        var snapshot=store.capture(id,work,()->true);Files.writeString(root.resolve("snapshots").resolve(id.toString()).resolve("0.bin"),"changed");
        assertThrows(java.io.IOException.class,()->store.parserInput(snapshot));
    }
    @Test void byteLimitRejectsWholeSnapshotAndImmutableMetadataCannotBeChanged() throws Exception {
        Path work=workspace();Files.writeString(work.resolve("index.html"),"fixture");
        var small=new ImportStartupSnapshotStore(root.resolve("small"),List.of(work),100,3);
        assertThrows(java.io.IOException.class,()->small.capture(UUID.randomUUID(),work,()->true));
        var snapshot=store(work).capture(UUID.randomUUID(),work,()->true);
        assertThrows(UnsupportedOperationException.class,()->snapshot.files().clear());
        assertThrows(UnsupportedOperationException.class,()->snapshot.attributes().clear());
    }
    @Test void emptyDirectoriesAndPermissionChangesAreVersioned() throws Exception {
        Path work=workspace();Files.writeString(work.resolve("index.html"),"fixture");var store=store(work);var snapshot=store.capture(UUID.randomUUID(),work,()->true);
        Files.createDirectory(work.resolve("empty"));assertThrows(java.io.IOException.class,()->store.verify(snapshot,work,()->true));Files.delete(work.resolve("empty"));
        var view=Files.getFileAttributeView(work.resolve("index.html"),java.nio.file.attribute.DosFileAttributeView.class);
        if(view!=null){
            boolean before=view.readAttributes().isReadOnly();
            try{view.setReadOnly(!before);assertThrows(java.io.IOException.class,()->store.verify(snapshot,work,()->true));}
            finally{view.setReadOnly(before);}
        }
    }
}
