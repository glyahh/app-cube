package com.dbts.glyahhaigeneratecode.mapper;

import com.dbts.glyahhaigeneratecode.model.Entity.AgentRun;
import com.mybatisflex.core.BaseMapper;

/**
 * Agent 执行台账 映射层。
 *
 * @author <a href="https://github.com/glyahh">glyahh</a>
 */
public interface AgentRunMapper extends BaseMapper<AgentRun> {
    /** String 参数必须显式 CAST，PostgreSQL 不会把 varchar 隐式转成 jsonb。 */
    @org.apache.ibatis.annotations.Update("""
            UPDATE agent_run
            SET usage_json = COALESCE(usage_json, '{}'::jsonb) || CAST(#{usageJson} AS jsonb),
                update_time = now()
            WHERE id = #{runId} AND is_delete = 0
            """)
    int mergeUsageJson(@org.apache.ibatis.annotations.Param("runId") Long runId,
                       @org.apache.ibatis.annotations.Param("usageJson") String usageJson);
}
