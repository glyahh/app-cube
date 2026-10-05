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
 * 生成产物的预览入口（读 {@code temp/code_output}）。
 *
 * <p>与 {@link DeployResourceController} 的区别：那个读 {@code temp/code_deploy}（正式发布），
 * 这个读 {@code temp/code_output}（生成后即时预览）。
 */
@RestController
@RequestMapping("/static")
@RequiredArgsConstructor
public class StaticResourceController {

    private final WorkspacePathResolver pathResolver;
    private final com.dbts.glyahhaigeneratecode.projectimport.ImportWorkspaceGate importGate;
    private final com.dbts.glyahhaigeneratecode.service.UserService users;

    /**
     * 提供静态资源访问，支持目录重定向。
     * 访问格式：http://localhost:8124/api/static/{dirName}[/{fileName}]
     *
     * <p>注意路径变量是**产物目录名**（如 {@code vue_project_431379639885025280}），
     * 不是 deployKey。收敛前这个名字有误导性。
     */
    @GetMapping("/{dirName}/**")
    public ResponseEntity<Resource> serveStaticResource(
            @PathVariable String dirName,
            HttpServletRequest request) {
        String resourcePath = (String) request.getAttribute(HandlerMapping.PATH_WITHIN_HANDLER_MAPPING_ATTRIBUTE);
        resourcePath = resourcePath.substring(("/static/" + dirName).length());
        boolean imported;
        try {
            String canonical = pathResolver.canonicalOutputName(dirName);
            Long userId = null;
            if (importGate.requireReadable(canonical) != null) userId = users.getUserInSession(request).getId();
            imported = importGate.authorizeStatic(canonical, userId);
            if (imported && com.dbts.glyahhaigeneratecode.service.support.ProjectImportManifest.isSensitivePath(resourcePath)) return ResponseEntity.notFound().build();
        } catch (MyException e) { return ResponseEntity.notFound().build(); }

        // 目录访问（不带尾斜杠）重定向到带斜杠的 URL，否则相对资源会解析错
        if (resourcePath.isEmpty()) {
            HttpHeaders headers = new HttpHeaders();
            headers.add("Location", request.getRequestURI() + "/");
            return new ResponseEntity<>(headers, HttpStatus.MOVED_PERMANENTLY);
        }
        if (resourcePath.equals("/")) {
            resourcePath = "/index.html";
        }

        try {
            Path file = pathResolver.resolveOutputResource(dirName, resourcePath);
            if (!Files.isRegularFile(file)) {
                return ResponseEntity.notFound().build();
            }
            // 禁止浏览器缓存，确保预览始终加载最新内容
            return ResponseEntity.ok()
                    .header("Content-Type", ContentTypeUtils.fromFileName(file.getFileName().toString()))
                    .header("Cache-Control", "no-cache, no-store, must-revalidate")
                    .header("Pragma", "no-cache")
                    .header("Expires", "0")
                    .header("X-Content-Type-Options", "nosniff")
                    .header("Content-Security-Policy", imported
                            ? "sandbox allow-scripts; default-src 'self' data: blob:; script-src 'self' 'unsafe-inline' 'unsafe-eval'; style-src 'self' 'unsafe-inline'; connect-src 'none'; form-action 'none'; frame-src 'none'; object-src 'none'; base-uri 'none'"
                            : "")
                    .body(new FileSystemResource(file));
        } catch (MyException e) {
            // 路径越界。回 404 而不是 400/500 —— 不向探测者透露目录结构。
            return ResponseEntity.notFound().build();
        } catch (Exception e) {
            return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR).build();
        }
    }
}
