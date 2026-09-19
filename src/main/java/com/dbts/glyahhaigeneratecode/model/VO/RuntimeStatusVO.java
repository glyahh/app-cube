package com.dbts.glyahhaigeneratecode.model.VO;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.io.Serializable;
import java.time.LocalDateTime;
import java.util.List;

/**
 * 应用运行状态视图对象。
 *
 * <p>面向前端工具栏与运行面板：现在跑没跑、跑的是哪一版、怎么访问、有没有异常。
 * {@code stale} 是「运行版本落后」—— 用户改了代码但没重新运行时的提示依据。
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class RuntimeStatusVO implements Serializable {

    private static final long serialVersionUID = 1L;

    private Long appId;

    /** 启动编号；从未运行过为 null。 */
    private Integer runNo;

    /** STARTING / RUNNING / STOPPED / FAILED / UNKNOWN */
    private String status;

    /** 是否正在运行（前端直接用这个判断按钮形态）。 */
    private Boolean running;

    private String containerName;

    /** 宿主侧映射端口；无 HTTP 入口时为 null。 */
    private Integer gatewayPort;

    /** 运行形态：STATIC / NODE / PYTHON / JAVA / UNSUPPORTED。 */
    private String kind;

    /** 受管进程清单（命令与端口），供运行面板展示。 */
    private List<ProcessInfo> processes;

    /** 启动时所用源码的版本标识。 */
    private String sourceVersion;

    /** 工作区当前版本；与 {@code sourceVersion} 不同即落后。 */
    private String currentVersion;

    /** 运行版本落后：改了代码但没重新运行。 */
    private Boolean stale;

    private LocalDateTime startedAt;

    private LocalDateTime stoppedAt;

    /** 进程退出码；未退出或未知为 null。 */
    private Integer exitCode;

    private String errorMessage;

    /** 该项目当前形态是否支持运行。false 时前端给出明确不支持的原因。 */
    private Boolean supported;

    /** 单个受管进程的对外描述。 */
    @Data
    @Builder
    @NoArgsConstructor
    @AllArgsConstructor
    public static class ProcessInfo implements Serializable {

        private static final long serialVersionUID = 1L;

        private String name;

        private String command;

        private Integer port;
    }

    /** 从未运行过时的状态。 */
    public static RuntimeStatusVO notStarted(String currentVersion) {
        return RuntimeStatusVO.builder()
                .status("STOPPED")
                .running(false)
                .processes(List.of())
                .currentVersion(currentVersion)
                .stale(false)
                .supported(true)
                .build();
    }
}
