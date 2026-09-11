package com.dbts.glyahhaigeneratecode.LangGraph4j.ai;

import com.dbts.glyahhaigeneratecode.LangGraph4j.model.ImageCollectionPlan;
import dev.langchain4j.service.SystemMessage;
import dev.langchain4j.service.UserMessage;

public interface ImageCollectionPlanService {

    /**
     * 根据用户提示词分析需要收集的图片类型和参数
     */
    @SystemMessage(fromResource = "Prompt/all_picture_search_plan.txt")
    ImageCollectionPlan planImageCollection(@UserMessage String userPrompt);
}
