package com.dbts.glyahhaigeneratecode.model.DTO;

import lombok.Data;

import java.io.Serializable;
import java.util.List;

/**
 * 修改应用看板功能开关的请求。
 */
@Data
public class DashboardFeaturesRequest implements Serializable {

    /**
     * 要启用的功能标识（{@code metrics} / {@code logs} / {@code knowledge}）。
     * 空列表表示全部关闭。
     */
    private List<String> features;

    private static final long serialVersionUID = 1L;
}
