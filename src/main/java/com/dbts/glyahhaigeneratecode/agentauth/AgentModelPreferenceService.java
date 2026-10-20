package com.dbts.glyahhaigeneratecode.agentauth;

import com.dbts.glyahhaigeneratecode.exception.ErrorCode;
import com.dbts.glyahhaigeneratecode.exception.MyException;
import com.dbts.glyahhaigeneratecode.exception.ThrowUtils;
import com.dbts.glyahhaigeneratecode.projectimport.ImportAgentModelClient;
import com.dbts.glyahhaigeneratecode.service.AppService;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

import java.util.List;
import java.util.Objects;

/**
 * 普通应用的模型偏好。
 *
 * <p>只负责"选哪个模型、用什么思考力度"，不承载权限模式或授权票据。
 * 未设置过偏好时返回空值，由执行侧回落到全局配置。
 */
@Service
@RequiredArgsConstructor
public class AgentModelPreferenceService {
    public record PreferenceVO(String profileId, String effort, String revision, boolean configured) { }
    public record PreferenceDTO(String revision, String profileId, String effort) { }
    /** 一次返回当前选择、可选目录与授权模式，前端据此渲染同一个控件。 */
    public record ConfigVO(PreferenceVO preference, List<AgentModelsService.ModelVO> models,
                           String mode, String sandboxMode, String policyRevision) { }

    private final AgentModelPreferenceRepository repository;
    private final ImportAgentModelClient client;
    private final AppService apps;
    private final AgentModelsService modelsService;
    private final AgentEditSettingsService editSettings;

    public PreferenceVO current(long ownerId, long appId) {
        requireOwner(ownerId, appId);
        return view(repository.find(ownerId, appId));
    }

    public ConfigVO config(long ownerId, long appId, String loginSessionId) {
        requireOwner(ownerId, appId);
        // 导入应用有自己的设置接口，这里只提供普通应用的两轴取值；
        // 目录由 AgentModelsService 统一投影，两条链路共用同一份数据。
        var mode = modelsService.isImportApp(appId) ? null : editSettings.current(ownerId, appId, loginSessionId);
        return new ConfigVO(view(repository.find(ownerId, appId)),
                modelsService.catalog(ownerId, appId),
                mode == null ? null : mode.mode(),
                mode == null ? null : mode.sandboxMode(),
                mode == null ? null : mode.policyRevision());
    }

    public PreferenceVO save(long ownerId, long appId, PreferenceDTO input) {
        ThrowUtils.throwIf(input == null || input.profileId() == null || input.profileId().isBlank(),
                ErrorCode.PARAMS_ERROR, "请选择模型。");
        Long expected = revision(input.revision());
        requireOwner(ownerId, appId);
        // 只接受目录里已配置的模型与受支持的力度，不在服务端补默认值。
        AgentModelCatalog.require(AgentModelCatalog.parse(client.catalog()),
                input.profileId(), blankToNull(input.effort()));
        return view(repository.save(ownerId, appId, expected, input.profileId(), blankToNull(input.effort())));
    }

    /** 供请求工厂在应用锁内重读；不校验归属，调用方已完成。 */
    public AgentModelPreferenceRepository.Preference freeze(long ownerId, long appId) {
        return repository.find(ownerId, appId);
    }

    public void requireOwner(long ownerId, long appId) {
        var app = apps.getById(appId);
        ThrowUtils.throwIf(app == null || !Objects.equals(app.getUserId(), ownerId),
                ErrorCode.NOT_FOUND_ERROR, "应用不存在。");
    }

    private static PreferenceVO view(AgentModelPreferenceRepository.Preference value) {
        return value == null ? new PreferenceVO(null, null, null, false)
                : new PreferenceVO(value.profileId(), value.effort(), Long.toString(value.revision()), true);
    }

    private static Long revision(String value) {
        if (value == null || value.isBlank()) return null;
        ThrowUtils.throwIf(!value.matches("0|[1-9][0-9]{0,18}"), ErrorCode.PARAMS_ERROR, "模型设置版本无效。");
        try { return Long.parseLong(value); }
        catch (NumberFormatException error) { throw new MyException(ErrorCode.PARAMS_ERROR, "模型设置版本无效。"); }
    }

    private static String blankToNull(String value) {
        return value == null || value.isBlank() ? null : value;
    }
}