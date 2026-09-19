package com.dbts.glyahhaigeneratecode.model.VO;

import lombok.Data;

import java.io.Serializable;

/**
 * 项目文件树的一个条目（单层，不递归）。
 *
 * <p>与 {@link ProjectFileVO} 的区别：那个接口一次把整个工作区的文本文件**连同内容**
 * 全量返回；文件树需要按需展开，所以这里只描述结构，不携带内容。
 */
@Data
public class ProjectTreeEntryVO implements Serializable {

    /**
     * 条目名称（不含路径），如 {@code main.ts}
     */
    private String name;

    /**
     * 相对工作区根的路径，统一用 {@code /} 分隔
     */
    private String path;

    /**
     * 条目类型：{@code dir} 或 {@code file}
     */
    private String type;

    /**
     * 文件字节数；目录为 null
     */
    private Long size;

    /**
     * 是否可以作为文本编辑；目录为 null
     */
    private Boolean isText;

    /**
     * 最后修改时间（毫秒）
     */
    private Long updatedAt;

    private static final long serialVersionUID = 1L;
}
