package com.dbts.glyahhaigeneratecode.utils;

/**
 * 静态资源的 Content-Type 判定。
 *
 * <p>刻意不用 {@link java.nio.file.Files#probeContentType}：它不给 charset，
 * 中文 HTML 会乱码。这里显式给文本类加上 UTF-8。
 */
public final class ContentTypeUtils {

    private ContentTypeUtils() {
    }

    public static String fromFileName(String fileName) {
        String name = fileName == null ? "" : fileName.toLowerCase();
        if (name.endsWith(".html") || name.endsWith(".htm")) return "text/html; charset=UTF-8";
        if (name.endsWith(".css")) return "text/css; charset=UTF-8";
        if (name.endsWith(".js") || name.endsWith(".mjs")) return "application/javascript; charset=UTF-8";
        if (name.endsWith(".json")) return "application/json; charset=UTF-8";
        if (name.endsWith(".svg")) return "image/svg+xml";
        if (name.endsWith(".png")) return "image/png";
        if (name.endsWith(".jpg") || name.endsWith(".jpeg")) return "image/jpeg";
        if (name.endsWith(".webp")) return "image/webp";
        if (name.endsWith(".gif")) return "image/gif";
        return "application/octet-stream";
    }
}
