package com.dbts.glyahhaigeneratecode.service.support;

import com.dbts.glyahhaigeneratecode.mapper.AppMapper;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

/**
 * 基于应用主表的存在性检查器。
 * <p>应用删除事务提交后主记录不可见，所有实例都能据此拒绝迟到索引任务。</p>
 */
@Component
@RequiredArgsConstructor
public class DatabaseAppExistenceChecker implements AppExistenceChecker {

    private final AppMapper appMapper;

    /**
     * 对无效 id 直接返回不存在；有效 id 以数据库主键查询结果为准。
     */
    @Override
    public boolean exists(Long appId) {
        return appId != null && appId > 0 && appMapper.selectOneById(appId) != null;
    }
}
