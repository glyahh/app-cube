package com.dbts.glyahhaigeneratecode.agent;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.context.event.EventListener;
import org.springframework.stereotype.Component;

/** 模型配置保存后回收空闲 Worker；运行中的任务保持旧配置直到归还。 */
@Slf4j
@Component
@RequiredArgsConstructor
public class AgentModelConfigReloadListener {

    private final AgentWorkerPool pool;

    @EventListener
    public void onChanged(ModelConfigChangedEvent event) {
        try {
            pool.recycleForModelReload();
        } catch (RuntimeException error) {
            log.warn("模型配置已保存，但 Worker 池回收失败；旧进程仍在使用旧配置", error);
        }
    }
}
