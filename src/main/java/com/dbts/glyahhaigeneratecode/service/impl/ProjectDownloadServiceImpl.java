package com.dbts.glyahhaigeneratecode.service.impl;

import cn.hutool.core.util.StrUtil;
import cn.hutool.core.util.ZipUtil;
import com.dbts.glyahhaigeneratecode.config.WorkspaceProperties;
import com.dbts.glyahhaigeneratecode.exception.ErrorCode;
import com.dbts.glyahhaigeneratecode.exception.MyException;
import com.dbts.glyahhaigeneratecode.exception.ThrowUtils;
import com.dbts.glyahhaigeneratecode.service.ProjectDownloadService;
import jakarta.servlet.http.HttpServletResponse;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.io.File;
import java.io.IOException;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.Set;

/**
 * 项目打包下载 服务实现。
 */
@Service
@Slf4j
@RequiredArgsConstructor
public class ProjectDownloadServiceImpl implements ProjectDownloadService {

    private final WorkspaceProperties workspaceProperties;

    /**
     * 需要过滤的文件扩展名
     */
    private static final Set<String> IGNORED_EXTENSIONS = Set.of(
            ".log",
            ".tmp",
            ".cache"
    );


    /**
     *
     * @param response    HTTP 响应
     * @param appName     应用名称，默认为项目名
     * @param projectPath 项目所在目录的绝对路径（位于 temp/code_output 下）
     */
    @Override
    public void downloadProject(HttpServletResponse response, String appName, String projectPath) {
        if (response == null) {
            throw new MyException(ErrorCode.PARAMS_ERROR, "响应对象为空");
        }
        ThrowUtils.throwIf(StrUtil.isBlank(projectPath), ErrorCode.PARAMS_ERROR, "项目路径不能为空");

        // 统一校验：必须位于 code_output 根目录下
        Path rootPath = Paths.get(projectPath).normalize();
        Path baseRoot = workspaceProperties.getOutputRoot().toAbsolutePath().normalize();
        ThrowUtils.throwIf(!rootPath.startsWith(baseRoot), ErrorCode.NO_AUTH_ERROR, "非法的项目路径");
        ThrowUtils.throwIf(!Files.isDirectory(rootPath), ErrorCode.NOT_FOUND_ERROR, "项目目录不存在");

        // 处理 zip 文件名
        String safeAppName = StrUtil.isBlank(appName) ? "project" : appName.trim();
        // 避免非法文件名字符
        safeAppName = safeAppName.replaceAll("[\\\\/:*?\"<>|]", "_");
        String zipFileName = safeAppName + ".zip";

        try {
            String encodedFileName = URLEncoder.encode(zipFileName, StandardCharsets.UTF_8);
            response.setStatus(HttpServletResponse.SC_OK);
            response.setContentType("application/zip");
            response.setHeader("Content-Disposition",
                    "attachment; filename=\"" + encodedFileName + "\"; filename*=UTF-8''" + encodedFileName);

            File rootDir = rootPath.toFile();

//             Hutool ZipUtil.zip(
//               OutputStream out: 输出目标（这里是浏览器响应流）,
//               Charset charset: 压缩文件名编码,
//               boolean withSrcDir: 是否包含根目录本身,
//               FileFilter filter: 文件过滤逻辑（返回 true 表示打包）,
//               File... files: 需要被打包的源文件 / 目录
//             )
            ZipUtil.zip(response.getOutputStream(), StandardCharsets.UTF_8, false,
                    file -> acceptForZip(rootDir, file),
                    rootDir);

            log.error("项目打包下载成功, projectPath={}", projectPath);

        } catch (IOException e) {
            log.error("项目打包下载失败, projectPath={}", projectPath, e);
            throw new MyException(ErrorCode.SYSTEM_ERROR, "项目打包下载失败");
        }
    }

    /**
     * Hutool 打包时的文件过滤逻辑
     *
     * @param rootDir 根目录（项目路径）-> (对应的vue项目的vue_project_1145141919810文件夹)
     * @param file    当前待判断文件或目录
     * @return true 表示允许打进 zip，false 表示跳过
     */
    private boolean acceptForZip(File rootDir, File file) {
        // 名称命中忽略列表。走 workspaceProperties 而不是直接读常量 ——
        // 那个字段的注释写的是「避免打包下载和记忆扫描用两份列表」，两边同源但仍是两份。
        if (workspaceProperties.getIgnore().contains(file.getName())) {
            return false;
        }
        // 扩展名命中忽略列表
        String name = file.getName();
        int dotIndex = name.lastIndexOf('.');
        // 如果文件名有扩展名，并且扩展名在忽略列表中，则返回 false
        if (dotIndex != -1) {
            String ext = name.substring(dotIndex);
            if (IGNORED_EXTENSIONS.contains(ext)) {
                return false;
            }
        }

        // 父目录命中忽略列表, 防止node_modules的子文件也被下载
        File parent = file.getParentFile();
        while (parent != null && !parent.equals(rootDir)) {
            if (workspaceProperties.getIgnore().contains(parent.getName())) {
                return false;
            }
            parent = parent.getParentFile();
        }
        return true;
    }
}

