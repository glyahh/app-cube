package com.dbts.glyahhaigeneratecode.service.impl;

import cn.hutool.core.bean.BeanUtil;
import cn.hutool.core.io.FileUtil;
import cn.hutool.core.util.StrUtil;
import com.dbts.glyahhaigeneratecode.constant.AppConstant;
import com.dbts.glyahhaigeneratecode.core.Builder.vueProjectBuilder;
import com.dbts.glyahhaigeneratecode.exception.ErrorCode;
import com.dbts.glyahhaigeneratecode.exception.MyException;
import com.dbts.glyahhaigeneratecode.exception.ThrowUtils;
import com.dbts.glyahhaigeneratecode.manage.OssManager;
import com.dbts.glyahhaigeneratecode.constant.UserConstant;
import com.dbts.glyahhaigeneratecode.model.DTO.AppAddRequest;
import com.dbts.glyahhaigeneratecode.model.DTO.AppAdminUpdateRequest;
import com.dbts.glyahhaigeneratecode.model.DTO.AppUpdateRequest;
import com.dbts.glyahhaigeneratecode.model.VO.ProjectFileVO;
import com.dbts.glyahhaigeneratecode.service.AppLoopService;
import com.dbts.glyahhaigeneratecode.service.ChatHistoryService;
import com.dbts.glyahhaigeneratecode.service.ProjectDownloadService;
import com.dbts.glyahhaigeneratecode.service.ScreenshotService;
import com.dbts.glyahhaigeneratecode.service.support.AppServiceSupport;
import com.dbts.glyahhaigeneratecode.service.support.WorkspacePathResolver;
import com.mybatisflex.core.paginate.Page;
import jakarta.servlet.http.HttpServletResponse;
import com.mybatisflex.spring.service.impl.ServiceImpl;
import com.mybatisflex.core.update.UpdateChain;
import com.dbts.glyahhaigeneratecode.model.Entity.App;
import com.dbts.glyahhaigeneratecode.mapper.AppMapper;
import com.dbts.glyahhaigeneratecode.model.DTO.AppQueryRequest;
import com.dbts.glyahhaigeneratecode.model.Entity.User;
import com.dbts.glyahhaigeneratecode.model.VO.AppVO;
import com.dbts.glyahhaigeneratecode.model.VO.UserVO;
import com.dbts.glyahhaigeneratecode.model.enums.CodeGenTypeEnum;
import com.dbts.glyahhaigeneratecode.service.AppService;
import com.dbts.glyahhaigeneratecode.service.UserService;
import com.mybatisflex.core.query.QueryWrapper;
import jakarta.annotation.Resource;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.io.File;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.time.LocalDateTime;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.stream.Collectors;

/**
 * 应用 服务层实现。
 *
 * @author <a href="https://github.com/glyahh">glyahh</a>
 */
@Service
@Slf4j
public class AppServiceImpl extends ServiceImpl<AppMapper, App> implements AppService {

    @Resource
    private UserService userService;

    @Resource
    private vueProjectBuilder vueProjectBuilder;

    @Resource
    private ScreenshotService screenshotService;

    @Resource
    private OssManager ossManager;

    @Resource
    private AppServiceSupport appServiceSupport;

    @Resource
    private ChatHistoryService chatHistoryService;

    @Resource
    private ProjectDownloadService projectDownloadService;

    @Resource
    private AppLoopService appLoopService;

    @Resource
    private WorkspacePathResolver workspacePathResolver;

    @Override
    public long createApp (User loginUser, AppAddRequest appAddRequest) {
        App app = new App();
        BeanUtil.copyProperties(appAddRequest, app);
        app.setUserId(loginUser.getId());
        if (app.getIsBeta() == null) {
            app.setIsBeta(0);
        }

        // 优先使用前端显式选择的 codeGenType，避免 LLM 路由器覆盖用户意图。
        // 如前端传入 vue_project（但后端 value 约定为 vue），CodeGenTypeEnum#getEnumByValue 会做兼容映射。
        // 不再用模型猜类型：原来前端没传时会调一次 LLM 做分类，
        // 那条链路已随 LangChain4j 一起移除。现在要求显式指定，
        // 猜错会写出格式不对的产物，比直接报错更糟。
        CodeGenTypeEnum codeGenTypeEnum = CodeGenTypeEnum.getEnumByValue(appAddRequest.getCodeGenType());
        ThrowUtils.throwIf(codeGenTypeEnum == null, ErrorCode.PARAMS_ERROR, "请指定生成类型");
        app.setCodeGenType(codeGenTypeEnum.getValue());

        //这里最好使用这个save,不要mapper中的insert,否则id会因为没有声明而被覆盖雪花算法的值,而且其他未声明的字段会报数据库不能非空
        boolean save = this.save(app);
        ThrowUtils.throwIf(!save, ErrorCode.OPERATION_ERROR, "创建应用失败");

        // 创建成功后，如果有关联的 Loop 技能则绑定
        if (appAddRequest.getLoopIds() != null && !appAddRequest.getLoopIds().isEmpty()) {
            appLoopService.bindLoops(app.getId(), appAddRequest.getLoopIds(), "creation");
        }

        return app.getId();
    }

    @Override
    public AppVO getAppVO(App app) {
        if (app == null) {
            throw new MyException(ErrorCode.PARAMS_ERROR, "传入的 App 为空，无法转换为 AppVO");
        }
        AppVO appVO = new AppVO();
        BeanUtil.copyProperties(app, appVO);
        // 按 code_output 目录判定是否已有生成产物
        appVO.setHasGeneratedCode(appServiceSupport.hasGeneratedCode(app));
        return appVO;
    }

    /**
     * 这里使用map分转好id,userVo一起返回list,避免循环查询
     * @param appList 应用实体列表
     * @return
     */
    @Override
    public List<AppVO> getAppVOList(List<App> appList) {
        if (appList == null) {
            throw new MyException(ErrorCode.PARAMS_ERROR, "传入的 AppList 为空，无法转换为 AppVOList");
        }
        if (appList.isEmpty()) {
            return Collections.emptyList();
        }

        // 因为1000个app可能只对应200个用户,这样只要查200次,再通过map存储映射到UserVo里去
        // 收集所有 userId，批量查用户并封装为 UserVO，再按 userId 组成 Map
        List<Long> userIds = appList.stream()
                .map(App::getUserId)
                .filter(Objects::nonNull)
                .distinct() // 去除List流中的重复元素
                .collect(Collectors.toList());

        Map<Long, UserVO> userIdToUserVO = Collections.emptyMap();

        if (!userIds.isEmpty()) {
            List<User> users = userService.listByIds(userIds);
            List<UserVO> userVOList = userService.getUserVOList(users);
            userIdToUserVO = userVOList.stream()
                    .collect(Collectors.toMap(UserVO::getId, userVO -> userVO));
        }
        Map<Long, UserVO> finalUserIdToUserVO = userIdToUserVO;

        return appList.stream()
                .map(app -> {
                    AppVO appVO = new AppVO();
                    BeanUtil.copyProperties(app, appVO);
                    appVO.setUserVO(finalUserIdToUserVO.get(app.getUserId()));
                    // 批量列表项同样走 support 判定生成产物
                    appVO.setHasGeneratedCode(appServiceSupport.hasGeneratedCode(app));
                    return appVO;
                })
                .collect(Collectors.toList());
    }

    @Override
    public QueryWrapper buildAppQueryWrapper(AppQueryRequest appQueryRequest) {
        if (appQueryRequest == null) {
            throw new MyException(ErrorCode.PARAMS_ERROR, "构建 App QueryWrapper 时，appQueryRequest 为空");
        }

        QueryWrapper queryWrapper = new QueryWrapper();

        Long id = appQueryRequest.getId();
        String appName = appQueryRequest.getAppName();
        String cover = appQueryRequest.getCover();
        String initPrompt = appQueryRequest.getInitPrompt();
        String codeGenType = appQueryRequest.getCodeGenType();
        String deployKey = appQueryRequest.getDeployKey();
        Integer priority = appQueryRequest.getPriority();
        Long userId = appQueryRequest.getUserId();
        Integer isDelete = appQueryRequest.getIsDelete();

        String sortField = appQueryRequest.getSortField();
        String sortOrder = appQueryRequest.getSortOrder();

        if (id != null) {
            queryWrapper.eq(App::getId, id);
        }
        if (StrUtil.isNotBlank(appName)) {
            queryWrapper.like(App::getAppName, appName);
        }
        if (StrUtil.isNotBlank(cover)) {
            queryWrapper.eq(App::getCover, cover);
        }
        if (StrUtil.isNotBlank(initPrompt)) {
            queryWrapper.like(App::getInitPrompt, initPrompt);
        }
        if (StrUtil.isNotBlank(codeGenType)) {
            queryWrapper.eq(App::getCodeGenType, codeGenType);
        }
        if (StrUtil.isNotBlank(deployKey)) {
            queryWrapper.eq(App::getDeployKey, deployKey);
        }
        if (priority != null) {
            // 优先级大于等于给定值（例如精选应用：priority >= GOOD_APP_PRIORITY）
            queryWrapper.ge(App::getPriority, priority);
        }
        if (userId != null) {
            queryWrapper.eq(App::getUserId, userId);
        }
        if (isDelete != null) {
            queryWrapper.eq(App::getIsDelete, isDelete);
        }

        if (StrUtil.isNotBlank(sortField) && StrUtil.isNotBlank(sortOrder)) {
            queryWrapper.orderBy(sortField, "ascend".equals(sortOrder));
        }

        return queryWrapper;
    }

    @Override
    public QueryWrapper buildMyAppQueryWrapper(AppQueryRequest appQueryRequest, Long userId) {
        if (appQueryRequest == null) {
            throw new MyException(ErrorCode.PARAMS_ERROR, "构建当前用户应用查询条件时，请求参数为空");
        }
        if (userId == null) {
            throw new MyException(ErrorCode.PARAMS_ERROR, "构建当前用户应用查询条件时，用户 id 为空");
        }

        QueryWrapper queryWrapper = new QueryWrapper();
        queryWrapper.eq(App::getUserId, userId);

        if (StrUtil.isNotBlank(appQueryRequest.getAppName())) {
            queryWrapper.like(App::getAppName, appQueryRequest.getAppName());
        }
        // 工作流/普通生成筛选：isWorkflow=1 对应 isBeta=1，isWorkflow=0 对应 isBeta=0
        if (appQueryRequest.getIsWorkflow() != null) {
            queryWrapper.eq(App::getIsBeta, appQueryRequest.getIsWorkflow());
        }
        // 部署状态筛选：isDeployed=1 要求 deployKey 非空，isDeployed=0 要求 deployKey 为空
        if (appQueryRequest.getIsDeployed() != null) {
            if (appQueryRequest.getIsDeployed() == 1) {
                queryWrapper.isNotNull(App::getDeployKey);
                queryWrapper.ne(App::getDeployKey, "");
            } else {
                queryWrapper.and("(deployKey IS NULL OR deployKey = '')");
            }
        }
        // 代码类型筛选
        if (StrUtil.isNotBlank(appQueryRequest.getCodeGenType())) {
            queryWrapper.eq(App::getCodeGenType, appQueryRequest.getCodeGenType());
        }
        if (StrUtil.isNotBlank(appQueryRequest.getSortField()) && StrUtil.isNotBlank(appQueryRequest.getSortOrder())) {
            queryWrapper.orderBy(appQueryRequest.getSortField(), "ascend".equals(appQueryRequest.getSortOrder()));
        } else {
            queryWrapper.orderBy("createTime", false);
        }

        return queryWrapper;
    }


    @Override
    public String deployApp(Long appId, User loginUser) {
        // 1. 权限校验
        if (appId == null || appId <= 0) {
            throw new MyException(ErrorCode.PARAMS_ERROR, "应用 id 异常");
        }

        // 2. 获取应用信息
        App app = this.getById(appId);
        if (app == null) {
            throw new MyException(ErrorCode.NOT_FOUND_ERROR, "应用不存在");
        }

        // 3. 校验权限/是否本人部署
        if (!app.getUserId().equals(loginUser.getId())) {
            throw new MyException(ErrorCode.NO_AUTH_ERROR, "只能部署自己的应用");
        }

        // 4. 校验deployKey是否存在,不存在则生成deployKey(8为 字母+数字)
        String deployKey = app.getDeployKey();
        if (StrUtil.isBlank(deployKey)) {
            // 生成全局唯一的 deployKey
            deployKey = appServiceSupport.generateUniqueDeployKey();
            app.setDeployKey(deployKey);
        }

        // 5. 根据应用文件类型超找到本应该的路径 (参考路径生成代码)
        String codeGenType = app.getCodeGenType();
        CodeGenTypeEnum codeGenTypeEnum = CodeGenTypeEnum.getEnumByValue(codeGenType);
        if (codeGenTypeEnum == null && StrUtil.isNotBlank(codeGenType)) {
            try {
                codeGenTypeEnum = CodeGenTypeEnum.valueOf(codeGenType);
            } catch (IllegalArgumentException ignored) {
            }
        }
        if (codeGenTypeEnum == null) {
            log.info("codeGenType = {}", codeGenType);
            throw new MyException(ErrorCode.PARAMS_ERROR, "应用配置的 codeGenType 无效");
        }

        // 产物目录名统一走 resolver。VUE 的 "vue_project" 特例已经封在枚举的
        // getDirPrefix() 里，调用点再拼一次 "value + _project_" 是重复实现 ——
        // 两处一旦不同步，就会出现「生成写 A 目录、部署读 B 目录」，且不一定报错。
        Path sourceDirPath = workspacePathResolver.outputDir(appId, codeGenTypeEnum);

        // 6. 校验路径下的文件是否存在
        if (!Files.isDirectory(sourceDirPath)) {
            throw new MyException(ErrorCode.NOT_FOUND_ERROR, "应用代码产物目录不存在，请先生成代码再部署");
        }
        Path indexPath = sourceDirPath.resolve("index.html");
        if (!Files.isRegularFile(indexPath)) {
            throw new MyException(ErrorCode.NOT_FOUND_ERROR, "应用代码产物缺少 index.html, 请删除再试~");
        }
        if (CodeGenTypeEnum.MULTI_FILE.equals(codeGenTypeEnum)) {
            if (!Files.isRegularFile(sourceDirPath.resolve("style.css"))) {
                throw new MyException(ErrorCode.NOT_FOUND_ERROR, "应用代码产物缺少 style.css, 请删除再试~");
            }
            if (!Files.isRegularFile(sourceDirPath.resolve("script.js"))) {
                throw new MyException(ErrorCode.NOT_FOUND_ERROR, "应用代码产物缺少 script.js, 请删除再试~");
            }
        }

        // 6. 检查源目录是否存在
        File sourceDir = new File(String.valueOf(sourceDirPath));
        if (!sourceDir.exists() || !sourceDir.isDirectory()) {
            throw new MyException(ErrorCode.SYSTEM_ERROR, "应用代码不存在，请先生成代码");
        }


        // 7. Vue 项目特殊处理：执行npm构建
        if (codeGenTypeEnum == CodeGenTypeEnum.VUE) {
            // Vue 项目需要构建
            boolean buildSuccess = vueProjectBuilder.buildProject(String.valueOf(sourceDirPath));
            ThrowUtils.throwIf(!buildSuccess, ErrorCode.SYSTEM_ERROR, "Vue 项目构建失败，请检查代码和依赖");
            // 检查 目标目录下 vue文件是否存在
            File distDir = new File(String.valueOf(sourceDirPath), "dist");
            ThrowUtils.throwIf(!distDir.exists(), ErrorCode.SYSTEM_ERROR, "Vue 项目构建完成但未生成 dist 目录");
            // 将 dist 目录作为部署源
            sourceDir = distDir;
            log.info("Vue 项目构建成功，将部署 dist 目录: {}", distDir.getAbsolutePath());
        }

        // 8. 复制文件到部署目录。
        // 走 resolver 而不是字符串拼接：那里做归一化与越界校验。同一个 deployKey
        // 在 Resource Controller 上受保护、在这里不受保护，是不该存在的差异。
        Path deployDir = workspacePathResolver.deployDir(deployKey);
        String deployDirPath = deployDir.toString();
        try {
            FileUtil.copyContent(sourceDir, deployDir.toFile(), true);
        } catch (Exception e) {
            throw new MyException(ErrorCode.SYSTEM_ERROR, "部署文件复制失败: " + e.getMessage());
        }

        //10. 异步生成应用截图保存到阿里云
        generateAppScreenshotAsync(appId, deployDirPath);

        // 11. 更新数据库
        app.setDeployedTime(LocalDateTime.now());
        app.setUpdateTime(LocalDateTime.now());
        app.setDeployKey(deployKey);
        boolean updateResult = this.updateById(app);
        if (!updateResult) {
            throw new MyException(ErrorCode.OPERATION_ERROR, "部署信息更新失败");
        }

        // 12. 返回 deployKey；最终 URL 由控制层按“当前请求域名+端口+context-path”拼接，避免返回错误主机。
        return deployKey;
    }

    /**
     * 取消部署应用：删除部署目录并清空应用的部署信息
     *
     * @param appId     应用 id
     * @param loginUser 当前登录用户
     * @return 已清理部署信息（含删除目录或仅校正库内字段）返回 true；从未部署过（无 deployKey）返回 false
     */
    @Override
    public boolean undeployApp(Long appId, User loginUser) {
        // 1. 权限校验
        if (appId == null || appId <= 0) {
            throw new MyException(ErrorCode.PARAMS_ERROR, "应用 id 异常");
        }

        // 2. 获取应用信息
        App app = this.getById(appId);
        if (app == null) {
            throw new MyException(ErrorCode.NOT_FOUND_ERROR, "应用不存在");
        }

        // 3. 校验权限 / 是否本人操作
        if (!app.getUserId().equals(loginUser.getId())) {
            throw new MyException(ErrorCode.NO_AUTH_ERROR, "只能操作自己的应用");
        }

        // 4. 验证是否已部署（deployKey 存在且部署目录存在）
        String deployKey = app.getDeployKey();
        if (StrUtil.isBlank(deployKey)) {
            // 没有部署标识，视为未部署
            return false;
        }

        Path deployDir = workspacePathResolver.deployDir(deployKey);
        // 5. 下线同时删除 OSS 中该应用截图（cover）
        // 说明：cover 由部署时异步截图写入；下线后该截图通常已无意义，避免对象存储堆积。
        // 备注：即使部署目录不存在（被手动删/异常丢失），也应清理 cover 与 deployKey，保证状态可回到“未部署”。
        String cover = app.getCover();
        if (StrUtil.isNotBlank(cover)) {
            boolean deleted = ossManager.deleteFile(cover);
            ThrowUtils.throwIf(!deleted, ErrorCode.OPERATION_ERROR, "删除 OSS 截图失败，请检查对象存储权限配置");
        }

        // 6. 删除部署目录（目录不存在则忽略，仅做字段修正）
        if (Files.isDirectory(deployDir)) {
            try {
                FileUtil.del(deployDir.toFile());
            } catch (Exception e) {
                throw new MyException(ErrorCode.SYSTEM_ERROR, "取消部署失败: " + e.getMessage());
            }
        }

        // 7. 清理应用上的部署信息（强制将字段置空落库：updateById 默认可能忽略 null）
        boolean cleared = UpdateChain.create(this.getMapper())
                .set("deployKey", null)
                .set("deployedTime", null)
                .set("cover", null)
                .set("updateTime", LocalDateTime.now())
                .eq("id", appId)
                .update();
        ThrowUtils.throwIf(!cleared, ErrorCode.OPERATION_ERROR, "取消部署信息更新失败");
        return true;
    }

    /**
     * 异步生成应用截图并更新封面
     *
     * @param appId  应用ID
     * @param appUrl 应用访问URL
     */
    @Override
    public void generateAppScreenshotAsync(Long appId, String appUrl) {
        // 使用虚拟线程异步执行
        Thread.startVirtualThread(() -> {
            // 调用截图服务生成截图并上传
            String screenshotUrl = screenshotService.generateAndUploadScreenshot(appUrl);
            // 更新应用封面字段
            App updateApp = new App();
            updateApp.setId(appId);
            updateApp.setCover(screenshotUrl);
            boolean updated = this.updateById(updateApp);
            ThrowUtils.throwIf(!updated, ErrorCode.OPERATION_ERROR, "更新应用封面字段失败");
        });
    }

    @Override
    public Boolean updateMyApp(User loginUser, AppUpdateRequest appUpdateRequest) {
        ThrowUtils.throwIf(appUpdateRequest == null || appUpdateRequest.getId() == null,
                ErrorCode.PARAMS_ERROR, "更新应用请求参数异常");
        ThrowUtils.throwIf(StrUtil.isBlank(appUpdateRequest.getAppName()), ErrorCode.PARAMS_ERROR, "应用名称不能为空");

        App app = this.getById(appUpdateRequest.getId());
        ThrowUtils.throwIf(app == null, ErrorCode.NOT_FOUND_ERROR, "应用不存在");
        ThrowUtils.throwIf(!loginUser.getId().equals(app.getUserId()) && !loginUser.getUserRole().equals(UserConstant.ADMIN_ROLE), ErrorCode.NO_AUTH_ERROR, "只能修改自己的应用");

        app.setAppName(appUpdateRequest.getAppName());
        app.setUpdateTime(LocalDateTime.now());
        boolean result = this.updateById(app);
        ThrowUtils.throwIf(!result, ErrorCode.OPERATION_ERROR, "更新应用失败");
        return true;
    }

    @Override
    public Boolean deleteMyApp(User loginUser, Long appId) {
        ThrowUtils.throwIf(appId == null || appId <= 0,
                ErrorCode.PARAMS_ERROR, "删除应用请求参数异常");

        App app = this.getById(appId);
        ThrowUtils.throwIf(app == null, ErrorCode.NOT_FOUND_ERROR, "应用不存在");
        ThrowUtils.throwIf(!loginUser.getId().equals(app.getUserId()) && !loginUser.getUserRole().equals(UserConstant.ADMIN_ROLE), ErrorCode.NO_AUTH_ERROR, "只能删除自己的应用");

        boolean result = this.removeById(appId);
        ThrowUtils.throwIf(!result, ErrorCode.OPERATION_ERROR, "删除应用失败");
        // 关联删除该应用的所有对话历史，失败不影响应用删除
        try {
            chatHistoryService.removeByAppId(appId);
        } catch (Exception e) {
            log.warn("删除应用对话历史失败, appId={}", appId, e);
        }
        // 清理 code_output 下该应用产物目录
        appServiceSupport.removeCodeOutputDirByAppId(appId);
        return true;
    }

    @Override
    public AppVO getMyAppVOById(User loginUser, String id) {
        // 其实这里后端可以不用转化的,但是写都写了(
        // 接收字符串类型的 id，避免前端 number 精度丢失问题
        Long appId;
        try {
            appId = Long.parseLong(id);
        } catch (NumberFormatException e) {
            throw new MyException(ErrorCode.PARAMS_ERROR, "应用 id 格式错误");
        }
        ThrowUtils.throwIf(appId <= 0, ErrorCode.PARAMS_ERROR, "应用 id 异常");

        App app = this.getById(appId);
        ThrowUtils.throwIf(app == null, ErrorCode.NOT_FOUND_ERROR, "应用不存在");
        ThrowUtils.throwIf(!loginUser.getId().equals(app.getUserId()) && !loginUser.getUserRole().equals(UserConstant.ADMIN_ROLE) && app.getPriority()==0, ErrorCode.NO_AUTH_ERROR, "只能查看自己的应用");

        return this.getAppVO(app);
    }

    @Override
    public Page<AppVO> listMyAppVOByPage(User loginUser, AppQueryRequest appQueryRequest) {
        ThrowUtils.throwIf(appQueryRequest == null, ErrorCode.PARAMS_ERROR, "分页查询请求参数为空");

        int pageNum = appQueryRequest.getPageNum();
        int pageSize = appQueryRequest.getPageSize();
        ThrowUtils.throwIf(pageSize <= 0 || pageSize > AppConstant.MAX_APP_LIST_PAGE_SIZE,
                ErrorCode.PARAMS_ERROR, "每页最多 " + AppConstant.MAX_APP_LIST_PAGE_SIZE + " 条");

        // 构造当前用户自己的应用查询条件
        QueryWrapper queryWrapper = this.buildMyAppQueryWrapper(appQueryRequest, loginUser.getId());

        Page<App> appPage = this.page(Page.of(pageNum, pageSize), queryWrapper);
        Page<AppVO> appVOPage = new Page<>(pageNum, pageSize, appPage.getTotalRow());
        appVOPage.setRecords(this.getAppVOList(appPage.getRecords()));
        return appVOPage;
    }

    @Override
    public Page<AppVO> listGoodAppVOByPage(AppQueryRequest appQueryRequest) {
        ThrowUtils.throwIf(appQueryRequest == null, ErrorCode.PARAMS_ERROR);
        long pageSize = appQueryRequest.getPageSize();
        ThrowUtils.throwIf(pageSize <= 0 || pageSize > AppConstant.MAX_APP_LIST_PAGE_SIZE,
                ErrorCode.PARAMS_ERROR, "每页最多查询 " + AppConstant.MAX_APP_LIST_PAGE_SIZE + " 个应用");
        long pageNum = appQueryRequest.getPageNum();
        // 只查询精选的应用,管理员没有声明按99,否则按照管理员设置的
        if (appQueryRequest.getPriority() != null){
            appQueryRequest.setPriority(appQueryRequest.getPriority());
        }
        else{
            appQueryRequest.setPriority(AppConstant.GOOD_APP_PRIORITY);
        }
        log.info("查询精选应用列表, appQueryRequest: {}", appQueryRequest);

        QueryWrapper queryWrapper = this.buildAppQueryWrapper(appQueryRequest);
        // 分页查询
        Page<App> appPage = this.page(Page.of(pageNum, pageSize), queryWrapper);
        // 数据封装
        List<AppVO> appVOList = this.getAppVOList(appPage.getRecords());

        Page<AppVO> appVOPage = new Page<>(pageNum, pageSize, appPage.getTotalRow());
        appVOPage.setRecords(appVOList);
        return appVOPage;
    }

    @Override
    public Boolean deleteAppByAdmin(Long appId) {
        ThrowUtils.throwIf(appId == null || appId <= 0,
                ErrorCode.PARAMS_ERROR, "删除应用请求参数异常");
        boolean result = this.removeById(appId);
        ThrowUtils.throwIf(!result, ErrorCode.OPERATION_ERROR, "删除应用失败");
        // 关联删除该应用的所有对话历史，失败不影响应用删除
        try {
            chatHistoryService.removeByAppId(appId);
        } catch (Exception e) {
            log.warn("管理员删除应用对话历史失败, appId={}", appId, e);
        }
        // 清理 code_output 下该应用产物目录
        appServiceSupport.removeCodeOutputDirByAppId(appId);
        return true;
    }

    @Override
    public Boolean updateAppByAdmin(AppAdminUpdateRequest appAdminUpdateRequest) {
        ThrowUtils.throwIf(appAdminUpdateRequest == null || appAdminUpdateRequest.getId() == null,
                ErrorCode.PARAMS_ERROR, "更新应用请求参数异常");

        App app = this.getById(appAdminUpdateRequest.getId());
        ThrowUtils.throwIf(app == null, ErrorCode.NOT_FOUND_ERROR, "应用不存在");

        if (StrUtil.isNotBlank(appAdminUpdateRequest.getAppName())) {
            app.setAppName(appAdminUpdateRequest.getAppName());
        }
        if (StrUtil.isNotBlank(appAdminUpdateRequest.getCover())) {
            app.setCover(appAdminUpdateRequest.getCover());
        }
        if (appAdminUpdateRequest.getPriority() != null) {
            app.setPriority(appAdminUpdateRequest.getPriority());
        }
        app.setUpdateTime(LocalDateTime.now());

        boolean result = this.updateById(app);
        ThrowUtils.throwIf(!result, ErrorCode.OPERATION_ERROR, "管理员更新应用失败");
        return true;
    }

    @Override
    public Page<AppVO> listAppVOByPageAdmin(AppQueryRequest appQueryRequest) {
        ThrowUtils.throwIf(appQueryRequest == null, ErrorCode.PARAMS_ERROR, "分页查询请求参数为空");

        int pageNum = appQueryRequest.getPageNum();
        int pageSize = appQueryRequest.getPageSize();
        ThrowUtils.throwIf(pageSize <= 0, ErrorCode.PARAMS_ERROR, "pageSize 必须大于 0");

        QueryWrapper queryWrapper = this.buildAppQueryWrapper(appQueryRequest);
        Page<App> appPage = this.page(Page.of(pageNum, pageSize), queryWrapper);

        Page<AppVO> appVOPage = new Page<>(pageNum, pageSize, appPage.getTotalRow());
        appVOPage.setRecords(this.getAppVOList(appPage.getRecords()));
        return appVOPage;
    }

    @Override
    public AppVO getAppVOByIdAdmin(long id) {
        ThrowUtils.throwIf(id <= 0, ErrorCode.PARAMS_ERROR, "应用 id 异常");
        App app = this.getById(id);
        ThrowUtils.throwIf(app == null, ErrorCode.NOT_FOUND_ERROR, "应用不存在");
        return this.getAppVO(app);
    }

    @Override
    public void downloadProject(User loginUser, Long appId, HttpServletResponse response) {
        ThrowUtils.throwIf(appId == null || appId <= 0, ErrorCode.PARAMS_ERROR, "应用 ID 不能为空");

        // 获取应用并校验归属
        App app = this.getById(appId);
        ThrowUtils.throwIf(app == null, ErrorCode.NOT_FOUND_ERROR, "应用不存在");
        ThrowUtils.throwIf(!loginUser.getId().equals(app.getUserId())
                        && !UserConstant.ADMIN_ROLE.equals(loginUser.getUserRole()),
                ErrorCode.NO_AUTH_ERROR, "只能下载自己的应用");

        // 组装 service 所需参数：appName + 项目路径
        String appName = app.getAppName();

        String projectPath = resolveOutputDir(app).toString();

        projectDownloadService.downloadProject(response, appName, projectPath);
    }

    /**
     * 解析应用的 codeGenType 并定位其产物目录。
     *
     * <p>这段解析原先在下载与文件列表两个方法里各复制了一份，目录名也各自拼接
     * （其中一处直接写死了 {@code "/vue_project_"} 这类字面量）。收敛到一处之后，
     * 目录命名只由 {@link CodeGenTypeEnum#getDirPrefix()} 决定 —— 否则一旦枚举改规则，
     * 会出现「生成写 A 目录、读取读 B 目录」而不一定报错的错位。
     */
    private Path resolveOutputDir(App app) {
        String codeGenType = app.getCodeGenType();
        CodeGenTypeEnum type = CodeGenTypeEnum.getEnumByValue(codeGenType);
        if (type == null && StrUtil.isNotBlank(codeGenType)) {
            try {
                type = CodeGenTypeEnum.valueOf(codeGenType);
            } catch (IllegalArgumentException ignored) {
                // 落到下面的统一校验里报错
            }
        }
        ThrowUtils.throwIf(type == null, ErrorCode.PARAMS_ERROR, "应用配置的 codeGenType 无效");
        return workspacePathResolver.outputDir(app.getId(), type);
    }

    @Override
    public List<ProjectFileVO> getProjectFiles(User loginUser, Long appId) {
        ThrowUtils.throwIf(appId == null || appId <= 0, ErrorCode.PARAMS_ERROR, "应用 ID 不能为空");

        App app = this.getById(appId);
        ThrowUtils.throwIf(app == null, ErrorCode.NOT_FOUND_ERROR, "应用不存在");
        ThrowUtils.throwIf(!loginUser.getId().equals(app.getUserId())
                        && !UserConstant.ADMIN_ROLE.equals(loginUser.getUserRole()),
                ErrorCode.NO_AUTH_ERROR, "只能查看自己的应用");

        // 根据 codeGenType 定位项目根目录
        String projectRootPath = resolveOutputDir(app).toString();

        Path projectRoot = Paths.get(projectRootPath);
        if (!Files.isDirectory(projectRoot)) {
            return List.of();
        }

        // 遍历目录收集文本代码文件
        return appServiceSupport.collectProjectFiles(projectRoot);
    }

}
