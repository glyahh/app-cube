package com.dbts.glyahhaigeneratecode.projectimport;

import org.junit.jupiter.api.Test;
import java.net.InetAddress;
import java.util.List;
import static org.junit.jupiter.api.Assertions.*;

class ImportGitNetworkPolicyTest {
    @Test void directEgressPinsOnlyPublicAddressesAndNeverDisablesTlsOrAllowsRedirect() throws Exception {
        var policy=ImportGitNetworkPolicy.direct("github.com",List.of(InetAddress.getByName("140.82.112.3")));
        assertTrue(policy.gitConfig().contains("http.curloptResolve=github.com:443:140.82.112.3"));
        assertTrue(policy.gitConfig().contains("http.sslVerify=true"));
        assertTrue(policy.gitConfig().contains("http.followRedirects=false"));
        assertDoesNotThrow(()->policy.requireSource(ImportRepositorySource.parse("GitHub","https://github.com/octocat/Hello-World")));
        assertThrows(RuntimeException.class,()->policy.requireSource(ImportRepositorySource.parse("Gitee","https://gitee.com/sdk/gitee5j")));
    }
    @Test void privateSyntheticMulticastDocumentationAndMixedDnsAnswersFailClosed() throws Exception {
        for(String ip:new String[]{"127.0.0.1","10.0.0.1","192.168.1.1","172.16.0.1","169.254.1.1","100.64.0.1","198.18.0.160","192.0.2.1","224.0.0.1","::1","fc00::1","fe80::1","2001:db8::1"}) {
            var bad=InetAddress.getByName(ip);
            assertThrows(RuntimeException.class,()->ImportGitNetworkPolicy.direct("github.com",List.of(bad)),ip);
            assertThrows(RuntimeException.class,()->ImportGitNetworkPolicy.direct("github.com",List.of(InetAddress.getByName("140.82.112.3"),bad)),ip);
        }
        assertThrows(RuntimeException.class,()->ImportGitNetworkPolicy.direct("evil.test",List.of(InetAddress.getByName("140.82.112.3"))));
    }
    @Test void explicitLocalProxyDoesNotPretendToProveTargetDnsAndCannotContainSecretsOrExternalHost() {
        var policy=ImportGitNetworkPolicy.proxy("http://127.0.0.1:17897");
        assertEquals("EXPLICIT_PROXY_TLS_HOST",policy.evidenceMode());
        assertTrue(policy.gitConfig().contains("http.proxy=http://127.0.0.1:17897"));
        assertFalse(policy.gitConfig().stream().anyMatch(s->s.startsWith("http.curloptResolve=")));
        for(String proxy:new String[]{"http://token@127.0.0.1:17897","http://evil.test:8080","http://127.0.0.1:17897/path","http://127.0.0.1:17897?token=x","socks5://127.0.0.1:17897"}) {
            assertThrows(RuntimeException.class,()->ImportGitNetworkPolicy.proxy(proxy));
        }
    }
}
