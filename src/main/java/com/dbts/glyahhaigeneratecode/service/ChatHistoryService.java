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

import java.time.LocalDateTime;
import java.util.List;

/**
 * 对话历史 服务层。
 *
 * <p>agent_next 起这套接口只负责**存储与查询对话流水**：谁在哪个应用下说了什么。
 *
 * <p>原来的「AI 可见记忆」相关方法（加载记忆状态并注入、摘要压缩、失败轮清理、
 * 轮次收口）已全部移除 —— 上下文构造归 Python 侧的 checkpointer 与压缩机制，
 * Java 不再参与。两套记忆并存只会互相打架。
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

    /** 为已落库的 AI 消息补写动态思考阶段。 */
    boolean updateThinkingSegments(Long messageId, String thinkingSegments);

    /** 正文和阶段在同一事务中保存，避免只有回答而丢失阶段。 */
    Long saveAgentMessage(Long appId, Long userId, String message, String thinkingSegments);

    /**
     * 添加一条带审查字段与 loopId 的对话消息并返回主键 id
     *
     * <p>返回的 id 就是协议里的 {@code roundId}，同时充当落库幂等键。
     *
     * @param appId        应用 id
     * @param message      消息内容
     * @param messageType  消息类型（user/ai/error）
     * @param userId       用户 id
     * @param auditAction  审查动作（ALLOW / REJECT / SKIP）
     * @param auditHitRule 命中的规则编码（如 NONE / SENSITIVE_WORD）
     * @param loopId       Loop ID（nullable）
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
     * 查询某应用全部对话历史（用于导出到本地，仅应用创建者或管理员可调用）
     *
     * @param appId     应用 id
     * @param loginUser 当前登录用户（用于权限校验）
     * @return 按创建时间升序的对话历史 VO 列表
     */
    List<ChatHistoryVO> listAllByAppIdForExport(Long appId, User loginUser);

    /**
     * 统计某应用的对话轮数（用户一问 + AI 一答为一轮，按用户消息条数统计）
     *
     * @param appId     应用 id
     * @param loginUser 当前登录用户（用于权限校验）
     * @return 对话轮数，非负整数
     */
    int countRoundsByAppId(Long appId, User loginUser);
}
