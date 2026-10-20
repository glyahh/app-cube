package com.dbts.glyahhaigeneratecode.agentauth;

import com.dbts.glyahhaigeneratecode.exception.ErrorCode;
import com.dbts.glyahhaigeneratecode.exception.MyException;
import com.dbts.glyahhaigeneratecode.exception.ThrowUtils;
import com.dbts.glyahhaigeneratecode.projectimport.ImportWorkspaceCoordinator;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.HexFormat;
import java.util.Set;
import java.util.UUID;

/**
 * 普通应用的会话授权模式。
 *
 * <p>与导入应用的设置分开存储：导入的候选机制建立在隔离卷与发布目录上，
 * 普通应用直接读写工作区，两者的授权对象不同，不能共用一张表。
 */
@Service
@RequiredArgsConstructor
public class AgentEditSettingsService {
    public record SettingsVO(String appId, String threadId, String mode, String sandboxMode,
                             String policyRevision, String sessionEpoch,
                             java.time.Instant sessionExpiresAt) { }
    public record SettingsDTO(String policyRevision, String mode, String sandboxMode) { }

    private static final Set<String> MODES = Set.of("MANUAL", "GUARDED_AUTO", "AUTO");

    private final AgentEditSettingsRepository repository;
    private final AgentModelsService models;
    private final ImportWorkspaceCoordinator coordinator;
    private final UUID generation = UUID.randomUUID();

    public SettingsVO current(long ownerId, long appId, String loginSessionId) {
        return coordinator.withLock(appId, () -> {
            models.requireVisible(ownerId, appId);
            return view(repository.restore(ownerId, appId, binding(ownerId, loginSessionId), generation));
        });
    }

    /** 供请求工厂冻结审批模式；未登记过时按表默认值 MANUAL。 */
    public String mode(long ownerId, long appId) {
        var rows = repository.find(ownerId, appId);
        return rows == null ? "MANUAL" : rows.mode();
    }

    /** 供请求工厂冻结沙箱档位；未登记过时按表默认值 WORKSPACE_WRITE。 */
    public String sandboxMode(long ownerId, long appId) {
        var rows = repository.find(ownerId, appId);
        return rows == null ? AgentSandboxPolicy.WORKSPACE_WRITE : rows.sandboxMode();
    }

    public SettingsVO save(long ownerId, long appId, String loginSessionId, SettingsDTO input) {
        ThrowUtils.throwIf(input == null, ErrorCode.PARAMS_ERROR, "授权设置不完整。");
        long revision = revision(input.policyRevision());
        ThrowUtils.throwIf(input.mode() == null || !MODES.contains(input.mode()),
                ErrorCode.PARAMS_ERROR, "授权模式无效。");
        ThrowUtils.throwIf(!AgentSandboxPolicy.valid(input.sandboxMode()),
                ErrorCode.PARAMS_ERROR, "沙箱档位无效。");
        return coordinator.withLock(appId, () -> {
            // 1. 应用锁内重读会话与 revision，不用之前的查询替代准入。
            models.requireVisible(ownerId, appId);
            var observed = repository.restore(ownerId, appId, binding(ownerId, loginSessionId), generation);
            ThrowUtils.throwIf(observed.revision() != revision,
                    ErrorCode.CONFLICT_ERROR, "授权设置已变化，请刷新后重试。");
            // 2. 保存成功后才返回新值，不用页面默认值代替数据库设置。
            return view(repository.save(observed, input.mode(), input.sandboxMode()));
        });
    }

    /** 执行侧冻结当前模式；会话失效或未就绪时拒绝，不替旧请求补默认值。 */
    public AgentEditSettingsRepository.Settings freeze(long ownerId, long appId) {
        var current = repository.current(ownerId, appId);
        ThrowUtils.throwIf(!current.serverGeneration().equals(generation)
                        || !current.sessionExpiresAt().isAfter(java.time.Instant.now()),
                ErrorCode.CONFLICT_ERROR, "授权会话已失效，请刷新后重新发送。");
        return current;
    }

    private static SettingsVO view(AgentEditSettingsRepository.Settings value) {
        return new SettingsVO(Long.toString(value.appId()), value.threadId(), value.mode(),
                value.sandboxMode(), Long.toString(value.revision()), value.sessionEpoch().toString(),
                value.sessionExpiresAt());
    }

    private static String binding(long ownerId, String loginSessionId) {
        ThrowUtils.throwIf(loginSessionId == null || loginSessionId.isBlank(),
                ErrorCode.NOT_LOGIN_ERROR, "登录状态已失效。");
        try {
            byte[] value = (ownerId + "\u0000" + loginSessionId).getBytes(StandardCharsets.UTF_8);
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(value));
        } catch (java.security.NoSuchAlgorithmException error) {
            throw new IllegalStateException(error);
        }
    }

    private static long revision(String value) {
        ThrowUtils.throwIf(value == null || !value.matches("0|[1-9][0-9]{0,18}"),
                ErrorCode.PARAMS_ERROR, "授权版本无效。");
        try { return Long.parseLong(value); }
        catch (NumberFormatException error) { throw new MyException(ErrorCode.PARAMS_ERROR, "授权版本无效。"); }
    }
}