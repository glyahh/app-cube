package com.dbts.glyahhaigeneratecode.model.enums;

import cn.hutool.core.util.ObjUtil;
import lombok.Getter;

@Getter
public enum CodeGenTypeEnum {

    HTML("原生 HTML 模式", "html"),
    MULTI_FILE("原生多文件模式", "multi_file"),
    VUE("Vue3 项目模式", "vue");

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
     * <p>收敛前这个映射散在 BaseTool / AppServiceImpl / VueEditContextBuilder /
     * ConversationMemoryManifestSupport / CodeGeneratorNode / VueTokenStreamAdapter
     * 六处硬编码，收敛后统一从这里取。
     */
    public String getDirPrefix() {
        return this == VUE ? "vue_project" : value;
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
