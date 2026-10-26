package com.dbts.glyahhaigeneratecode.agentauth;

import com.dbts.glyahhaigeneratecode.config.WorkspaceProperties;
import com.dbts.glyahhaigeneratecode.exception.ErrorCode;
import com.dbts.glyahhaigeneratecode.exception.ThrowUtils;
import com.dbts.glyahhaigeneratecode.model.enums.CodeGenTypeEnum;
import com.dbts.glyahhaigeneratecode.service.support.WorkspacePathResolver;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Comparator;
import java.util.List;
import java.util.UUID;

/**
 * 按变更账本回滚普通应用的工作区。
 *
 * <p>与导入应用的发布恢复不同：这里回到「本轮开始前」，不是回到某个发布版本。
 * 回滚只处理账本里记录过的路径，不触碰其他文件。
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class AgentChangeRollbackService {
    private final AgentChangeLedgerRepository ledger;
    private final AgentEditSessionRepository sessions;
    private final WorkspacePathResolver paths;
    private final WorkspaceProperties properties;

    /**
     * 回滚一个会话的全部变更。
     *
     * @return 实际回滚的文件数
     */
    public int rollback(long ownerId, UUID sessionId, long appId) {
        sessions.require(sessionId, ownerId);
        var plan = ledger.rollbackPlan(sessionId);
        // 空账本不能当成「回滚成功」：调用方已经承诺回到本轮开始前，
        // 返回 0 会让界面显示已回滚，实际文件一个都没还原。
        ThrowUtils.throwIf(plan.isEmpty(), ErrorCode.CONFLICT_ERROR,
                "本轮变更记录已丢失，无法自动回滚，请手动核对工作区。");
        Path root = workspace(appId);
        int restored = 0;
        // 预检后先撤销新增文件；文件系统 I/O 错误仍可能中断执行，失败时账本保留。
        var ordered = plan.stream()
                .sorted(Comparator.comparing(row -> !"ADDED".equals(row.get("change"))))
                .toList();
        // 先完整预检，任何一项无法恢复时都不修改文件或清空账本。
        for (var row : ordered) {
            String relative = String.valueOf(row.get("path"));
            if (Boolean.TRUE.equals(row.get("truncated"))
                    || (row.get("before_blob") == null && row.get("before_hash") != null)) {
                // 没有正文就无法安全回滚；明确失败，不猜内容。
                throw new com.dbts.glyahhaigeneratecode.exception.MyException(
                        ErrorCode.OPERATION_ERROR,
                        "文件 " + relative + " 原始内容未完整保留，无法自动回滚，请手动处理。");
            }
            resolve(root, relative);
        }
        for (var row : ordered) {
            String relative = String.valueOf(row.get("path"));
            Path target = resolve(root, relative);
            Object before = row.get("before_blob");
            try {
                if (before == null) {
                    // 本轮新增：回滚即删除。
                    Files.deleteIfExists(target);
                } else {
                    Files.createDirectories(target.getParent());
                    Files.writeString(target, before.toString(), StandardCharsets.UTF_8);
                }
                restored++;
            } catch (IOException error) {
                throw new com.dbts.glyahhaigeneratecode.exception.MyException(
                        ErrorCode.OPERATION_ERROR, "回滚 " + relative + " 失败。");
            }
        }
        ledger.clear(sessionId);
        return restored;
    }

    private Path workspace(long appId) {
        var type = CodeGenTypeEnum.AUTO;
        return paths.outputDir(appId, type);
    }

    /** 只允许工作区内的相对路径，避免账本被污染后写到别处。 */
    private Path resolve(Path root, String relative) {
        Path normalizedRoot = root.toAbsolutePath().normalize();
        Path target = normalizedRoot.resolve(relative).normalize();
        ThrowUtils.throwIf(!target.startsWith(normalizedRoot),
                ErrorCode.PARAMS_ERROR, "回滚路径越界。");
        return target;
    }
}
