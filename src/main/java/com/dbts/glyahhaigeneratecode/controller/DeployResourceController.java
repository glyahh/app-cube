package com.dbts.glyahhaigeneratecode.controller;

import com.dbts.glyahhaigeneratecode.exception.MyException;
import com.dbts.glyahhaigeneratecode.service.support.WorkspacePathResolver;
import com.dbts.glyahhaigeneratecode.utils.ContentTypeUtils;
import jakarta.servlet.http.HttpServletRequest;
import lombok.RequiredArgsConstructor;
import org.springframework.core.io.FileSystemResource;
import org.springframework.core.io.Resource;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.servlet.HandlerMapping;

import java.nio.file.Files;
import java.nio.file.Path;

/**
 * 部署静态资源访问：读取 {@code temp/code_deploy/{deployKey}}。
 *
 * <p>对外发布入口，拿到 deployKey 的人都能访问 —— 所以**必须**做路径越界校验。
 * 收敛前这里是把 URL 子路径直接字符串拼接后交给 {@code new File(...)}，
 * 没有归一化，存在 {@code ..} 穿越。
 */
@RestController
@RequestMapping("/deploy")
@RequiredArgsConstructor
public class DeployResourceController {

    private final WorkspacePathResolver pathResolver;

    @GetMapping("/{deployKey}/**")
    public ResponseEntity<Resource> serveDeployResource(@PathVariable String deployKey, HttpServletRequest request) {
        String resourcePath = (String) request.getAttribute(HandlerMapping.PATH_WITHIN_HANDLER_MAPPING_ATTRIBUTE);
        resourcePath = resourcePath.substring(("/deploy/" + deployKey).length());

        // 目录访问时补尾斜杠，避免相对资源路径解析偏移
        if (resourcePath.isEmpty()) {
            HttpHeaders headers = new HttpHeaders();
            headers.add("Location", request.getRequestURI() + "/");
            return new ResponseEntity<>(headers, HttpStatus.MOVED_PERMANENTLY);
        }
        if ("/".equals(resourcePath)) {
            resourcePath = "/index.html";
        }

        try {
            Path file = pathResolver.resolveDeployResource(deployKey, resourcePath);
            if (!Files.isRegularFile(file)) {
                return ResponseEntity.notFound().build();
            }
            return ResponseEntity.ok()
                    .header("Content-Type", ContentTypeUtils.fromFileName(file.getFileName().toString()))
                    .header("Cache-Control", "no-cache, no-store, must-revalidate")
                    .header("Pragma", "no-cache")
                    .header("Expires", "0")
                    .body(new FileSystemResource(file));
        } catch (MyException e) {
            // 路径越界。回 404 而不是 400/500 —— 不向探测者透露目录结构。
            return ResponseEntity.notFound().build();
        } catch (Exception e) {
            return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR).build();
        }
    }
}
