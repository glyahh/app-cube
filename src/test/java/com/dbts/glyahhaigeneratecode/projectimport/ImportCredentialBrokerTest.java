package com.dbts.glyahhaigeneratecode.projectimport;

import org.junit.jupiter.api.Test;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import static org.junit.jupiter.api.Assertions.*;

class ImportCredentialBrokerTest {
    HttpResponse<String> call(ImportCredentialBroker broker,String capability,String prompt) throws Exception {
        return HttpClient.newHttpClient().send(HttpRequest.newBuilder(URI.create(broker.endpoint()))
                .header("X-Import-Capability",capability).POST(HttpRequest.BodyPublishers.ofString(prompt)).build(),HttpResponse.BodyHandlers.ofString());
    }
    @Test void privateChannelReturnsOnlyRequestedFieldForExactRepository() throws Exception {
        var source=ImportRepositorySource.parse("GitHub","https://github.com/example/project");
        try(var broker=new ImportCredentialBroker(source,()->true,()->new ImportCredentialBroker.Secret("x-access-token","FAKE_SECRET_ONLY"))) {
            assertFalse(broker.endpoint().contains("FAKE_SECRET_ONLY"));
            assertEquals("x-access-token",call(broker,broker.capability(),"Username for 'https://github.com/example/project.git': ").body());
            assertEquals("FAKE_SECRET_ONLY",call(broker,broker.capability(),"Password for 'https://x-access-token@github.com/example/project.git': ").body());
        }
    }
    @Test void wrongCapabilityForeignHostOrRepositoryCannotReadSecret() throws Exception {
        AtomicInteger reads=new AtomicInteger();
        try(var broker=new ImportCredentialBroker(ImportRepositorySource.parse("GitHub","https://github.com/example/project"),()->true,()->{reads.incrementAndGet();return new ImportCredentialBroker.Secret("x-access-token","FAKE_SECRET_ONLY");})) {
            assertEquals(403,call(broker,"wrong","Password for 'https://github.com/example/project.git': ").statusCode());
            assertEquals(403,call(broker,broker.capability(),"Password for 'https://evil.test/example/project.git': ").statusCode());
            assertEquals(403,call(broker,broker.capability(),"Password for 'https://github.com/example/other.git': ").statusCode());
            assertEquals(0,reads.get());
        }
    }
    @Test void revokedEligibilityOrUnavailableSecretNeverFallsBack() throws Exception {
        AtomicBoolean eligible=new AtomicBoolean(true);
        try(var broker=new ImportCredentialBroker(ImportRepositorySource.parse("Gitee","https://gitee.com/example/project"),eligible::get,()->{throw new IllegalStateException("secret unavailable");})) {
            String prompt="Password for 'https://gitee.com/example/project.git': ";
            assertEquals(503,call(broker,broker.capability(),prompt).statusCode());
            eligible.set(false); assertEquals(409,call(broker,broker.capability(),prompt).statusCode());
        }
    }
    @Test void trustedPythonAskpassUsesPrivateProtocolWithoutSecretInArguments() throws Exception {
        java.nio.file.Path python=java.nio.file.Path.of("agent", ".venv", System.getProperty("os.name").toLowerCase().contains("win")?"Scripts/python.exe":"bin/python").toAbsolutePath();
        assertTrue(java.nio.file.Files.isRegularFile(python),"专用 helper 验证需要已有 Agent Python 环境，不自动安装。");
        try(var broker=new ImportCredentialBroker(ImportRepositorySource.parse("GitHub","https://github.com/example/project"),()->true,()->new ImportCredentialBroker.Secret("x-access-token","FAKE_SECRET_ONLY"))) {
            var builder=new ProcessBuilder(python.toString(),java.nio.file.Path.of("scripts/import-git-askpass.py").toAbsolutePath().toString(),"Password for 'https://x-access-token@github.com/example/project.git': ");
            assertFalse(builder.command().toString().contains("FAKE_SECRET_ONLY"));
            builder.environment().put("IMPORT_ASKPASS_ENDPOINT",broker.endpoint());
            builder.environment().put("IMPORT_ASKPASS_CAPABILITY",broker.capability());
            var process=builder.start();
            try {
                assertTrue(process.waitFor(15,java.util.concurrent.TimeUnit.SECONDS));
                assertEquals(0,process.exitValue());
                assertEquals("FAKE_SECRET_ONLY\n",new String(process.getInputStream().readAllBytes(),java.nio.charset.StandardCharsets.UTF_8));
                assertEquals(0,process.getErrorStream().readAllBytes().length);
            } finally {if(process.isAlive())process.destroyForcibly();}
        }
    }
    @Test void stoppedListenerDoesNotPretendBlockedSecretCallbackHasExited()throws Exception {
        var entered=new java.util.concurrent.CountDownLatch(1);var release=new java.util.concurrent.CountDownLatch(1);
        var broker=new ImportCredentialBroker(ImportRepositorySource.parse("GitHub","https://github.com/example/project"),()->true,()->{
            entered.countDown();
            while(release.getCount()>0)try{release.await();}catch(InterruptedException ignored){ }
            return new ImportCredentialBroker.Secret("fixture-account","FAKE_STOP_ONLY");
        });
        try {
            var client=HttpClient.newHttpClient();
            var request=HttpRequest.newBuilder(URI.create(broker.endpoint())).header("X-Import-Capability",broker.capability())
                    .POST(HttpRequest.BodyPublishers.ofString("Username for 'https://github.com/example/project.git': ")).build();
            var pending=client.sendAsync(request,HttpResponse.BodyHandlers.ofString());
            assertTrue(entered.await(3,java.util.concurrent.TimeUnit.SECONDS));
            assertThrows(ImportCredentialBroker.StopFailure.class,broker::close);assertFalse(broker.confirmedStopped());
            release.countDown();long deadline=System.nanoTime()+java.util.concurrent.TimeUnit.SECONDS.toNanos(3);
            while(!broker.confirmedStopped() && System.nanoTime()<deadline)Thread.sleep(20);
            assertTrue(broker.confirmedStopped());pending.cancel(true);
        }finally{release.countDown();broker.close();}
    }
}
