package com.dbts.glyahhaigeneratecode.controller;

import com.dbts.glyahhaigeneratecode.exception.MyException;
import com.dbts.glyahhaigeneratecode.model.Entity.User;
import com.dbts.glyahhaigeneratecode.service.UserService;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.mock.web.MockHttpServletRequest;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

class UserPreferenceControllerTest {
    @Test void updateUsesSessionIdentityAndOnlyPreference() {
        UserService service = mock(UserService.class);
        var request = new MockHttpServletRequest();
        when(service.getUserInSession(request)).thenReturn(User.builder().id(42L).build());
        when(service.updateById(any(User.class))).thenReturn(true);
        new UserPreferenceController(service)
                .update(new UserPreferenceController.Preference(true, null, null, null), request);
        ArgumentCaptor<User> user = ArgumentCaptor.forClass(User.class);
        verify(service).updateById(user.capture());
        assertEquals(42L, user.getValue().getId());
        assertTrue(user.getValue().getExpandExecutionProcess());
        assertNull(user.getValue().getUserRole());
        assertNull(user.getValue().getUserPassword());
    }

    @Test void rejectsMissingPreference() {
        UserService service = mock(UserService.class);
        var request = new MockHttpServletRequest();
        when(service.getUserInSession(request)).thenReturn(User.builder().id(42L).build());
        assertThrows(MyException.class, () -> new UserPreferenceController(service)
                .update(new UserPreferenceController.Preference(null, null, null, null), request));
        verify(service, never()).updateById(any(User.class));
    }

    /**
     * 只传一个新开关时，其余字段必须是 null —— updateById 会跳过 null 字段，
     * 这样三项偏好各存各的，不会互相覆盖。
     */
    @Test void partialUpdateTouchesOnlyProvidedFields() {
        UserService service = mock(UserService.class);
        var request = new MockHttpServletRequest();
        when(service.getUserInSession(request)).thenReturn(User.builder().id(7L).build());
        when(service.updateById(any(User.class))).thenReturn(true);

        new UserPreferenceController(service)
                .update(new UserPreferenceController.Preference(null, false, null, null), request);

        ArgumentCaptor<User> user = ArgumentCaptor.forClass(User.class);
        verify(service).updateById(user.capture());
        assertEquals(Boolean.FALSE, user.getValue().getAlertOnStartupFailure());
        assertNull(user.getValue().getAlertOnRuntimeFailure(), "未传的开关不该被写");
        assertNull(user.getValue().getExpandExecutionProcess(), "未传的开关不该被写");
    }

    /** 全空请求没有可改的东西，直接拒绝而不是白跑一次更新。 */
    @Test void rejectsEmptyUpdate() {
        UserService service = mock(UserService.class);
        var request = new MockHttpServletRequest();
        when(service.getUserInSession(request)).thenReturn(User.builder().id(7L).build());

        assertThrows(MyException.class, () -> new UserPreferenceController(service)
                .update(new UserPreferenceController.Preference(null, null, null, null), request));
    }

    /** 读取时空值折叠成默认值：启动异常默认开、运行异常默认关。 */
    @Test void getFoldsNullsToDefaults() {
        UserService service = mock(UserService.class);
        var request = new MockHttpServletRequest();
        when(service.getUserInSession(request)).thenReturn(User.builder().id(9L).build());
        when(service.getById(9L)).thenReturn(User.builder().id(9L).build());

        var preference = new UserPreferenceController(service).get(request).getData();

        assertEquals(Boolean.FALSE, preference.expandExecutionProcess());
        assertEquals(Boolean.TRUE, preference.alertOnStartupFailure());
        assertEquals(Boolean.FALSE, preference.alertOnRuntimeFailure());
        assertEquals(Boolean.FALSE, preference.autoModelProxyFallback());
        assertTrue(preference.autoNameProject());
    }

    @Test void autoFallbackCanBeUpdatedAlone() {
        UserService service = mock(UserService.class);
        var request = new MockHttpServletRequest();
        when(service.getUserInSession(request)).thenReturn(User.builder().id(7L).build());
        when(service.updateById(any(User.class))).thenReturn(true);

        new UserPreferenceController(service)
                .update(new UserPreferenceController.Preference(null, null, null, true), request);

        ArgumentCaptor<User> user = ArgumentCaptor.forClass(User.class);
        verify(service).updateById(user.capture());
        assertEquals(Boolean.TRUE, user.getValue().getAutoModelProxyFallback());
        assertNull(user.getValue().getExpandExecutionProcess());
        assertNull(user.getValue().getAlertOnStartupFailure());
        assertNull(user.getValue().getAlertOnRuntimeFailure());
    }
    @Test void autoNamingCanBeDisabledAlone() {
        UserService service = mock(UserService.class);
        var request = new MockHttpServletRequest();
        when(service.getUserInSession(request)).thenReturn(User.builder().id(42L).build());
        when(service.updateById(any(User.class))).thenReturn(true);
        new UserPreferenceController(service).update(
                new UserPreferenceController.Preference(null, null, null, null, false), request);
        ArgumentCaptor<User> user = ArgumentCaptor.forClass(User.class);
        verify(service).updateById(user.capture());
        assertEquals(Boolean.FALSE, user.getValue().getAutoNameProject());
        assertNull(user.getValue().getExpandExecutionProcess());
    }
}
