package com.dbts.glyahhaigeneratecode.model.DTO;

import lombok.Data;

import java.io.Serializable;
import java.util.List;

/**
 * 用户创建应用请求
 */
@Data
public class AppAddRequest implements Serializable {

    /**
     * 应用名称
     */
    private String appName;

    /**
     * 应用封面
     */
    private String cover;

    /**
     * 应用初始化的 prompt（必填）
     */
    private String initPrompt;

    /**
     * 代码生成类型（枚举）
     */
    private String codeGenType;

    /**
     * 是否 beta 应用：0-否，1-是（workflow beta）
     */
    private Integer isBeta;

    /**
     * 关联的 Loop 技能 ID 列表（创建应用时可选绑定）
     */
    private List<Long> loopIds;

    /**
     * 看板功能开关（{@code metrics} 监控 / {@code logs} 日志 / {@code knowledge} 知识图谱）。
     * 不传或空列表表示全不选。
     */
    private List<String> dashboardFeatures;

    private static final long serialVersionUID = 1L;
}

