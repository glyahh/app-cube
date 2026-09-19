# AI 记忆分层注入 Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use subagent-driven-development (recommended) or super_run_plan to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**目标：** 将用户风格、Loop技能、用户原话分层管理：风格会话级 SystemMessage（每窗口1次）、Loop轮次级 UserMessage、压缩仅用户原话层、MySQL存原话+loop_id、重建时 assemble+renderXml。

**架构：** 6个独立模块可并行（M1/M2/M3无顺序依赖，M4/M6各自独立，M5依赖M3产出的loop_id列）。方案1（每轮热更新风格SystemMessage）。

**技术栈：** Spring Boot, LangChain4j, MyBatis-Flex, Redis, MySQL, Reactor

---

## 文件结构

### 新建文件
| 文件 | 职责 |
|------|------|
| `core/memory/MemorySessionInjectSupport.java` | 读风格→构建XML SystemMessage→更新ChatMemory |
| `core/memory/MemoryMessageXmlSupport.java` | XML包裹/解析/渲染工具方法（静态） |

### 修改文件
| 文件 | 职责 |
|------|------|
| `model/Entity/ChatHistory.java` | 新增 loopId 字段 |
| `mapper/ChatHistoryMapper.java` | 新增添加 loop_id 列的 DDL 方法 |
| `service/ChatHistoryService.java` | addChatMessageAndReturnId 增加 loopId 参数 |
| `service/impl/ChatHistoryServiceImpl.java` | 实现 loopId 落库；压缩隔离 XML 剥离 |
| `core/support/ChatHistorySchemaMigrationSupport.java` | 新增 ensureLoopIdColumnIfMissing；修改 summariz 方法输入剥离 XML |
| `service/support/LoopInjectService.java` | 输出格式改为 `<loop_skill loopId="...">...</loop_skill>` |
| `service/impl/ChatToGenCodeImpl.java` | 风格注入改为 SystemMessage；用户消息包裹 `<user_original>`；loopId 传参落库 |
| `core/memory/ChatHistoryAiMemoryRebuildSupport.java` | 重建时读 loop_id + 当前风格 → assemble → renderXml |
| `core/memory/ConversationMemoryInjectTexts.java` | 新增 `<inject_prompt>` 元说明正文（可选） |

---

### M1: 用户风格 SystemMessage 注入（MemorySessionInjectSupport）

**设计原因：** 当前风格作为前缀拼入 UserMessage，每轮重复浪费 token。改为独立的 SystemMessage 注入 ChatMemory，复用方案1（每轮读最新值后 update）。不影响压缩逻辑（压缩只扫 UserMessage）。

**涉及知识点：**
- LangChain4j ChatMemoryStore.updateMessages() 定位替换 SystemMessage
- UserPersonalizationServiceImpl 缓存读取（Cache-Aside + 击穿防护）
- XML tag 文本构造

**可替代方案：**
- 方案2（rebuild 时注入）：省一次 Redis 读，但风格更新不即时。弃用原因：产品预期风格应即时生效，且每次 Redis GET <1ms 成本可忽略。

---

### M2: 用户消息 XML 包裹 + Loop 标签改造

**设计原因：** `<user_original>` 包裹用户原话 + `<loop_skill>` 作为轮次级附加。与 M1 配合实现注入顺序：SystemMessage(风格) → 历史 → 本轮 UserMessage(原话+loop)。echo 回显展示原话不变。

**涉及知识点：**
- Reactor Flux 流中消息处理
- XML 文本转义（防止标签断裂）
- 日志脱敏（不暴露 XML 包裹给前端）

**可替代方案：**
- 整体 JSON 序列化再 render：过度设计，XML 文本直接可读且 LangChain4j 原生支持 ChatMessage 字符串。

---

### M3: loop_id 列落库

**设计原因：** 当前 LoopId 仅 HTTP 参数未落库，重建时丢失。新增 chat_history.loop_id 列，每轮快照。

**涉及知识点：**
- MyBatis-Flex 实体字段映射
- DDL 在线迁移（information_schema 探测 + ALTER TABLE）
- 兼容旧行（null → 无 Loop）

**可替代方案：**
- 新建独立 loop_history 表：过度设计，每轮最多一个 loopId，单列足够。

---

### M4: 压缩隔离（仅 `<user_original>` 参与摘要）

**设计原因：** 当前压缩将完整消息（含注入标签）送入 AI 摘要，浪费 token 且风格/Loop 混入摘要。改为剥离 XML 标签后仅用原始用户文本做摘要。

**涉及知识点：**
- AI 摘要提示词构造
- 正则/简单文本剥离（仅 `<user_original>` 标签）
- ChatHistorySchemaMigrationSupport 静态方法

**可替代方案：**
- JSON 格式存储再解析：XML 文本简单 `<tag>...</tag>` 剥离用正则足够，不需 JSON 序列化。

---

### M5: Redis 重建 assemble + renderXml

**设计原因：** 重建时需将 memory_shrink 摘要 + 未合并 chat_history（含 loop_id）+ 当前风格 → 按时间轴组装 → 渲染为 XML 文本写入 Redis。

**涉及知识点：**
- LangChain4j UserMessage/AiMessage/SystemMessage 类型
- ChatMemoryStore.updateMessages() 完整替换
- UserPersonalizationService 风格读取
- 兼容旧数据（无分层结构降级为仅 `<user_original>`）

**数据流：** 
```
shrink.user_summary → SystemMessage(<user_original>...)
shrink.ai_summary → AiMessage
chat_history 未合并行 → UserMessage(<user_original>...) / AiMessage
当前风格 → SystemMessage(<user_style>...)
```
→ 按 createTime 排序 → updateMessages 写入 Redis

---

### M6: 兼容层

**设计原因：** 旧 Redis 中 UserMessage 是裸文本，无 XML 包裹。读到时按「仅 `<user_original>`」降级处理。旧 chat_history 行 loop_id=null 正常读取。

**涉及知识点：**
- instanceof 类型判断
- 空安全降级逻辑
- 无额外依赖

---

## 任务分解

### Task 1: ChatHistory 实体 + Mapper DDL — loop_id 列

**Files:**
- Modify: `src/main/java/com/dbts/glyahhaigeneratecode/model/Entity/ChatHistory.java`
- Modify: `src/main/java/com/dbts/glyahhaigeneratecode/mapper/ChatHistoryMapper.java`
- Modify: `src/main/java/com/dbts/glyahhaigeneratecode/core/support/ChatHistorySchemaMigrationSupport.java`

- [ ] **Step 1: ChatHistory 实体添加 loopId 字段**

在 `ChatHistory.java` 中 auditHitRule 字段后增加：

```java
    /**
     * Loop ID（本轮注入的 Loop 快照，nullable）
     */
    @Column("loopId")
    private Long loopId;
```

- [ ] **Step 2: ChatHistoryMapper 添加 DDL 方法**

```java
    @Update("""
            ALTER TABLE chat_history ADD COLUMN loopId BIGINT NULL DEFAULT NULL COMMENT 'Loop ID（快照）' AFTER auditHitRule
            """)
    int alterChatHistoryAddLoopId();
```

- [ ] **Step 3: ChatHistorySchemaMigrationSupport 添加循环列校验与 DDL 调用**

在 `ensureAuditColumnsIfMissing` 方法末尾追加 loopId 探测：

```java
    // 追加到 ensureAuditColumnsIfMissing 末尾：
    boolean loopIdExists = isColumnExists(chatHistoryMapper, "loopId");
    if (!loopIdExists) {
        chatHistoryMapper.alterChatHistoryAddLoopId();
        log.info("chat_history 表已添加 loopId 列，appId={}", appId);
    }
```

### Task 2: ChatHistoryService 接口 + 实现 — loopId 参数传递

**Files:**
- Modify: `src/main/java/com/dbts/glyahhaigeneratecode/service/ChatHistoryService.java`
- Modify: `src/main/java/com/dbts/glyahhaigeneratecode/service/impl/ChatHistoryServiceImpl.java`

- [ ] **Step 1: ChatHistoryService 接口新增带 loopId 的 addChatMessageAndReturnId**

```java
    /**
     * 添加一条带审查字段和 loopId 的对话消息并返回主键 id
     *
     * @param appId        应用 id
     * @param message      消息内容
     * @param messageType  消息类型（user/ai/error）
     * @param userId       用户 id
     * @param auditAction  审查动作
     * @param auditHitRule 命中规则
     * @param loopId       Loop ID（可选）
     * @return 保存后的主键 id；失败返回 null
     */
    Long addChatMessageAndReturnId(Long appId, String message, String messageType, Long userId,
                                   String auditAction, String auditHitRule, Long loopId);
```

- [ ] **Step 2: ChatHistoryServiceImpl 实现带 loopId 的重载**

```java
    @Override
    public Long addChatMessageAndReturnId(Long appId, String message, String messageType, Long userId,
                                          String auditAction, String auditHitRule, Long loopId) {
        ThrowUtils.throwIf(appId == null || appId <= 0, ErrorCode.PARAMS_ERROR, "应用ID不能为空");
        ThrowUtils.throwIf(StrUtil.isBlank(message), ErrorCode.PARAMS_ERROR, "消息内容不能为空");
        ThrowUtils.throwIf(StrUtil.isBlank(messageType), ErrorCode.PARAMS_ERROR, "消息类型不能为空");
        ThrowUtils.throwIf(userId == null || userId <= 0, ErrorCode.PARAMS_ERROR, "用户ID不能为空");
        ThrowUtils.throwIf(StrUtil.isBlank(auditAction), ErrorCode.PARAMS_ERROR, "审查动作不能为空");
        ThrowUtils.throwIf(StrUtil.isBlank(auditHitRule), ErrorCode.PARAMS_ERROR, "命中规则不能为空");

        ChatHistoryMessageTypeEnum messageTypeEnum = ChatHistoryMessageTypeEnum.getEnumByValue(messageType);
        ThrowUtils.throwIf(messageTypeEnum == null, ErrorCode.PARAMS_ERROR, "不支持的消息类型: " + messageType);

        ChatHistorySchemaMigrationSupport.ensureAuditColumnsIfMissing(appId, this.getMapper());
        ChatHistory chatHistory = ChatHistory.builder()
                .appId(appId)
                .message(message)
                .messageType(messageType)
                .userId(userId)
                .auditAction(auditAction)
                .auditHitRule(auditHitRule)
                .loopId(loopId)  // 新增 loopId 赋值
                .build();
        boolean saved = this.save(chatHistory);
        if (!saved || chatHistory.getId() == null || chatHistory.getId() <= 0) {
            return null;
        }
        chatHistoryEchoRedisSupport.invalidate(appId);
        if (ChatHistoryMessageTypeEnum.USER.getValue().equals(messageType)) {
            chatAiMemoryRedisSupport.refreshAiMemoryTtl(appId);
        }
        return chatHistory.getId();
    }
```

- [ ] **Step 3: 现有 5 参重载委托新 6 参重载，传递 loopId=null**

```java
    @Override
    public Long addChatMessageAndReturnId(Long appId, String message, String messageType, Long userId,
                                          String auditAction, String auditHitRule) {
        return addChatMessageAndReturnId(appId, message, messageType, userId, auditAction, auditHitRule, null);
    }
```

`addChatMessageAndReturnId(appId, message, messageType, userId)` 和 `addChatMessage(Long...)` 同理保持链条不变，最终都走到 null。

### Task 3: MemoryMessageXmlSupport — XML 包裹工具

**Files:**
- Create: `src/main/java/com/dbts/glyahhaigeneratecode/core/memory/MemoryMessageXmlSupport.java`

- [ ] **Step 1: 新建工具类，提供静态 XML 包裹/剥离方法**

```java
package com.dbts.glyahhaigeneratecode.core.memory;

import cn.hutool.core.util.StrUtil;

/**
 * 记忆消息 XML 包裹/解析/渲染工具。
 * 仅提供静态方法，无状态，可测试。
 */
public final class MemoryMessageXmlSupport {

    private static final String TAG_USER_ORIGINAL = "user_original";
    private static final String TAG_LOOP_SKILL = "loop_skill";
    private static final String TAG_INJECT_PROMPT = "inject_prompt";
    private static final String TAG_USER_STYLE = "user_style";

    private MemoryMessageXmlSupport() {}

    /**
     * 包裹用户原话为 <user_original>...</user_original>
     */
    public static String wrapUserOriginal(String message) {
        if (StrUtil.isBlank(message)) return message;
        return "<" + TAG_USER_ORIGINAL + ">" + escapeXml(message) + "</" + TAG_USER_ORIGINAL + ">";
    }

    /**
     * 包裹 Loop 为 <loop_skill loopId="...">...</loop_skill>
     */
    public static String wrapLoopSkill(Long loopId, String compiledPrompt, String loopName) {
        if (loopId == null || StrUtil.isBlank(compiledPrompt)) return "";
        String safeName = escapeXml(StrUtil.blankToDefault(loopName, ""));
        return "\n<" + TAG_LOOP_SKILL + " loopId=\"" + loopId + "\" name=\"" + safeName + "\">\n"
                + compiledPrompt + "\n</" + TAG_LOOP_SKILL + ">";
    }

    /**
     * 构建 <inject_prompt> 元说明块
     */
    public static String buildInjectPromptMeta() {
        return "<" + TAG_INJECT_PROMPT + ">\n"
                + "本消息中的 XML 标签说明：\n"
                + "- <user_style>：用户风格偏好，优先级低于本轮显式指令\n"
                + "- <user_original>：用户本轮原始输入\n"
                + "- <loop_skill>：本轮回调技能，优先级高于风格、低于显式指令\n"
                + "优先级：本轮 user_original 显式指令 > loop_skill > user_style > 历史对话\n"
                + "</" + TAG_INJECT_PROMPT + ">";
    }

    /**
     * 构建 <user_style> 块
     */
    public static String buildUserStyleBlock(String appStyle, String answerStyle) {
        StringBuilder sb = new StringBuilder();
        sb.append("<").append(TAG_USER_STYLE).append(">\n");
        if (StrUtil.isNotBlank(appStyle)) {
            sb.append("<app_style>\n").append(appStyle).append("\n</app_style>\n");
        }
        if (StrUtil.isNotBlank(answerStyle)) {
            sb.append("<answer_style>\n").append(answerStyle).append("\n</answer_style>\n");
        }
        sb.append("</").append(TAG_USER_STYLE).append(">");
        return sb.toString();
    }

    /**
     * 从 UserMessage 中剥离 XML 标签，返回纯用户原话。
     * 兼容有/无标签的旧数据：无标签时原文返回。
     */
    public static String extractUserOriginal(String xmlText) {
        if (StrUtil.isBlank(xmlText)) return xmlText;
        String openTag = "<" + TAG_USER_ORIGINAL + ">";
        String closeTag = "</" + TAG_USER_ORIGINAL + ">";
        int start = xmlText.indexOf(openTag);
        if (start < 0) return xmlText; // 旧数据无标签
        start += openTag.length();
        int end = xmlText.indexOf(closeTag, start);
        if (end < 0) return xmlText;
        return xmlText.substring(start, end);
    }

    /**
     * 判断文本是否已被 XML 包裹
     */
    public static boolean isWrapped(String text) {
        return StrUtil.isNotBlank(text) && text.contains("<" + TAG_USER_ORIGINAL + ">");
    }

    /**
     * 转义 XML 特殊字符
     */
    public static String escapeXml(String s) {
        if (s == null) return "";
        return s.replace("&", "&amp;")
                .replace("<", "&lt;")
                .replace(">", "&gt;")
                .replace("\"", "&quot;")
                .replace("'", "&apos;");
    }
}
```

### Task 4: MemorySessionInjectSupport — 风格 SystemMessage 注入

**Files:**
- Create: `src/main/java/com/dbts/glyahhaigeneratecode/core/memory/MemorySessionInjectSupport.java`
- Modify: `src/main/java/com/dbts/glyahhaigeneratecode/constant/UserPersonalizationConstant.java`

- [ ] **Step 1: UserPersonalizationConstant 新增注入标签常量**

```java
    /** 注入标签：inject_prompt 元说明 */
    public static final String INJECT_TAG_INJECT_PROMPT = "<inject_prompt>";
    /** 注入标签：user_style */
    public static final String INJECT_TAG_USER_STYLE = "<user_style>";
```

- [ ] **Step 2: 新建 MemorySessionInjectSupport**

```java
package com.dbts.glyahhaigeneratecode.core.memory;

import cn.hutool.core.util.StrUtil;
import com.dbts.glyahhaigeneratecode.service.UserPersonalizationService;
import dev.langchain4j.data.message.ChatMessage;
import dev.langchain4j.data.message.SystemMessage;
import dev.langchain4j.store.memory.chat.ChatMemoryStore;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * 会话级记忆注入支持：将用户风格以 <inject_prompt> + <user_style> SystemMessage 注入 ChatMemory。
 * <p>
 * 方案1：每轮读取最新风格值，通过定位替换 ChatMemory 中的旧 SystemMessage 来更新。
 * 首次注入时 insert，后续更新时 replace（始终保持仅 1 条会话级 SystemMessage）。
 * </p>
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class MemorySessionInjectSupport {

    private final UserPersonalizationService userPersonalizationService;
    private final ChatMemoryStore chatMemoryStore;

    /** 会话级 SystemMessage 前缀标识，用于定位替换 */
    static final String SESSION_PREFIX = "<inject_prompt>";

    /**
     * 每轮请求时调用：读取最新风格，替换 ChatMemory 中的会话级 SystemMessage。
     *
     * @param appId  应用 id（同时也是 ChatMemory 的 memoryId）
     * @param userId 用户 id
     */
    public void injectOrUpdateSessionStyle(Long appId, Long userId) {
        if (appId == null || appId <= 0 || userId == null || userId <= 0) return;

        // 1. 读取最新风格
        String appStyle = userPersonalizationService.getCachedAppStyle(userId);
        String answerStyle = userPersonalizationService.getCachedAnswerStyle(userId);
        boolean hasApp = StrUtil.isNotBlank(appStyle);
        boolean hasAns = StrUtil.isNotBlank(answerStyle);
        if (!hasApp && !hasAns) {
            // 无风格配置 → 移除可能存在的旧会话级 SystemMessage
            removeExistingSessionMessage(appId);
            return;
        }

        // 2. 构造会话级 SystemMessage 正文
        String injectMeta = MemoryMessageXmlSupport.buildInjectPromptMeta();
        String styleBlock = MemoryMessageXmlSupport.buildUserStyleBlock(appStyle, answerStyle);
        String sessionBody = injectMeta + "\n" + styleBlock;

        // 3. 替换 ChatMemory 中的旧消息
        upsertSessionSystemMessage(appId, sessionBody);
    }

    /**
     * 在 ChatMemory 中查找并替换会话级 SystemMessage。
     * 存在则更新内容，不存在则追加。
     */
    private void upsertSessionSystemMessage(Long appId, String sessionBody) {
        List<ChatMessage> messages = chatMemoryStore.getMessages(appId);
        if (messages == null) messages = new ArrayList<>();

        int foundIndex = -1;
        for (int i = 0; i < messages.size(); i++) {
            if (messages.get(i) instanceof SystemMessage sm
                    && sm.text() != null
                    && sm.text().startsWith(SESSION_PREFIX)) {
                foundIndex = i;
                break;
            }
        }

        if (foundIndex >= 0) {
            // 替换旧内容
            messages.set(foundIndex, SystemMessage.from(sessionBody));
        } else {
            // 追加到 ChatMemory 头部（历史消息之前的位置）
            // 在第一个非 SystemMessage 之前插入，保持 SystemMessage 在前的顺序
            int insertPos = 0;
            for (int i = 0; i < messages.size(); i++) {
                if (!(messages.get(i) instanceof SystemMessage)) {
                    insertPos = i;
                    break;
                }
            }
            messages.add(insertPos, SystemMessage.from(sessionBody));
        }
        chatMemoryStore.updateMessages(appId, messages);
    }

    /**
     * 移除已有的会话级 SystemMessage（当风格为空时调用）
     */
    private void removeExistingSessionMessage(Long appId) {
        List<ChatMessage> messages = chatMemoryStore.getMessages(appId);
        if (messages == null || messages.isEmpty()) return;
        messages.removeIf(msg -> msg instanceof SystemMessage sm
                && sm.text() != null
                && sm.text().startsWith(SESSION_PREFIX));
        chatMemoryStore.updateMessages(appId, messages);
    }
}
```

- [ ] **Step 3: UserPersonalizationService 接口补充 getCachedAppStyle / getCachedAnswerStyle**

```java
    // 在 UserPersonalizationService 接口中新增：
    String getCachedAppStyle(Long userId);
    String getCachedAnswerStyle(Long userId);
```

- [ ] **Step 4: UserPersonalizationServiceImpl 实现**

利用已有的 `getCachedPrompt` 私有方法（需将其可见性改为包级或提取为 protected）：

```java
    @Override
    public String getCachedAppStyle(Long userId) {
        return getCachedPrompt(userId, true);
    }

    @Override
    public String getCachedAnswerStyle(Long userId) {
        return getCachedPrompt(userId, false);
    }
```

（注意：`getCachedPrompt` 当前是 private，需改为 `protected` 或包级可见。推荐改为包级 private + 抽取到 package-private 方法。）

### Task 5: ChatToGenCodeImpl 改造 — XML 包裹 + SystemMessage 注入

**Files:**
- Modify: `src/main/java/com/dbts/glyahhaigeneratecode/service/impl/ChatToGenCodeImpl.java`
- Modify: `src/main/java/com/dbts/glyahhaigeneratecode/service/support/LoopInjectService.java`

- [ ] **Step 1: LoopInjectService 输出改为 XML 标签格式**

修改 `injectIfPresent` 方法中的 block 格式：

```java
    // 旧格式：
    // String block = "\n[loop_skill name=\"" + escape(loopName) + "\"]\n" + compiled + "\n[/loop_skill]";
    // 新格式：使用 MemoryMessageXmlSupport
    String block = MemoryMessageXmlSupport.wrapLoopSkill(loopId, compiled, loopName);
```

（注：需要注入 MemoryMessageXmlSupport 或直接使用静态方法）

- [ ] **Step 2: ChatToGenCodeImpl — 注入 MemorySessionInjectSupport**

新增字段：

```java
    private final MemorySessionInjectSupport memorySessionInjectSupport;
```

在构造函数参数中添加（`@RequiredArgsConstructor` 自动注入）。

- [ ] **Step 3: 修改 chatToGenCode 方法——注入风格 SystemMessage + 包裹用户原话**

在第 5 步（入库）之后、第 6 步（构建 enhancedMessage）之前，注入风格：

```java
    // 5.1 会话级风格注入（SystemMessage）
    memorySessionInjectSupport.injectOrUpdateSessionStyle(appId, user.getId());
```

修改第 6 步——不再将风格拼入 user message，仅包裹 user_original + loop：

```java
    // 6. 包裹用户原话 + 注入 Loop skill
    // 风格已通过 SystemMessage 注入，user message 仅包裹 <user_original>
    String enhancedMessage = MemoryMessageXmlSupport.wrapUserOriginal(message);
    enhancedMessage = loopInjectService.injectIfPresent(enhancedMessage, user.getId(), appId, loopId);
```

日志打印保留原始 message，不暴露包裹格式：

```java
    log.info("===== Prompt 分发日志 (appId={}, userId={}, loopId={}) =====", appId, user.getId(), loopId);
    log.info("【原始用户消息】\n{}", message);
```

- [ ] **Step 4: 入库调用传递 loopId**

```java
    // 修改入库调用，传入 loopId
    roundId = chatHistoryService.addChatMessageAndReturnId(
            appId,
            message,
            ChatHistoryMessageTypeEnum.USER.getValue(),
            user.getId(),
            auditResult.getAction(),
            auditResult.getHitRule(),
            loopId  // 新增参数
    );
```

- [ ] **Step 5: `chatToGenCodeByWorkflow` 做相同改造**

workflow 路径同样需要风格注入和用户原话包裹。注意 workflow 路径没有 loopId 参数，所以 loopId 传 null。

```java
    // 风格 SystemMessage 注入
    memorySessionInjectSupport.injectOrUpdateSessionStyle(appId, user.getId());
    // 用户原话包裹
    String enhancedMessage = MemoryMessageXmlSupport.wrapUserOriginal(message);
    // workflow 没有 loopId，无需 loop 注入
```

- [ ] **Step 6: UserPersonalizationServiceImpl.buildInjectPrompt 不再使用（可选降级保留）**

原有的 `buildInjectPrompt` 方法保留不删，但不再被 ChatToGenCodeImpl 调用。标记 `@Deprecated`：

```java
    @Deprecated // 已被 MemorySessionInjectSupport 替代，保留仅用于测试兼容
```

### Task 6: 压缩隔离 — 仅 user_original 参与摘要

**Files:**
- Modify: `src/main/java/com/dbts/glyahhaigeneratecode/core/support/ChatHistorySchemaMigrationSupport.java`

- [ ] **Step 1: summarizeTwoRoundsWithAi 输入剥离 XML**

在构建摘要 prompt 前，对每条消息剥离 XML 标签：

```java
    public static String summarizeTwoRoundsWithAi(List<ChatHistory> fourMessages, ChatModel chatModel) {
        StringBuilder content = new StringBuilder();
        for (int i = 0; i < fourMessages.size(); i++) {
            ChatHistory m = fourMessages.get(i);
            String role = ChatHistoryMessageTypeEnum.USER.getValue().equals(m.getMessageType()) ? "用户" : "AI";
            // 剥离 XML 包裹：仅适用 user_original 原始文本参与摘要
            String rawMessage = m.getMessage();
            if (ChatHistoryMessageTypeEnum.USER.getValue().equals(m.getMessageType())) {
                rawMessage = MemoryMessageXmlSupport.extractUserOriginal(rawMessage);
            }
            content.append("第").append((i / 2) + 1).append("轮-").append(role).append("：").append(rawMessage).append("\n");
        }
        // 后续不变
        String prompt = "...";
        return chatModel.chat(prompt);
    }
```

- [ ] **Step 2: summarizeWithExistingSummary 同样剥离**

```java
    public static String summarizeWithExistingSummary(String existingUserSummary, String existingAiSummary,
                                                      List<ChatHistory> messages, ChatModel chatModel) {
        // 已有摘要通常已为纯文本，无需剥离。
        // 待合并的新对话中 USER 消息需要剥离：
        StringBuilder content = new StringBuilder();
        content.append("【已有用户摘要】").append(StrUtil.blankToDefault(existingUserSummary, "")).append("\n");
        content.append("【已有AI摘要】").append(StrUtil.blankToDefault(existingAiSummary, "")).append("\n\n");
        content.append("【待合并的新对话】\n");
        for (int i = 0; i < messages.size(); i++) {
            ChatHistory m = messages.get(i);
            String role = ChatHistoryMessageTypeEnum.USER.getValue().equals(m.getMessageType()) ? "用户" : "AI";
            String rawMessage = m.getMessage();
            if (ChatHistoryMessageTypeEnum.USER.getValue().equals(m.getMessageType())) {
                rawMessage = MemoryMessageXmlSupport.extractUserOriginal(rawMessage);
            }
            content.append("新对话-").append(role).append("：").append(rawMessage).append("\n");
        }
        // 后续不变
    }
```

### Task 7: Redis 重建 — assemble + renderXml

**Files:**
- Modify: `src/main/java/com/dbts/glyahhaigeneratecode/core/memory/ChatHistoryAiMemoryRebuildSupport.java`

- [ ] **Step 1: rebuildAiChatMemoryFromShrink — assemble 风格 SystemMessage + XML UserMessage**

在时间轴构建完成后、写入 ChatMemory 前，注入风格 SystemMessage。需要 inject UserPersonalizationService。

修改方法签名或通过依赖注入获取风格数据：

```java
    // rebuildAiChatMemoryFromShrink 方法中，在 timeline 循环之前：
    
    // 0. 注入会话级风格 SystemMessage（实时读当前值）
    String sessionStyle = buildSessionStyleMessage(appId);
    if (StrUtil.isNotBlank(sessionStyle)) {
        messageWindowChatMemory.add(SystemMessage.from(sessionStyle));
        restored++;
    }
```

添加私有方法：

```java
    /**
     * 构建会话级风格 SystemMessage（读取当前用户风格 + inject_prompt 元说明）
     */
    private String buildSessionStyleMessage(Long appId) {
        // 通过 appId 反查 userId（需注入 AppService 或 ChatHistoryMapper）
        // 此处需权衡：rebuild 调用时未必有 userId 上下文
        // 替代方案：从最近一条 chat_history 获取 userId
        // 简化方案：在 rebuildAiChatMemoryFromShrink 参数中传入 userId
        return null; // TODO: 需要 userId 才能读取风格
    }
```

- [ ] **Step 2: 明确 userId 传入方式**

方案：在 `rebuildAiChatMemoryFromShrink` 参数列表中增加 `Long userId`。所有调用方需调整传递。

```java
    public int rebuildAiChatMemoryFromShrink(Long appId, MessageWindowChatMemory messageWindowChatMemory,
                                             int maxCount, CodeGenTypeEnum codeGenTypeEnum, Long userId) {
```

但这样改动波及面大。更少的改动做法：从 timeline 中提取最近一个 userId。

实际上看 `buildAiTimeline` 中的 `AiMemoryTimelineItem` 已经包含 userId 字段，可以从 timeline 中获取：

```java
    // 从 timeline 中获取最近一个 userId
    Long userId = null;
    for (int i = timeline.size() - 1; i >= 0; i--) {
        if (timeline.get(i).userId != null) {
            userId = timeline.get(i).userId;
            break;
        }
    }
```

这样不需要改方法签名。

- [ ] **Step 3: 完善 buildSessionStyleMessage — 通过 UserPersonalizationService 读取风格**

添加 `userPersonalizationService` 字段注入：

```java
    @Resource
    private UserPersonalizationService userPersonalizationService;
```

实现：

```java
    private String buildSessionStyleMessage(Long userId) {
        if (userId == null || userId <= 0) return null;
        try {
            String appStyle = userPersonalizationService.getCachedAppStyle(userId);
            String answerStyle = userPersonalizationService.getCachedAnswerStyle(userId);
            boolean hasApp = StrUtil.isNotBlank(appStyle);
            boolean hasAns = StrUtil.isNotBlank(answerStyle);
            if (!hasApp && !hasAns) return null;
            
            String injectMeta = MemoryMessageXmlSupport.buildInjectPromptMeta();
            String styleBlock = MemoryMessageXmlSupport.buildUserStyleBlock(appStyle, answerStyle);
            return injectMeta + "\n" + styleBlock;
        } catch (Exception e) {
            log.warn("读取用户风格失败，跳过会话级注入，appId={}", appId, e);
            return null;
        }
    }
```

- [ ] **Step 4: 时间轴中 chat_history 消息包裹 XML**

在 `buildAiTimeline` 返回后、`AiServices` 注入前，对未压缩的 USER 消息做 XML 包裹：

```java
    // 在 rebuildAiChatMemoryFromShrink 的 timeline 循环中：
    if (ChatHistoryMessageTypeEnum.USER.getValue().equals(item.messageType)) {
        // 对非 shrink 来源的 USER 消息包裹 XML（shrink 摘要已是纯文本摘要）
        String msg = item.fromShrink ? item.message : MemoryMessageXmlSupport.wrapUserOriginal(item.message);
        messageWindowChatMemory.add(new UserMessage(msg));
        restored++;
        continue;
    }
```

### Task 8: 单元测试

**Files:**
- Create: `src/test/java/com/dbts/glyahhaigeneratecode/core/memory/MemoryMessageXmlSupportTest.java`

- [ ] **Step 1: MemoryMessageXmlSupport 单元测试**

```java
package com.dbts.glyahhaigeneratecode.core.memory;

import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class MemoryMessageXmlSupportTest {

    @Test
    void wrapUserOriginal_shouldWrapPlainText() {
        String result = MemoryMessageXmlSupport.wrapUserOriginal("帮我生成一个登录页面");
        assertEquals("<user_original>帮我生成一个登录页面</user_original>", result);
    }

    @Test
    void wrapUserOriginal_shouldEscapeXml() {
        String result = MemoryMessageXmlSupport.wrapUserOriginal("a < b && c > d");
        assertEquals("<user_original>a &lt; b &amp;&amp; c &gt; d</user_original>", result);
    }

    @Test
    void wrapUserOriginal_shouldReturnBlankForBlank() {
        assertNull(MemoryMessageXmlSupport.wrapUserOriginal(null));
        assertEquals("", MemoryMessageXmlSupport.wrapUserOriginal(""));
    }

    @Test
    void extractUserOriginal_shouldExtractFromWrapped() {
        String wrapped = "<user_original>帮我生成登录页面</user_original>";
        assertEquals("帮我生成登录页面", MemoryMessageXmlSupport.extractUserOriginal(wrapped));
    }

    @Test
    void extractUserOriginal_shouldReturnOriginalForUnwrapped() {
        String raw = "帮我生成登录页面";
        assertEquals(raw, MemoryMessageXmlSupport.extractUserOriginal(raw));
    }

    @Test
    void wrapLoopSkill_shouldBuildWithAttributes() {
        String result = MemoryMessageXmlSupport.wrapLoopSkill(42L, "print('hello')", "hello-world");
        assertTrue(result.contains("<loop_skill loopId=\"42\" name=\"hello-world\">"));
        assertTrue(result.contains("print('hello')"));
        assertTrue(result.contains("</loop_skill>"));
    }

    @Test
    void wrapLoopSkill_shouldReturnEmptyForNullLoopId() {
        assertTrue(MemoryMessageXmlSupport.wrapLoopSkill(null, "prompt", "name").isEmpty());
    }

    @Test
    void buildInjectPromptMeta_shouldContainPriority() {
        String meta = MemoryMessageXmlSupport.buildInjectPromptMeta();
        assertTrue(meta.contains("user_original"));
        assertTrue(meta.contains("user_style"));
        assertTrue(meta.contains("loop_skill"));
        assertTrue(meta.contains("优先级"));
    }

    @Test
    void buildUserStyleBlock_shouldBuildBothStyles() {
        String block = MemoryMessageXmlSupport.buildUserStyleBlock("专业", "简洁");
        assertTrue(block.contains("<app_style>"));
        assertTrue(block.contains("专业"));
        assertTrue(block.contains("<answer_style>"));
        assertTrue(block.contains("简洁"));
    }

    @Test
    void isWrapped_shouldDetectWrappedText() {
        assertTrue(MemoryMessageXmlSupport.isWrapped("<user_original>hello</user_original>"));
        assertFalse(MemoryMessageXmlSupport.isWrapped("hello"));
        assertFalse(MemoryMessageXmlSupport.isWrapped(null));
    }
}
```

运行测试：

```bash
mvn test -pl . -Dtest=MemoryMessageXmlSupportTest -DfailIfNoTests=false
```

### Task 9: 集成测试调整

- [ ] **Step 1: ChatHistoryServiceImplTest 适配新增 loopId 参数**

检查 `ChatHistoryServiceImplTest` 中直接调用 `addChatMessageAndReturnId` 的地方，如果不传 loopId，使用的是默认 null 重载，不需要改。

- [ ] **Step 2: 确认现有单测不回归**

```bash
mvn test -pl . -Dtest=ChatHistoryServiceImplTest,ChatHistoryConstantTest,MemoryShrinkEffectiveRoundsTest -DfailIfNoTests=false
```

---

## 不涉及的改动

- `ConversationMemoryStateInjectSupport`：现有 `[memory_policy]/[memory_index]/[memory_file_note]` 注入不变
- `ConversationMemoryInjectTexts`：不修改已有标签格式
- AiServices `@SystemMessage`：框架机制不变
- Echo 回显：仍然展示原始 message
- 前端 SSE 协议：不变
