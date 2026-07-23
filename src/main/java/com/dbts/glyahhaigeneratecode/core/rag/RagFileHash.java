package com.dbts.glyahhaigeneratecode.core.rag;

import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;

/**
 * RAG 文件版本指纹工具。
 */
public final class RagFileHash {

    private static final int PREFIX_BYTES = 10;

    private RagFileHash() {
    }

    /**
     * 计算 SHA-256 的前 20 个十六进制字符。
     */
    public static String sha256Prefix(byte[] bytes) {
        if (bytes == null) {
            throw new IllegalArgumentException("bytes must not be null");
        }
        try {
            byte[] digest = MessageDigest.getInstance("SHA-256").digest(bytes);
            StringBuilder hash = new StringBuilder(PREFIX_BYTES * 2);
            for (int i = 0; i < PREFIX_BYTES; i++) {
                hash.append(Character.forDigit((digest[i] >>> 4) & 0x0f, 16));
                hash.append(Character.forDigit(digest[i] & 0x0f, 16));
            }
            return hash.toString();
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 is unavailable", e);
        }
    }
}
