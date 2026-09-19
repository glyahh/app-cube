package com.dbts.glyahhaigeneratecode.model.enums;

import cn.hutool.core.util.ObjUtil;
import lombok.Getter;

@Getter
public enum CodeGenTypeEnum {

    HTML("原生 HTML 模式", "html"),
    MULTI_FILE("原生多文件模式", "multi_file"),
    VUE("Vue3 项目模式", "vue"),
    /**
     * 新项目的统一类型。
     *
     * <p>不再让用户预选，也不再按需求文本的关键词猜 —— 产物形态由 agent 按实际需要决定，
     * 能不能预览/运行由工作区描述按真实产物判定。旧项目各自保留原来的类型值。
     */
    AUTO("自动模式", "auto");

    private final String text;
    private final String value;

    CodeGenTypeEnum(String text, String value) {
        this.text = text;
        this.value = value;
    }

    /**
     * 产物目录名前缀。
     *
     * <p>VUE 的目录是 {@code vue_project_{appId}} 而不是 {@code vue_{appId}}，
     * 与枚举 value 不一致。这是历史遗留（前端还会传 {@code vue_project}，
     * 见 {@link #getEnumByValue} 的归一化），**不要改**，改了会找不到已有产物。
     *
     * <p>AUTO 用与语言无关的 {@code project_{appId}}：新项目的产物形态由 agent 决定，
     * 目录名不该再编码「是哪一种生成模式」。
     */
    public String getDirPrefix() {
        if (this == VUE) {
            return "vue_project";
        }
        if (this == AUTO) {
            return "project";
        }
        return value;
    }

    /**
     * 根据 value 获取枚举
     *
     * @param value 枚举值的value
     * @return 枚举值
     */
    public static CodeGenTypeEnum getEnumByValue(String value) {
        if (ObjUtil.isEmpty(value)) {
            return null;
        }
        // 前端 codeGenType 可能传入 vue_project，但后端枚举 value 约定为 vue
        String normalized = String.valueOf(value).trim();
        if ("vue_project".equalsIgnoreCase(normalized)) {
            normalized = "vue";
        }
        // 注意这里的value不是字段的value，而是返回所有枚举类的方法
        for (CodeGenTypeEnum anEnum : CodeGenTypeEnum.values()) {
            if (anEnum.value.equals(normalized)) {
                return anEnum;
            }
        }
        return null;
    }
}
