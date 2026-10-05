package com.dbts.glyahhaigeneratecode.projectimport;

import org.junit.jupiter.api.Test;
import java.nio.charset.StandardCharsets;
import static org.junit.jupiter.api.Assertions.*;

class ImportPlatformIdentityTest {
    byte[] response(String body,String status){return (body+"\n"+status).getBytes(StandardCharsets.UTF_8);}
    @Test void authenticatedIdentityRequiresPositiveIdAndExplicitLogin() {
        assertEquals("account-name",ImportPlatformIdentity.login(response("{\"login\":\"account-name\",\"id\":12,\"email\":\"not retained\"}","200")));
        for(String body:java.util.List.of("{}","{\"login\":\"x\",\"id\":0}","{\"login\":\"bad name\",\"id\":1}","[]","{\"login\":\"x\",\"id\":1.5}"))
            assertThrows(RuntimeException.class,()->ImportPlatformIdentity.login(response(body,"200")));
    }
    @Test void authFailuresAndRedirectsNeverExposePlatformBodyOrGuessUser() {
        for(String status:java.util.List.of("401","403","429","301","500")) {
            var error=assertThrows(RuntimeException.class,()->ImportPlatformIdentity.login(response("FAKE_SECRET_RESPONSE",status)));
            assertFalse(error.getMessage().contains("FAKE_SECRET_RESPONSE"));
            assertTrue(ImportPlatformIdentity.errorCode(error).startsWith("AUTH_"));
        }
        assertThrows(RuntimeException.class,()->ImportPlatformIdentity.login("{bad".getBytes(StandardCharsets.UTF_8)));
    }
}
