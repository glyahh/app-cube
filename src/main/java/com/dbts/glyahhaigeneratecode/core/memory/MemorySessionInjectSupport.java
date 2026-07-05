package com.dbts.glyahhaigeneratecode.core.memory;

import cn.hutool.core.util.StrUtil;
import com.dbts.glyahhaigeneratecode.ai.aiCodeGeneratorServiceFactory;
import com.dbts.glyahhaigeneratecode.service.UserPersonalizationService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

/**
 * 会话级记忆注入支持：将用户风格以 &lt;inject_prompt&gt; + &lt;user_style&gt; SystemMessage 注入 ChatMemory。
 * <p>
 * 依赖 {@link aiCodeGeneratorServiceFactory#applySessionStyle} 操作已缓存的
 * {@link dev.langchain4j.memory.chat.MessageWindowChatMemory} 实例，
 * 确保内部列表与 ChatMemoryStore 一致。
 * </p>
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class MemorySessionInjectSupport {

    private final UserPersonalizationService userPersonalizationService;
    private final aiCodeGeneratorServiceFactory aiCodeGeneratorServiceFactory;

    /** 会话级 SystemMessage 前缀标识，用于定位替换 */
    public static final String SESSION_PREFIX = "<inject_prompt>";

    /**
     * 每轮请求时调用：读取最新风格，构建 XML 正文，委托 factory 应用。
     *
     * @param appId  应用 id（同时也是 ChatMemory 的 memoryId）
     * @param userId 用户 id
     */
    public void injectOrUpdateSessionStyle(Long appId, Long userId) {
        if (appId == null || appId <= 0 || userId == null || userId <= 0) {
            return;
        }

        // 1. 读取最新风格（异常时降级跳过）
        String appStyle;
        String answerStyle;
        try {
            appStyle = userPersonalizationService.getCachedAppStyle(userId);
            answerStyle = userPersonalizationService.getCachedAnswerStyle(userId);
        } catch (Exception e) {
            log.warn("读取用户风格失败，跳过会话级注入，appId={}, userId={}", appId, userId, e);
            return;
        }

        boolean hasApp = StrUtil.isNotBlank(appStyle);
        boolean hasAns = StrUtil.isNotBlank(answerStyle);

        if (!hasApp && !hasAns) {
            // 如果用户删除了其中两种风格 -> 无风格配置 → 移除可能存在的旧会话级 SystemMessage
            aiCodeGeneratorServiceFactory.applySessionStyle(appId, null);
            return;
        }

        // 2. 构造会话级 SystemMessage 正文
        String inject_prompt = MemoryMessageXmlSupport.buildInjectPromptMeta();
        String styleBlock = MemoryMessageXmlSupport.buildUserStyleBlock(appStyle, answerStyle);
        // 整个 style string
        String sessionBody = inject_prompt + "\n" + styleBlock;

        // 3. 委托 factory 应用到 MessageWindowChatMemory 实例, 更新缓存
        aiCodeGeneratorServiceFactory.applySessionStyle(appId, sessionBody);
    }
}
