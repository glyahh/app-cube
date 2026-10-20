package com.dbts.glyahhaigeneratecode.agentauth;

import com.dbts.glyahhaigeneratecode.exception.ErrorCode;
import com.dbts.glyahhaigeneratecode.exception.ThrowUtils;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

import java.time.Duration;
import java.time.Instant;
import java.util.UUID;

/**
 * 普通应用的编辑会话生命周期。
 *
 * <p>同一应用同时只允许一个未关闭会话；会话到期或关闭后，
 * 旧票据不再能驱动任何副作用。
 */
@Service
@RequiredArgsConstructor
public class AgentEditSessionService {
    /** 与请求整体时限一致，避免会话比执行活得更久。 */
    private static final Duration LIFETIME = Duration.ofMinutes(30);

    private final AgentEditSessionRepository sessions;

    public AgentEditSessionRepository.Session open(long ownerId, long appId, long runId,
                                                   String sandboxMode, String approvalMode) {
        ThrowUtils.throwIf(!AgentSandboxPolicy.valid(sandboxMode), ErrorCode.PARAMS_ERROR, "沙箱档位无效。");
        ThrowUtils.throwIf(approvalMode == null, ErrorCode.PARAMS_ERROR, "审批模式无效。");
        return sessions.open(ownerId, appId, runId, "0".repeat(64), sandboxMode, approvalMode, 0,
                UUID.randomUUID(), UUID.randomUUID(), Instant.now().plus(LIFETIME));
    }

    public AgentEditSessionRepository.Session require(UUID sessionId, long ownerId) {
        return sessions.require(sessionId, ownerId);
    }

    public void close(UUID sessionId, long ownerId, String state) {
        sessions.close(sessionId, ownerId, state);
    }

    /** 应用被删除时归档它的会话与未决票据，避免唯一索引被永久占用。 */
    public int archiveByApp(long appId) {
        return sessions.archiveByApp(appId);
    }
}