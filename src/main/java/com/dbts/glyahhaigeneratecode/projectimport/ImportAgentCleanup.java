package com.dbts.glyahhaigeneratecode.projectimport;

import com.dbts.glyahhaigeneratecode.agent.AppRunCoordinator;
import com.dbts.glyahhaigeneratecode.exception.ErrorCode;
import com.dbts.glyahhaigeneratecode.exception.MyException;
import com.dbts.glyahhaigeneratecode.runtime.DockerCli;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.function.Supplier;

/** 导入应用删除前清理主 Agent 的会话容器；不触碰其他会话。 */
@Component
@RequiredArgsConstructor
public class ImportAgentCleanup {

    private final ImportTaskRepository tasks;
    private final AppRunCoordinator runs;
    private final DockerCli docker;
    private final ImportAgentPublication publication;

    /** 锁覆盖整个删除；拿锁后新排队的 Agent 会重新校验应用是否存在。 */
    public <T> T whenIdle(long appId, Supplier<T> action) {
        return tasks.publicationByApp(appId) == null ? action.get() : runs.whenIdle(appId, action);
    }

    /** 只能由完成归属校验且持有应用许可的删除流程调用。 */
    public void release(long appId) {
        if (tasks.publicationByApp(appId) == null) {
            return;
        }

        // 1. 按会话列出容器；不能把查询失败当作没有容器。
        String session = "app:" + appId;
        var listed = list(session);
        if (!listed.ok()) {
            throw failure();
        }
        for (String id : listed.stdout().strip().split("\\s+")) {
            if (!id.isEmpty()) {
                removeContainer(id, session);
            }
        }

        // 2. 再查实际资源，确认没有容器后才释放产物。
        var remaining = list(session);
        if (!remaining.ok() || !remaining.stdout().isBlank()) {
            throw failure();
        }
        publication.releaseOutputs(appId);
    }

    private void removeContainer(String id, String session) {
        // 1. 核对容器身份，只能删除当前会话的容器。
        if (!id.matches("[a-f0-9]{12,64}")) {
            throw failure();
        }
        var identity = docker.run(List.of("inspect", "--format", "{{json .Config.Labels}}", id), 30);
        if (!identity.ok() || !sameSession(identity.stdout(), session)) {
            throw failure();
        }

        // 2. 删除失败即阻断，不提前释放资源。
        if (!docker.run(List.of("rm", "-f", id), 60).ok()) {
            throw failure();
        }
    }

    private DockerCli.Result list(String session) {
        return docker.run(List.of("ps", "-a", "--filter", "label=glyahh.session_id=" + session, "--format", "{{.ID}}"), 30);
    }

    private static boolean sameSession(String labels, String session) {
        try {
            var value = new com.fasterxml.jackson.databind.ObjectMapper().readTree(labels);
            return value != null && value.isObject() && value.path("glyahh.session_id").isTextual()
                    && session.equals(value.path("glyahh.session_id").asText());
        } catch (com.fasterxml.jackson.core.JsonProcessingException invalid) {
            return false;
        }
    }

    private static MyException failure() {
        return new MyException(ErrorCode.OPERATION_ERROR, "Agent 容器尚未确认停止，应用未删除。");
    }
}
