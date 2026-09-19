package com.dbts.glyahhaigeneratecode.constant;

import java.util.List;

public interface AppConstant {

    /**
     * 首页「我的应用 / 精选应用」列表单页最大条数（与前端 page-size-options 上限一致）
     */
    int MAX_APP_LIST_PAGE_SIZE = 80;

    /**
     * 精选应用的优先级
     */
    Integer GOOD_APP_PRIORITY = 99;

    /**
     * 默认应用优先级
     */
    Integer DEFAULT_APP_PRIORITY = 0;

    /**
     * 应用生成目录
     */
    String CODE_OUTPUT_ROOT_DIR = System.getProperty("user.dir") + "/temp/code_output";

    /**
     * 应用部署目录
     */
    String CODE_DEPLOY_ROOT_DIR = System.getProperty("user.dir") + "/temp/code_deploy";

    /**
     * 应用运行日志根目录。按 {@code {appId}/{runNo}/} 分层，
     * 与产物目录分开 —— 日志会轮转删除，不能和源码混在一起。
     */
    String RUNTIME_LOG_ROOT_DIR = System.getProperty("user.dir") + "/temp/runtime_logs";

    /**
     * 应用部署域名
     */
    String CODE_DEPLOY_HOST = "http://localhost";

    /**
     * 项目打包下载时需要忽略的文件或目录名称
     */
    List<String> PROJECT_DOWNLOAD_IGNORE_FILES = List.of(
            "node_modules",
            ".git",
            "dist",
            "build",
            ".DS_Store",
            ".env",
            "target",
            ".mvn",
            ".idea",
            ".vscode"
    );

}
