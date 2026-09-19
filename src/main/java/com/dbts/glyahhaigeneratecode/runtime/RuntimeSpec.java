package com.dbts.glyahhaigeneratecode.runtime;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.io.Serializable;
import java.util.List;

/**
 * 一个项目的运行规格：怎么把它跑起来。
 *
 * <p>由 {@link RuntimeSpecResolver} 从工作区实际内容推断得到，也可由工作区里的
 * {@code .glyahh/run.json} 显式覆盖（AI 生成复杂项目时可写它）。平台负责校验
 * 命令、工作目录、端口是否落在工作区内且合法 —— 推断结果不经过校验不许执行。
 *
 * <p>之所以是「多个进程」而不是一个：前后端分离的项目需要同时起两个进程，
 * 首期就按多进程建模，免得之后再加一层结构。
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class RuntimeSpec implements Serializable {

    private static final long serialVersionUID = 1L;

    /** 运行形态，决定平台怎么呈现与探测。 */
    private RuntimeKind kind;

    /** 受管进程列表，按顺序启动。 */
    private List<ManagedProcess> processes;

    /**
     * 展示用的入口端口（宿主侧映射后由平台分配），一般是前端/主服务的端口。
     * 没有 HTTP 入口的项目（如纯脚本）为 null —— 此时不显示 QPS。
     */
    private Integer primaryPort;

    /** 是否需要先跑一次构建（如 npm install / npm run build）。 */
    private boolean buildRequired;

    /** 构建命令（在工作区内执行，退出即结束，不作为受管进程）。 */
    private String buildCommand;

    /** 推断来源，便于排障时区分「猜的」和「声明的」。 */
    private String source;

    /** 运行形态。 */
    public enum RuntimeKind {
        /** 纯静态页面：无需进程，靠静态服务器即可。 */
        STATIC,
        /** Node.js 服务。 */
        NODE,
        /** Python 服务。 */
        PYTHON,
        /** Java 服务。 */
        JAVA,
        /** 未识别出可运行形态。 */
        UNSUPPORTED
    }

    /** 单个受管进程。 */
    @Data
    @Builder
    @NoArgsConstructor
    @AllArgsConstructor
    public static class ManagedProcess implements Serializable {

        private static final long serialVersionUID = 1L;

        /** 进程名，如 web / api，用于日志区分与状态展示。 */
        private String name;

        /** 在工作区根目录下执行的命令。 */
        private String command;

        /** 相对工作区根的工作目录，空表示根目录。 */
        private String workDir;

        /**
         * 进程监听的容器内端口。用于端口映射与就绪探测。
         * 没有网络端口的进程（如 worker）为 null。
         */
        private Integer port;

        /**
         * 就绪判据：{@code PORT}（端口可连）或 {@code NONE}（只要求进程存活）。
         */
        private Readiness readiness;

        public enum Readiness {
            /** 端口可建立连接即视为就绪。 */
            PORT,
            /** 不做主动探测，只要进程没退出就算就绪。 */
            NONE
        }
    }
}
