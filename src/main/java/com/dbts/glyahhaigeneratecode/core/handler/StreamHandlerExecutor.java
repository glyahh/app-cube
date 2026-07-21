package com.dbts.glyahhaigeneratecode.core.handler;

import cn.hutool.core.util.StrUtil;
import com.dbts.glyahhaigeneratecode.guardrail.UserFacingOutputSanitizer;
import com.dbts.glyahhaigeneratecode.model.Entity.User;
import com.dbts.glyahhaigeneratecode.model.enums.CodeGenTypeEnum;
import com.dbts.glyahhaigeneratecode.service.ChatHistoryService;
import jakarta.annotation.Resource;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;
import reactor.core.publisher.Flux;
import reactor.core.publisher.SignalType;

import java.util.concurrent.atomic.AtomicBoolean;

/**
 * 流处理器执行器
 * 根据代码生成类型创建合适的流处理器：
 * 1. 传统的 Flux<String> 流（HTML、MULTI_FILE） -> SimpleTextStreamHandler
 * 2. TokenStream 格式的复杂流（VUE_PROJECT） -> JsonMessageStreamHandler
 */
@Slf4j
@Component
public class StreamHandlerExecutor {

    @Resource
    private JsonMessageStreamHandler jsonMessageStreamHandler;

    @Resource
    private UserFacingOutputSanitizer userFacingOutputSanitizer;

    @Resource
    private WorkflowTextStreamHandler workflowTextStreamHandler;

    @Resource
    private SimpleTextStreamHandler simpleTextStreamHandler;

    /**
     * 按生成类型与工作流开关选择具体 Handler，并在流上挂载「轮次结束」统计回调
     *
     * @param originFlux         原始 SSE 文本流
     * @param chatHistoryService 会话服务（含 onRoundCompleted）
     * @param appId              应用 ID
     * @param loginUser          当前用户
     * @param codeGenType        HTML / MULTI_FILE / VUE
     * @param workflowMode       是否走 LangGraph 工作流文本处理器
     * @param firstRound         是否首轮（Vue JSON 处理器用）
     * @param userMessage        用户原文（Vue JSON 处理器用）
     * @param roundId            当前对话轮次 ID（非法则跳过 onRoundCompleted）
     * @return 包装后的 Flux
     */
    public Flux<String> doExecute(Flux<String> originFlux,
                                  ChatHistoryService chatHistoryService,
                                  long appId, User loginUser, CodeGenTypeEnum codeGenType,
                                  boolean workflowMode, boolean firstRound, String userMessage, Long roundId) {
        long startMs = System.currentTimeMillis();
        AtomicBoolean once = new AtomicBoolean(false);
        int[] bufferChars = new int[]{0};

        // 用户可见输出脱敏：仅对“纯文本流”生效，避免破坏 VUE(JSON 工具协议)流式解析
        // 目的是让用户看到的回复更干净，少暴露内部实现细节。
        Flux<String> userFacingFlux = originFlux;
        if (workflowMode) {
            UserFacingOutputSanitizer.StreamBuffer streamBuffer = userFacingOutputSanitizer.newStreamBuffer();
            userFacingFlux = originFlux
                    .map(chunk -> userFacingOutputSanitizer.sanitizeChunk(streamBuffer, chunk))
                    .concatWith(Flux.defer(() -> {
                        String tail = userFacingOutputSanitizer.flush(streamBuffer);
                        return StrUtil.isBlank(tail) ? Flux.empty() : Flux.just(tail);
                    }));
        }

        // 1. 根据 workflowMode / codeGenType 选择具体流处理器实现
        Flux<String> handledFlux;
        if (workflowMode) {
            handledFlux = workflowTextStreamHandler.handle(userFacingFlux, chatHistoryService, appId, loginUser);
        } else {
            handledFlux = switch (codeGenType) {
                case VUE -> // 使用注入的组件实例
                        jsonMessageStreamHandler.handle(userFacingFlux, chatHistoryService, appId, loginUser, firstRound, userMessage);
                case HTML, MULTI_FILE ->
                        simpleTextStreamHandler.handle(userFacingFlux, chatHistoryService, appId, loginUser);
            };
        }

        return handledFlux
                .doOnNext(chunk -> {
                    // 1. 累加已输出字符数，便于监控token是否异常
                    if (chunk != null) {
                        bufferChars[0] += chunk.length();
                    }
                })
                .doFinally(signal -> {
                    // 确保 doFinally 只被进入一次
                    if (!once.compareAndSet(false, true)) {
                        return;
                    }
                    if (signal == SignalType.CANCEL) {
                        log.warn("代码类型:{} 用户取消 跳过 onRoundCompleted，回滚用户消息 appId={} roundId={} bufferedChars={}",
                                codeGenType, appId, roundId, bufferChars[0]);
                        try {
                            // 当用户取消时删除之前添加的用户消息
                            chatHistoryService.removeUserMessageByContent(appId, loginUser.getId(), userMessage);
                        } catch (Exception e) {
                            log.warn("回滚用户消息失败，appId={}, roundId={}", appId, roundId, e);
                        }
                        return;
                    }
                    // 2. roundId 非法则跳过统计回调, 避免脏数据持久化到数据库中
                    if (roundId == null || roundId <= 0) {
                        log.warn("onRoundCompleted 跳过，roundId 非法，appId={}, roundId={}", appId, roundId);
                        return;
                    }
                    try {
                        // 上报统计回调
                        long elapsedMs = Math.max(0L, System.currentTimeMillis() - startMs);
                        // 写入 RAG 记忆库
                        chatHistoryService.onRoundCompleted(appId, roundId, loginUser.getId(), codeGenType, workflowMode, bufferChars[0], elapsedMs);
                    } catch (Exception e) {
                        log.warn("onRoundCompleted 执行失败已忽略，appId={}, roundId={}, signal={}",
                                appId, roundId, signal == null ? SignalType.ON_COMPLETE : signal, e);
                    }
        });
    }
}
