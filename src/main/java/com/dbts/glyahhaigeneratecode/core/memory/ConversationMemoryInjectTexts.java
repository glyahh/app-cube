package com.dbts.glyahhaigeneratecode.core.memory;

import cn.hutool.core.util.StrUtil;
import com.dbts.glyahhaigeneratecode.model.memory.FileNoteEntry;

import java.util.List;
import java.util.Map;

/**
 * 构建 [memory_index] / [memory_file_note] SystemMessage 正文。
 * 读取 changedFiles 与 fileNotes 映射 -> 拼接带固定 tag 的说明文本 -> 返回可注入 ChatMemory 的字符串（无内容则 null）。
 */
public final class ConversationMemoryInjectTexts {

    // 多轮续聊策略标签，须排在 index/fileNote 之前注入
    private static final String POLICY_TAG = "[memory_policy]";
    // 上一轮 manifest diff 变更路径索引标签
    private static final String INDEX_TAG = "[memory_index]";
    // 各文件中文说明（fileNote）标签
    private static final String FILE_NOTE_TAG = "[memory_file_note]";
    // ES 检索源码片段标签（在 fileNote 之后、ChatMemory 历史之前注入）
    public static final String INJECT_TAG = "[memory_inject]";
    private static final String DISCLAIMER = "以下索引与文件说明仅供参考，请以用户当轮指令与磁盘 readFile 结果为准。";

    private static final String STATE_PRIORITY_BODY = """
            多轮续聊时，请先阅读本上下文中 [memory_index]、[memory_file_note] 等会话工程 state 索引，再理解并执行用户当轮指令。
            [memory_index] 与 [memory_file_note] 仅为短索引，不包含文件完整内容。
            修改或理解代码实现细节前，必须使用 readFile 工具读取磁盘上对应文件的最新完整内容，以磁盘为准。
            禁止仅凭记忆或索引中的片段臆造文件内容、路径或实现。""";

    /**
     * 工具类禁止实例化
     */
    private ConversationMemoryInjectTexts() {
    }

    /**
     * 生成多轮续聊时「优先查看 state」的策略说明，置于 index/fileNote 之前注入 ChatMemory
     *
     * @return 带 [memory_policy] 标签的正文
     */
    public static String buildMemoryStatePriorityMessage() {
        return POLICY_TAG + "\n" + STATE_PRIORITY_BODY.trim();
    }

    /**
     * 根据上一轮变更路径列表生成 memory_index 注入正文
     *
     * @param changedFiles 上一轮 manifest diff 得到的相对路径列表
     * @return 带 [memory_index] 标签的正文；列表为空时返回 null
     */
    public static String buildMemoryIndexMessage(List<String> changedFiles) {
        if (changedFiles == null || changedFiles.isEmpty()) {
            return null;
        }
        return INDEX_TAG + "\n"
                + "last_round_changed=" + String.join(", ", changedFiles) + "\n"
                + DISCLAIMER;
    }

    /**
     * 根据路径到 fileNote 的映射生成 memory_file_note 注入正文
     *
     * @param fileNotes 路径到 FileNoteEntry 的映射
     * @return 带 [memory_file_note] 标签的正文；无有效说明时返回 null
     */
    public static String buildMemoryFileNoteMessage(Map<String, FileNoteEntry> fileNotes) {
        if (fileNotes == null || fileNotes.isEmpty()) {
            return null;
        }
        // 1. 遍历有效 fileNote，拼成 path= + 说明正文
        StringBuilder body = new StringBuilder();
        for (Map.Entry<String, FileNoteEntry> e : fileNotes.entrySet()) {
            if (e.getKey() == null || e.getValue() == null || StrUtil.isBlank(e.getValue().note())) {
                continue;
            }
            if (!body.isEmpty()) {
                body.append("\n\n");
            }
            body.append("path=").append(e.getKey()).append('\n').append(e.getValue().note().trim());
        }
        if (body.isEmpty()) {
            return null;
        }
        // 2. 加上 [memory_file_note] 标签后返回，供 ChatMemory 注入
        return FILE_NOTE_TAG + "\n" + body;
    }}
