package com.dbts.glyahhaigeneratecode.projectimport;

import java.time.Instant;
import java.util.List;

/** 导入权限入口；所有状态来自服务端，不接受执行 Agent 的策略结论。 */
public interface ImportAgentPermissionService {
    record SettingsVO(String appId, String threadId, String mode, String profileId, String effort,
                      String policyRevision, String sessionEpoch, Instant sessionExpiresAt) { }
    record ModelVO(String id, String name, String model, String provider, List<String> efforts, boolean configured) { }
    record SettingsDTO(String policyRevision, String mode, String profileId, String effort) { }

    SettingsVO settings(long ownerId, long appId, String loginSessionId);
    SettingsVO saveSettings(long ownerId, long appId, String loginSessionId, SettingsDTO input);
    List<ModelVO> models(long ownerId, long appId);
}
