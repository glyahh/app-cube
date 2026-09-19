package com.dbts.glyahhaigeneratecode.model.VO;

import lombok.Data;

import java.io.Serializable;

/**
 * 单个项目文件的内容。
 *
 * <p>二进制文件不返回 content：{@link #isText} 为 false 时前端只展示基本信息与下载入口，
 * 不强行作为文本打开。
 */
@Data
public class ProjectFileContentVO implements Serializable {

    /**
     * 相对工作区根的路径
     */
    private String path;

    /**
     * 是否可以按文本编辑
     */
    private Boolean isText;

    /**
     * 文件字节数
     */
    private Long size;

    /**
     * 语言标识（仅文本文件有值）
     */
    private String language;

    /**
     * 文件内容；非文本文件为 null
     */
    private String content;

    /**
     * 内容版本标识。保存时需原样回传，服务端据此判断文件是否已被他人改写。
     *
     * <p>不用 lastModified 做版本：同一毫秒内的两次写入会得到相同的时间戳，
     * 「保存后立刻再保存」的校验会失效。内容摘要没有这个问题。
     */
    private String version;

    /**
     * 最后修改时间（毫秒）
     */
    private Long updatedAt;

    private static final long serialVersionUID = 1L;
}
