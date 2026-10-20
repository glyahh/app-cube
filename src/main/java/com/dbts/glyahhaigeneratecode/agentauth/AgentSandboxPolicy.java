package com.dbts.glyahhaigeneratecode.agentauth;

import java.util.Set;

/**
 * 沙箱档位：能碰什么的硬边界。
 *
 * <p>与应用级审批模式正交。沙箱拒绝时不进入审批环节，
 * 因此不存在「危险操作经用户批准后放行」。
 */
public final class AgentSandboxPolicy {
    public static final String READ_ONLY = "READ_ONLY";
    public static final String WORKSPACE_WRITE = "WORKSPACE_WRITE";
    private static final Set<String> MODES = Set.of(READ_ONLY, WORKSPACE_WRITE);

    private AgentSandboxPolicy() {
    }

    public static boolean valid(String value) {
        return value != null && MODES.contains(value);
    }

    /** 只读档位拒绝一切写操作与命令执行。 */
    public static boolean allowsWrite(String sandboxMode) {
        return WORKSPACE_WRITE.equals(sandboxMode);
    }
}