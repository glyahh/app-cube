package com.dbts.glyahhaigeneratecode.core.memory;

import com.dbts.glyahhaigeneratecode.ai.aiCodeGeneratorServiceFactory;
import com.dbts.glyahhaigeneratecode.model.enums.CodeGenTypeEnum;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

/**
 * RAG 工程记忆注入支持：缓存本轮 user query，由 factory 在 memory 就绪后 flush 为 {@code [memory_inject]}。
 */
@Component
@RequiredArgsConstructor
public class MemoryRagInjectSupport {

    private final aiCodeGeneratorServiceFactory aiCodeGeneratorServiceFactory;

    /**
     * 每轮请求时调用：缓存本轮 query，待 {@code getAiCodeGeneratorService} 链路 flush。
     *
     * @param appId       应用 id
     * @param userMessage 用户本轮 message
     */
    public void stageRagQuery(Long appId, String userMessage, CodeGenTypeEnum codeGenType) {
        aiCodeGeneratorServiceFactory.stageRagQuery(appId, userMessage, codeGenType);
    }
}
