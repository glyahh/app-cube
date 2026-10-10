package com.dbts.glyahhaigeneratecode.projectimport;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.LoggerFactory;
import java.time.Instant;
import java.util.*;
import java.util.function.Consumer;

/**
 * 安装请求的脱敏计时；只观察，不参与执行资格、超时或清理决定。
 * 阶段可以汇出给安装任务持久化，但汇出失败不影响安装本身。
 */
final class ImportInstallDiagnostics {
    private static final ThreadLocal<Context> CURRENT = new ThreadLocal<>();
    private static final ObjectMapper JSON = new ObjectMapper();
    private static final List<String> STAGES = ImportInstallStages.NAMES;

    private static final class Context {
        final String diagnosticId = UUID.randomUUID().toString();
        final long appId;
        final String jobId;
        final Consumer<ImportStartupInstallJob.Stage> sink;
        final Set<String> seen = new HashSet<>();
        UUID executionId;
        String commandStage;
        Context(long appId, String jobId, Consumer<ImportStartupInstallJob.Stage> sink) { this.appId = appId; this.jobId = jobId; this.sink = sink; }
    }
    @FunctionalInterface interface Action<T, E extends Exception> { T run() throws E; }

    static Scope open(long appId) { return new Scope(appId, null, null); }
    /** 绑定安装任务身份；阶段会同时写日志和汇出给任务登记。 */
    static Scope open(long appId, String jobId, Consumer<ImportStartupInstallJob.Stage> sink) { return new Scope(appId, jobId, sink); }
    static void execution(UUID id) {
        var context = CURRENT.get();
        if (context != null) {
            context.executionId = id;
        }
    }

    static void setCommandStage(String stage) {
        var context = CURRENT.get();
        if (context != null) {
            context.commandStage = stage;
        }
    }

    static String commandStage(String command) {
        var context = CURRENT.get();
        return context != null && context.commandStage != null
                ? context.commandStage : commandType(command);
    }

    static Span start(String stage) { return start(stage, stage); }
    static Span start(String stage, String commandType) { return new Span(CURRENT.get(), stage, commandType); }
    static <T, E extends Exception> T call(String stage, Action<T, E> action) throws E {
        try (var span = start(stage)) {
            try {
                var result = action.run();
                if (result instanceof ImportExecutionRunner.Result command) {
                    boolean ok = "SUCCEEDED".equals(command.state());
                    if (CURRENT.get() != null && command.executionId() != null) CURRENT.get().executionId = command.executionId();
                    span.result(ok ? "SUCCEEDED" : "FAILED", ok ? null : command.reason(), command.exitCode());
                } else if (result instanceof ImportExecutionSandbox.Engine.Result command)
                    span.result(command.ok()?"SUCCEEDED":"FAILED",command.ok()?null:"COMMAND_FAILED",command.exitCode());
                else if("ADMISSION".equals(stage)&&result instanceof Collection<?> refused&&!refused.isEmpty())
                    span.result("REFUSED","ADMISSION_REFUSED",null);
                else if(result instanceof Boolean confirmed&&!confirmed)
                    span.result("FAILED","CHECK_NOT_CONFIRMED",null);
                else if("VERIFY".equals(stage)&&result instanceof String)
                    span.result("REFUSED","LIMIT_NOT_APPLIED",null);
                else if(result instanceof ImportExecutionRunner.Result execution)span.result(execution.state(),execution.reason(),execution.exitCode());
                else span.result("SUCCEEDED", null, null);
                return result;
            }
            catch (Exception error) { span.result("FAILED", error.getClass().getSimpleName(), null); throw error; }
        }
    }
    /** 标记框架内部阶段进入并正常结束，让阶段摘要不总是 NOT_STARTED。 */
    static <T, E extends Exception> T phase(String stage, Action<T, E> action) throws E {
        return call(stage, action);
    }
    static String commandType(String command) {
        if (command != null && command.matches("(?s).*\\b(npm|pnpm|yarn)\\b.*")) return "NPM_INSTALL";
        if (command != null && command.matches("(?s).*\\b(pip|pip3)\\b.*")) return "PIP_INSTALL";
        return "OTHER_INSTALL";
    }

    static final class Scope implements AutoCloseable {
        private final Context previous = CURRENT.get();
        private final Context context;
        private final Span request;
        Scope(long appId, String jobId, Consumer<ImportStartupInstallJob.Stage> sink) { context = new Context(appId, jobId, sink); CURRENT.set(context); request = start("INSTALL_REQUEST"); }
        void result(String state, String reason) { request.result(state, reason, null); }
        @Override public void close() {
            try {
                request.close();
                for (String stage : STAGES) if (!context.seen.contains(stage)) {
                    emit(context, UUID.randomUUID().toString(), stage, stage, null, null, null, null, "NOT_STARTED", null);
                    publish(context, stage, stage, "NOT_STARTED", null, null, null, null, null);
                }
            } finally { if (previous == null) CURRENT.remove(); else CURRENT.set(previous); }
        }
    }

    static final class Span implements AutoCloseable {
        private final Context context;
        private final String spanId = UUID.randomUUID().toString();
        private final String stage, commandType;
        private final Instant start = Instant.now();
        private final long nanos = System.nanoTime();
        private String status = "INCOMPLETE", reason = "STAGE_ABORTED";
        private Integer exitCode;
        private boolean closed;
        private Instant measuredEnd;
        private Long measuredDuration;
        Span(Context context, String stage, String commandType) {
            this.context = context; this.stage = stage; this.commandType = commandType;
            if (context != null) { context.seen.add(stage); emit(context, spanId, stage, commandType, start, null, null, null, "STARTED", null); publish(context,stage,commandType,"STARTED",null,null,start,null,null); }
        }
        void result(String status, String reason, Integer exitCode) { this.status = status; this.reason = reason; this.exitCode = exitCode; }
        void stopClock() { if(measuredEnd==null){ measuredEnd=Instant.now(); measuredDuration=(System.nanoTime()-nanos)/1_000_000; } }
        @Override public void close() {
            stopClock();
            if (!closed && context != null) {
                emit(context, spanId, stage, commandType, start, measuredEnd, measuredDuration, exitCode, status, reason);
                publish(context, stage, commandType, status, reason, exitCode, start, measuredEnd, measuredDuration);
            }
            closed = true;
        }
    }

    private static void emit(Context context, String spanId, String stage, String commandType, Instant start, Instant end,
                             Long duration, Integer exitCode, String status, String reason) {
        // 只写固定类型和身份，不接收命令正文、路径、环境变量或异常消息。
        var fields = new LinkedHashMap<String, Object>();
        fields.put("diagnosticId", context.diagnosticId); fields.put("spanId", spanId);
        fields.put("jobId", context.jobId);
        fields.put("executionId", context.executionId == null ? null : context.executionId.toString());
        fields.put("appId", Long.toString(context.appId)); fields.put("stage", stage); fields.put("commandType", commandType);
        fields.put("startTime", start == null ? null : start.toString()); fields.put("endTime", end == null ? null : end.toString());
        fields.put("durationMs", duration); fields.put("exitCode", exitCode); fields.put("status", safe(status)); fields.put("failureReason", safe(reason));
        try { LoggerFactory.getLogger(ImportInstallDiagnostics.class).info("IMPORT_INSTALL_DIAGNOSTIC {}", JSON.writeValueAsString(fields)); }
        catch (Exception ignored) { /* 诊断写入失败不改变执行结果。 */ }
    }
    private static void publish(Context context, String stage, String commandType, String status, String reason, Integer exitCode,
                                Instant start, Instant end, Long duration) {
        // 只汇出计划要求的固定阶段；Docker CLI、进程等待等内部 span 留在日志里。
        // EXIT_OBSERVED 是执行引擎的命令结束标记，结果由外层命令阶段判定，不重复汇出。
        if (context == null || context.sink == null || !STAGES.contains(stage) || "EXIT_OBSERVED".equals(status)) return;
        try {
            context.sink.accept(new ImportStartupInstallJob.Stage(stage, commandType, status, safe(reason), exitCode, start, end, duration,
                context.executionId == null ? null : context.executionId.toString()));
        } catch (RuntimeException ignored) { /* 阶段汇出失败不改变执行结果。 */ }
    }
    private static String safe(String value) { return value == null ? null : value.matches("[A-Za-z][A-Za-z0-9_:,]{0,160}") ? value : "REDACTED"; }
}
