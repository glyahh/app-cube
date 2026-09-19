package com.dbts.glyahhaigeneratecode.agent;

import com.dbts.glyahhaigeneratecode.mapper.AppMapper;
import com.dbts.glyahhaigeneratecode.mapper.UserMapper;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.context.event.EventListener;
import org.springframework.stereotype.Service;
import java.time.Duration;
import java.util.UUID;
import java.util.concurrent.Semaphore;

@Service
@RequiredArgsConstructor
@Slf4j
public class ProjectTitleService {
    private final AppMapper appMapper;
    private final UserMapper userMapper;
    private final AgentRunService runs;
    private final ObjectMapper objectMapper;
    private final Semaphore slots = new Semaphore(2);

    @EventListener
    public void onRequested(ProjectTitleRequested event) {
        Thread.startVirtualThread(() -> generate(event.request()));
    }

    void generate(AgentRunRequest source) {
        boolean acquired = false;
        try {
            // 1. 只接受首轮，数据库保证重连和并发不会重复调用。
            if (source.getRoundId() == null) return;
            Long appId = Long.valueOf(source.getAppId());
            Long userId = Long.valueOf(source.getUserId());
            if (appMapper.claimAutoTitle(appId, userId, Long.valueOf(source.getRoundId())) != 1) return;
            var user = userMapper.selectOneById(userId);
            if (user == null || Boolean.FALSE.equals(user.getAutoNameProject())) return;
            var app = appMapper.selectOneById(appId);
            if (app == null || !slots.tryAcquire()) return;
            acquired = true;

            // 2. 独立单次摘要请求，没有工具、工作区操作或生成会话记忆。
            AgentRunRequest request = source.toBuilder()
                    .runId(UUID.randomUUID().toString()).roundId(null)
                    .threadId("app:" + appId + ":title").taskKind("title")
                    .allowProxyFallback(false).deadlineMs(60000L).maxModelCalls(1).build();
            var events = runs.streamUncoordinated(request.toFrameJson(objectMapper))
                    .timeout(Duration.ofSeconds(60)).collectList().block(Duration.ofSeconds(65));
            if (events == null || events.stream().noneMatch(e -> AgentEvent.RUN_COMPLETED.equals(e.event()) && "succeeded".equals(e.status()))) return;
            String title = events.stream().filter(e -> AgentEvent.TEXT_MESSAGE.equals(e.event()))
                    .map(AgentEvent::textContent).filter(t -> t != null).reduce((a, b) -> b).orElse("");
            title = cleanTitle(title);

            // 3. 比对原名称与编辑时间，不覆盖期间的手动改名。
            if (!title.isEmpty()) appMapper.finishAutoTitle(appId, app.getAppName(), app.getEditTime(), title);
        } catch (Exception e) {
            log.warn("自动命名失败 appId={}", source.getAppId());
        } finally {
            if (acquired) slots.release();
        }
    }

    static String cleanTitle(String value) {
        String title = value.strip().replaceAll("^[\"'「『]+|[\"'」』]+$", "");
        if (title.isBlank() || title.contains("\n") || title.contains("\r") || title.contains("<")) return "";
        return title.codePoints().limit(24).collect(StringBuilder::new,
                StringBuilder::appendCodePoint, StringBuilder::append).toString();
    }
}
