package com.dbts.glyahhaigeneratecode.projectimport;

import com.dbts.glyahhaigeneratecode.exception.MyException;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class ImportProviderPolicyTest {
    @Test void onlyGithubCanStartNewWork() {
        assertDoesNotThrow(() -> ImportProviderPolicy.requireSupported("GitHub"));
        for (String provider : new String[]{null, "Gitee", "github", "Other"}) {
            assertThrows(MyException.class, () -> ImportProviderPolicy.requireSupported(provider));
        }
    }

    @Test void historicalGiteeSourceRemainsReadableWithoutExecutionEligibility() {
        var historical = ImportRepositorySource.parse("Gitee", "https://gitee.com/example/project");
        assertEquals("project", historical.repository());
        assertFalse(ImportProviderPolicy.supported(historical.provider()));
    }
}
