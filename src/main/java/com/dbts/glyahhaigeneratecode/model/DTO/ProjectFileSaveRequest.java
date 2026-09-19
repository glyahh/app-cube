package com.dbts.glyahhaigeneratecode.model.DTO;

import lombok.Data;

import java.io.Serializable;

/**
 * 保存单个项目文件的请求。
 */
@Data
public class ProjectFileSaveRequest implements Serializable {

    /**
     * 相对工作区根的路径
     */
    private String path;

    /**
     * 新的文件内容
     */
    private String content;

    /**
     * 客户端读取该文件时拿到的版本标识（{@link com.dbts.glyahhaigeneratecode.model.VO.ProjectFileContentVO#getVersion}）。
     *
     * <p>服务端会与当前内容重新比对；不一致说明文件已被 AI 写入或被其他窗口改过，拒绝覆盖。
     */
    private String baseVersion;

    private static final long serialVersionUID = 1L;
}
