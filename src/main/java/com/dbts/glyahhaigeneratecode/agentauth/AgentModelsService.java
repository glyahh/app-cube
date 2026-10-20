package com.dbts.glyahhaigeneratecode.agentauth;

import com.dbts.glyahhaigeneratecode.exception.ErrorCode;
import com.dbts.glyahhaigeneratecode.exception.ThrowUtils;
import com.dbts.glyahhaigeneratecode.projectimport.ImportAgentModelClient;
import com.dbts.glyahhaigeneratecode.projectimport.ImportWorkspaceGate;
import lombok.RequiredArgsConstructor;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;

import java.util.List;

/**
 * 模型目录：所有应用共用同一个能力，不区分导入与新建。
 *
 * <p>目录本身来自本地配置（{@code AGENT_MODEL_PROFILES_JSON}），与应用类型无关；
 * 这里只做"哪些模型可用、各自支持什么力度"的统一投影。
 * 真正按应用类型分叉的只有"偏好存在哪张表"，那属于偏好服务，不属于目录。
 */
@Service
@RequiredArgsConstructor
public class AgentModelsService {
    public record ModelVO(String id, String name, String model, String provider,
                          List<String> efforts, boolean configured) { }

    private final ImportAgentModelClient client;
    private final ImportWorkspaceGate gate;
    // 直接查归属，不经 AppService：后者与导入链路互相引用，会形成构造期循环依赖。
    private final JdbcTemplate jdbc;

    /** 当前用户可见的模型目录；导入应用额外核对发布归属。 */
    public List<ModelVO> catalog(long ownerId, long appId) {
        requireVisible(ownerId, appId);
        return AgentModelCatalog.parse(client.catalog()).stream()
                .map(model -> new ModelVO(model.id(), model.name(), model.model(), model.provider(),
                        model.efforts(), model.configured()))
                .toList();
    }

    /**
     * 读权限校验：导入应用走发布归属，普通应用走应用归属。
     *
     * <p>两条规则不能互换：导入应用的发布记录是它的权威归属来源，
     * 普通应用没有发布记录，只能用 {@code biz.app.user_id}。
     */
    public void requireVisible(long ownerId, long appId) {
        var publication = gate.requireAgentEditAllowed(appId);
        if (publication != null) {
            ThrowUtils.throwIf(publication.ownerId() != ownerId,
                    ErrorCode.NOT_FOUND_ERROR, "导入应用不存在。");
            return;
        }
        var owners = jdbc.queryForList("SELECT user_id FROM biz.app WHERE id=? AND is_delete=0",
                Long.class, appId);
        ThrowUtils.throwIf(owners.isEmpty() || owners.getFirst() != ownerId,
                ErrorCode.NOT_FOUND_ERROR, "应用不存在。");
    }

    /** 供 controller 判断该应用是否需要权限模式（导入编辑独有能力）。 */
    public boolean isImportApp(long appId) {
        return gate.requireAgentEditAllowed(appId) != null;
    }
}