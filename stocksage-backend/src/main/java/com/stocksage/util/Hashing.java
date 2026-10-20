package com.stocksage.util;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;

/**
 * 文本指纹的通用工具。
 */
public final class Hashing {

    private Hashing() {
    }

    /**
     * 计算 UTF-8 文本的 SHA-256。
     *
     * @param value 输入文本；null 时抛出 NullPointerException，空值归一由调用方决定
     * @return 64 位小写十六进制摘要
     */
    public static String sha256Hex(String value) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256")
                    .digest(value.getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException impossible) {
            throw new IllegalStateException("JVM does not support SHA-256", impossible);
        }
    }
}
