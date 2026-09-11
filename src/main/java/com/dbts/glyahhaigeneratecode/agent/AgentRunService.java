package com.dbts.glyahhaigeneratecode.agent;

import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import reactor.core.publisher.Flux;
import reactor.core.scheduler.Schedulers;

import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;

/**
 * 把 Python 子进程的阻塞读取桥接成 Reactor 流。
 *
 * <p>两条传输路径（WebSocket 与 SSE）都从这里拿 {@code Flux<AgentEvent>}，
 * 各自只做传输适配 —— 执行逻辑只有一份。
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class AgentRunService {

    private final AgentWorkerPool pool;
    private final AgentProperties props;
    private final ObjectMapper objectMapper;

    /**
     * 跑一次 run，返回事件流。
     *
     * <p>取消语义：订阅方取消 → 杀掉 Python 进程 → 阻塞中的读立刻 EOF → 循环退出。
     * 这也是子进程方案相对 HTTP 方案最省事的地方，不需要协作式取消加轮询。
     *
     * @param requestJson 已经序列化好的请求帧
     */
    public Flux<AgentEvent> stream(String requestJson) {
        return Flux.<AgentEvent>create(sink -> {
            // 取消可能发生在「进程已归还池子」之后。若不设这道闸，
            // 迟到的取消会把一个健康的池内进程杀掉。
            AtomicBoolean cancelled = new AtomicBoolean(false);
            AtomicReference<AgentWorker> holder = new AtomicReference<>();

            sink.onCancel(() -> {
                cancelled.set(true);
                // 用 getAndSet 抢占 —— 与 settle 的结算互斥，避免同一个进程被处理两次。
                // 抢不到说明已经结算完了（进程已归池），此时**不能**再杀它，
                // 否则会杀掉一个健康的池内进程。
                AgentWorker worker = holder.getAndSet(null);
                if (worker != null) {
                    pool.discard(worker);
                }
            });

            AgentWorker worker = null;
            boolean cleanFinish = false;
            try {
                // 1. 借进程（池满时这里会等待，超时抛 TOO_MANY_REQUEST）
                worker = pool.acquire();
                holder.set(worker);

                // 2. 下发请求
                worker.send(requestJson);

                // 3. 逐帧读取，直到终止帧或流结束
                String line;
                while ((line = worker.readEvent(props.getReadTimeout())) != null) {
                    AgentEvent event;
                    try {
                        event = AgentEvent.parse(objectMapper, line);
                    } catch (Exception e) {
                        // 单帧解析失败不该弄死整轮 —— 记日志跳过，继续读。
                        // 保持与 Python 侧同样的态度：坏输入降级，不静默中断。
                        log.warn("跳过无法解析的事件帧：{}", line, e);
                        continue;
                    }
                    sink.next(event);
                    if (event.isEnd()) {
                        break;
                    }
                }
                cleanFinish = true;
            } catch (Throwable t) {
                log.warn("Agent run 执行失败", t);
                settle(worker, holder, cancelled, cleanFinish);
                sink.error(t);
                return;
            }

            // 先把进程归还，再通知完成 —— 否则消费方看到流结束时资源还没回池，
            // 紧接着查池会看到它仍在借出中。
            settle(worker, holder, cancelled, cleanFinish);
            sink.complete();
        }).subscribeOn(Schedulers.boundedElastic());
    }

    /**
     * 决定进程的最终归属。
     *
     * <p>干净跑完 → 归还；取消、超时、进程已死 → 丢弃（状态不可信）。
     * 用 CAS 保证只结算一次：取消信号可能比正常收尾先到。
     */
    private void settle(AgentWorker worker,
                        AtomicReference<AgentWorker> holder,
                        AtomicBoolean cancelled,
                        boolean cleanFinish) {
        AgentWorker used = holder.getAndSet(null);
        if (used == null) {
            // 已经被取消路径处理过了
            return;
        }
        if (cancelled.get() || !cleanFinish || !used.isAlive()) {
            pool.discard(used);
        } else {
            pool.release(used);
        }
    }
}
