package com.dbts.glyahhaigeneratecode.constant;

/**
 * 对话历史相关常量（含超长对话压缩策略）
 */
public interface ChatHistoryConstant {

    /**
     * AI 上下文可见的「用户轮」上限：1 条压缩摘要（计 1 轮）+ {@link #TARGET_UNMERGED_DB_USER_ROUNDS} 条未合并完整轮。
     */
    int MAX_ROUNDS_BEFORE_SUMMARY = 3;

    /**
     * 合并后 chat_history 中保留的未合并 USER 轮数（上一轮完整 + 当前轮）。
     */
    int TARGET_UNMERGED_DB_USER_ROUNDS = 2;

    /**
     * 对话记忆窗口
     * 窗口上限（条），含 user/ai/tool 等。
     */
    int CHAT_MEMORY_MAX_MESSAGES = 160;

    /**
     * 从 MySQL 预加载进 Redis 的最大条数（配合历史重建链路），宜大于「可见轮数×2」并预留 tool/error 占位。
     */
    int MEMORY_PRELOAD_MESSAGE_ROWS = 80;

    /**
     * 每次压缩时，将最早几轮合并为一轮（2 轮 → 1 轮，即 4 条消息 → 2 条）。
     */
    int ROUNDS_TO_MERGE = 2;

    /**
     * 合并时取的最早消息条数（2 轮 = 2 用户 + 2 AI = 4 条）。
     */
    int MESSAGES_PER_MERGE = ROUNDS_TO_MERGE * 2;

    /**
     * 流式生成失败时写入 chat_history 的用户可见文案（禁止落库真实异常堆栈/配额 JSON）。
     */
    String GENERATION_FAILED_USER_MESSAGE = "[生成失败] 代码生成流异常中断，请重试。";

    /**
     * 用户取消 SSE 订阅时，追加在已缓冲 AI 片段末尾的标记（由前端解析为「中断」卡片）。
     */
    String GENERATION_INTERRUPTED_MARKER = "[中断]";
}
