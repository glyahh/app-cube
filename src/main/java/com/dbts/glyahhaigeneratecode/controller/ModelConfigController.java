package com.dbts.glyahhaigeneratecode.controller;

import com.dbts.glyahhaigeneratecode.annotation.MyRole;
import com.dbts.glyahhaigeneratecode.common.BaseResponse;
import com.dbts.glyahhaigeneratecode.common.ResultUtils;
import com.dbts.glyahhaigeneratecode.constant.UserConstant;
import com.dbts.glyahhaigeneratecode.service.ModelConfigService;
import com.dbts.glyahhaigeneratecode.service.ModelConfigService.ConfigVO;
import com.dbts.glyahhaigeneratecode.service.ModelConfigService.ModelsDTO;
import com.dbts.glyahhaigeneratecode.service.ModelConfigService.ModelsVO;
import com.dbts.glyahhaigeneratecode.service.ModelConfigService.SaveDTO;
import com.dbts.glyahhaigeneratecode.service.ModelConfigService.TestDTO;
import com.dbts.glyahhaigeneratecode.service.ModelConfigService.TestVO;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/** BYOK 模型配置；实例级配置，仅管理员可读写。 */
@RestController
@RequestMapping("/model-config")
@RequiredArgsConstructor
public class ModelConfigController {

    private final ModelConfigService service;

    @GetMapping("/providers")
    @MyRole(role = UserConstant.ADMIN_ROLE)
    public BaseResponse<ConfigVO> config() {
        return ResultUtils.success(service.config());
    }

    @PostMapping("/providers")
    @MyRole(role = UserConstant.ADMIN_ROLE)
    public BaseResponse<ConfigVO> save(@RequestBody SaveDTO input) {
        return ResultUtils.success(service.save(input));
    }

    @DeleteMapping("/providers/{id}")
    @MyRole(role = UserConstant.ADMIN_ROLE)
    public BaseResponse<ConfigVO> delete(@PathVariable String id) {
        return ResultUtils.success(service.delete(id));
    }

    @PostMapping("/providers/{id}/default")
    @MyRole(role = UserConstant.ADMIN_ROLE)
    public BaseResponse<ConfigVO> setDefault(@PathVariable String id) {
        return ResultUtils.success(service.setDefault(id));
    }

    @PostMapping("/test")
    @MyRole(role = UserConstant.ADMIN_ROLE)
    public BaseResponse<TestVO> test(@RequestBody TestDTO input) {
        return ResultUtils.success(service.test(input));
    }

    @PostMapping("/models")
    @MyRole(role = UserConstant.ADMIN_ROLE)
    public BaseResponse<ModelsVO> models(@RequestBody ModelsDTO input) {
        return ResultUtils.success(service.models(input));
    }
}
