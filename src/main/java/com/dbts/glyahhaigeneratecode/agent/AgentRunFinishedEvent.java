package com.dbts.glyahhaigeneratecode.agent;

/**
 * 一轮生成结束时发布。
 *
 * <p>用事件而不是在 {@link AgentRunPersistence} 里直接调知识模块：agent 包是执行层，
 * 不该反过来依赖业务模块。谁关心生成结束谁自己订阅。
 *
 * @param appId  应用 id
 * @param userId 发起人
 * @param runId  本轮 run id
 * @param status 终态：SUCCEEDED / FAILED / CANCELLED
 */
public record AgentRunFinishedEvent(Long appId, Long userId, String runId, String status) {

    public boolean succeeded() {
        return "SUCCEEDED".equals(status);
    }
}
