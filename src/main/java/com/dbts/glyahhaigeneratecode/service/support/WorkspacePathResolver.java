package com.dbts.glyahhaigeneratecode.service.support;

import com.dbts.glyahhaigeneratecode.config.WorkspaceProperties;
import com.dbts.glyahhaigeneratecode.exception.ErrorCode;
import com.dbts.glyahhaigeneratecode.exception.MyException;
import com.dbts.glyahhaigeneratecode.model.enums.CodeGenTypeEnum;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

import java.nio.file.InvalidPathException;
import java.nio.file.Path;

/**
 * 产物路径的唯一构造与校验入口。
 *
 * <p>所有从外部输入（URL 子路径、appId、deployKey）拼出来的路径都必须过这里。
 * 收敛前 {@code DeployResourceController} 和 {@code StaticResourceController}
 * 是把 URL 里的子路径直接字符串拼接后交给 {@code new File(...)}，没有归一化，
 * 存在 {@code ..} 穿越。
 */
@Component
@RequiredArgsConstructor
public class WorkspacePathResolver {

    private final WorkspaceProperties props;

    /**
     * 生成产物的目录名，形如 {@code html_431379639885025280} 或 {@code vue_project_...}。
     *
     * <p>目录前缀映射统一走 {@link CodeGenTypeEnum#getDirPrefix()}，
     * 不要再在调用点硬编码 {@code "vue_project_"}。
     */
    public String dirName(Long appId, CodeGenTypeEnum type) {
        if (appId == null || appId <= 0) {
            throw new MyException(ErrorCode.PARAMS_ERROR, "应用 ID 不合法");
        }
        if (type == null) {
            throw new MyException(ErrorCode.PARAMS_ERROR, "生成类型不合法");
        }
        return type.getDirPrefix() + "_" + appId;
    }

    /** 生成产物目录。 */
    public Path outputDir(Long appId, CodeGenTypeEnum type) {
        return guardWithin(props.getOutputRoot(), dirName(appId, type));
    }

    /** 发布产物目录。 */
    public Path deployDir(String deployKey) {
        return guardWithin(props.getDeployRoot(), deployKey);
    }

    /**
     * 解析预览请求要读的文件。
     *
     * @param dirName      产物目录名
     * @param resourcePath URL 里的剩余路径，如 {@code /index.html}
     */
    public Path resolveOutputResource(String dirName, String resourcePath) {
        // 必须分两段解析：先把 dirName 定位到某一个应用自己的目录，再在它下面解析子路径。
        // 拼成一个字符串交给同一个根校验是不行的 —— ../ 会带着路径跑到 outputRoot 下
        // 别的应用目录里（甚至别 人的应用），而 startsWith(outputRoot) 依然成立，校验形同虚设。
        Path appRoot = guardWithin(props.getOutputRoot(), dirName);
        return guardWithin(appRoot, normalize(resourcePath));
    }

    /**
     * 解析发布请求要读的文件。
     *
     * @param deployKey    发布 key
     * @param resourcePath URL 里的剩余路径
     */
    public Path resolveDeployResource(String deployKey, String resourcePath) {
        // 同 resolveOutputResource：两段解析，避免 ../ 跨到别的发布目录
        Path deployDir = guardWithin(props.getDeployRoot(), deployKey);
        return guardWithin(deployDir, normalize(resourcePath));
    }

    /**
     * 把外部传入的相对资源路径规整成可以直接交给 {@link Path#resolve} 的形式。
     *
     * <p><b>必须去掉前导分隔符。</b>以前这里是「不以 / 开头就补一个」，因为那时是
     * {@code dirName + resourcePath} 拼成一个字符串再解析，前导 / 只当分隔符用。
     * 改成两段解析之后语义就反了：{@code resolve("/sub/a.txt")} 在 Java 里表示
     * 「从磁盘根开始拼」，结果必然跑出应用目录、被越界校验拦下 —— 正常请求全废。
     * 空串按目录本身处理。
     */
    private static String normalize(String resourcePath) {
        if (resourcePath == null) {
            return "";
        }
        String trimmed = resourcePath.trim();
        while (trimmed.startsWith("/") || trimmed.startsWith("\\")) {
            trimmed = trimmed.substring(1);
        }
        return trimmed;
    }

    /**
     * 把名字拼到根下，并确认结果没跑出根。
     *
     * <p>用 {@link Path#startsWith} 而不是字符串前缀比较 —— 它是按路径元素比的，
     * {@code /a/bc} 不会被误判成在 {@code /a/b} 之下。字符串比较会有这个漏洞。
     */
    private Path guardWithin(Path root, String name) {
        Path rootAbs = root.toAbsolutePath().normalize();
        Path candidate;
        try {
            candidate = rootAbs.resolve(name).normalize();
        } catch (InvalidPathException e) {
            // 冒号、空字节这类字符会让 Path 解析直接抛异常。
            // 它是调用方传错了参数，不是服务端故障 —— 不能让它冒泡成 500。
            throw new MyException(ErrorCode.PARAMS_ERROR, "路径不合法");
        }
        if (!candidate.startsWith(rootAbs)) {
            throw new MyException(ErrorCode.PARAMS_ERROR, "路径越界");
        }
        return candidate;
    }
}
