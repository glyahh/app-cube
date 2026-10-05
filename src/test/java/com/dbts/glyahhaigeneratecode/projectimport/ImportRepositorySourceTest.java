package com.dbts.glyahhaigeneratecode.projectimport;

import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class ImportRepositorySourceTest {
    @Test void canonicalizesOnlyExpectedHttpsPlatformAndRepository() {
        var github=ImportRepositorySource.parse("GitHub","https://github.com/Example/customer-console.git");
        assertEquals("https://github.com/Example/customer-console.git",github.cloneUrl());
        assertEquals("customer-console",github.repository()); assertEquals("Example",github.owner());
        var gitee=ImportRepositorySource.parse("Gitee","https://gitee.com/example/my_app/");
        assertEquals("https://gitee.com/example/my_app.git",gitee.cloneUrl());
    }
    @Test void rejectsSecretUrlsProtocolsPortsQueriesAndForeignDestinations() {
        for(String source:new String[]{"http://github.com/a/b","https://token@github.com/a/b","https://github.com:444/a/b","https://github.com.evil.test/a/b","https://127.0.0.1/a/b","file:///tmp/a","ssh://github.com/a/b","https://github.com/a/b?token=secret","https://github.com/a/b#main","https://github.com/a/b/tree/main","https://github.com/a/%2e%2e","https://github.com/a/..","https://github.com/a/b\\x","https://github.com/a/b\n"}) {
            assertThrows(RuntimeException.class,()->ImportRepositorySource.parse("GitHub",source),source);
        }
        assertThrows(RuntimeException.class,()->ImportRepositorySource.parse("Gitee","https://github.com/a/b"));
    }
    @Test void branchIsOneValidatedRefNotCommandOptionsOrRevisionExpression() {
        assertEquals("feature/customer",ImportRepositorySource.branch("feature/customer"));
        for(String ref:new String[]{"", "--upload-pack=x", "main..next", "HEAD~1", "a:b", "a@{1}", "a\\b", "a//b", "a.lock", "a/b.lock", "/a", "a/", "a.", "a b", "a\nb"}) {
            assertThrows(RuntimeException.class,()->ImportRepositorySource.branch(ref),ref);
        }
    }
}
