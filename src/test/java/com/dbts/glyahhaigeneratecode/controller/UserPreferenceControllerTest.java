package com.dbts.glyahhaigeneratecode.controller;

import com.dbts.glyahhaigeneratecode.model.Entity.User;
import com.dbts.glyahhaigeneratecode.service.UserService;
import com.dbts.glyahhaigeneratecode.exception.MyException;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockHttpServletRequest;
import org.mockito.ArgumentCaptor;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class UserPreferenceControllerTest {
    @Test void updateUsesSessionIdentityAndOnlyPreference() {
        UserService service = mock(UserService.class);
        var request = new MockHttpServletRequest();
        when(service.getUserInSession(request)).thenReturn(User.builder().id(42L).build());
        when(service.updateById(any(User.class))).thenReturn(true);
        new UserPreferenceController(service).update(new UserPreferenceController.Preference(true), request);
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
                .update(new UserPreferenceController.Preference(null), request));
        verify(service, never()).updateById(any(User.class));
    }
}
