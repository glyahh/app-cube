package com.dbts.glyahhaigeneratecode.agentauth;

import com.dbts.glyahhaigeneratecode.exception.ErrorCode;
import com.dbts.glyahhaigeneratecode.exception.ThrowUtils;
import com.fasterxml.jackson.databind.JsonNode;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import java.util.Set;
import java.util.UUID;

/**
 * 变更账本的写入入口。
 *
 * <p>内容由执行侧上报，这里只做会话归属校验与摘要计算；
 * 超限内容不落正文，标 truncated 供审阅如实展示。
 */
@Service
@RequiredArgsConstructor
public class AgentChangeLedgerService {
    private static final Set<String> CHANGES = Set.of("ADDED", "MODIFIED", "DELETED");

    private final AgentChangeLedgerRepository ledger;
    private final AgentEditSessionRepository sessions;

    public void record(long ownerId, UUID sessionId, JsonNode frame) {
        var session = sessions.require(sessionId, ownerId);
        sessions.qualified(session);
        String path = frame.path("path").asText();
        String change = frame.path("change").asText();
        ThrowUtils.throwIf(path.isBlank() || !CHANGES.contains(change),
                ErrorCode.PARAMS_ERROR, "变更记录无效。");
        String before = text(frame.path("beforeText"));
        String after = text(frame.path("afterText"));
        boolean truncated = overLimit(before) || overLimit(after);
        ledger.record(sessionId, new AgentChangeLedgerRepository.Change(
                path, change, before == null ? null : sha256(before), after == null ? null : sha256(after),
                truncated ? null : before, truncated ? null : after, truncated));
    }

    public java.util.List<AgentChangeLedgerRepository.Change> list(long ownerId, UUID sessionId) {
        sessions.require(sessionId, ownerId);
        return ledger.list(sessionId);
    }

    public void clear(long ownerId, UUID sessionId) {
        sessions.require(sessionId, ownerId);
        ledger.clear(sessionId);
    }

    private static boolean overLimit(String value) {
        return value != null && value.length() > AgentChangeLedgerRepository.MAX_BLOB_CHARS;
    }

    private static String text(JsonNode node) {
        return node == null || node.isNull() || !node.isTextual() ? null : node.asText();
    }

    private static String sha256(String value) {
        try {
            byte[] digest = MessageDigest.getInstance("SHA-256")
                    .digest(value.getBytes(StandardCharsets.UTF_8));
            return HexFormat.of().formatHex(digest);
        } catch (NoSuchAlgorithmException error) {
            throw new IllegalStateException(error);
        }
    }
}