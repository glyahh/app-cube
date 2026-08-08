package com.dbts.glyahhaigeneratecode.core;

import com.dbts.glyahhaigeneratecode.ai.aiCodeGeneratorService;
import com.dbts.glyahhaigeneratecode.ai.aiCodeGeneratorServiceFactory;
import com.dbts.glyahhaigeneratecode.ai.model.HtmlCodeResult;
import com.dbts.glyahhaigeneratecode.ai.model.MultiFileCodeResult;
import com.dbts.glyahhaigeneratecode.ai.tool.ToolManager;
import com.dbts.glyahhaigeneratecode.ai.tool.tools.FileModifyTool;
import com.dbts.glyahhaigeneratecode.constant.AppConstant;
import com.dbts.glyahhaigeneratecode.core.Builder.vueProjectBuilder;
import com.dbts.glyahhaigeneratecode.core.context.HtmlMultiFileEditContextBuilder;
import com.dbts.glyahhaigeneratecode.core.context.VueEditContextBuilder;
import com.dbts.glyahhaigeneratecode.core.memory.AppGenerationCoordinator;
import com.dbts.glyahhaigeneratecode.core.memory.MemoryMessageXmlSupport;
import com.dbts.glyahhaigeneratecode.core.parser.CodeParserExecutor;
import com.dbts.glyahhaigeneratecode.core.saver.CodeFileSaverExecutor;
import com.dbts.glyahhaigeneratecode.core.support.HtmlMultiFileTokenStreamAdapter;
import com.dbts.glyahhaigeneratecode.core.support.LegacyHtmlStreamIntegrity;
import com.dbts.glyahhaigeneratecode.core.support.VueTokenStreamAdapter;
import com.dbts.glyahhaigeneratecode.exception.ErrorCode;
import com.dbts.glyahhaigeneratecode.exception.MyException;
import com.dbts.glyahhaigeneratecode.model.enums.CodeGenTypeEnum;
import com.dbts.glyahhaigeneratecode.service.ChatHistoryService;
import dev.langchain4j.service.TokenStream;
import jakarta.annotation.Resource;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import reactor.core.publisher.Flux;

import java.io.File;

/**
 * AI 代码生成门面，统一编排 service 获取、模型调用、流式适配、解析和制品落盘。
 * <p>
 * 同一 app 的同步与流式入口均通过 {@link AppGenerationCoordinator} 进入同一互斥域，确保共享
 * ChatMemory、RAG 注入和工程目录不会被并发轮次交叉修改。
 * </p>
 */
@Service
@Slf4j
public class AiCodeGeneratorFacade {

    @Resource
    private aiCodeGeneratorServiceFactory aiCodeGeneratorServiceFactory;

    @Resource
    private HtmlMultiFileEditContextBuilder htmlMultiFileEditContextBuilder;

    @Resource
    private VueEditContextBuilder vueEditContextBuilder;

    @Resource
    private vueProjectBuilder vueProjectBuilder;

    @Resource
    private ToolManager toolManager;

    @Resource
    private ChatHistoryService chatHistoryService;

    @Resource
    private AppGenerationCoordinator appGenerationCoordinator;

    private static final CodeFileSaverExecutor codeFileSaverExecutor = new CodeFileSaverExecutor();
    private static final CodeParserExecutor codeParserExecutor = new CodeParserExecutor();

    /**
     * 同步生成 HTML 或多文件代码并保存到磁盘（非流式入口）
     * <p>应用锁覆盖 service 创建、同步模型调用和最终落盘，与流式入口互斥。</p>
     *
     * @param userMessage       用户提示词
     * @param codeGenTypeEnum   仅支持 HTML、MULTI_FILE
     * @param appId             应用主键
     * @return 保存目录对应的 File
     */
    public File generateAndSaveCode(String userMessage, CodeGenTypeEnum codeGenTypeEnum, Long appId) {
        validateCodeGenType(codeGenTypeEnum);

        return appGenerationCoordinator.serializeBlocking(appId, () -> switch (codeGenTypeEnum) {
                case HTML, MULTI_FILE -> generateAndSaveHtmlMultiFileCode(userMessage, appId, codeGenTypeEnum);
                default -> throw new MyException(
                        ErrorCode.SYSTEM_ERROR, "不支持的生成类型: " + codeGenTypeEnum.getValue());
            });
    }

    /**
     * 同步生成 HTML / MULTI_FILE 并落盘（逻辑同 {@link #generateHtmlMultiFileCodeStream}，非流式）
     *
     * @param userMessage 用户提示
     * @param appId       应用主键
     * @param codeGenType HTML 或 MULTI_FILE
     * @return 保存目录对应的 File
     */
    private File generateAndSaveHtmlMultiFileCode(String userMessage, Long appId, CodeGenTypeEnum codeGenType) {
        boolean firstRound = chatHistoryService.isFirstRound(appId, false);
        boolean editIntent = isHtmlMultiEditIntent(codeGenType, userMessage, appId);
        boolean htmlMultiToollessBootstrap = firstRound && !editIntent;
        aiCodeGeneratorService svc = aiCodeGeneratorServiceFactory.getAiCodeGeneratorService(
                appId, codeGenType, htmlMultiToollessBootstrap,
                MemoryMessageXmlSupport.extractUserOriginal(userMessage));
        if (editIntent) {
            TokenStream tokenStream = resolveHtmlMultiFileSyncTokenStream(svc, codeGenType, appId, userMessage);
            HtmlMultiFileTokenStreamAdapter.adapt(
                    tokenStream, codeGenType, appId, false, toolManager, null).blockLast();
            return htmlMultiOutputDir(codeGenType, appId);
        }
        return switch (codeGenType) {
            case HTML -> codeFileSaverExecutor.execute(codeGenType, svc.generateCodeHTML(appId, userMessage), appId);
            case MULTI_FILE -> codeFileSaverExecutor.execute(codeGenType, svc.generateCodeMultiFile(appId, userMessage), appId);
            default -> throw new MyException(ErrorCode.SYSTEM_ERROR, "不支持的生成类型: " + codeGenType.getValue());
        };
    }

    /**
     * 流式生成入口（默认非首轮）
     *
     * @param userMessage     用户提示词
     * @param codeGenTypeEnum HTML / MULTI_FILE / VUE
     * @param appId           应用主键
     * @return SSE 文本流
     */
    public Flux<String> generateAndSaveCodeStream(String userMessage, CodeGenTypeEnum codeGenTypeEnum, Long appId) {
        boolean firstRound = chatHistoryService.isFirstRound(appId, false);
        return generateAndSaveCodeStream(userMessage, codeGenTypeEnum, appId, firstRound);
    }

    /**
     * 流式生成入口，可指定是否首轮
     * <p>
     * 锁在订阅时获取，并持续到下游完成、异常或取消。这样不仅保护 service 获取，也保护模型流和
     * 工具写盘的完整生命周期。
     * </p>
     *
     * @param userMessage     用户提示词
     * @param codeGenTypeEnum 生成类型
     * @param appId           应用主键
     * @param firstRound      是否首轮（Vue 工具白名单等）
     * @return SSE 文本流
     */
    public Flux<String> generateAndSaveCodeStream(String userMessage, CodeGenTypeEnum codeGenTypeEnum, Long appId, boolean firstRound) {
        validateCodeGenType(codeGenTypeEnum);

        return appGenerationCoordinator.serialize(appId, () -> {
            // action 在应用锁内执行，以下操作不得提前到 serialize 外部。
            clearModifyFileDedup();
            return switch (codeGenTypeEnum) {
                case HTML, MULTI_FILE ->
                        generateHtmlMultiFileCodeStream(codeGenTypeEnum, userMessage, appId, firstRound);
                case VUE -> generateAndSaveVueCodeStream(userMessage, appId, firstRound);
                default -> Flux.error(new MyException(
                        ErrorCode.SYSTEM_ERROR, "不支持的生成类型: " + codeGenTypeEnum.getValue()));
            };
        });
    }

    /**
     * 将聚合后的模型输出解析为结果对象并写入磁盘
     *
     * @param codeGenTypeEnum 生成类型
     * @param full            聚合全文
     * @param appId           应用主键
     */
    private void persistParsedResult(CodeGenTypeEnum codeGenTypeEnum, String full, Long appId) {
        int len = full == null ? 0 : full.length();
        String safeFull = full == null ? "" : full;
        log.info(
                "legacy 流式聚合完成 appId={} codeGenType={} charLen={} possibleTruncatedTail={}\ntailSample=\n{}",
                appId,
                codeGenTypeEnum,
                len,
                LegacyHtmlStreamIntegrity.looksLikeIncompleteTrailingTag(safeFull)
        );
        try {
            Object executeResult = codeParserExecutor.execute(codeGenTypeEnum, safeFull);
            if (isParseResultEmpty(codeGenTypeEnum, executeResult)) {
                log.info("模型本轮未生成代码，跳过落盘。appId={} codeGenType={} charLen={}",
                        appId, codeGenTypeEnum, len);
                return;
            }
            File file = codeFileSaverExecutor.execute(codeGenTypeEnum, executeResult, appId);
            log.info("保存目录: {}", file.getAbsolutePath());
        } catch (Exception e) {
            log.error("生成代码失败: {}", e.getMessage(), e);
            // 不重新抛出异常：模型已成功输出自然语言或代码流，
            // 落盘失败不应通过 sink.error 触发前端错误文案拼接（方案 A）。
            // 真实生成失败由流中断/工具执行异常等其他机制捕获。
        }
    }

    /**
     * 判断解析结果是否为空（模型本轮未生成任何代码文件内容）
     * <p>
     * 用于在持久化前提前退出，避免空内容写入时抛异常，
     * 进而触发 sink.error → SimpleTextStreamHandler 拼接失败文案 → 污染 chat_history。
     *
     * @param codeGenTypeEnum 生成类型
     * @param executeResult   CodeParserExecutor 返回的解析结果
     * @return true 表示所有文件内容均为空，无需保存
     */
    private boolean isParseResultEmpty(CodeGenTypeEnum codeGenTypeEnum, Object executeResult) {
        if (executeResult == null) {
            return true;
        }
        return switch (codeGenTypeEnum) {
            case MULTI_FILE -> {
                MultiFileCodeResult r = (MultiFileCodeResult) executeResult;
                yield r.getHtmlCode() == null && r.getCssCode() == null && r.getJsCode() == null;
            }
            case HTML -> {
                HtmlCodeResult r = (HtmlCodeResult) executeResult;
                yield r.getHtmlCode() == null;
            }
            default -> false;
        };
    }

    /**
     * HTML / MULTI_FILE 流式生成：单文件 HTML 与多文件共用同一套 editIntent 判定与 TokenStream 适配逻辑
     *
     * @param codeGenType  HTML 或 MULTI_FILE
     * @param userMessage  用户提示
     * @param appId        应用主键
     * @param firstRound   是否首轮
     * @return SSE 文本流
     */
    private Flux<String> generateHtmlMultiFileCodeStream(
            CodeGenTypeEnum codeGenType, String userMessage, Long appId, boolean firstRound) {
        boolean editIntent = isHtmlMultiEditIntent(codeGenType, userMessage, appId);
        boolean htmlMultiToollessBootstrap = firstRound && !editIntent;
        aiCodeGeneratorService aiCodeGeneratorService =
                aiCodeGeneratorServiceFactory.getAiCodeGeneratorService(
                        appId, codeGenType, htmlMultiToollessBootstrap,
                        MemoryMessageXmlSupport.extractUserOriginal(userMessage));
        TokenStream tokenStream = resolveHtmlMultiFileStreamTokenStream(
                aiCodeGeneratorService, codeGenType, appId, userMessage, editIntent);
        boolean persistOnComplete = !editIntent;
        return HtmlMultiFileTokenStreamAdapter.adapt(
                tokenStream,
                codeGenType,
                appId,
                persistOnComplete,
                toolManager,
                persistOnComplete ? full -> persistParsedResult(codeGenType, full, appId) : null);
    }

    /**
     * 按 HTML / MULTI_FILE 与 editIntent 选择流式 TokenStream 入口（modify / generate）
     */
    private TokenStream resolveHtmlMultiFileStreamTokenStream(
            aiCodeGeneratorService svc, CodeGenTypeEnum codeGenType, Long appId, String userMessage, boolean editIntent) {
        // 编辑轮采用 modify 提示词，生成轮用 generate 提示词
        return switch (codeGenType) {
            case HTML -> editIntent
                    ? svc.modifyCodeHTMLTokenStream(appId, userMessage)
                    : svc.generateCodeHTMLTokenStream(appId, userMessage);
            case MULTI_FILE -> editIntent
                    ? svc.modifyCodeMultiFileTokenStream(appId, userMessage)
                    : svc.generateCodeMultiFileTokenStream(appId, userMessage);
            default -> throw new MyException(ErrorCode.SYSTEM_ERROR, "不支持的生成类型: " + codeGenType.getValue());
        };
    }

    /**
     * 同步编辑轮：按 HTML / MULTI_FILE 选择 modify TokenStream（工具直接写盘）
     */
    private TokenStream resolveHtmlMultiFileSyncTokenStream(
            aiCodeGeneratorService svc, CodeGenTypeEnum codeGenType, Long appId, String userMessage) {
        return switch (codeGenType) {
            case HTML -> svc.modifyCodeHTML(appId, userMessage);
            case MULTI_FILE -> svc.modifyCodeMultiFile(appId, userMessage);
            default -> throw new MyException(ErrorCode.SYSTEM_ERROR, "不支持的生成类型: " + codeGenType.getValue());
        };
    }

    /**
     * Vue 流式生成：TokenStream 适配为 JSON 行 + 流结束后触发本地 npm build
     *
     * @param userMessage 用户提示
     * @param appId       应用主键
     * @param firstRound  是否首轮
     * @return Flux
     */
    private Flux<String> generateAndSaveVueCodeStream(String userMessage, Long appId, boolean firstRound) {
        boolean editIntent = htmlMultiFileEditContextBuilder.isEditIntentMessage(userMessage)
                && vueEditContextBuilder.hasExistingVueFiles(appId);
        String enhancedMessage = editIntent
                ? vueEditContextBuilder.buildPromptIfNeed(userMessage, appId)
                : userMessage;
        aiCodeGeneratorService aiCodeGeneratorService =
                aiCodeGeneratorServiceFactory.getAiCodeGeneratorService(
                        appId, CodeGenTypeEnum.VUE, firstRound && !editIntent,
                        MemoryMessageXmlSupport.extractUserOriginal(userMessage));
        TokenStream tokenStream = editIntent
                ? aiCodeGeneratorService.modifyCodeVueFileStream(appId, enhancedMessage)
                : aiCodeGeneratorService.generateCodeVueFileStream(appId, enhancedMessage);
        return VueTokenStreamAdapter.adapt(tokenStream, appId, vueProjectBuilder);
    }

    private static void validateCodeGenType(CodeGenTypeEnum codeGenTypeEnum) {
        if (codeGenTypeEnum == null) {
            throw new MyException(ErrorCode.SYSTEM_ERROR, "生成类型为空");
        }
    }

    private void clearModifyFileDedup() {
        try {
            FileModifyTool fmt = toolManager.getTool("modifyFile") instanceof FileModifyTool f ? f : null;
            if (fmt != null) {
                fmt.clearRoundDedup();
            }
        } catch (Exception e) {
            log.error("每轮清空工具modifyFile失败", e);
        }
    }

    private boolean isHtmlMultiEditIntent(CodeGenTypeEnum codeGenType, String userMessage, Long appId) {
        return htmlMultiFileEditContextBuilder.isEditIntentMessage(userMessage)
                && htmlMultiFileEditContextBuilder.hasExistingEditableFiles(codeGenType, appId);
    }

    private static File htmlMultiOutputDir(CodeGenTypeEnum codeGenType, Long appId) {
        return new File(AppConstant.CODE_OUTPUT_ROOT_DIR + File.separator
                + codeGenType.getValue() + "_" + appId);
    }

}
