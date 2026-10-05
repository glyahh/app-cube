package com.dbts.glyahhaigeneratecode.projectimport;

import com.dbts.glyahhaigeneratecode.controller.ImportStartupPreparationController;
import com.dbts.glyahhaigeneratecode.exception.MyException;
import com.dbts.glyahhaigeneratecode.model.Entity.User;
import com.dbts.glyahhaigeneratecode.service.UserService;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockHttpServletRequest;
import java.util.UUID;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.Mockito.*;

class ImportStartupPreparationControllerTest {
    UserService users=mock(UserService.class);
    ImportStartupPreparationService preparations=mock(ImportStartupPreparationService.class);
    MockHttpServletRequest request=new MockHttpServletRequest();
    User user=User.builder().id(7L).build();
    ImportStartupPreparationController controller=new ImportStartupPreparationController(users,preparations);
    UUID plan=UUID.randomUUID();
    String revision="a".repeat(64);

    @Test void installAndRunRequireSessionOwnerAndPlan(){
        when(users.getUserInSession(request)).thenReturn(user);
        assertThrows(MyException.class,()->controller.install(42,new ImportStartupPreparationController.DecisionRequest("8",plan,revision,true),request));
        assertThrows(MyException.class,()->controller.install(42,new ImportStartupPreparationController.DecisionRequest("7",plan,revision,null),request));
        assertThrows(MyException.class,()->controller.run(42,new ImportStartupPreparationController.RunRequest("7",null,revision),request));
        verifyNoInteractions(preparations);
        controller.install(42,new ImportStartupPreparationController.DecisionRequest("7",plan,revision,false),request);
        controller.run(42,new ImportStartupPreparationController.RunRequest("7",plan,revision),request);
        controller.retry(42,new ImportStartupPreparationController.RunRequest("7",plan,revision),request);
        verify(preparations).install(user,42,plan,revision,false);
        verify(preparations).run(user,42,plan,revision);
        verify(preparations).retry(user,42,plan,revision);
    }
}
