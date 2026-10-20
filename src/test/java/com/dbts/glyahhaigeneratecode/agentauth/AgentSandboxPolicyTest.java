package com.dbts.glyahhaigeneratecode.agentauth;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** 沙箱档位是硬边界：只读档位不得放行任何写入。 */
class AgentSandboxPolicyTest {

    @Test
    void workspaceWriteAllowsWrite() {
        assertTrue(AgentSandboxPolicy.allowsWrite(AgentSandboxPolicy.WORKSPACE_WRITE));
    }

    @Test
    void readOnlyRejectsWrite() {
        assertFalse(AgentSandboxPolicy.allowsWrite(AgentSandboxPolicy.READ_ONLY));
    }

    @Test
    void unknownOrMissingModeIsNotWritable() {
        assertFalse(AgentSandboxPolicy.allowsWrite(null));
        assertFalse(AgentSandboxPolicy.allowsWrite(""));
        assertFalse(AgentSandboxPolicy.allowsWrite("FULL_ACCESS"));
    }

    @Test
    void onlyTwoModesAreValid() {
        assertTrue(AgentSandboxPolicy.valid(AgentSandboxPolicy.READ_ONLY));
        assertTrue(AgentSandboxPolicy.valid(AgentSandboxPolicy.WORKSPACE_WRITE));
        assertFalse(AgentSandboxPolicy.valid("FULL_ACCESS"));
        assertFalse(AgentSandboxPolicy.valid(null));
    }
}