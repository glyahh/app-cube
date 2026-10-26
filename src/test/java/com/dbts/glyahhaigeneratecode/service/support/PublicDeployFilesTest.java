package com.dbts.glyahhaigeneratecode.service.support;

import com.dbts.glyahhaigeneratecode.config.WorkspaceProperties;
import com.dbts.glyahhaigeneratecode.controller.DeployResourceController;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.web.servlet.HandlerMapping;
import java.nio.file.*;
import static org.junit.jupiter.api.Assertions.*;

class PublicDeployFilesTest {
    @TempDir Path temp;
    private void put(Path root, String name) throws Exception {
        Path file=root.resolve(name); Files.createDirectories(file.getParent()); Files.writeString(file,"placeholder");
    }
    @Test void publishesAssetsButNeverPrivateImportFilesAndRemovesOldCopies() throws Exception {
        Path source=temp.resolve("source"), destination=temp.resolve("deploy/key");
        for (String name : new String[]{"index.html","style.css","assets/app.js","assets/logo.svg","data.json",
                ".env","nested/.env.production","nested/private.key",".git/config",".aws/credentials",
                ".agent/state.json","node_modules/module/index.js","application-local.yml","server.java"}) put(source,name);
        put(destination,".env"); put(destination,"old-secret.txt");
        PublicDeployFiles.publish(source,destination);
        for(String name:new String[]{"index.html","style.css","assets/app.js","assets/logo.svg","data.json"})
            assertTrue(Files.isRegularFile(destination.resolve(name)),name);
        for(String name:new String[]{".env","nested/.env.production","nested/private.key",".git/config",
                ".aws/credentials",".agent/state.json","node_modules/module/index.js","application-local.yml",
                "server.java","old-secret.txt"}) assertFalse(Files.exists(destination.resolve(name)),name);
        PublicDeployFiles.publish(source,destination);
        assertEquals("placeholder",Files.readString(destination.resolve("index.html")));
    }
    @Test void skipsLinksRatherThanFollowingThem() throws Exception {
        Path source=temp.resolve("source"), destination=temp.resolve("deploy/key");
        Path outside=temp.resolve("outside");
        Files.createDirectories(outside);
        Files.writeString(outside.resolve("keep.txt"),"outside");
        put(source,"index.html");
        Path link=source.resolve("public-link");
        if (System.getProperty("os.name").startsWith("Windows")) {
            Process process=new ProcessBuilder("cmd.exe","/d","/c","mklink","/J",link.toString(),outside.toString())
                    .redirectErrorStream(true).start();
            assertTrue(process.waitFor(5,java.util.concurrent.TimeUnit.SECONDS));
            assertEquals(0,process.exitValue(),new String(process.getInputStream().readAllBytes()));
        } else {
            Files.createSymbolicLink(link,outside);
        }
        PublicDeployFiles.publish(source,destination);
        assertFalse(Files.exists(destination.resolve("public-link"),LinkOption.NOFOLLOW_LINKS));
    }
    @Test void publicEndpointDeniesLegacySecretsWith404ButServesHomepage() throws Exception {
        WorkspaceProperties props=new WorkspaceProperties(); props.setDeployRoot(temp.resolve("deploy"));
        var controller=new DeployResourceController(new WorkspacePathResolver(props,null));
        for(String name:new String[]{"index.html",".env","nested/private.key",".git/config",".agent/state.json","node_modules/x/index.js"})
            put(props.getDeployRoot().resolve("key"),name);
        for(String name:new String[]{".env","nested/private.key",".git/config",".agent/state.json","node_modules/x/index.js"}) {
            var request=new MockHttpServletRequest();
            request.setAttribute(HandlerMapping.PATH_WITHIN_HANDLER_MAPPING_ATTRIBUTE,"/deploy/key/"+name);
            assertEquals(404,controller.serveDeployResource("key",request).getStatusCode().value(),name);
        }
        var request=new MockHttpServletRequest(); request.setAttribute(HandlerMapping.PATH_WITHIN_HANDLER_MAPPING_ATTRIBUTE,"/deploy/key/");
        assertEquals(200,controller.serveDeployResource("key",request).getStatusCode().value());
    }
}
