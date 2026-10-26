package com.dbts.glyahhaigeneratecode.agent;

import com.dbts.glyahhaigeneratecode.controller.AgentChatController;
import com.dbts.glyahhaigeneratecode.exception.*;
import com.dbts.glyahhaigeneratecode.mapper.*;
import com.dbts.glyahhaigeneratecode.model.Entity.*;
import com.dbts.glyahhaigeneratecode.service.*;
import com.dbts.glyahhaigeneratecode.rateLimiter.service.RateLimiterService;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.web.socket.*;
import java.util.HashMap;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class AgentOwnershipBeforeWriteTest {
    @Test void sseRejectsForeignAndMissingAppsBeforeHistoryOrRun() {
        for(ErrorCode code : new ErrorCode[]{ErrorCode.NO_AUTH_ERROR,ErrorCode.NOT_FOUND_ERROR}) {
            var factory=mock(AgentRunRequestFactory.class); var persistence=mock(AgentRunPersistence.class);
            var runs=mock(AgentRunService.class); var users=mock(UserService.class);
            var user=new User(); user.setId(2L); when(users.getUserInSession(any())).thenReturn(user);
            doThrow(new MyException(code,"denied")).when(factory).requireOwnedApp(1L,2L);
            var controller=new AgentChatController(runs,factory,persistence,new AgentProperties(),users);
            assertThrows(MyException.class,()->controller.stream(1L,"hello",null,new MockHttpServletRequest()));
            verifyNoInteractions(persistence,runs); verify(factory,never()).build(any(),any(),any(),any());
        }
    }
    @Test void allowedSseChecksOwnershipBeforePersistingAndBuilding() {
        var factory=mock(AgentRunRequestFactory.class); var persistence=mock(AgentRunPersistence.class);
        var runs=mock(AgentRunService.class); var users=mock(UserService.class);
        var user=new User(); user.setId(2L); when(users.getUserInSession(any())).thenReturn(user);
        when(persistence.beginRound(1L,2L,"hello",null)).thenReturn(9L);
        var request=AgentRunRequest.builder().runId("10").appId("1").userId("2").build();
        when(factory.build(1L,2L,"hello",9L)).thenReturn(request);
        when(runs.streamForDisplay(eq(1L),org.mockito.ArgumentMatchers.<java.util.function.Supplier<AgentRunRequest>>any())).thenReturn(reactor.core.publisher.Flux.empty());
        new AgentChatController(runs,factory,persistence,new AgentProperties(),users)
                .stream(1L,"hello",null,new MockHttpServletRequest()).blockLast();
        var order=inOrder(factory,persistence);
        order.verify(factory).requireOwnedApp(1L,2L);
        order.verify(persistence).beginRound(1L,2L,"hello",null);
        order.verify(factory).build(1L,2L,"hello",9L);
    }
    @Test void websocketRejectsForeignAndMissingAppsBeforeHistoryOrRun() throws Exception {
        for(ErrorCode code : new ErrorCode[]{ErrorCode.NO_AUTH_ERROR,ErrorCode.NOT_FOUND_ERROR}) {
            var factory=mock(AgentRunRequestFactory.class); var persistence=mock(AgentRunPersistence.class);
            var runs=mock(AgentRunService.class);
            doThrow(new MyException(code,"denied")).when(factory).requireOwnedApp(1L,2L);
            var handler=new AgentWebSocketHandler(runs,factory,persistence,new AgentProperties(),mock(RateLimiterService.class),new ObjectMapper());
            var session=mock(WebSocketSession.class); when(session.getId()).thenReturn("test"); when(session.isOpen()).thenReturn(true);
            var attributes=new HashMap<String,Object>(); attributes.put(AgentHandshakeInterceptor.ATTR_USER_ID,2L);
            when(session.getAttributes()).thenReturn(attributes);
            var existing=mock(reactor.core.Disposable.class);
            @SuppressWarnings("unchecked")
            var running=(java.util.Map<String,reactor.core.Disposable>)org.springframework.test.util.ReflectionTestUtils.getField(handler,"running");
            running.put("test",existing);
            handler.handleTextMessage(session,new TextMessage("{\"action\":\"start\",\"appId\":1,\"message\":\"hello\"}"));
            verifyNoInteractions(persistence,runs); verify(factory,never()).build(any(),any(),any(),any());
            verify(existing,never()).dispose();
            verify(session,atLeastOnce()).sendMessage(any());
        }
    }
    @Test void persistenceGuardCannotSwallowDenialOrWriteForeignHistory() {
        var history=mock(ChatHistoryService.class); var apps=mock(AppMapper.class);
        var persistence=new AgentRunPersistence(history,mock(AgentRunMapper.class),apps,mock(org.springframework.context.ApplicationEventPublisher.class));
        assertThrows(MyException.class,()->persistence.beginRound(1L,2L,"hello",null));
        var app=new App(); app.setUserId(3L); when(apps.selectOneById(1L)).thenReturn(app);
        assertThrows(MyException.class,()->persistence.beginRound(1L,2L,"hello",null));
        verifyNoInteractions(history);
        app.setUserId(2L);
        when(history.addChatMessageAndReturnId(any(),any(),any(),any(),any(),any(),any())).thenReturn(9L);
        assertEquals(9L,persistence.beginRound(1L,2L,"hello",null));
    }
    @Test void sharedOwnershipCheckRejectsMissingForeignAndInvalidUsers() {
        var apps=mock(AppService.class);
        var factory=new AgentRunRequestFactory(apps,null,null,null,null,null,null,null,null,null);
        assertThrows(MyException.class,()->factory.requireOwnedApp(1L,2L));
        var app=new App(); app.setUserId(3L); when(apps.getById(1L)).thenReturn(app);
        assertThrows(MyException.class,()->factory.requireOwnedApp(1L,2L));
        assertThrows(MyException.class,()->factory.requireOwnedApp(1L,null));
        app.setUserId(2L); assertSame(app,factory.requireOwnedApp(1L,2L));
    }
}
