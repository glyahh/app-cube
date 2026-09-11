package com.dbts.glyahhaigeneratecode.service.impl;

import com.dbts.glyahhaigeneratecode.constant.UserConstant;
import com.dbts.glyahhaigeneratecode.model.Entity.App;
import com.dbts.glyahhaigeneratecode.model.Entity.User;
import com.dbts.glyahhaigeneratecode.service.ChatHistoryService;
import com.dbts.glyahhaigeneratecode.service.support.AppServiceSupport;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.test.util.ReflectionTestUtils;

import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.doReturn;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.spy;
import static org.mockito.Mockito.verify;

@ExtendWith(MockitoExtension.class)
class AppServiceImplRagCleanupTest {

    private static final Long APP_ID = 88L;
    private static final Long USER_ID = 99L;

    @Mock
    private ChatHistoryService chatHistoryService;
    @Mock
    private AppServiceSupport appServiceSupport;
    @Mock
    private CodeChunkIndexService codeChunkIndexService;

    private AppServiceImpl appService;

    @BeforeEach
    void setUp() {
        appService = spy(new AppServiceImpl());
        ReflectionTestUtils.setField(appService, "chatHistoryService", chatHistoryService);
        ReflectionTestUtils.setField(appService, "appServiceSupport", appServiceSupport);
        ReflectionTestUtils.setField(appService, "codeChunkIndexService", codeChunkIndexService);
        doReturn(true).when(appService).removeById(APP_ID);
    }

    @Test
    void deleteMyApp_cleansRagChunks() {
        App app = App.builder().id(APP_ID).userId(USER_ID).build();
        User user = User.builder().id(USER_ID).userRole(UserConstant.DEFAULT_ROLE).build();
        doReturn(app).when(appService).getById(APP_ID);

        assertTrue(appService.deleteMyApp(user, APP_ID));

        verify(codeChunkIndexService).deleteAppChunks(APP_ID);
    }

    @Test
    void deleteAppByAdmin_cleansRagChunks() {
        assertTrue(appService.deleteAppByAdmin(APP_ID));

        verify(codeChunkIndexService).deleteAppChunks(APP_ID);
    }

    @Test
    void deleteMyApp_ragCleanupFailure_doesNotBlockDeletion() {
        App app = App.builder().id(APP_ID).userId(USER_ID).build();
        User user = User.builder().id(USER_ID).userRole(UserConstant.DEFAULT_ROLE).build();
        doReturn(app).when(appService).getById(APP_ID);
        doThrow(new RuntimeException("es unavailable")).when(codeChunkIndexService).deleteAppChunks(APP_ID);

        assertTrue(appService.deleteMyApp(user, APP_ID));
    }
}
