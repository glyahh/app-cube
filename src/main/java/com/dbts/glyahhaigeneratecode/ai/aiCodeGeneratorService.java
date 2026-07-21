package com.dbts.glyahhaigeneratecode.ai;

import com.dbts.glyahhaigeneratecode.ai.model.HtmlCodeResult;
import com.dbts.glyahhaigeneratecode.ai.model.MultiFileCodeResult;
import dev.langchain4j.service.MemoryId;
import dev.langchain4j.service.SystemMessage;
import dev.langchain4j.service.TokenStream;
import dev.langchain4j.service.UserMessage;
import dev.langchain4j.service.V;
import reactor.core.publisher.Flux;

public interface aiCodeGeneratorService {
    /**
     * 返回用户提示词的输出单个HTML文件
     * @param appId 会话记忆 id，与文件工具 {@code @ToolMemoryId} 对齐
     * @param userMessage 用户输入
     * @return 解析结果
     */
    @SystemMessage(fromResource = "Prompt/Single_File_Prompt.txt")
    @UserMessage("{{userMessage}}")
    HtmlCodeResult generateCodeHTML(@MemoryId Long appId, @V("userMessage") String userMessage);


    /**
     * 返回用户提示词的输出多个文件
     * @param appId 会话记忆 id
     * @param userMessage 用户输入
     * @return 解析结果
     */
    @SystemMessage(fromResource = "Prompt/Various_File_Prompt.txt")
    @UserMessage("{{userMessage}}")
    MultiFileCodeResult generateCodeMultiFile(@MemoryId Long appId, @V("userMessage") String userMessage);



    /**
     * 返回用户提示词的输出单个HTML文件
     * @param appId 会话记忆 id
     * @param userMessage 用户输入
     * @return 流式片段
     */
    @SystemMessage(fromResource = "Prompt/Single_File_Prompt.txt")
    @UserMessage("{{userMessage}}")
    Flux<String> generateCodeHTMLStream(@MemoryId Long appId, @V("userMessage") String userMessage);


    /**
     * 返回用户提示词的输出多个文件
     * @param appId 会话记忆 id
     * @param userMessage 用户输入
     * @return 流式片段
     */
    @SystemMessage(fromResource = "Prompt/Various_File_Prompt.txt")
    @UserMessage("{{userMessage}}")
    Flux<String> generateCodeMultiFileStream(@MemoryId Long appId, @V("userMessage") String userMessage);

    /**
     * 返回用户提示词的输出单个HTML文件（TokenStream 流式）。
     * @param appId 会话记忆 id
     * @param userMessage 用户输入
     * @return TokenStream 流
     */
    @SystemMessage(fromResource = "Prompt/Single_File_Prompt.txt")
    @UserMessage("{{userMessage}}")
    TokenStream generateCodeHTMLTokenStream(@MemoryId Long appId, @V("userMessage") String userMessage);

    /**
     * 返回用户提示词的输出多个文件（TokenStream 流式）。
     * @param appId 会话记忆 id
     * @param userMessage 用户输入
     * @return TokenStream 流
     */
    @SystemMessage(fromResource = "Prompt/Various_File_Prompt.txt")
    @UserMessage("{{userMessage}}")
    TokenStream generateCodeMultiFileTokenStream(@MemoryId Long appId, @V("userMessage") String userMessage);


    /**
     * 使用流式输出返回Vue文件
     * @param appId  交给ai记忆
     * @param userMessage
     * @return
     */
    @SystemMessage(fromResource = "Prompt/Vue_File_Prompt.txt")
    @UserMessage("{{userMessage}}")
    TokenStream generateCodeVueFileStream(@MemoryId Long appId, @V("userMessage") String userMessage);


    // ======================== 以下为次轮修改专用方法  ========================

    /**
     * 次轮修改：根据用户要求修改已有的单HTML文件（TokenStream 流式，工具直接写盘）
     */
    @SystemMessage(fromResource = "Prompt/Single_File_Prompt_Modify.txt")
    @UserMessage("{{userMessage}}")
    TokenStream modifyCodeHTML(@MemoryId Long appId, @V("userMessage") String userMessage);

    /**
     * 次轮修改：根据用户要求修改已有的多文件项目（TokenStream 流式，工具直接写盘）
     */
    @SystemMessage(fromResource = "Prompt/Various_File_Prompt_Modify.txt")
    @UserMessage("{{userMessage}}")
    TokenStream modifyCodeMultiFile(@MemoryId Long appId, @V("userMessage") String userMessage);

    /**
     * 次轮修改：流式输出修改单HTML文件
     */
    @SystemMessage(fromResource = "Prompt/Single_File_Prompt_Modify.txt")
    @UserMessage("{{userMessage}}")
    Flux<String> modifyCodeHTMLStream(@MemoryId Long appId, @V("userMessage") String userMessage);

    /**
     * 次轮修改：流式输出修改多文件项目
     */
    @SystemMessage(fromResource = "Prompt/Various_File_Prompt_Modify.txt")
    @UserMessage("{{userMessage}}")
    Flux<String> modifyCodeMultiFileStream(@MemoryId Long appId, @V("userMessage") String userMessage);

    /**
     * 次轮修改：TokenStream 流式修改单HTML文件
     */
    @SystemMessage(fromResource = "Prompt/Single_File_Prompt_Modify.txt")
    @UserMessage("{{userMessage}}")
    TokenStream modifyCodeHTMLTokenStream(@MemoryId Long appId, @V("userMessage") String userMessage);

    /**
     * 次轮修改：TokenStream 流式修改多文件项目
     */
    @SystemMessage(fromResource = "Prompt/Various_File_Prompt_Modify.txt")
    @UserMessage("{{userMessage}}")
    TokenStream modifyCodeMultiFileTokenStream(@MemoryId Long appId, @V("userMessage") String userMessage);

    /**
     * 次轮修改：TokenStream 流式修改Vue项目
     */
    @SystemMessage(fromResource = "Prompt/Vue_File_Prompt_Modify.txt")
    @UserMessage("{{userMessage}}")
    TokenStream modifyCodeVueFileStream(@MemoryId Long appId, @V("userMessage") String userMessage);
}
