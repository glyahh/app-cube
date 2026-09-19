package com.dbts.glyahhaigeneratecode.mapper;

import com.mybatisflex.core.BaseMapper;
import com.dbts.glyahhaigeneratecode.model.Entity.App;

/**
 * 应用 映射层。
 *
 * @author <a href="https://github.com/glyahh">glyahh</a>
 */
public interface AppMapper extends BaseMapper<App> {
    /** 首轮原子占位：关闭偏好或失败后也不在后续轮次重新命名。 */
    @org.apache.ibatis.annotations.Update("""
            UPDATE app SET auto_title_attempted = TRUE
            WHERE id = #{appId} AND user_id = #{userId} AND is_delete = 0
              AND auto_title_attempted = FALSE
              AND EXISTS (SELECT 1 FROM chat_history h WHERE h.id = #{roundId}
                  AND h.app_id = #{appId} AND h.user_id = #{userId} AND h.message_type = 'user')
              AND NOT EXISTS (SELECT 1 FROM chat_history h WHERE h.app_id = #{appId}
                  AND h.message_type = 'user' AND h.id < #{roundId})
            """)
    int claimAutoTitle(@org.apache.ibatis.annotations.Param("appId") Long appId,
                      @org.apache.ibatis.annotations.Param("userId") Long userId,
                      @org.apache.ibatis.annotations.Param("roundId") Long roundId);

    /** 用户手动编辑过标题后，不允许异步结果覆盖。 */
    @org.apache.ibatis.annotations.Update("""
            UPDATE app SET app_name = #{title}, update_time = now()
            WHERE id = #{appId} AND is_delete = 0 AND app_name IS NOT DISTINCT FROM #{original}
              AND edit_time IS NOT DISTINCT FROM #{editTime}
            """)
    int finishAutoTitle(@org.apache.ibatis.annotations.Param("appId") Long appId,
                        @org.apache.ibatis.annotations.Param("original") String original,
                        @org.apache.ibatis.annotations.Param("editTime") java.time.LocalDateTime editTime,
                        @org.apache.ibatis.annotations.Param("title") String title);

    /** CAS：只有第一次降级能把 false 改成 true，并获得用户提示权。 */
    @org.apache.ibatis.annotations.Update("""
            UPDATE app
            SET force_model_direct = TRUE, update_time = now()
            WHERE id = #{appId} AND force_model_direct = FALSE AND is_delete = 0
            """)
    int forceModelDirectIfNeeded(@org.apache.ibatis.annotations.Param("appId") Long appId);
}
