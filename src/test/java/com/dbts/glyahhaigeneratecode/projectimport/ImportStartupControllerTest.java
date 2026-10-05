package com.dbts.glyahhaigeneratecode.projectimport;

import com.dbts.glyahhaigeneratecode.controller.ImportStartupController;
import com.dbts.glyahhaigeneratecode.exception.MyException;
import com.dbts.glyahhaigeneratecode.model.Entity.User;
import com.dbts.glyahhaigeneratecode.service.UserService;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockHttpServletRequest;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class ImportStartupControllerTest {
    UserService users=mock(UserService.class);ImportStartupService startup=mock(ImportStartupService.class);
    ImportStartupScanService scans=mock(ImportStartupScanService.class);
    MockHttpServletRequest request=new MockHttpServletRequest();User user=User.builder().id(7L).build();
    @Test void routesOnlyAuthenticatedOwnerAndActualScope(){
        when(users.getUserInSession(request)).thenReturn(user);
        new ImportStartupController(users,startup,scans).inspect(42,new ImportStartupController.InspectionRequest("7","frontend"),request);
        verify(scans).inspect(user,42,"frontend");
    }
    @Test void staleSessionOwnerAndMissingBodyDoNotInspect(){
        when(users.getUserInSession(request)).thenReturn(user);var controller=new ImportStartupController(users,startup,scans);
        assertThrows(MyException.class,()->controller.inspect(42,new ImportStartupController.InspectionRequest("8","."),request));
        assertThrows(MyException.class,()->controller.inspect(42,null,request));verifyNoInteractions(startup,scans);
    }
    @Test void configurationNeedsExplicitVersionsAndOwner(){
        when(users.getUserInSession(request)).thenReturn(user);var controller=new ImportStartupController(users,startup,scans);
        assertThrows(MyException.class,()->controller.saveConfiguration(42,new ImportStartupController.ConfigurationRequest("7",null,0L,".",java.util.List.of()),request));
        assertThrows(MyException.class,()->controller.saveConfiguration(42,new ImportStartupController.ConfigurationRequest("8",0L,0L,".",java.util.List.of()),request));
        verifyNoInteractions(startup,scans);
        controller.saveConfiguration(42,new ImportStartupController.ConfigurationRequest("7",1L,2L,".",java.util.List.of()),request);
        verify(startup).saveConfiguration(user,42,1,2,".",java.util.List.of());
    }
    @Test void completedInspectionLookupUsesSessionNotClientOwner(){
        when(users.getUserInSession(request)).thenReturn(user);var scan=java.util.UUID.randomUUID();
        new ImportStartupController(users,startup,scans).inspection(42,scan,request);
        verify(startup).inspection(user,42,scan);
    }
    @Test void createNeedsFrozenVersionsAndMatchesSessionOwner(){
        when(users.getUserInSession(request)).thenReturn(user);var controller=new ImportStartupController(users,startup,scans);var id=java.util.UUID.randomUUID();
        assertThrows(MyException.class,()->controller.createScan(42,new ImportStartupController.ScanRequest("8",id,0L,0L,"."),request));
        assertThrows(MyException.class,()->controller.createScan(42,new ImportStartupController.ScanRequest("7",id,null,0L,"."),request));
        verifyNoInteractions(scans);
        controller.createScan(42,new ImportStartupController.ScanRequest("7",id,1L,2L,"backend"),request);
        verify(scans).create(user,42,id,1,2,"backend");
    }
    @Test void scanAndRequestLookupOnlyUseSessionIdentity(){
        when(users.getUserInSession(request)).thenReturn(user);var controller=new ImportStartupController(users,startup,scans);var id=java.util.UUID.randomUUID();
        controller.getScan(42,id,request);controller.getRequest(42,id,request);
        verify(scans).get(user,42,id);verify(scans).byRequest(user,42,id);
    }
    @Test void cancellationNeedsExplicitRevisionAndSessionOwner(){
        when(users.getUserInSession(request)).thenReturn(user);var controller=new ImportStartupController(users,startup,scans);var id=java.util.UUID.randomUUID();
        assertThrows(MyException.class,()->controller.cancelScan(42,id,new ImportStartupController.CancelRequest("8",1L),request));
        assertThrows(MyException.class,()->controller.cancelScan(42,id,new ImportStartupController.CancelRequest("7",null),request));
        verifyNoInteractions(scans);controller.cancelScan(42,id,new ImportStartupController.CancelRequest("7",1L),request);
        verify(scans).cancel(user,42,id,1);
    }
    @Test void reconcileRequiresCurrentOwnerAndRevision(){
        when(users.getUserInSession(request)).thenReturn(user);var controller=new ImportStartupController(users,startup,scans);var id=java.util.UUID.randomUUID();
        assertThrows(MyException.class,()->controller.reconcileScan(42,id,new ImportStartupController.CancelRequest("8",1L),request));
        assertThrows(MyException.class,()->controller.reconcileScan(42,id,new ImportStartupController.CancelRequest("7",null),request));
        verifyNoInteractions(scans);controller.reconcileScan(42,id,new ImportStartupController.CancelRequest("7",1L),request);verify(scans).reconcile(user,42,id,1);
    }
}
