package com.dbts.glyahhaigeneratecode.monitor;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.io.Serial;
import java.io.Serializable;

/**
 * 监控上下文参数
 */

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class MonitorContext implements Serializable {

    private String userId;

    private String appId;

    /** 对话轮次 id（chat_history 主键），用于 Token 按轮统计 */
    private String roundId;

    @Serial
    private static final long serialVersionUID = 1L;
}
