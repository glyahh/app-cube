package com.dbts.glyahhaigeneratecode.projectimport;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;
import org.junit.jupiter.api.io.TempDir;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicBoolean;
import static org.junit.jupiter.api.Assertions.*;

class ImportGitReaderTest {
    @TempDir Path directory;
    Path git(){return Path.of(System.getProperty("import.test.git","D:/mainJava/all lib/Git/cmd/git.exe"));}
    @Test void ownedBareMetadataAndEmptyConfigAreSeparateFromSource() throws Exception {
        var resources=new CopyOnWriteArrayList<ImportProcessRunner.Resource>();
        var eligible=new AtomicBoolean(true);
        var reader=new ImportGitReader(git(),directory,ImportGitNetworkPolicy.proxy("http://127.0.0.1:7897"),eligible::get,resources::add);
        assertTrue(Files.isRegularFile(directory.resolve("metadata.git/HEAD")));
        assertEquals(0,Files.size(directory.resolve("home/empty.gitconfig")));
        Path hooks=directory.resolve("metadata.git/hooks");
        if(Files.exists(hooks))try(var entries=Files.list(hooks)){assertTrue(entries.findAny().isEmpty());}
        eligible.set(false);
        var error=assertThrows(RuntimeException.class,()->reader.branches(ImportRepositorySource.parse("GitHub","https://github.com/octocat/Hello-World")));
        assertTrue(error.getMessage().contains("REVOKED_BEFORE_START"));
        resources.forEach(resource->assertFalse(ProcessHandle.of(resource.pid()).map(ProcessHandle::isAlive).orElse(false)));
    }
    @Test void existingPrivateHomeCannotBeReusedOrOverwritten() throws Exception {
        Files.createDirectory(directory.resolve("home"));Files.writeString(directory.resolve("home/sentinel"),"keep");
        assertThrows(java.io.IOException.class,()->new ImportGitReader(git(),directory,ImportGitNetworkPolicy.proxy("http://127.0.0.1:7897"),()->true,r->{}));
        assertEquals("keep",Files.readString(directory.resolve("home/sentinel")));
    }
    @Test void nativeGitUsesPrivateAskpassPipeWithoutTokenInConfigOrArguments() throws Exception {
        var source=ImportRepositorySource.parse("GitHub","https://github.com/octocat/Hello-World");
        var resources=new CopyOnWriteArrayList<ImportProcessRunner.Resource>();
        Path python=Path.of("agent/.venv/Scripts/python.exe").toAbsolutePath();
        Path helper=Path.of("scripts/import-git-askpass.py").toAbsolutePath();
        try(var broker=new ImportCredentialBroker(source,()->true,()->new ImportCredentialBroker.Secret("fixture-account","FAKE_NATIVE_GIT_SECRET"))) {
            var env=new java.util.HashMap<>(ImportGitReader.askpass(python,helper,broker,directory));
            env.put("GIT_CONFIG_NOSYSTEM","1");env.put("GIT_CONFIG_GLOBAL",Files.createFile(directory.resolve("empty.gitconfig")).toString());
            env.put("GIT_TERMINAL_PROMPT","0");env.put("PATH",git().getParent().toString());
            if(System.getenv("SystemRoot")!=null)env.put("SystemRoot",System.getenv("SystemRoot"));
            var command=java.util.List.of(git().toString(),"-c","credential.helper=","-c","credential.useHttpPath=true","credential","fill");
            byte[] input="protocol=https\nhost=github.com\npath=octocat/Hello-World.git\n\n".getBytes(java.nio.charset.StandardCharsets.US_ASCII);
            boolean supplied=ImportProcessRunner.run(command,directory,env,java.time.Duration.ofSeconds(15),4096,65536,()->true,resources::add,input,
                    output->{String value=new String(output.readAllBytes(),java.nio.charset.StandardCharsets.US_ASCII);return value.contains("username=fixture-account\n") && value.contains("password=FAKE_NATIVE_GIT_SECRET\n");}).value();
            assertTrue(supplied,"实际 Git 必须从私有通道取得选中凭据。");
            assertFalse(command.toString().contains("FAKE_NATIVE_GIT_SECRET"));
            assertFalse(env.toString().contains("FAKE_NATIVE_GIT_SECRET"));
            assertEquals(0,Files.size(directory.resolve("empty.gitconfig")));
            String wrapper=Files.readString(directory.resolve("askpass.cmd"));
            assertFalse(wrapper.contains("FAKE_NATIVE_GIT_SECRET"));assertFalse(wrapper.contains(broker.capability()));
        }
        resources.forEach(resource->assertFalse(ProcessHandle.of(resource.pid()).map(ProcessHandle::isAlive).orElse(false)));
    }
    @Test void actualBlobStreamCoversEmptyShortLargeAndRejectsPartialConsumersAndLfs() throws Exception {
        var reader=new ImportGitReader(git(),directory,ImportGitNetworkPolicy.proxy("http://127.0.0.1:7897"),()->true,r->{});
        for(byte[] bytes:java.util.List.of(new byte[0],"short".getBytes(java.nio.charset.StandardCharsets.UTF_8),new byte[10000])) {
            var blob=fixtureBlob(bytes);
            assertArrayEquals(bytes,reader.blob(blob,input->input.readAllBytes()));
            if(bytes.length>0)assertThrows(RuntimeException.class,()->reader.blob(blob,input->null));
            var wrongSize=new ImportGitMetadata.Blob(blob.path(),blob.objectId(),bytes.length+1,false);
            assertThrows(RuntimeException.class,()->reader.blob(wrongSize,input->input.readAllBytes()));
        }
        var lfs=fixtureBlob("version https://git-lfs.github.com/spec/v1\noid sha256:fixture\nsize 100".getBytes(java.nio.charset.StandardCharsets.US_ASCII));
        assertThrows(RuntimeException.class,()->reader.blob(lfs,input->input.readAllBytes()));
    }
    private ImportGitMetadata.Blob fixtureBlob(byte[] content)throws Exception {
        Path file=Files.createTempFile(directory,"fixture-",".bin");Files.write(file,content);
        var environment=new java.util.HashMap<String,String>();
        if(System.getenv("SystemRoot")!=null)environment.put("SystemRoot",System.getenv("SystemRoot"));
        String id=ImportProcessRunner.run(java.util.List.of(git().toString(),"--git-dir="+directory.resolve("metadata.git"),
                "hash-object","-w","--no-filters","--",file.toString()),directory,environment,java.time.Duration.ofSeconds(5),128,65536,
                ()->true,r->{},input->new String(input.readAllBytes(),java.nio.charset.StandardCharsets.US_ASCII).strip()).value();
        return new ImportGitMetadata.Blob("fixture.bin",id,content.length,false);
    }
    @com.dbts.glyahhaigeneratecode.ExternalTest
    void managedReaderObtainsBothPublicPlatformsWithoutExecutingProjects() throws Exception {
        var resources=new CopyOnWriteArrayList<ImportProcessRunner.Resource>();
        for(var source:java.util.List.of(ImportRepositorySource.parse("GitHub","https://github.com/octocat/Hello-World"),
                ImportRepositorySource.parse("Gitee","https://gitee.com/sdk/gitee5j"))) {
            Path owned=Files.createDirectory(directory.resolve(source.provider()));
            var reader=new ImportGitReader(git(),owned,ImportGitNetworkPolicy.proxy("http://127.0.0.1:7897"),()->true,resources::add);
            var branches=reader.branches(source);
            assertFalse(branches.isEmpty());
            assertTrue(branches.stream().anyMatch(branch->branch.name().equals(source.provider().equals("GitHub")?"master":"main")));
            assertFalse(Files.exists(owned.resolve("README")),"分支读取不能 checkout 工程。");
            if(source.provider().equals("GitHub")) {
                var selected=branches.stream().filter(branch->branch.name().equals("master")).findFirst().orElseThrow();
                var snapshot=reader.fetch(source,selected,100,1024*1024,16*1024*1024,1024*1024);
                assertEquals(selected.commit(),snapshot.commit());assertFalse(snapshot.blobs().isEmpty());
                var offline=ImportGitReader.reopen(git(),owned,()->true,resources::add);
                assertEquals(snapshot,offline.fixedSnapshot(snapshot.branch(),snapshot.commit(),100,1024*1024));
                assertThrows(RuntimeException.class,()->offline.branches(source));
                assertThrows(RuntimeException.class,()->offline.fetch(source,selected,100,1024*1024,16*1024*1024,1024*1024));
                assertThrows(RuntimeException.class,()->offline.fixedSnapshot(snapshot.branch(),"b".repeat(40),100,1024*1024));
                for(var blob:snapshot.blobs())assertEquals(blob.size(),reader.blob(blob,input->input.readAllBytes()).length);
                var blob=snapshot.blobs().getFirst();
                var altered=new ImportGitMetadata.Blob(blob.path(),blob.objectId(),blob.size()+1,blob.executable());
                assertThrows(RuntimeException.class,()->reader.blob(altered,input->input.readAllBytes()));
                System.out.println("PUBLIC_GIT_FIXED_CONTENT commit="+snapshot.commit()+" blobs="+snapshot.blobs().size());
            }
            System.out.println("PUBLIC_GIT_READER "+source.provider()+" branches="+branches.size()+" resourceCount="+resources.size());
        }
        resources.forEach(resource->assertFalse(ProcessHandle.of(resource.pid()).map(ProcessHandle::isAlive).orElse(false)));
    }
    @Test void reopeningMissingObjectsDoesNotInventARepository()throws Exception {
        assertThrows(java.io.IOException.class,()->ImportGitReader.reopen(git(),directory,()->true,r->{}));
        assertFalse(Files.exists(directory.resolve("metadata.git")));
        assertThrows(RuntimeException.class,()->ImportGitNetworkPolicy.offline().requireSource(ImportRepositorySource.parse("GitHub","https://github.com/octocat/Hello-World")));
    }
}
