package com.dbts.glyahhaigeneratecode.service.impl;

import cn.hutool.core.bean.BeanUtil;
import cn.hutool.core.util.StrUtil;
import com.dbts.glyahhaigeneratecode.exception.ErrorCode;
import com.dbts.glyahhaigeneratecode.exception.MyException;
import com.dbts.glyahhaigeneratecode.exception.ThrowUtils;
import com.dbts.glyahhaigeneratecode.mapper.ChatHistoryMapper;
import com.dbts.glyahhaigeneratecode.model.DTO.ChatHistoryQueryRequest;
import com.dbts.glyahhaigeneratecode.model.Entity.App;
import com.dbts.glyahhaigeneratecode.model.Entity.ChatHistory;
import com.dbts.glyahhaigeneratecode.model.Entity.User;
import com.dbts.glyahhaigeneratecode.model.VO.AppChatHistoryPageVO;
import com.dbts.glyahhaigeneratecode.model.VO.ChatHistoryVO;
import com.dbts.glyahhaigeneratecode.model.VO.UserChatHistoryItemVO;
import com.dbts.glyahhaigeneratecode.model.enums.ChatHistoryMessageTypeEnum;
import com.dbts.glyahhaigeneratecode.constant.UserConstant;
import com.dbts.glyahhaigeneratecode.service.AppService;
import com.dbts.glyahhaigeneratecode.service.ChatHistoryService;
import com.mybatisflex.core.paginate.Page;
import com.mybatisflex.core.query.QueryWrapper;
import com.mybatisflex.core.query.RawQueryCondition;
import com.mybatisflex.spring.service.impl.ServiceImpl;
import jakarta.annotation.Resource;
import lombok.extern.slf4j.Slf4j;
import org.springframework.context.annotation.Lazy;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * 对话历史 服务层实现。
 *
 * <p>agent_next 起这套实现只负责**存储与查询对话流水**：谁在哪个应用下说了什么。
 *
 * <p>原来的「AI 可见记忆」整块（从 DB 重建 Redis ChatMemory、摘要压缩、
 * 失败轮清理、轮次收口同步快照与 RAG 增量索引）已移除。上下文构造现在归
 * Python 侧的 LangGraph checkpointer 与四层压缩机制 —— Java 不再参与，
 * 两套记忆并存只会互相打架，而且是 V4 复杂度的根源。
 *
 * <p>主链路：USER 行落库并返回 roundId -> 该 roundId 进协议下发给 Python ->
 * 终态时落一条 AI 行。其余是分页查询、导出与轮次统计。
 *
 * @author <a href="https://github.com/glyahh">glyahh</a>
 */
@Service
@Slf4j
public class ChatHistoryServiceImpl extends ServiceImpl<ChatHistoryMapper, ChatHistory> implements ChatHistoryService {

    /**
     * 应用服务。用 {@code @Lazy} 断开循环依赖：
     * AppServiceImpl 需要本类做删除应用的级联清理，本类又需要它校验应用存在。
     */
    @Resource
    @Lazy
    private AppService appService;

    /**
     * 添加一条对话消息并返回是否写入成功（默认审查占位 SKIP/NONE）
     *
     * @param appId       应用 id
     * @param message     消息内容
     * @param messageType 消息类型
     * @param userId      用户 id
     * @return 落库成功为 true，失败为 false
     */
    @Override
    public boolean addChatMessage(Long appId, String message, String messageType, Long userId) {
        // 方法大纲：
        // 1. 委托带主键返回的写库重载，以雪花 id 是否生成为成功标志    L113-L114

        // 1. 委托可返回主键的写库方法,根据雪花 id 是否生成得到本次写入是否成功
        return addChatMessageAndReturnId(appId, message, messageType, userId) != null;
    }

    /**
     * 添加一条对话消息并返回主键 id。
     *
     * @param appId 应用 id
     * @param message 消息内容
     * @param messageType 消息类型
     * @param userId 用户 id
     * @return 保存后的主键 id；失败返回 null
     */
    public Long addChatMessageAndReturnId(Long appId, String message, String messageType, Long userId) {
        // 方法大纲：
        // 1. 使用默认审查占位 SKIP/NONE，委托带审计字段的完整写库重载    L131-L132

        // 使用默认审计占位 SKIP/NONE,将实际写库委托给带审计参数的重载方法（loopId 传 null）
        return addChatMessageAndReturnId(appId, message, messageType, userId, "SKIP", "NONE", null);
    }

    /**
     * 添加一条带审查字段的对话消息并返回是否写入成功
     *
     * @param appId        应用 id
     * @param message      消息内容
     * @param messageType  消息类型
     * @param userId       用户 id
     * @param auditAction  审查动作
     * @param auditHitRule 命中规则
     * @return 落库成功为 true，失败为 false
     */
    public boolean addChatMessage(Long appId, String message, String messageType, Long userId, String auditAction, String auditHitRule) {
        // 方法大纲：
        // 1. 委托带主键返回的写库重载，以雪花 id 是否生成为成功标志    L151-L152

        // 委托带审查字段的写库方法,根据主键是否生成得到布尔成功标志
        return addChatMessageAndReturnId(appId, message, messageType, userId, auditAction, auditHitRule) != null;
    }

    /**
     * 添加一条带审查字段的对话消息并返回主键 id（loopId 传 null 兼容旧调用方）。
     *
     * @param appId 应用 id
     * @param message 消息内容
     * @param messageType 消息类型
     * @param userId 用户 id
     * @param auditAction 审查动作
     * @param auditHitRule 命中规则
     * @return 保存后的主键 id；失败返回 null
     */
    public Long addChatMessageAndReturnId(Long appId, String message, String messageType, Long userId,
                                          String auditAction, String auditHitRule) {
        return addChatMessageAndReturnId(appId, message, messageType, userId, auditAction, auditHitRule, null);
    }


    /**
     * 添加一条带审查字段的对话消息并返回主键 id。
     *
     * @param appId 应用 id
     * @param message 消息内容
     * @param messageType 消息类型
     * @param userId 用户 id
     * @param auditAction 审查动作
     * @param auditHitRule 命中规则
     * @param loopId Loop ID（nullable）
     * @return 保存后的主键 id；失败返回 null
     */
    @Override
    public Long addChatMessageAndReturnId(Long appId, String message, String messageType, Long userId,
                                          String auditAction, String auditHitRule, Long loopId) {
        // 方法大纲：
        // 1. 校验 appId、消息内容、类型、用户与审查字段合法性
        // 2. 组装实体并落库，主键由雪花算法生成
        // 3. 返回主键，调用方拿它当 roundId 与落库幂等键

        // 1. 校验 appId、消息内容、类型、用户与审查字段合法性
        ThrowUtils.throwIf(appId == null || appId <= 0, ErrorCode.PARAMS_ERROR, "应用ID不能为空");
        ThrowUtils.throwIf(StrUtil.isBlank(message), ErrorCode.PARAMS_ERROR, "消息内容不能为空");
        ThrowUtils.throwIf(StrUtil.isBlank(messageType), ErrorCode.PARAMS_ERROR, "消息类型不能为空");
        ThrowUtils.throwIf(userId == null || userId <= 0, ErrorCode.PARAMS_ERROR, "用户ID不能为空");
        ThrowUtils.throwIf(StrUtil.isBlank(auditAction), ErrorCode.PARAMS_ERROR, "审查动作不能为空");
        ThrowUtils.throwIf(StrUtil.isBlank(auditHitRule), ErrorCode.PARAMS_ERROR, "命中规则不能为空");
        // 验证消息类型是否有效
        ChatHistoryMessageTypeEnum messageTypeEnum = ChatHistoryMessageTypeEnum.getEnumByValue(messageType);
        ThrowUtils.throwIf(messageTypeEnum == null, ErrorCode.PARAMS_ERROR, "不支持的消息类型: " + messageType);
        // 2. 组装实体并落库
        ChatHistory chatHistory = ChatHistory.builder()
                .appId(appId)
                .message(message)
                .messageType(messageType)
                .userId(userId)
                .auditAction(auditAction)
                .auditHitRule(auditHitRule)
                .loopId(loopId)
                .build();
        // 1. 先落库，再从实体回填的雪花 id 作为 roundId 来源。
        boolean id = this.save(chatHistory);
        if (!id || chatHistory.getId() == null || chatHistory.getId() <= 0) {
            return null;
        }
        // 3. 返回雪花主键，调用方拿它当 roundId
        return chatHistory.getId();
    }



    /**
     * 分页查询指定应用的对话历史（游标分页，优先 echo 缓存）
     *
     * @param appId          应用 id
     * @param pageSize       每页条数（1-50）
     * @param lastCreateTime 游标：仅返回 createTime 小于等于该时间的记录；首页为 null
     * @param loginUser      当前登录用户
     * @return 带 beta 标记的分页 VO
     */
    @Override
    public AppChatHistoryPageVO listAppChatHistoryByPage(Long appId, int pageSize,
                                                      LocalDateTime lastCreateTime,
                                                      User loginUser) {
        // 方法大纲：
        // 1. 校验 appId、分页大小与登录态    L228-L230
        // 2. 校验应用存在性与创建者/管理员权限    L232-L238
        // 3. 从 echo 或 DB 加载分页并补 workflow 阶段状态后组装 VO    L240-L243

        // 1. 校验应用 id、分页大小与登录态,得到可继续鉴权与查库的前置条件
        ThrowUtils.throwIf(appId == null || appId <= 0, ErrorCode.PARAMS_ERROR, "应用ID不能为空");
        ThrowUtils.throwIf(pageSize <= 0 || pageSize > 50, ErrorCode.PARAMS_ERROR, "页面大小必须在1-50之间");
        ThrowUtils.throwIf(loginUser == null, ErrorCode.NOT_LOGIN_ERROR);

        // 2. 校验应用存在性与创建者/管理员权限
        // 验证权限：只有应用创建者和管理员可以查看
        App app = appService.getById(appId);
        ThrowUtils.throwIf(app == null, ErrorCode.NOT_FOUND_ERROR, "应用不存在");
        boolean isAdmin = UserConstant.ADMIN_ROLE.equals(loginUser.getUserRole());
        boolean isCreator = app.getUserId().equals(loginUser.getId());
        ThrowUtils.throwIf(!isAdmin && !isCreator, ErrorCode.NO_AUTH_ERROR, "无权查看该应用的对话历史");

        // 3. 从 echo 或 DB 加载分页并补 workflow 阶段状态后组装 VO
        Page<ChatHistory> page = loadAppChatHistoryPageFromEchoOrDb(appId, pageSize, lastCreateTime);

        return AppChatHistoryPageVO.from(page, app.getIsBeta());
    }

    /**
     * 从 chat_history 加载全量并在内存里按游标分页。
     *
     * @param appId          应用 id
     * @param pageSize       每页条数
     * @param lastCreateTime 游标：仅返回 createTime 小于等于该时间的记录；首页为 null
     * @return 内存分页结果，records 为当前页、totalRow 为全文条数
     */
    private Page<ChatHistory> loadAppChatHistoryPageFromEchoOrDb(Long appId, int pageSize, LocalDateTime lastCreateTime) {
        // 方法大纲：
        // 1. 尝试 echo_memory 命中全文列表，miss 时从 DB 升序拉全量并回填    L262-L278
        // 2. 按 lastCreateTime 过滤并在内存中倒序分页    L279-L305
        // 3. 组装 Page 对象（records 为当前页、totalRow 为全文条数）    L306-L310

        // 1. 直接从 DB 读该应用全量。
        // 原来这里套了一层 Redis echo 缓存，随 V4 记忆一起移除了 ——
        // 分页本来就走 (app_id, create_time) 索引，缓存换来的复杂度不值得。
        QueryWrapper allQ = new QueryWrapper();
        allQ.eq(ChatHistory::getAppId, appId);
        allQ.orderBy(ChatHistory::getCreateTime, true);
        List<ChatHistory> fullList = this.list(allQ);
        if (fullList == null) {
            fullList = Collections.emptyList();
        }
        // 2. 按 lastCreateTime 过滤并在内存中倒序分页
        List<ChatHistory> filtered = fullList.stream()
                .filter(row -> lastCreateTime == null
                        || row.getCreateTime() == null
                        || !row.getCreateTime().isAfter(lastCreateTime))
                .sorted((a, b) -> {
                    if (a.getCreateTime() == null && b.getCreateTime() == null) {
                        return Long.compare(
                                b.getId() == null ? 0L : b.getId(),
                                a.getId() == null ? 0L : a.getId());
                    }
                    if (a.getCreateTime() == null) {
                        return 1;
                    }
                    if (b.getCreateTime() == null) {
                        return -1;
                    }
                    int cmp = b.getCreateTime().compareTo(a.getCreateTime());
                    if (cmp != 0) {
                        return cmp;
                    }
                    return Long.compare(
                            b.getId() == null ? 0L : b.getId(),
                            a.getId() == null ? 0L : a.getId());
                })
                .limit(pageSize)
                .toList();
        // 3. 组装 Page 对象（records 为当前页、totalRow 为全文条数）
        Page<ChatHistory> page = new Page<>(1, pageSize);
        page.setRecords(new ArrayList<>(filtered));
        page.setTotalRow(fullList.size());
        return page;
    }


    /**
     * 分页查询当前登录用户自己的对话历史，并批量补全应用名称
     *
     * @param queryRequest 查询条件（pageNum/pageSize/messageType/appId 等）
     * @param loginUser    当前登录用户
     * @return 带 appName 的用户对话历史分页 VO
     */
    @Override
    public Page<UserChatHistoryItemVO> listMyChatHistoryByPage(ChatHistoryQueryRequest queryRequest, User loginUser) {
        // 方法大纲：
        // 1. 校验查询参数与登录态    L325-L327
        // 2. 构造安全查询对象并强制写入当前用户 id    L329-L338
        // 3. 分页查库并批量补全 appName    L340-L374
        // 4. 实体转 VO 并组装分页结果返回    L376-L379

        // 1. 校验查询参数和登录态，确保只在合法请求下继续执行分页查询。
        ThrowUtils.throwIf(queryRequest == null, ErrorCode.PARAMS_ERROR, "参数不能为空");
        ThrowUtils.throwIf(loginUser == null, ErrorCode.NOT_LOGIN_ERROR);

        // 2. 读取分页参数并校验分页大小，得到后续数据库分页查询所需的页码与容量。
        int pageNum = queryRequest.getPageNum();
        int pageSize = queryRequest.getPageSize();
        ThrowUtils.throwIf(pageSize <= 0, ErrorCode.PARAMS_ERROR, "pageSize 必须大于 0");

        // 3. 基于原始请求构造安全查询对象，并强制写入当前登录用户ID，避免越权查询他人数据。
        ChatHistoryQueryRequest safeRequest = new ChatHistoryQueryRequest();
        safeRequest.setPageNum(pageNum);
        safeRequest.setPageSize(pageSize);
        safeRequest.setMessageType(queryRequest.getMessageType());
        safeRequest.setAppId(queryRequest.getAppId());
        safeRequest.setAppName(queryRequest.getAppName());
        safeRequest.setUserId(loginUser.getId());

        // 3. 分页查库并批量补全 appName
        // 4. 生成查询条件并执行分页查询，得到当前页的对话历史实体数据。
        QueryWrapper queryWrapper = buildQueryWrapper(safeRequest);
        Page<ChatHistory> page = this.page(Page.of(pageNum, pageSize), queryWrapper);

        // 5. 提取分页记录并收集其中涉及的应用ID集合，用于批量补全应用名称。
        List<ChatHistory> records = page.getRecords() == null ? Collections.emptyList() : page.getRecords();
        Set<Long> appIds = records.stream()
                .map(ChatHistory::getAppId)
                .filter(Objects::nonNull)
                .collect(Collectors.toSet());

        // 6. 批量查询应用信息并构建 appId -> appName 映射，减少逐条查库带来的性能损耗。
        Map<Long, String> appNameMap = new HashMap<>();
        if (!appIds.isEmpty()) {
            List<App> apps = appService.listByIds(appIds);
            if (apps != null && !apps.isEmpty()) {
                for (App app : apps) {
                    if (app == null || app.getId() == null) {
                        continue;
                    }
                    appNameMap.putIfAbsent(app.getId(), app.getAppName());
                }
            }
        }

        // 7. 将数据库实体转换为前端展示VO，补齐应用名称并设置默认占位值。
        List<UserChatHistoryItemVO> voList = records.stream().map(item -> {
            UserChatHistoryItemVO vo = new UserChatHistoryItemVO();
            vo.setId(item.getId());
            vo.setMessage(item.getMessage());
            vo.setMessageType(item.getMessageType());
            vo.setAppId(item.getAppId());
            vo.setCreateTime(item.getCreateTime());
            vo.setUpdateTime(item.getUpdateTime());
            vo.setAppName(appNameMap.getOrDefault(item.getAppId(), "-"));
            return vo;
        }).toList();

        // 4. 实体转 VO 并组装分页结果返回
        // 8. 组装VO分页对象并返回，保持总条数与分页参数和原始查询结果一致。
        Page<UserChatHistoryItemVO> voPage = new Page<>(pageNum, pageSize, page.getTotalRow());
        voPage.setRecords(voList);
        return voPage;
    }

    /**
     * 按应用 id 删除该应用下的全部对话历史
     *
     * @param appId 应用 id
     * @return 删除成功为 true；非法 appId 返回 false
     */
    @Override
    public boolean removeByAppId(Long appId) {
        // 方法大纲：
        // 1. 非法 appId 直接返回 false（幂等）    L398-L401
        // 2. 构造按 appId 匹配的删除条件并执行 remove    L402-L406

        // 1. 非法 appId 直接视为无需删除,得到幂等 false 结果
        if (appId == null || appId <= 0) {
            return false;
        }
        // 2. 构造按 appId 精确匹配的删除条件,得到一次性清理该应用全部历史的语义
        QueryWrapper queryWrapper = new QueryWrapper();
        queryWrapper.eq(ChatHistory::getAppId, appId);
        // 3. 调用父类 remove,得到物理/逻辑删除是否成功的布尔值
        return this.remove(queryWrapper);
    }

    /**
     * 根据查询请求组装 MyBatis-Flex QueryWrapper（支持 eq/like/le/orderBy）
     *
     * @param queryRequest 查询条件
     * @return 可交给分页或列表查询的 QueryWrapper
     */
    @Override
    public QueryWrapper buildQueryWrapper(ChatHistoryQueryRequest queryRequest) {
        // 方法大纲：
        // 1. 请求体为空时抛业务异常    L422-L425
        // 2. 逐字段叠加 eq/like/le 条件    L427-L455
        // 3. 设置排序（自定义字段或默认 createTime 降序）并返回    L457-L464

        // 1. 请求体为空时直接抛业务异常,得到明确的参数错误反馈而非空指针
        if (queryRequest == null) {
            throw new MyException(ErrorCode.PARAMS_ERROR, "查询请求参数为空");
        }

        // 2. 创建空 QueryWrapper 并逐步叠加 eq/like/le/orderBy 条件,得到可交给 MyBatis-Flex 执行的查询对象
        QueryWrapper queryWrapper = new QueryWrapper();

        Long id = queryRequest.getId();
        String message = queryRequest.getMessage();
        String messageType = queryRequest.getMessageType();
        Long appId = queryRequest.getAppId();
        Long userId = queryRequest.getUserId();
        LocalDateTime lastCreateTime = queryRequest.getLastCreateTime();
        String sortField = queryRequest.getSortField();
        String sortOrder = queryRequest.getSortOrder();

        if (appId != null) {
            queryWrapper.eq(ChatHistory::getAppId, appId);
        }
        if (StrUtil.isNotBlank(queryRequest.getAppName())) {
            queryWrapper.and(new RawQueryCondition(
                "EXISTS (SELECT 1 FROM app WHERE app.id = chat_history.appId AND app.appName LIKE CONCAT('%', ?, '%'))",
                new Object[]{queryRequest.getAppName()}
            ));
        }
        if (userId != null) {
            queryWrapper.eq(ChatHistory::getUserId, userId);
        }
        if (id != null) {
            queryWrapper.eq(ChatHistory::getId, id);
        }
        if (StrUtil.isNotBlank(message)) {
            queryWrapper.like(ChatHistory::getMessage, message);
        }
        if (StrUtil.isNotBlank(messageType)) {
            queryWrapper.eq(ChatHistory::getMessageType, messageType);
        }
        // 创建时间小于等于 lastCreateTime
        if (lastCreateTime != null) {
            queryWrapper.le(ChatHistory::getCreateTime, lastCreateTime);
        }

        // 如果有排序字段，则设置排序为对应的排序顺序
        if (StrUtil.isNotBlank(sortField) && StrUtil.isNotBlank(sortOrder)) {
            queryWrapper.orderBy(sortField, "ascend".equals(sortOrder));
        } else {
            // 否则按创建时间降序
            queryWrapper.orderBy("createTime", false);
        }

        // 3. 返回组装完毕的 QueryWrapper,供分页或列表查询复用
        return queryWrapper;
    }

    /**
     * 将 ChatHistory 实体转换为前端展示 VO
     *
     * @param chatHistory 对话历史实体
     * @return 转换后的 VO；入参为 null 时返回 null
     */
    @Override
    public ChatHistoryVO getChatHistoryVO(ChatHistory chatHistory) {
        // 方法大纲：
        // 1. 空实体直接返回 null    L483-L486
        // 2. 复制同名字段到 VO 并返回    L487-L490

        // 1. 实体入参为空时无 VO 可组装,直接返回 null 表示无数据
        if (chatHistory == null) {
            return null;
        }
        // 2. 创建 VO 并复制同名字段,得到前端展示用的 ChatHistoryVO 实例
        ChatHistoryVO chatHistoryVO = new ChatHistoryVO();
        BeanUtil.copyProperties(chatHistory, chatHistoryVO);
        // 3. 返回填充后的 VO,供接口层序列化给前端
        return chatHistoryVO;
    }


    /**
     * 批量将 ChatHistory 实体列表转换为 VO 列表
     *
     * @param chatHistoryList 对话历史实体列表
     * @return 与输入同序的 VO 列表；空或 null 时返回不可变空集合
     */
    @Override
    public List<ChatHistoryVO> getChatHistoryVOList(List<ChatHistory> chatHistoryList) {
        // 方法大纲：
        // 1. 空列表返回 Collections.emptyList()    L507-L510
        // 2. 逐条 map 为 VO 并收集返回    L511-L514

        // 1. 空或空列表直接返回不可变空集合,得到调用方无需再判空的流式安全结果
        if (chatHistoryList == null || chatHistoryList.isEmpty()) {
            return Collections.emptyList();
        }
        // 2. 逐条映射为 VO 并收集为 List,得到与输入同序的展示对象列表
        return chatHistoryList.stream()
                .map(this::getChatHistoryVO)
                .collect(Collectors.toList());
    }






    /**
     * 分页查询指定应用的全部对话历史（导出用，需创建者或管理员权限）
     *
     * @param appId     应用 id
     * @param loginUser 当前登录用户
     * @return 该应用全部对话历史的 VO 列表
     */
    @Override
    public List<ChatHistoryVO> listAllByAppIdForExport(Long appId, User loginUser) {
        // 方法大纲：
        // 1. 校验 appId 与登录态并校验创建者/管理员权限
        // 2. 从 DB 升序拉全量
        // 3. 批量转 VO 列表返回

        // 1. 校验 appId 与登录态,得到可继续鉴权的前提
        ThrowUtils.throwIf(appId == null || appId <= 0, ErrorCode.PARAMS_ERROR, "应用ID不能为空");
        ThrowUtils.throwIf(loginUser == null, ErrorCode.NOT_LOGIN_ERROR);

        App app = appService.getById(appId);
        ThrowUtils.throwIf(app == null, ErrorCode.NOT_FOUND_ERROR, "应用不存在");
        boolean isAdmin = UserConstant.ADMIN_ROLE.equals(loginUser.getUserRole());
        boolean isCreator = app.getUserId().equals(loginUser.getId());
        ThrowUtils.throwIf(!isAdmin && !isCreator, ErrorCode.NO_AUTH_ERROR, "无权导出该应用的对话历史");

        // 2. 从 DB 升序拉全量
        QueryWrapper queryWrapper = new QueryWrapper();
        queryWrapper.eq(ChatHistory::getAppId, appId);
        queryWrapper.orderBy(ChatHistory::getCreateTime, true);
        List<ChatHistory> list = this.list(queryWrapper);
        if (list == null) {
            list = Collections.emptyList();
        }
        log.info("导出对话历史，appId={}, count={}", appId, list.size());
        // 3. 将实体列表批量转为 VO 列表,得到导出接口可直接序列化的结果
        return getChatHistoryVOList(list);
    }

    /**
     * 统计指定应用的对话轮数（以 DB 中 USER 条数为准）
     *
     * @param appId     应用 id
     * @return DB 中 USER 消息条数（对话轮数）
     */
    public int countUserRoundsInternal(Long appId) {
        // 方法大纲：
        // 1. 非法 appId 直接返回 0    L856-L858
        // 2. 按 appId + USER 类型统计 chat_history 条数并返回    L859-L862

        // 1. 非法 appId 视为无轮次,得到幂等 0 结果
        if (appId == null || appId <= 0) {
            return 0;
        }
        // 2. 构造 USER 类型精确匹配条件并 count,得到 DB 权威对话轮数
        QueryWrapper queryWrapper = new QueryWrapper();
        queryWrapper.eq(ChatHistory::getAppId, appId);
        queryWrapper.eq(ChatHistory::getMessageType, ChatHistoryMessageTypeEnum.USER.getValue());
        return (int) this.count(queryWrapper);
    }

    /**
     * 统计指定应用对话轮数：以 DB 中 USER 条数为权威值
     *
     * @param appId     应用 id
     * @param loginUser 当前登录用户（创建者或管理员可查看）
     * @return DB 中 USER 消息条数（对话轮数）
     */
    @Override
    public int countRoundsByAppId(Long appId, User loginUser) {
        // 方法大纲：
        // 1. 校验 appId、登录态与创建者/管理员权限
        // 2. 统计 DB 中 USER 条数并返回

        // 1. 校验 appId 与登录态,得到可统计轮数的安全上下文
        ThrowUtils.throwIf(appId == null || appId <= 0, ErrorCode.PARAMS_ERROR, "应用ID不能为空");
        ThrowUtils.throwIf(loginUser == null, ErrorCode.NOT_LOGIN_ERROR);

        App app = appService.getById(appId);
        ThrowUtils.throwIf(app == null, ErrorCode.NOT_FOUND_ERROR, "应用不存在");
        boolean isAdmin = UserConstant.ADMIN_ROLE.equals(loginUser.getUserRole());
        boolean isCreator = app.getUserId().equals(loginUser.getId());
        ThrowUtils.throwIf(!isAdmin && !isCreator, ErrorCode.NO_AUTH_ERROR, "无权查看该应用的对话轮数");

        // 2. DB 就是权威来源。
        // 原来这里还跟 Redis echo 全文缓存比一次、偏差时自愈一轮 ——
        // 那层缓存随 V4 记忆一起移除了，比对对象不存在，逻辑也就没意义了。
        return countUserRoundsInternal(appId);
    }







}
