package com.dbts.glyahhaigeneratecode.service;

import com.dbts.glyahhaigeneratecode.model.DTO.ChatHistoryQueryRequest;
import com.dbts.glyahhaigeneratecode.model.Entity.ChatHistory;
import com.dbts.glyahhaigeneratecode.model.Entity.User;
import com.dbts.glyahhaigeneratecode.model.VO.UserChatHistoryItemVO;
import com.dbts.glyahhaigeneratecode.model.VO.AppChatHistoryPageVO;
import com.dbts.glyahhaigeneratecode.model.VO.ChatHistoryVO;
import com.mybatisflex.core.paginate.Page;
import com.mybatisflex.core.query.QueryWrapper;
import com.mybatisflex.core.service.IService;
import dev.langchain4j.memory.chat.MessageWindowChatMemory;
import com.dbts.glyahhaigeneratecode.model.enums.CodeGenTypeEnum;

import java.time.LocalDateTime;
import java.util.List;

/**
 * 对话历史 服务层。
 *
 * @author <a href="https://github.com/glyahh">glyahh</a>
 */
public interface ChatHistoryService extends IService<ChatHistory> {

    /**
     * 添加一条对话消息
     *
     * @param appId       应用 id
     * @param message     消息内容
     * @param messageType 消息类型（user/ai/error）
     * @param userId      用户 id
     * @return 是否保存成功
     */
    boolean addChatMessage(Long appId, String message, String messageType, Long userId);

    /**
     * 添加一条对话消息并返回主键 id（roundId 来源）
     *
     * @param appId       应用 id
     * @param message     消息内容
     * @param messageType 消息类型（user/ai/error）
     * @param userId      用户 id
     * @return 保存后的消息主键 id，失败返回 null
     */
    Long addChatMessageAndReturnId(Long appId, String message, String messageType, Long userId);

    /**
     * 添加一条带审查字段的对话消息
     *
     * @param appId        应用 id
     * @param message      消息内容
     * @param messageType  消息类型（user/ai/error）
     * @param userId       用户 id
     * @param auditAction  审查动作（ALLOW / REJECT / SKIP）
     * @param auditHitRule 命中的规则编码（如 NONE / SENSITIVE_WORD）
     * @return 是否保存成功
     */
    boolean addChatMessage(Long appId, String message, String messageType, Long userId, String auditAction, String auditHitRule);

    /**
     * 添加一条带审查字段的对话消息并返回主键 id（roundId 来源）
     *
     * @param appId        应用 id
     * @param message      消息内容
     * @param messageType  消息类型（user/ai/error）
     * @param userId       用户 id
     * @param auditAction  审查动作（ALLOW / REJECT / SKIP）
     * @param auditHitRule 命中的规则编码（如 NONE / SENSITIVE_WORD）
     * @return 保存后的消息主键 id，失败返回 null
     */
    Long addChatMessageAndReturnId(Long appId, String message, String messageType, Long userId, String auditAction, String auditHitRule);

    /**
     * 添加一条带审查字段与 loopId 的对话消息并返回主键 id（roundId 来源）
     *
     * @param appId        应用 id
     * @param message      消息内容
     * @param messageType  消息类型（user/ai/error）
     * @param userId       用户 id
     * @param auditAction  审查动作（ALLOW / REJECT / SKIP）
     * @param auditHitRule 命中的规则编码（如 NONE / SENSITIVE_WORD）
     * @param loopId       Loop ID（本轮注入的 Loop 快照，nullable）
     * @return 保存后的消息主键 id，失败返回 null
     */
    Long addChatMessageAndReturnId(Long appId, String message, String messageType, Long userId,
                                   String auditAction, String auditHitRule, Long loopId);

    /**
     * 分页查询某个应用的对话历史（基于时间游标，向前加载）
     *
     * @param appId          应用 id
     * @param pageSize       每次加载条数
     * @param lastCreateTime 游标时间（传 null 加载最新的，传具体时间加载早于该时间的消息）
     * @param loginUser      当前登录用户（用于权限校验）
     * @return 分页结果
     */
    AppChatHistoryPageVO listAppChatHistoryByPage(Long appId, int pageSize, LocalDateTime lastCreateTime, User loginUser);

    /**
     * 【用户】分页查询当前登录用户的对话历史（支持 messageType/appId 筛选，按时间降序）
     *
     * @param queryRequest 查询请求（忽略其中的 userId）
     * @param loginUser    当前登录用户
     * @return 分页结果（带应用名称）
     */
    Page<UserChatHistoryItemVO> listMyChatHistoryByPage(ChatHistoryQueryRequest queryRequest, User loginUser);

    /**
     * 根据应用 id 删除所有对话历史（逻辑删除）
     *
     * @param appId 应用 id
     * @return 是否删除成功
     */
    boolean removeByAppId(Long appId);

    /**
     * 构建 QueryWrapper
     *
     * @param queryRequest 查询请求
     * @return QueryWrapper
     */
    QueryWrapper buildQueryWrapper(ChatHistoryQueryRequest queryRequest);

    /**
     * 将实体转换为 VO
     *
     * @param chatHistory 对话历史实体
     * @return 对话历史 VO
     */
    ChatHistoryVO getChatHistoryVO(ChatHistory chatHistory);

    /**
     * 批量将实体转换为 VO
     *
     * @param chatHistoryList 对话历史实体列表
     * @return 对话历史 VO 列表
     */
    List<ChatHistoryVO> getChatHistoryVOList(List<ChatHistory> chatHistoryList);

    /**
     * 加载会话 memory_state 并按需注入文件内容到 Redis ChatMemory
     *
     * @param appId                   应用 id
     * @param messageWindowChatMemory 聊天内存
     * @param maxCount                最大历史条数
     * @param codeGenTypeEnum         代码生成类型
     * @return 注入后的内存消息条数
     */
    int loadConversationMemoryStateAndInject(Long appId, MessageWindowChatMemory messageWindowChatMemory, int maxCount, CodeGenTypeEnum codeGenTypeEnum);

    /**
     * 当前 AI 可见记忆版本。用于缓存 service 命中时判断 JVM 内存窗口是否仍可复用。
     *
     * @param appId 应用 id
     * @return 版本标识
     */
    String getAiVisibleMemoryVersion(Long appId);

    /**
     * 标记 AI 可见记忆发生变化。仅用于无法自然落到 conversation_memory_state 的 Redis/回滚类变更。
     *
     * @param appId 应用 id
     * @param reason 变更来源
     */
    void markAiVisibleMemoryChanged(Long appId, String reason);

    /**
     * 查询某应用全部对话历史（用于导出到本地，仅应用创建者或管理员可调用）
     *
     * @param appId     应用 id
     * @param loginUser 当前登录用户（用于权限校验）
     * @return 按创建时间升序的对话历史 VO 列表
     */
    List<ChatHistoryVO> listAllByAppIdForExport(Long appId, User loginUser);

    /**
     * 统计某应用的对话轮数（用户一问 + AI 一答为一轮，按用户消息条数统计）
     * 仅应用创建者或管理员可调用。
     *
     * @param appId     应用 id
     * @param loginUser 当前登录用户（用于权限校验）
     * @return 对话轮数，非负整数
     */
    int countRoundsByAppId(Long appId, User loginUser);

    /**
     * 内部统计：按 appId 统计 DB 中 USER 消息条数（不做权限校验）。
     * 供生成链路判定首轮使用，与 {@link #countRoundsByAppId(Long, User)} 的 DB 统计口径一致。
     *
     * @param appId 应用 id
     * @return 非负整数
     */
    int countUserRoundsInternal(Long appId);

    /**
     * 判定当前是否为「首轮生成」（roundsBefore == 0）。
     *
     * @param appId                        应用 id
     * @param currentUserMessagePersisted  当前这轮用户消息是否已写入 DB（对话入口在入库后调用 facade 时应为 true）
     * @return true 表示首轮
     */
    default boolean isFirstRound(Long appId, boolean currentUserMessagePersisted) {
        if (appId == null || appId <= 0) return true;
        int count = countUserRoundsInternal(appId);
        int roundsBefore = currentUserMessagePersisted ? Math.max(0, count - 1) : count;
        return roundsBefore == 0;
    }

    /**
     * 当 DB 中用户轮数超过 {@link com.dbts.glyahhaigeneratecode.constant.ChatHistoryConstant#MAX_ROUNDS_BEFORE_SUMMARY} 时，
     * 将最早两轮（4 条）用 AI 总结为 1 轮摘要：逻辑删除原文、插入 2 条带 audit 的摘要行，并在全部合并完成后按 DB 重建 Redis ChatMemory。
     * 由对话入口或流式完成后调用，无需权限校验（内部按 appId 操作）。
     *
     * @param appId  应用 id
     * @param userId 用户 id（用于写入总结记录的 userId）
     */
    default boolean trySummarizeOldestRoundsIfNeeded(Long appId, Long userId) {
        return trySummarizeOldestRoundsIfNeeded(appId, userId, "unknown");
    }

    /**
     * 带触发来源标记的会话级总结压缩，便于线上定位由谁触发。
     *
     * @param appId         应用 id
     * @param userId        用户 id
     * @param triggerReason 触发来源（如 entry_normal / entry_workflow）
     * @return 是否实际执行了压缩（true=有合并操作）
     */
    boolean trySummarizeOldestRoundsIfNeeded(Long appId, Long userId, String triggerReason);

    /**
     * 刷新 AI ChatMemory Redis TTL（memoryId=appId）。
     *
     * @param appId 应用 id
     */
    void refreshAiChatMemoryTtl(Long appId);

    /**
     * 工作流重试前，定向清理 Redis ChatMemory 里上一轮失败生成的 AI 长消息（仅删失败产物，不清空整段会话）。
     *
     * @param appId           应用 id
     * @param codeGenTypeEnum 代码生成类型
     * @return 是否执行了删除
     */
    default boolean removeLatestFailedAiMessageForRetry(Long appId, CodeGenTypeEnum codeGenTypeEnum) {
        return removeLatestFailedAiMessageForRetry(appId, codeGenTypeEnum, "unknown");
    }

    /**
     * 带触发来源标记的失败轮无效产物清理。
     *
     * @param appId           应用 id
     * @param codeGenTypeEnum 代码生成类型
     * @param triggerReason   触发来源（如 workflow_retry / workflow_retry_exhausted）
     * @return 是否执行了删除
     */
    boolean removeLatestFailedAiMessageForRetry(Long appId, CodeGenTypeEnum codeGenTypeEnum, String triggerReason);

    /**
     * 按 (appId, userId, message, type=USER) 删除最新一条用户消息，用于异常回滚。
     *
     * @param appId   应用 id
     * @param userId  用户 id
     * @param message 用户消息原文
     * @return 是否删除成功
     */
    boolean removeUserMessageByContent(Long appId, Long userId, String message);

    /**
     * 对话轮次结束后的收口：快照、差异、摘要状态、ref 归档、缓存回填与 RAG 增量索引。
     *
     * @param appId           应用 id
     * @param roundId         本轮 roundId（即用户消息 chat_history.id）
     * @param userId          用户 id
     * @param codeGenTypeEnum 代码生成类型
     * @param workflowMode    是否 workflow 模式
     * @param bufferChars     本轮输出字符数（无可观测数据时传 0）
     * @param elapsedMs       本轮耗时毫秒（无可观测数据时传 0）
     * @return 无
     */
    void onRoundCompleted(Long appId, Long roundId, Long userId, CodeGenTypeEnum codeGenTypeEnum,
                          boolean workflowMode, int bufferChars, long elapsedMs);

    /**
     * workflow 入口是否应触发会话级总结压缩。
     * 仅在“非首轮 + 上一轮 workflow 成功”场景返回 true。
     *
     * @param appId 应用 id
     * @return 是否触发会话级总结
     */
    boolean shouldSummarizeBeforeWorkflowGeneration(Long appId);
}
