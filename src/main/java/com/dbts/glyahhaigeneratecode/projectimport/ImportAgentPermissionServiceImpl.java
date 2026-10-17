package com.dbts.glyahhaigeneratecode.projectimport;

import com.dbts.glyahhaigeneratecode.agent.AgentRunRequest;
import com.dbts.glyahhaigeneratecode.exception.ErrorCode;
import com.dbts.glyahhaigeneratecode.exception.MyException;
import com.dbts.glyahhaigeneratecode.exception.ThrowUtils;
import com.fasterxml.jackson.databind.JsonNode;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.HexFormat;
import java.util.List;
import java.util.Set;
import java.util.UUID;

@Service
@RequiredArgsConstructor
@Slf4j
public class ImportAgentPermissionServiceImpl implements ImportAgentPermissionService {
    private final ImportWorkspaceGate gate;
    private final ImportWorkspaceCoordinator coordinator;
    private final ImportAgentSettingsRepository settings;
    private final ImportAgentModelClient model;
    private final UUID generation = UUID.randomUUID();

    @Override
    public SettingsVO settings(long ownerId, long appId, String loginSessionId) {
        return coordinator.withLock(appId, () -> {
            requireOwner(ownerId, appId);
            return view(settings.restore(ownerId, appId, binding(ownerId, loginSessionId), generation));
        });
    }

    @Override
    public SettingsVO saveSettings(long ownerId, long appId, String loginSessionId, SettingsDTO input) {
        ThrowUtils.throwIf(input == null, ErrorCode.PARAMS_ERROR, "权限设置不完整。");
        long revision = revision(input.policyRevision());
        ThrowUtils.throwIf(input.mode() == null || !Set.of("MANUAL", "GUARDED_AUTO", "AUTO").contains(input.mode()),
                ErrorCode.PARAMS_ERROR, "权限模式无效。");
        requireProfile(models(ownerId, appId), input.profileId(), input.effort());
        return coordinator.withLock(appId, () -> {
            // 1. 应用锁内重读来源、会话及revision，不用之前的查询替代准入。
            requireOwner(ownerId, appId);
            var observed = settings.restore(ownerId, appId, binding(ownerId, loginSessionId), generation);
            ThrowUtils.throwIf(observed.revision() != revision, ErrorCode.CONFLICT_ERROR, "权限设置已变化，请刷新后重试。");
            // 2. 保存成功后才返回新值；未关闭编辑由SQL和数据库守卫共同拒绝。
            return view(settings.save(observed, input.mode(), input.profileId(), input.effort()));
        });
    }

    @Override
    public List<ModelVO> models(long ownerId, long appId) {
        requireOwner(ownerId, appId);
        return projectModels(model.catalog());
    }

    /** 调用者已持有应用锁；冻结当前配置，不替旧请求补默认模式或模型。 */
    public ImportAgentSettingsRepository.Settings freeze(AgentRunRequest request) {
        long owner = Long.parseLong(request.getUserId()), app = Long.parseLong(request.getAppId());
        requireOwner(owner, app);
        var current = settings.current(owner, app);
        ThrowUtils.throwIf(current.revision() != revision(request.getImportPolicyRevision())
                        || !current.sessionEpoch().toString().equals(request.getImportSessionEpoch())
                        || !current.serverGeneration().equals(generation) || !current.sessionExpiresAt().isAfter(Instant.now()),
                ErrorCode.CONFLICT_ERROR, "权限设置或会话已失效，请刷新后重新发送。");
        requireProfile(models(owner, app), current.profileId(), current.effort());
        return current;
    }

    /** 决定接口只核对现有权限会话，不通过恢复悄悄续期。 */
    public ImportAgentSettingsRepository.Settings requireSession(long ownerId,long appId,String loginSessionId) {
        requireOwner(ownerId,appId);
        var current=settings.current(ownerId,appId);
        ThrowUtils.throwIf(!current.sessionBinding().equals(binding(ownerId,loginSessionId))
                        || !current.serverGeneration().equals(generation) || !current.sessionExpiresAt().isAfter(Instant.now()),
                ErrorCode.CONFLICT_ERROR,"权限会话已失效，请刷新后重试。");
        return current;
    }

    public void requireOwner(long ownerId, long appId) {
        var publication = gate.requireAgentEditAllowed(appId);
        ThrowUtils.throwIf(publication == null || publication.ownerId() != ownerId,
                ErrorCode.NOT_FOUND_ERROR, "导入应用不存在。");
    }

    static List<ModelVO> projectModels(JsonNode directory) {
        ThrowUtils.throwIf(directory == null || !directory.isObject() || directory.size() != 1
                        || !directory.path("profiles").isArray() || directory.path("profiles").size() > 32,
                ErrorCode.OPERATION_ERROR, "模型目录无效。");
        var result = new ArrayList<ModelVO>();
        var ids = new HashSet<String>();
        for (var profile : directory.path("profiles")) {
            String id = profile.path("id").asText();
            ThrowUtils.throwIf(!id.matches("[a-zA-Z0-9_-]{1,64}") || !ids.add(id)
                            || !profile.path("name").isTextual() || profile.path("name").asText().isBlank()
                            || profile.path("name").asText().length() > 120 || !profile.path("provider").isTextual()
                            || profile.path("provider").asText().length() > 120 || !profile.path("configured").isBoolean()
                            || !profile.path("efforts").isArray(), ErrorCode.OPERATION_ERROR, "模型目录无效。");
            var efforts = new ArrayList<String>();
            for (var effort : profile.path("efforts")) {
                ThrowUtils.throwIf(!effort.isTextual() || !Set.of("low", "medium", "high").contains(effort.asText())
                                || efforts.contains(effort.asText()), ErrorCode.OPERATION_ERROR, "模型力度配置无效。");
                efforts.add(effort.asText());
            }
            result.add(new ModelVO(id, profile.path("name").asText(), profile.path("provider").asText(),
                    List.copyOf(efforts), profile.path("configured").asBoolean()));
        }
        ThrowUtils.throwIf(result.stream().noneMatch(ModelVO::configured), ErrorCode.OPERATION_ERROR, "没有可用模型，请检查本地配置。");
        return List.copyOf(result);
    }

    static void requireProfile(List<ModelVO> profiles, String id, String effort) {
        var selected = profiles.stream().filter(profile -> profile.id().equals(id) && profile.configured()).findFirst();
        ThrowUtils.throwIf(selected.isEmpty(), ErrorCode.PARAMS_ERROR, "所选模型未配置或已失效。");
        ThrowUtils.throwIf(effort != null && !selected.orElseThrow().efforts().contains(effort),
                ErrorCode.PARAMS_ERROR, "所选模型不支持此思考力度。");
    }

    static long revision(String value) {
        ThrowUtils.throwIf(value == null || !value.matches("0|[1-9][0-9]{0,18}"), ErrorCode.PARAMS_ERROR, "权限版本无效。");
        try { return Long.parseLong(value); }
        catch (NumberFormatException error) { throw new MyException(ErrorCode.PARAMS_ERROR, "权限版本无效。"); }
    }

    private static String binding(long ownerId, String loginSessionId) {
        ThrowUtils.throwIf(loginSessionId == null || loginSessionId.isBlank(), ErrorCode.NOT_LOGIN_ERROR, "登录状态已失效。");
        try {
            byte[] value = (ownerId + "\u0000" + loginSessionId).getBytes(StandardCharsets.UTF_8);
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(value));
        } catch (java.security.NoSuchAlgorithmException error) { throw new IllegalStateException(error); }
    }

    private static SettingsVO view(ImportAgentSettingsRepository.Settings value) {
        return new SettingsVO(Long.toString(value.appId()), value.threadId(), value.mode(), value.profileId(), value.effort(),
                Long.toString(value.revision()), value.sessionEpoch().toString(), value.sessionExpiresAt());
    }
}
