package com.dbts.glyahhaigeneratecode.service.impl;

import cn.hutool.core.bean.BeanUtil;
import cn.hutool.core.io.FileUtil;
import cn.hutool.core.util.StrUtil;
import cn.hutool.json.JSONUtil;
import com.dbts.glyahhaigeneratecode.constant.AppConstant;
import com.dbts.glyahhaigeneratecode.core.Builder.vueProjectBuilder;
import com.dbts.glyahhaigeneratecode.exception.ErrorCode;
import com.dbts.glyahhaigeneratecode.exception.MyException;
import com.dbts.glyahhaigeneratecode.exception.ThrowUtils;
import com.dbts.glyahhaigeneratecode.manage.OssManager;
import com.dbts.glyahhaigeneratecode.utils.ContentTypeUtils;
import com.dbts.glyahhaigeneratecode.constant.UserConstant;
import com.dbts.glyahhaigeneratecode.model.DTO.AppAddRequest;
import com.dbts.glyahhaigeneratecode.model.DTO.AppAdminUpdateRequest;
import com.dbts.glyahhaigeneratecode.knowledge.DashboardFeaturesChangedEvent;
import com.dbts.glyahhaigeneratecode.model.DTO.AppUpdateRequest;
import com.dbts.glyahhaigeneratecode.model.DTO.DashboardFeaturesRequest;
import com.dbts.glyahhaigeneratecode.model.DTO.ProjectFileSaveRequest;
import com.dbts.glyahhaigeneratecode.model.VO.ProjectFileContentVO;
import com.dbts.glyahhaigeneratecode.model.VO.ProjectFileVO;
import com.dbts.glyahhaigeneratecode.model.VO.ProjectTreeEntryVO;
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
import com.dbts.glyahhaigeneratecode.model.VO.AppWorkspaceVO;
import com.dbts.glyahhaigeneratecode.model.VO.UserVO;
import com.dbts.glyahhaigeneratecode.model.enums.CodeGenTypeEnum;
import com.dbts.glyahhaigeneratecode.service.AppService;
import com.dbts.glyahhaigeneratecode.service.UserService;
import com.mybatisflex.core.query.QueryWrapper;
import jakarta.annotation.Resource;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.io.File;
import java.io.IOException;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.stream.Collectors;
import java.util.stream.Stream;

/**
 * 应用 服务层实现。
 *
 * @author <a href="https://github.com/glyahh">glyahh</a>
 */
@Service
@Slf4j
public class AppServiceImpl extends ServiceImpl<AppMapper, App> implements AppService {

    @Resource
    private com.dbts.glyahhaigeneratecode.projectimport.ImportWorkspaceCoordinator importWorkspaceCoordinator;
    @Resource
    private com.dbts.glyahhaigeneratecode.projectimport.ImportStartupConfigurationRepository startupConfigurations;
    @Resource
    private org.springframework.beans.factory.ObjectProvider<com.dbts.glyahhaigeneratecode.projectimport.ImportExecutionService> importExecutions;
    @Resource
    private com.dbts.glyahhaigeneratecode.projectimport.ImportAgentCleanup importAgentCleanup;
    @Resource
    private com.dbts.glyahhaigeneratecode.projectimport.ImportTaskRepository importPublications;
    @Resource
    private com.dbts.glyahhaigeneratecode.projectimport.ImportStartupInstallJobRepository importInstallJobs;

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

    /**
     * 删除应用时清运行痕迹。
     *
     * <p>放这里而不是 {@code AppServiceSupport}：那个类是构造器注入，
     * 再添一个依赖会连带改掉它的构造签名和一堆测试；这里用 {@code @Resource}，加字段无痛。
     */
    @Resource
    private com.dbts.glyahhaigeneratecode.runtime.RuntimeOrchestrator runtimeOrchestrator;

    /** 看板功能变更后广播：知识模块据此为存量项目补生成知识。 */
    @Resource
    private org.springframework.context.ApplicationEventPublisher eventPublisher;

    /** 合法的看板功能标识：监控 / 日志 / 知识图谱。 */
    private static final Set<String> DASHBOARD_FEATURES = Set.of("metrics", "logs", "knowledge");

    @Override
    public long createApp (User loginUser, AppAddRequest appAddRequest) {
        App app = new App();
        BeanUtil.copyProperties(appAddRequest, app);
        app.setUserId(loginUser.getId());
        if (app.getIsBeta() == null) {
            app.setIsBeta(0);
        }

        // 新项目统一 AUTO：不再让用户预选类型，也不再按需求文本关键词猜。
        // 请求里若带了 codeGenType 一律不采用（忽略旧格式约束），产物形态交给 agent 决定。
        app.setCodeGenType(CodeGenTypeEnum.AUTO.getValue());
        // 看板开关默认全不选；非法标识直接忽略，不因为这个拒绝整个创建请求
        app.setDashboardFeatures(normalizeDashboardFeatures(appAddRequest.getDashboardFeatures()));

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
            queryWrapper.orderBy("createTime".equals(sortField) ? "create_time" : sortField,
                    "ascend".equals(sortOrder));
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
                queryWrapper.and("(deploy_key IS NULL OR deploy_key = '')");
            }
        }
        // 代码类型筛选
        if (StrUtil.isNotBlank(appQueryRequest.getCodeGenType())) {
            queryWrapper.eq(App::getCodeGenType, appQueryRequest.getCodeGenType());
        }
        if (StrUtil.isNotBlank(appQueryRequest.getSortField()) && StrUtil.isNotBlank(appQueryRequest.getSortOrder())) {
            String sortField = appQueryRequest.getSortField();
            queryWrapper.orderBy("createTime".equals(sortField) ? "create_time" : sortField,
                    "ascend".equals(appQueryRequest.getSortOrder()));
        } else {
            queryWrapper.orderBy("create_time", false);
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

        // 6. 校验路径下的文件是否存在。
        // AUTO 的产物形态由工作区描述判定，不套旧类型「必须有 index.html」的约定。
        if (!Files.isDirectory(sourceDirPath)) {
            throw new MyException(ErrorCode.NOT_FOUND_ERROR, "应用代码产物目录不存在，请先生成代码再部署");
        }
        if (codeGenTypeEnum != CodeGenTypeEnum.AUTO) {
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
        }

        // 6. 检查源目录是否存在
        File sourceDir = new File(String.valueOf(sourceDirPath));
        if (!sourceDir.exists() || !sourceDir.isDirectory()) {
            throw new MyException(ErrorCode.SYSTEM_ERROR, "应用代码不存在，请先生成代码");
        }


        // 7. 按类型决定发布哪一层产物
        if (codeGenTypeEnum == CodeGenTypeEnum.AUTO) {
            String staticEntry = appServiceSupport.detectStaticEntry(sourceDirPath);
            ThrowUtils.throwIf(staticEntry == null, ErrorCode.OPERATION_ERROR,
                    "当前项目不是纯静态页面（需要安装依赖或常驻服务），暂不支持发布");
            // 入口在 dist 下时只发布 dist，否则会把源码和依赖一起发出去
            if (staticEntry.startsWith("dist/")) {
                sourceDir = sourceDirPath.resolve("dist").toFile();
            }
            log.info("AUTO 项目发布静态入口: {} ({})", staticEntry, sourceDirPath);
        } else if (codeGenTypeEnum == CodeGenTypeEnum.VUE) {
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
        app.setEditTime(LocalDateTime.now());
        app.setUpdateTime(LocalDateTime.now());
        boolean result = this.updateById(app);
        ThrowUtils.throwIf(!result, ErrorCode.OPERATION_ERROR, "更新应用失败");
        return true;
    }

    @Override
    public Boolean deleteMyApp(User loginUser, Long appId) {
        ThrowUtils.throwIf(appId == null || appId <= 0, ErrorCode.PARAMS_ERROR, "删除应用请求参数异常");
        App target=this.getById(appId);
        ThrowUtils.throwIf(target==null,ErrorCode.NOT_FOUND_ERROR,"应用不存在");
        ThrowUtils.throwIf(!loginUser.getId().equals(target.getUserId())&&!UserConstant.ADMIN_ROLE.equals(loginUser.getUserRole()),ErrorCode.NO_AUTH_ERROR,"只能删除自己的应用");
        requireNoActiveInstall(appId);
        return importWorkspaceCoordinator.withLock(appId, () -> importAgentCleanup.whenIdle(appId, () -> deleteMyAppLocked(loginUser, appId)));
    }

    private Boolean deleteMyAppLocked(User loginUser, Long appId) {
        ThrowUtils.throwIf(appId == null || appId <= 0,
                ErrorCode.PARAMS_ERROR, "删除应用请求参数异常");

        App app = this.getById(appId);
        ThrowUtils.throwIf(app == null, ErrorCode.NOT_FOUND_ERROR, "应用不存在");
        ThrowUtils.throwIf(!loginUser.getId().equals(app.getUserId()) && !loginUser.getUserRole().equals(UserConstant.ADMIN_ROLE), ErrorCode.NO_AUTH_ERROR, "只能删除自己的应用");

        // 0. 安装任务未完成或资源未核清时不能删除，先取消安装并确认停止。
        requireNoActiveInstall(appId);
        // 1. 导入容器没确认停掉时中断，应用和持久副本都保留。
        releaseImportedExecution(appId);
        // 2. 应用记录删掉之后，再把仍可用的导入资格标成已中止。
        boolean result = this.removeById(appId);
        ThrowUtils.throwIf(!result, ErrorCode.OPERATION_ERROR, "删除应用失败");
        importPublications.retirePublication(appId);
        // 关联删除该应用的所有对话历史，失败不影响应用删除
        try {
            chatHistoryService.removeByAppId(appId);
        } catch (Exception e) {
            log.warn("删除应用对话历史失败, appId={}", appId, e);
        }
        // 清理 code_output 下该应用产物目录
        // 运行容器与知识任务要在产物目录之前清：容器挂载着那个目录
        appServiceSupport.removeAppArtifacts(appId);
        // 容器删完再清台账与内存计数 —— 顺序反了会把还在跑的容器的指标清掉
        runtimeOrchestrator.purgeApp(appId);
        appServiceSupport.removeCodeOutputDirByAppId(appId);
        return true;
    }

    /** 有未完成或资源未核清的安装任务时拒绝删除；用户需先取消安装并确认停止。 */
    private void requireNoActiveInstall(long appId) {
        if (importInstallJobs.activeByApp(appId) != null) {
            throw new MyException(ErrorCode.CONFLICT_ERROR, "安装尚未结束，请先取消安装。");
        }
    }

    /** 导入容器还在时不能删应用，否则进程会继续在本机端口上提供已删除的副本。 */
    private void releaseImportedExecution(long appId) {
        importAgentCleanup.release(appId);
        if (!importExecutions.getObject().releaseApp(appId)) {
            throw new MyException(ErrorCode.OPERATION_ERROR, "导入项目的运行容器尚未确认停止，应用未删除。");
        }
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
        ThrowUtils.throwIf(appId == null || appId <= 0, ErrorCode.PARAMS_ERROR, "删除应用请求参数异常");
        return importWorkspaceCoordinator.withLock(appId, () -> importAgentCleanup.whenIdle(appId, () -> deleteAppByAdminLocked(appId)));
    }

    private Boolean deleteAppByAdminLocked(Long appId) {
        ThrowUtils.throwIf(appId == null || appId <= 0,
                ErrorCode.PARAMS_ERROR, "删除应用请求参数异常");
        // 0. 安装任务未完成或资源未核清时不能删除，先取消安装并确认停止。
        requireNoActiveInstall(appId);
        // 1. 导入容器没确认停掉时中断，应用和持久副本都保留。
        releaseImportedExecution(appId);
        // 2. 应用记录删掉之后，再把仍可用的导入资格标成已中止。
        boolean result = this.removeById(appId);
        ThrowUtils.throwIf(!result, ErrorCode.OPERATION_ERROR, "删除应用失败");
        importPublications.retirePublication(appId);
        // 关联删除该应用的所有对话历史，失败不影响应用删除
        try {
            chatHistoryService.removeByAppId(appId);
        } catch (Exception e) {
            log.warn("管理员删除应用对话历史失败, appId={}", appId, e);
        }
        // 清理 code_output 下该应用产物目录
        // 运行容器与知识任务要在产物目录之前清：容器挂载着那个目录
        appServiceSupport.removeAppArtifacts(appId);
        // 容器删完再清台账与内存计数 —— 顺序反了会把还在跑的容器的指标清掉
        runtimeOrchestrator.purgeApp(appId);
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
            app.setEditTime(LocalDateTime.now());
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
        CodeGenTypeEnum type = resolveTypeOrNull(app);
        ThrowUtils.throwIf(type == null, ErrorCode.PARAMS_ERROR, "应用配置的 codeGenType 无效");
        return workspacePathResolver.outputDir(app.getId(), type);
    }

    /**
     * 解析应用的 codeGenType，无法识别时返回 null 而不抛错。
     *
     * <p>给需要「降级成描述」的调用方用：工作区描述拿不到类型时应说明原因，
     * 而不是把 500 抛给前端。
     */
    private CodeGenTypeEnum resolveTypeOrNull(App app) {
        String codeGenType = app.getCodeGenType();
        CodeGenTypeEnum type = CodeGenTypeEnum.getEnumByValue(codeGenType);
        if (type == null && StrUtil.isNotBlank(codeGenType)) {
            try {
                type = CodeGenTypeEnum.valueOf(codeGenType);
            } catch (IllegalArgumentException ignored) {
                // 交由调用方决定是报错还是降级
            }
        }
        return type;
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

    @Override
    public AppWorkspaceVO getWorkspace(User loginUser, Long appId) {
        ThrowUtils.throwIf(appId == null || appId <= 0, ErrorCode.PARAMS_ERROR, "应用 ID 不能为空");

        App app = this.getById(appId);
        ThrowUtils.throwIf(app == null, ErrorCode.NOT_FOUND_ERROR, "应用不存在");
        ThrowUtils.throwIf(!loginUser.getId().equals(app.getUserId())
                        && !UserConstant.ADMIN_ROLE.equals(loginUser.getUserRole()),
                ErrorCode.NO_AUTH_ERROR, "只能查看自己的应用");

        AppWorkspaceVO vo = new AppWorkspaceVO();
        vo.setAppId(appId);
        vo.setCodeGenType(app.getCodeGenType());
        vo.setDashboardFeatures(parseDashboardFeatures(app.getDashboardFeatures()));

        // 类型无法识别时这里不抛错：描述不出来本身就是要给用户看的信息
        CodeGenTypeEnum type = resolveTypeOrNull(app);
        if (type == null) {
            vo.setHasSource(false);
            vo.setCapabilities(Collections.emptyList());
            vo.setUnsupportedReason("应用记录的生成类型无法识别，无法定位工作区");
            return vo;
        }
        vo.setWorkspaceDirName(workspacePathResolver.dirName(appId, type));

        Path projectRoot = workspacePathResolver.outputDir(appId, type);
        if (!Files.isDirectory(projectRoot)) {
            vo.setHasSource(false);
            vo.setCapabilities(Collections.emptyList());
            vo.setUnsupportedReason("工作区尚未生成");
            return vo;
        }
        vo.setHasSource(true);

        String staticEntry = appServiceSupport.detectStaticEntry(projectRoot);
        vo.setStaticEntry(staticEntry);
        if (staticEntry == null) {
            vo.setCapabilities(Collections.emptyList());
            vo.setUnsupportedReason("未找到可直接打开的静态入口，源码需要安装依赖或构建后才能预览");
        } else {
            vo.setCapabilities(List.of("preview"));
        }
        return vo;
    }

    @Override
    public AppWorkspaceVO updateDashboardFeatures(User loginUser, Long appId, DashboardFeaturesRequest request) {
        ThrowUtils.throwIf(request == null, ErrorCode.PARAMS_ERROR, "看板设置请求为空");

        App app = requireOwnedApp(loginUser, appId);
        boolean hadKnowledge = hasDashboardFeature(app.getDashboardFeatures(), "knowledge");

        // 这里必须用 UpdateChain：updateById 会跳过 null 字段，
        // 「全部关闭」（写 null）会退化成「不改动」，用户关不掉已经开着的功能。
        boolean updated = UpdateChain.create(this.getMapper())
                .set("dashboardFeatures", normalizeDashboardFeatures(request.getFeatures()))
                .set("updateTime", LocalDateTime.now())
                .eq("id", appId)
                .update();
        ThrowUtils.throwIf(!updated, ErrorCode.OPERATION_ERROR, "保存看板设置失败");

        AppWorkspaceVO workspace = getWorkspace(loginUser, appId);

        // 只有「本来是关的、这次打开了」才通知：关闭再打开、或本来就开着，都不该触发补生成。
        // 存量项目第一次启用时，Plan 要求针对最近一次完成的源码版本先补一份知识。
        boolean hasKnowledge = workspace.getDashboardFeatures() != null
                && workspace.getDashboardFeatures().contains("knowledge");
        if (!hadKnowledge && hasKnowledge) {
            eventPublisher.publishEvent(new DashboardFeaturesChangedEvent(
                    appId, loginUser.getId(), workspace.getDashboardFeatures(), true));
        }
        return workspace;
    }

    /** 判断功能集合里是否包含某项。反序列化后精准匹配，避免子串误判。 */
    private boolean hasDashboardFeature(String raw, String feature) {
        if (StrUtil.isBlank(raw) || StrUtil.isBlank(feature)) {
            return false;
        }
        return parseDashboardFeatures(raw).contains(feature);
    }

    @Override
    public Path resolveWorkspaceDir(User loginUser, Long appId) {
        App app = requireOwnedApp(loginUser, appId);
        return resolveOutputDir(app);
    }

    /** 过滤出合法的看板功能标识并序列化；全不选时返回 null（库里「未配置」的语义）。 */
    private static String normalizeDashboardFeatures(List<String> input) {        if (input == null || input.isEmpty()) {
            return null;
        }
        List<String> valid = input.stream().distinct().filter(DASHBOARD_FEATURES::contains).toList();
        return valid.isEmpty() ? null : JSONUtil.toJsonStr(valid);
    }

    /**
     * 解析库里的看板开关。
     *
     * <p>这里刻意吞掉解析异常：某一行的脏数据不该让整个工作区描述接口返回 500，
     * 降级成「全不选」用户还能继续操作。
     */
    private List<String> parseDashboardFeatures(String raw) {
        if (StrUtil.isBlank(raw)) {
            return Collections.emptyList();
        }
        try {
            return JSONUtil.toList(raw, String.class).stream()
                    .filter(DASHBOARD_FEATURES::contains)
                    .toList();
        } catch (Exception e) {
            log.warn("解析 dashboard_features 失败, raw={}", raw, e);
            return Collections.emptyList();
        }
    }

    @Override
    public List<ProjectTreeEntryVO> listProjectTree(User loginUser, Long appId, String relativePath) {
        App app = requireOwnedApp(loginUser, appId);
        ThrowUtils.throwIf(appServiceSupport.isPlatformInternalPath(relativePath),
                ErrorCode.PARAMS_ERROR, "该目录不开放浏览");
        Path target = resolveWithinWorkspace(app, relativePath);
        if (!Files.isDirectory(target)) {
            // 工作区还没生成时根目录本来就不存在 —— 那是「还没有内容」，不是「路径写错了」。
            // 与 getWorkspace 的降级语义保持一致，返回空列表而不是报错。
            if (target.equals(resolveOutputDir(app))) {
                return Collections.emptyList();
            }
            throw new MyException(ErrorCode.NOT_FOUND_ERROR, "目录不存在");
        }

        // 单层列举：文件树按需展开，不能像 collectProjectFiles 那样递归读全文
        Path root = resolveOutputDir(app);
        List<ProjectTreeEntryVO> entries = new ArrayList<>();
        try (Stream<Path> children = Files.list(target)) {
            children.filter(child -> !appServiceSupport.isHiddenFromTree(child))
                    .forEach(child -> entries.add(toTreeEntry(child, root)));
        } catch (IOException e) {
            throw new MyException(ErrorCode.SYSTEM_ERROR, "读取目录失败");
        }

        // 目录在前、文件在后，同类按名称 —— 与常见文件树一致
        entries.sort(Comparator
                .comparing((ProjectTreeEntryVO entry) -> !"dir".equals(entry.getType()))
                .thenComparing(ProjectTreeEntryVO::getName, String.CASE_INSENSITIVE_ORDER));
        return entries;
    }

    @Override
    public ProjectFileContentVO getProjectFileContent(User loginUser, Long appId, String relativePath) {
        App app = requireOwnedApp(loginUser, appId);
        ThrowUtils.throwIf(appServiceSupport.isPlatformInternalPath(relativePath),
                ErrorCode.PARAMS_ERROR, "该文件不开放浏览");
        Path file = resolveWithinWorkspace(app, relativePath);
        ThrowUtils.throwIf(!Files.isRegularFile(file), ErrorCode.NOT_FOUND_ERROR, "文件不存在");

        ProjectFileContentVO vo = new ProjectFileContentVO();
        vo.setPath(relativeWorkspacePath(app, file));
        vo.setUpdatedAt(lastModifiedMillis(file));
        vo.setSize(fileSize(file));

        boolean isText = appServiceSupport.isTextCodeFile(file);
        vo.setIsText(isText);
        if (!isText) {
            // 二进制文件不返回内容：由前端展示基本信息并提供下载
            return vo;
        }
        try {
            vo.setContent(Files.readString(file));
        } catch (IOException e) {
            // 扩展名在白名单里、内容却不是 UTF-8：降级为不可编辑，而不是把 500 抛给前端
            vo.setIsText(false);
            return vo;
        }
        vo.setLanguage(appServiceSupport.inferLanguage(vo.getPath()));
        vo.setVersion(appServiceSupport.contentVersion(file));
        return vo;
    }

    @Override
    public ProjectFileContentVO saveProjectFile(User loginUser, Long appId, ProjectFileSaveRequest request) {
        requireOwnedApp(loginUser, appId);
        return importWorkspaceCoordinator.withLock(appId, () -> saveProjectFileLocked(loginUser, appId, request));
    }

    private ProjectFileContentVO saveProjectFileLocked(User loginUser, Long appId, ProjectFileSaveRequest request) {
        ThrowUtils.throwIf(request == null, ErrorCode.PARAMS_ERROR, "保存请求为空");
        ThrowUtils.throwIf(StrUtil.isBlank(request.getPath()), ErrorCode.PARAMS_ERROR, "文件路径不能为空");
        ThrowUtils.throwIf(request.getContent() == null, ErrorCode.PARAMS_ERROR, "文件内容不能为空");

        App app = requireOwnedApp(loginUser, appId);
        ThrowUtils.throwIf(appServiceSupport.isPlatformInternalPath(request.getPath()),
                ErrorCode.PARAMS_ERROR, "平台受控目录文件不允许保存修改");
        Path file = resolveWithinWorkspace(app, request.getPath());
        ThrowUtils.throwIf(!Files.isRegularFile(file), ErrorCode.NOT_FOUND_ERROR, "文件不存在");
        ThrowUtils.throwIf(!appServiceSupport.isTextCodeFile(file), ErrorCode.PARAMS_ERROR, "只能保存文本文件");

        // 版本必填：以前省略 baseVersion 就等于把校验整个关掉，
        // 两个窗口同时保存时后写的会静默盖掉先写的，用户看不到任何冲突提示。
        ThrowUtils.throwIf(StrUtil.isBlank(request.getBaseVersion()),
                ErrorCode.PARAMS_ERROR, "缺少文件版本，请重新读取后再保存");

        // 校验与写入必须在同一把锁里完成。
        // 光让版本必填还不够：两个请求可以先后读到同一个版本、各自都通过校验，
        // 然后一前一后写下去，后写的静默覆盖先写的（实测 12 并发仍有 2 个成功）。
        synchronized (saveLockFor(file)) {
            String currentVersion = appServiceSupport.contentVersion(file);
            if (!currentVersion.equals(request.getBaseVersion())) {
                throw new MyException(ErrorCode.CONFLICT_ERROR, "文件已被修改，请重新读取后再保存");
            }
            try {
                startupConfigurations.revokeForWrite(appId);
                Files.writeString(file, request.getContent());
            } catch (IOException e) {
                throw new MyException(ErrorCode.SYSTEM_ERROR, "写入文件失败");
            }
        }
        return getProjectFileContent(loginUser, appId, request.getPath());
    }

    @Override
    public void downloadProjectFile(User loginUser, Long appId, String relativePath, HttpServletResponse response) {
        App app = requireOwnedApp(loginUser, appId);
        ThrowUtils.throwIf(appServiceSupport.isPlatformInternalPath(relativePath),
                ErrorCode.PARAMS_ERROR, "该文件不开放下载");
        Path file = resolveWithinWorkspace(app, relativePath);
        ThrowUtils.throwIf(!Files.isRegularFile(file), ErrorCode.NOT_FOUND_ERROR, "文件不存在");

        String fileName = file.getFileName().toString();
        // URLEncoder 是给表单编码用的，空格会变成 +，下载下来文件名就成了 with+space.js。
        // 按 RFC 5987 给两份：filename 用 %20 的降级写法，filename* 给认 UTF-8 的浏览器。
        String encoded = URLEncoder.encode(fileName, StandardCharsets.UTF_8).replace("+", "%20");
        response.setContentType(ContentTypeUtils.fromFileName(fileName));
        response.setHeader("Content-Disposition",
                "attachment;filename=\"" + encoded + "\";filename*=UTF-8''" + encoded);
        try {
            Files.copy(file, response.getOutputStream());
            response.flushBuffer();
        } catch (IOException e) {
            throw new MyException(ErrorCode.SYSTEM_ERROR, "下载文件失败");
        }
    }

    /** 保存锁的条带数，理由见 {@link #saveLockFor}。 */
    private static final int SAVE_LOCK_STRIPES = 64;

    private static final Object[] SAVE_LOCKS = createSaveLocks();

    private static Object[] createSaveLocks() {
        Object[] locks = new Object[SAVE_LOCK_STRIPES];
        for (int i = 0; i < locks.length; i++) {
            locks[i] = new Object();
        }
        return locks;
    }

    /**
     * 取某个文件的保存锁。
     *
     * <p>用固定条带而不是 {@code Map<Path, Lock>}：后者要么随保存过的文件无限增长，
     * 要么在清理时引入新的竞态。条带锁的代价是极少数无关文件会互相等一等，
     * 对保存这种低频操作无所谓。
     *
     * <p>这是<b>进程内</b>锁，够单实例部署用。多实例时要换成文件锁或分布式锁，
     * 否则同一个文件仍会被两个 JVM 同时写。
     */
    private static Object saveLockFor(Path file) {
        int idx = Math.floorMod(
                file.toAbsolutePath().normalize().toString().hashCode(), SAVE_LOCK_STRIPES);
        return SAVE_LOCKS[idx];
    }

    /**
     * 取应用并校验归属（本人或管理员）。
     *
     * <p>只给新增的文件类接口用。既有的 getProjectFiles / getWorkspace 里各有一份同类校验，
     * 这里没有一并合并 —— 那两处的文案已被前端依赖，为消除重复去动它们不划算。
     */
    private App requireOwnedApp(User loginUser, Long appId) {
        ThrowUtils.throwIf(appId == null || appId <= 0, ErrorCode.PARAMS_ERROR, "应用 ID 不能为空");
        App app = this.getById(appId);
        ThrowUtils.throwIf(app == null, ErrorCode.NOT_FOUND_ERROR, "应用不存在");
        ThrowUtils.throwIf(!loginUser.getId().equals(app.getUserId())
                        && !UserConstant.ADMIN_ROLE.equals(loginUser.getUserRole()),
                ErrorCode.NO_AUTH_ERROR, "只能操作自己的应用");
        return app;
    }

    /**
     * 把工作区内的相对路径解析成绝对路径。
     *
     * <p>走 {@link WorkspacePathResolver#resolveOutputResource} 而不是自己拼字符串：
     * 那里用 {@code Path.startsWith} 做越界校验，能挡住 {@code ../} 与绝对路径注入。
     */
    private Path resolveWithinWorkspace(App app, String relativePath) {
        CodeGenTypeEnum type = resolveTypeOrNull(app);
        ThrowUtils.throwIf(type == null, ErrorCode.PARAMS_ERROR, "应用配置的 codeGenType 无效");
        String dirName = workspacePathResolver.dirName(app.getId(), type);
        String sub = StrUtil.isBlank(relativePath) ? "/" : relativePath;
        Path resolved = workspacePathResolver.resolveOutputResource(dirName, sub);

        // 归一化只挡得住 `..` 这种字面量逃逸。工作区要是被放了指向外部的
        // 符号链接或目录联接（Windows junction），解析出来的路径仍然在应用目录下，
        // 但拿它去读写会走到链接指向的地方 —— 所以要按**真实路径**再校一次。
        //
        // toRealPath 要求路径存在，所以只在存在时校验：不存在时本来就什么也读不到，
        // 写路径也会先撞上 isRegularFile 检查。
        try {
            if (Files.exists(resolved)) {
                Path appRoot = workspacePathResolver.outputDir(app.getId(), type);
                if (!resolved.toRealPath().startsWith(appRoot.toRealPath())) {
                    throw new MyException(ErrorCode.PARAMS_ERROR, "路径越界");
                }
            }
        } catch (IOException e) {
            throw new MyException(ErrorCode.SYSTEM_ERROR, "解析路径失败");
        }
        return resolved;
    }

    /** 工作区内的相对路径，统一用 {@code /} 分隔。 */
    private String relativeWorkspacePath(App app, Path file) {
        Path root = resolveOutputDir(app);
        return root.relativize(file).toString().replace('\\', '/');
    }

    private ProjectTreeEntryVO toTreeEntry(Path child, Path root) {
        ProjectTreeEntryVO entry = new ProjectTreeEntryVO();
        entry.setName(child.getFileName().toString());
        entry.setPath(root.relativize(child).toString().replace('\\', '/'));
        entry.setUpdatedAt(lastModifiedMillis(child));

        boolean isDir = Files.isDirectory(child);
        entry.setType(isDir ? "dir" : "file");
        if (!isDir) {
            entry.setSize(fileSize(child));
            entry.setIsText(appServiceSupport.isTextCodeFile(child));
        }
        return entry;
    }

    private static Long fileSize(Path file) {
        try {
            return Files.size(file);
        } catch (IOException e) {
            return null;
        }
    }

    private static Long lastModifiedMillis(Path file) {
        try {
            return Files.getLastModifiedTime(file).toMillis();
        } catch (IOException e) {
            return null;
        }
    }

}
