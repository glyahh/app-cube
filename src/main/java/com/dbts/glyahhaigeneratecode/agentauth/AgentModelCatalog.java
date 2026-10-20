package com.dbts.glyahhaigeneratecode.agentauth;

import com.dbts.glyahhaigeneratecode.exception.ErrorCode;
import com.dbts.glyahhaigeneratecode.exception.ThrowUtils;
import com.fasterxml.jackson.databind.JsonNode;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;

/**
 * 模型目录的解析与选择校验。
 *
 * <p>只接受平台固定模块输出的公开元数据；未知字段、重复 ID、非法力度一律明确失败，
 * 不用示例模型或默认值兜底。
 */
public final class AgentModelCatalog {

    /** 平台支持的思考力度，顺序即界面展示顺序；须与 Python EFFORTS 及数据库 CHECK 保持一致。 */
    public static final List<String> EFFORTS = List.of("low", "medium", "high", "xhigh", "max");

    /**
     * @param model 真实模型名（如 deepseek-v4.1-flash），用于界面展示
     * @param name  配置项名称，可能只是供应商别名
     */
    public record Model(String id, String name, String model, String provider,
                        List<String> efforts, boolean configured) { }

    private AgentModelCatalog() {
    }

    public static List<Model> parse(JsonNode directory) {
        ThrowUtils.throwIf(directory == null || !directory.isObject() || directory.size() != 1
                        || !directory.path("profiles").isArray() || directory.path("profiles").size() > 32,
                ErrorCode.OPERATION_ERROR, "模型目录无效。");
        var result = new ArrayList<Model>();
        var ids = new HashSet<String>();
        for (var profile : directory.path("profiles")) {
            String id = profile.path("id").asText();
            ThrowUtils.throwIf(!id.matches("[a-zA-Z0-9_-]{1,64}") || !ids.add(id)
                            || !profile.path("name").isTextual() || profile.path("name").asText().isBlank()
                            || profile.path("name").asText().length() > 120 || !profile.path("provider").isTextual()
                            || profile.path("provider").asText().length() > 120 || !profile.path("configured").isBoolean()
                            || !profile.path("efforts").isArray(), ErrorCode.OPERATION_ERROR, "模型目录无效。");
            var efforts = new ArrayList<String>();
            for (var effort : profile.path("efforts")) {
                ThrowUtils.throwIf(!effort.isTextual() || !EFFORTS.contains(effort.asText())
                        || efforts.contains(effort.asText()), ErrorCode.OPERATION_ERROR, "模型力度配置无效。");
                efforts.add(effort.asText());
            }
            String model = profile.path("model").asText();
            ThrowUtils.throwIf(!model.isBlank() && model.length() > 120,
                    ErrorCode.OPERATION_ERROR, "模型目录无效。");
            result.add(new Model(id, profile.path("name").asText(), model, profile.path("provider").asText(),
                    List.copyOf(efforts), profile.path("configured").asBoolean()));
        }
        ThrowUtils.throwIf(result.stream().noneMatch(Model::configured), ErrorCode.OPERATION_ERROR,
                "没有可用模型，请检查本地配置。");
        return List.copyOf(result);
    }

    /** 选中项必须存在且已配置；力度为空表示跟随模型默认。 */
    public static void require(List<Model> models, String id, String effort) {
        var selected = models.stream().filter(model -> model.id().equals(id) && model.configured()).findFirst();
        ThrowUtils.throwIf(selected.isEmpty(), ErrorCode.PARAMS_ERROR, "所选模型未配置或已失效。");
        ThrowUtils.throwIf(effort != null && !selected.orElseThrow().efforts().contains(effort),
                ErrorCode.PARAMS_ERROR, "所选模型不支持此思考力度。");
    }
}