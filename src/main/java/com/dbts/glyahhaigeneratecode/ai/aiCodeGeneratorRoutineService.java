package com.dbts.glyahhaigeneratecode.ai;

import com.dbts.glyahhaigeneratecode.model.enums.CodeGenTypeEnum;
import dev.langchain4j.service.SystemMessage;
import dev.langchain4j.service.UserMessage;
import dev.langchain4j.service.V;


public interface aiCodeGeneratorRoutineService {

    /**
     * 使用ai判断用用户的代码生成类型
     * @param userMessage 用户输入
     * @return
     */
    @SystemMessage(fromResource = "Prompt/Generate_Code_Enum_Routine.txt")
    @UserMessage("{{userMessage}}")
    CodeGenTypeEnum aiCodeGeneratorRoutine(@V("userMessage") String userMessage);

}
