package com.dbts.glyahhaigeneratecode.projectimport;

import com.dbts.glyahhaigeneratecode.exception.ErrorCode;
import com.dbts.glyahhaigeneratecode.exception.MyException;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;
import java.util.regex.Pattern;

/** 所有受管工作区入口共用提交资格，私有导入预览另核对所有者。 */
@Component
@RequiredArgsConstructor
public class ImportWorkspaceGate {
    private final ImportTaskRepository repository;
    private static final Pattern PROJECT = Pattern.compile("(?i)project_([0-9]+)");

    public ImportTaskRepository.Publication requireReadable(String dirName) {
        var matcher = PROJECT.matcher(dirName);
        if (!matcher.matches()) return null;
        long id;
        try { id = Long.parseLong(matcher.group(1)); }
        catch (NumberFormatException e) { throw unavailable(); }
        var publication = repository.publicationByApp(id);
        if (publication != null && !publication.state().equals("READY")) throw unavailable();
        return publication;
    }

    public boolean authorizeStatic(String dirName, Long userId) {
        var publication = requireReadable(dirName);
        if (publication == null) return false;
        if (userId == null || userId != publication.ownerId()) throw unavailable();
        return true;
    }

    public void requireLegacyExecutionAllowed(Long appId) {
        if (appId != null && repository.publicationByApp(appId) != null) {
            throw new MyException(ErrorCode.OPERATION_ERROR, "导入项目的执行隔离尚未就绪，暂不能通过旧入口执行。");
        }
    }

    /**
     * Agent 对话/编辑通道的准入。
     *
     * <p>导入应用的工作区是发布副本（{@code temp/code_output/project_{appId}}），来源原件与保护快照
     * 留在导入私有目录，Agent 工具只作用于副本；发布完成后即放行编辑。未完成发布的工作区不完整，
     * 继续拒绝。运行与静态发布各有自己的隔离入口，不经过这里。
     */
    public void requireAgentEditAllowed(Long appId) {
        if (appId == null) return;
        var publication = repository.publicationByApp(appId);
        if (publication != null && !publication.state().equals("READY")) throw unavailable();
    }

    private static MyException unavailable() { return new MyException(ErrorCode.CONFLICT_ERROR, "导入内容尚不可用，请查看任务状态。"); }
}
