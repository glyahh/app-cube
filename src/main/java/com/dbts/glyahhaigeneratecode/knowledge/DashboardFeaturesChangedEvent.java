package com.dbts.glyahhaigeneratecode.knowledge;

/**
 * 应用的看板功能发生变化时发布。
 *
 * <p>知识模块据此决定要不要为「刚被启用知识图谱」的存量项目补一份知识 ——
 * Plan 明确要求首次启用已有项目时，针对最近一次完成的源码版本生成知识。
 *
 * @param appId    应用 id
 * @param userId   操作人
 * @param features 变更后的功能集合
 * @param knowledgeNewlyEnabled 这次变更是否**新**启用了知识图谱
 */
public record DashboardFeaturesChangedEvent(Long appId, Long userId, java.util.List<String> features,
                                            boolean knowledgeNewlyEnabled) {
}
