package com.dbts.glyahhaigeneratecode.config;

import org.springframework.context.annotation.Configuration;
import org.springframework.scheduling.annotation.EnableScheduling;

/**
 * 启用 Spring 计划任务（{@link org.springframework.scheduling.annotation.Scheduled}）。
 *
 * <p>当前没有任何 {@code @Scheduled} 任务在用 —— 原先唯一的用途是 V4 双轨记忆的
 * 定时清理，那套记忆已随旧链路移除。保留这个开关是为了后续加定时任务时不必再改配置。
 */
@Configuration
@EnableScheduling
public class SchedulingConfig {
}
