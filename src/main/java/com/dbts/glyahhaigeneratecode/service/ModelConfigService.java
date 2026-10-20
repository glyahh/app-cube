package com.dbts.glyahhaigeneratecode.service;

import java.util.List;

/** BYOK 模型配置：实例级、管理员维护，密钥只写仓库根 .env。 */
public interface ModelConfigService {

    record ProviderVO(String id, String name, String baseUrl, String model, List<String> models,
                      int contextWindow, int maxOutputTokens, List<String> efforts,
                      boolean configured, boolean isDefault) {
    }

    record ConfigVO(String defaultId, List<ProviderVO> providers) {
    }

    record SaveDTO(String id, String name, String baseUrl, String model, List<String> models,
                   String apiKey, Integer contextWindow, Integer maxOutputTokens,
                   List<String> efforts, Boolean makeDefault) {
    }

    record TestDTO(String id, String baseUrl, String model, String apiKey) {
    }

    record ModelsDTO(String id, String baseUrl, String apiKey) {
    }

    record TestVO(boolean ok, String message) {
    }

    record ModelsVO(List<String> models, String message) {
    }

    ConfigVO config();

    ConfigVO save(SaveDTO input);

    ConfigVO delete(String id);

    ConfigVO setDefault(String id);

    TestVO test(TestDTO input);

    ModelsVO models(ModelsDTO input);
}
