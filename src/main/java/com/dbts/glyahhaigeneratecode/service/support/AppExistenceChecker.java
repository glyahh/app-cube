package com.dbts.glyahhaigeneratecode.service.support;

/**
 * 应用存在性检查边界。
 * <p>
 * RAG 索引通过该接口判断异步任务对应的应用是否仍存在。生产实现必须查询持久化真相源，不能使用
 * 进程内 tombstone，否则进程重启或多实例部署会允许迟到任务重新写入索引。
 * </p>
 */
@FunctionalInterface
public interface AppExistenceChecker {

    /**
     * @param appId 应用标识
     * @return 应用主记录当前是否存在
     */
    boolean exists(Long appId);
}
