package com.dbts.glyahhaigeneratecode.projectimport;

import com.dbts.glyahhaigeneratecode.controller.ImportStartupApprovalController;
import com.dbts.glyahhaigeneratecode.exception.MyException;
import com.dbts.glyahhaigeneratecode.model.Entity.User;
import com.dbts.glyahhaigeneratecode.service.UserService;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockHttpServletRequest;
import java.util.List;
import java.util.UUID;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class ImportStartupApprovalControllerTest {
    UserService users=mock(UserService.class);ImportStartupApprovalService approvals=mock(ImportStartupApprovalService.class);
    MockHttpServletRequest request=new MockHttpServletRequest();User user=User.builder().id(7L).build();
    @Test void decisionNeedsSessionOwnerAndCompleteIdentity(){
        when(users.getUserInSession(request)).thenReturn(user);var controller=new ImportStartupApprovalController(users,approvals);var id=UUID.randomUUID();var scan=UUID.randomUUID();
        assertThrows(MyException.class,()->controller.decide(42,new ImportStartupApprovalController.DecisionRequest("8",id,scan,List.of("svc"),true),request));
        assertThrows(MyException.class,()->controller.decide(42,new ImportStartupApprovalController.DecisionRequest("7",id,scan,List.of("svc"),null),request));
        assertThrows(MyException.class,()->controller.decide(42,new ImportStartupApprovalController.DecisionRequest("7",id,scan,List.of(),true),request));
        assertThrows(MyException.class,()->controller.decide(42,null,request));verifyNoInteractions(approvals);
        controller.decide(42,new ImportStartupApprovalController.DecisionRequest("7",id,scan,List.of("svc"),false),request);
        verify(approvals).decide(user,42,id,scan,List.of("svc"),false);
    }
    @Test void lookupUsesSessionIdentityOnly(){
        when(users.getUserInSession(request)).thenReturn(user);var controller=new ImportStartupApprovalController(users,approvals);var id=UUID.randomUUID();
        controller.get(42,id,request);controller.byRequest(42,id,request);verify(approvals).get(user,42,id);verify(approvals).byRequest(user,42,id);
    }
    @Test void cancelAndReconcileNeedExplicitRevision(){
        when(users.getUserInSession(request)).thenReturn(user);var controller=new ImportStartupApprovalController(users,approvals);var id=UUID.randomUUID();
        assertThrows(MyException.class,()->controller.cancel(42,id,new ImportStartupApprovalController.RevisionRequest("8",1L),request));
        assertThrows(MyException.class,()->controller.cancel(42,id,new ImportStartupApprovalController.RevisionRequest("7",null),request));
        assertThrows(MyException.class,()->controller.reconcile(42,id,new ImportStartupApprovalController.RevisionRequest("7",-1L),request));
        verifyNoInteractions(approvals);
        controller.cancel(42,id,new ImportStartupApprovalController.RevisionRequest("7",1L),request);controller.reconcile(42,id,new ImportStartupApprovalController.RevisionRequest("7",2L),request);
        verify(approvals).cancel(user,42,id,1);verify(approvals).reconcile(user,42,id,2);
    }
}
