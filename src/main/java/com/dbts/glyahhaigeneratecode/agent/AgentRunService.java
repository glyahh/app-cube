package com.dbts.glyahhaigeneratecode.agent;

import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;
import reactor.core.scheduler.Schedulers;

import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;
import java.util.concurrent.TimeoutException;
import java.util.function.Supplier;

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
    private final AppRunCoordinator coordinator;
    private final AgentRunPersistence persistence;

    /**
     * 跑一次 run，返回事件流。
     *
     * <p>取消语义：订阅方取消 → 杀掉 Python 进程 → 阻塞中的读立刻 EOF → 循环退出。
     * 这也是子进程方案相对 HTTP 方案最省事的地方，不需要协作式取消加轮询。
     *
     * <p>整个 run 挂在 {@code appId} 的临界区里。同一个 app 的并发请求在这里排队 ——
     * 它们写的是同一个产物目录，并跑会互相覆盖。
     *
     * @param appId       应用标识，用于串行化
     * @param requestJson 已经序列化好的请求帧
     */
    public Flux<AgentEvent> stream(Long appId, String requestJson) {
        return coordinator.serialize(appId, () -> runOnce(requestJson))
                // 调度必须留在最外层：等锁是阻塞的，而 defer 的落点由它外层的 subscribeOn
                // 决定。放在 runOnce 里面的话，等锁会占住订阅线程（SSE 的 Tomcat 线程
                // 或 WS 的 Netty 事件循环）而不是 boundedElastic。
                .subscribeOn(Schedulers.boundedElastic());
    }

    /** SSE 与 WebSocket 的唯一展示入口，原始流保留内部执行语义。 */
    public Flux<AgentEvent> streamForDisplay(Long appId, String requestJson) {
        return UserFacingOutputHandler.apply(stream(appId, requestJson));
    }

    /**
     * 主生成统一入口。请求 supplier 在拿到 App 锁后执行，确保读取最新路由状态。
     */
    public Flux<AgentEvent> streamForDisplay(Long appId, Supplier<AgentRunRequest> requestSupplier) {
        return coordinator.serialize(appId, () -> Flux.defer(() -> {
                    AgentRunRequest request = requestSupplier.get();
                    persistence.recordRun(request);
                    Flux<AgentEvent> audited = persistence.tap(
                            runOnce(request.toFrameJson(objectMapper)), request);
                    return UserFacingOutputHandler.apply(audited);
                }))
                .subscribeOn(Schedulers.boundedElastic());
    }

    /**
     * 跑一次 run，但**不进项目的串行队列**。
     *
     * <p>知识任务走这条：它只读分析、不写产物目录，不该排在用户的生成请求后面，
     * 也不该把用户的生成请求堵在后面。代价是并发控制要由调用方自己负责 ——
     * 这里的调用方用独立信号量把知识任务限到很低。
     */
    public Flux<AgentEvent> streamUncoordinated(String requestJson) {
        return runOnce(requestJson).subscribeOn(Schedulers.boundedElastic());
    }

    /** 单次执行：借进程、下发、逐帧读回。锁的持有范围由调用方 {@link #stream} 划定。 */
    private Flux<AgentEvent> runOnce(String requestJson) {
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
                if (cancelled.get()) {
                    settle(worker, holder, cancelled, false);
                    return;
                }

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
        // 墙钟上限不随事件刷新，持续心跳也不能让卡住的任务永久占用进程。
        }).takeUntilOther(Mono.delay(props.getRunDeadline())
                .flatMap(ignored -> Mono.error(new TimeoutException("Agent 执行超过时间上限"))));
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
