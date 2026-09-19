package com.dbts.glyahhaigeneratecode.model.VO;

import lombok.Data;

import java.io.Serializable;
import java.util.List;

/**
 * 项目工作区描述视图对象。
 *
 * <p>存在的理由：前端历史上按「创建时选的 codeGenType」决定预览入口 —— 只要类型是
 * vue 就固定去取 {@code dist/index.html}。但产物目录里未必有 dist（例如一次纯静态页面
 * 需求被关键词路由判成了 vue），于是预览指向一个不存在的文件，页面空白。
 *
 * <p>这里改成由后端读真实产物来决定「能不能预览、入口是哪个」，前端只消费结论。
 */
@Data
public class AppWorkspaceVO implements Serializable {

    /**
     * 应用 id
     */
    private Long appId;

    /**
     * 应用记录里的生成类型。仅作旧数据兼容展示，不再作为预览入口的依据。
     */
    private String codeGenType;

    /**
     * 实际产物目录名，如 {@code vue_project_431379639885025280}、{@code html_431379639885025280}。
     */
    private String workspaceDirName;

    /**
     * 产物目录是否已存在
     */
    private Boolean hasSource;

    /**
     * 可直接用静态资源访问的入口，相对产物目录，如 {@code index.html} 或 {@code dist/index.html}。
     * 没有可用静态入口时为 null。
     */
    private String staticEntry;

    /**
     * 当前可用能力标识，目前只有 {@code preview}；无可用能力时为空列表。
     */
    private List<String> capabilities;

    /**
     * 能力缺失的原因，可直接展示给用户；无异常时为空。
     */
    private String unsupportedReason;

    /**
     * 已启用的看板功能（{@code metrics} 监控 / {@code logs} 日志 / {@code knowledge} 知识图谱）。
     * 全不选时为空列表。
     */
    private List<String> dashboardFeatures;

    private static final long serialVersionUID = 1L;
}
