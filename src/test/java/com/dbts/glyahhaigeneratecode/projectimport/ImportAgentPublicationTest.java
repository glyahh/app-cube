package com.dbts.glyahhaigeneratecode.projectimport;

import com.dbts.glyahhaigeneratecode.service.support.WorkspacePathResolver;
import com.dbts.glyahhaigeneratecode.model.enums.CodeGenTypeEnum;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import java.io.*;
import java.nio.file.*;
import java.security.MessageDigest;
import java.time.Instant;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class ImportAgentPublicationTest {
    @TempDir Path root;

    ImportAgentCandidateVerifier.FilesAccess access(Map<String,byte[]> data){
        return new ImportAgentCandidateVerifier.FilesAccess(){
            public ImportAgentCandidateVerifier.Inventory inventory(){throw new UnsupportedOperationException();}
            public InputStream open(String name){return new ByteArrayInputStream(data.get(name));}
        };
    }
    ImportAgentCandidateVerifier.FileStamp stamp(byte[] data)throws Exception{
        return new ImportAgentCandidateVerifier.FileStamp(HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(data)),data.length,0644);
    }

    @Test void materializePreservesBinaryEmptyFileEmptyDirectoryAndRejectsWrongBytes()throws Exception{
        Path source=Files.createDirectory(root.resolve("source")),candidate=Files.createDirectory(root.resolve("candidate"));
        Files.createDirectory(source.resolve("空 目录"));Files.write(source.resolve("binary.bin"),new byte[]{0,1,2});
        byte[] binary={0,-1,4,0};var data=Map.of("binary.bin",binary,"empty.txt",new byte[0]);
        var inventory=new ImportAgentCandidateVerifier.Inventory(Map.of("binary.bin",stamp(binary),"empty.txt",stamp(new byte[0])),Set.of(".","空 目录"));
        ImportAgentPublication.materialize(source,candidate,inventory,access(data),()->true);
        assertArrayEquals(binary,Files.readAllBytes(candidate.resolve("binary.bin")));
        assertEquals(0,Files.size(candidate.resolve("empty.txt")));assertTrue(Files.isDirectory(candidate.resolve("空 目录")));
        Path wrong=Files.createDirectory(root.resolve("wrong"));
        assertThrows(IOException.class,()->ImportAgentPublication.materialize(source,wrong,inventory,access(Map.of("binary.bin",new byte[]{3},"empty.txt",new byte[0])),()->true));
        assertArrayEquals(new byte[]{0,1,2},Files.readAllBytes(source.resolve("binary.bin")));
        assertThrows(IOException.class,()->ImportAgentPublication.materialize(source,Files.createDirectory(root.resolve("cancelled")),inventory,access(data),()->false));
    }

    @Test void recoverOldMoveWithoutPhaseWriteRestoresOriginalDirectory()throws Exception{recover(false,false);}

    @Test void hiddenReadonlyFileCanBeUpdatedWithoutLosingAttributes()throws Exception{
        Path source=Files.createDirectory(root.resolve("source")),candidate=Files.createDirectory(root.resolve("candidate"));
        Path configuration=Files.writeString(source.resolve(".env"),"original");
        var attributes=Files.getFileAttributeView(configuration,java.nio.file.attribute.DosFileAttributeView.class);
        if(attributes!=null){attributes.setHidden(true);attributes.setReadOnly(true);}
        byte[] updated="preserved-secret".getBytes(java.nio.charset.StandardCharsets.UTF_8);
        var inventory=new ImportAgentCandidateVerifier.Inventory(Map.of(".env",stamp(updated)),Set.of("."));
        try{
            ImportAgentPublication.materialize(source,candidate,inventory,access(Map.of(".env",updated)),()->true);
            assertArrayEquals(updated,Files.readAllBytes(candidate.resolve(".env")));
            if(attributes!=null){var after=Files.readAttributes(candidate.resolve(".env"),java.nio.file.attribute.DosFileAttributes.class);
                assertTrue(after.isHidden());assertTrue(after.isReadOnly());}
        }finally{
            if(attributes!=null)attributes.setReadOnly(false);
            var after=Files.getFileAttributeView(candidate.resolve(".env"),java.nio.file.attribute.DosFileAttributeView.class);
            if(after!=null&&Files.exists(candidate.resolve(".env")))after.setReadOnly(false);
        }
    }
    @Test void recoverNewMoveWithoutPhaseWriteRestoresOriginalDirectory()throws Exception{recover(true,false);}
    @Test void committedRecoveryKeepsNewDirectoryAndOnlyClearsBackup()throws Exception{recover(true,true);}

    void recover(boolean newMoved,boolean committed)throws Exception{
        var properties=new ImportProperties();properties.setStagingRoot(root.resolve("imports"));
        var ledger=mock(ImportAgentEditLedger.class);var paths=mock(WorkspacePathResolver.class);var volumes=mock(ImportDiskVolume.class);
        var publication=new ImportAgentPublication(ledger,mock(ImportWorkspaceCoordinator.class),paths,volumes,properties);
        UUID id=UUID.randomUUID();Path source=root.resolve("project"),folder=root.resolve("project_edit_publish").resolve(id.toString());
        Files.createDirectories(folder);Path backup=Files.createDirectory(folder.resolve("backup"));
        Files.writeString(backup.resolve("app.py"),"original");Files.createDirectory(backup.resolve("空 目录"));
        var snapshots=new ImportStartupSnapshotStore(root.resolve("snapshot"),List.of(),100,1<<20);
        String original=snapshots.describe(id,backup,()->true).contentHash();
        Path candidate=folder.resolve("candidate");Path newest=Files.createDirectory(newMoved?source:candidate);
        Files.writeString(newest.resolve("app.py"),"fixed");Files.createDirectory(newest.resolve("空 目录"));
        String updated=snapshots.describe(id,newest,()->true).contentHash();
        var entry=new ImportAgentEditLedger.Entry(id,7,101,UUID.randomUUID(),"201",original,0,0,UUID.randomUUID(),Long.MAX_VALUE,Instant.EPOCH,Instant.now().plusSeconds(60));
        var row=new ImportAgentEditLedger.Row(entry,"RECOVERY_REQUIRED",9,!committed,"[{\"state\":\"RELEASED\"}]","PENDING",committed?"COMMITTED":"CANDIDATE_READY",true);
        var journal=new ObjectMapper().createObjectNode().put("source",source.toString()).put("folder",folder.toString())
                .put("candidate",candidate.toString()).put("backup",backup.toString()).put("originalHash",original).put("publishedHash",updated);
        when(paths.outputDir(101L,CodeGenTypeEnum.AUTO)).thenReturn(source);when(ledger.publicationJournal(row)).thenReturn(journal);
        publication.recover(row);
        assertEquals(committed?"fixed":"original",Files.readString(source.resolve("app.py")));
        assertTrue(Files.isDirectory(source.resolve("空 目录")));assertFalse(Files.exists(folder));
        verify(ledger).finishPublication(row,committed);verifyNoInteractions(volumes);
    }
}
