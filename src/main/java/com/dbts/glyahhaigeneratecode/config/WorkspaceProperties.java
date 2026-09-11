package com.dbts.glyahhaigeneratecode.config;

import com.dbts.glyahhaigeneratecode.constant.AppConstant;
import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.context.annotation.Configuration;

import java.nio.file.Path;
import java.util.List;

/**
 * 工作区路径配置。
 *
 * <p>这是产物路径的**唯一可覆盖入口**。默认值直接用 {@link AppConstant} 里那两个常量的
 * 表达式，避免出现「两套默认值各自演化」——收敛前有三处路径来源不统一，
 * 其中 CodeFileSaverTemplate 还硬编码了 Windows 绝对路径。
 *
 * <p>Java 是路径真相源：这些值解析出来后，会随请求下发给 Python，
 * Python 侧不做任何自己的路径拼接。
 */
@Data
@Configuration
@ConfigurationProperties(prefix = "workspace")
public class WorkspaceProperties {

    /** 生成产物根目录，如 {@code temp/code_output}。 */
    private Path outputRoot = Path.of(AppConstant.CODE_OUTPUT_ROOT_DIR);

    /** 发布产物根目录，如 {@code temp/code_deploy}。 */
    private Path deployRoot = Path.of(AppConstant.CODE_DEPLOY_ROOT_DIR);

    /**
     * 遍历产物目录时忽略的名字。与 {@link AppConstant#PROJECT_DOWNLOAD_IGNORE_FILES}
     * 同源，避免打包下载和记忆扫描用两份列表。
     */
    private List<String> ignore = AppConstant.PROJECT_DOWNLOAD_IGNORE_FILES;
}
