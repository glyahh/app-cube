package com.dbts.glyahhaigeneratecode.projectimport;

import com.dbts.glyahhaigeneratecode.controller.ImportStartupPlanController;
import com.dbts.glyahhaigeneratecode.exception.MyException;
import com.dbts.glyahhaigeneratecode.model.Entity.User;
import com.dbts.glyahhaigeneratecode.service.UserService;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockHttpServletRequest;
import java.util.List;
import java.util.UUID;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class ImportStartupPlanControllerTest {
    UserService users=mock(UserService.class);ImportStartupPlanService plans=mock(ImportStartupPlanService.class);
    MockHttpServletRequest request=new MockHttpServletRequest();User user=User.builder().id(7L).build();
    ImportStartupPlanController controller=new ImportStartupPlanController(users,plans);
    @Test void creationNeedsSessionOwnerScanAndSelection(){
        when(users.getUserInSession(request)).thenReturn(user);var scan=UUID.randomUUID();
        assertThrows(MyException.class,()->controller.create(42,new ImportStartupPlanController.PlanRequest("8",scan,List.of("a")),request));
        assertThrows(MyException.class,()->controller.create(42,new ImportStartupPlanController.PlanRequest("7",null,List.of("a")),request));
        assertThrows(MyException.class,()->controller.create(42,new ImportStartupPlanController.PlanRequest("7",scan,List.of()),request));
        assertThrows(MyException.class,()->controller.create(42,null,request));verifyNoInteractions(plans);
        controller.create(42,new ImportStartupPlanController.PlanRequest("7",scan,List.of("a")),request);verify(plans).create(user,42,scan,List.of("a"));
    }
    @Test void confirmationNeedsExplicitRevisionAndAggregateAnswer(){
        when(users.getUserInSession(request)).thenReturn(user);var id=UUID.randomUUID();
        assertThrows(MyException.class,()->controller.confirm(42,id,new ImportStartupPlanController.ConfirmRequest("8","a".repeat(64),false),request));
        assertThrows(MyException.class,()->controller.confirm(42,id,new ImportStartupPlanController.ConfirmRequest("7",null,false),request));
        assertThrows(MyException.class,()->controller.confirm(42,id,new ImportStartupPlanController.ConfirmRequest("7","a".repeat(64),null),request));
        verifyNoInteractions(plans);
        controller.confirm(42,id,new ImportStartupPlanController.ConfirmRequest("7","a".repeat(64),true),request);verify(plans).confirm(user,42,id,"a".repeat(64),true);
    }
    @Test void lookupUsesSessionIdentityOnly(){
        when(users.getUserInSession(request)).thenReturn(user);var id=UUID.randomUUID();
        controller.get(42,id,request);controller.current(42,request);verify(plans).get(user,42,id);verify(plans).current(user,42);
    }
}
