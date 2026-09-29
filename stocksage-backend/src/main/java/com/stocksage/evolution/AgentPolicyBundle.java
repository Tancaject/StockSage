package com.stocksage.evolution;

import com.stocksage.agent.FundamentalsPrompts;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import java.util.Map;

/** A complete method is frozen before evaluation; provenance does not add runtime instructions. */
public record AgentPolicyBundle(String bundleId, String parentBundleId, String method,
                                String fixedContractSha256, String contentSha256) {
    public static final String BASELINE_ID = "baseline-v1";
    public static final int MAX_METHOD_CHARS = 2000;

    public AgentPolicyBundle {
        requireId(bundleId);
        if (parentBundleId != null) requireId(parentBundleId);
        if (method == null || method.isBlank() || method.length() > MAX_METHOD_CHARS) {
            throw new IllegalArgumentException("基本面方法必须为非空文本且不超过 " + MAX_METHOD_CHARS + " 字符。");
        }
        String expectedContract = contractHash();
        if (!expectedContract.equals(fixedContractSha256)) {
            throw new IllegalArgumentException("方法包固定契约与当前程序不一致，请重新验证方法包。");
        }
        if (!fingerprint(bundleId, parentBundleId, method, expectedContract).equals(contentSha256)) {
            throw new IllegalArgumentException("方法包内容哈希不匹配，请恢复已验证的原始方法包。");
        }
    }

    public static AgentPolicyBundle baseline() {
        return create(BASELINE_ID, null, FundamentalsPrompts.baselineMethod());
    }

    public static AgentPolicyBundle create(String id, String parentId, String method) {
        String contract = contractHash();
        return new AgentPolicyBundle(id, parentId, method, contract,
                fingerprint(id, parentId, method, contract));
    }

    public Map<String, String> identity() {
        return Map.of("bundleId", bundleId, "bundleSha256", contentSha256,
                "fixedContractSha256", fixedContractSha256, "scope", "FUNDAMENTALS_METHOD");
    }

    private static String contractHash() {
        return sha256(frame("FUNDAMENTALS_METHOD_V1")
                + frame(FundamentalsPrompts.system(""))
                + frame(FundamentalsPrompts.task("{{QUERY}}", "{{CONTEXT}}")));
    }

    private static String fingerprint(String id, String parent, String method, String contract) {
        return sha256(frame("FUNDAMENTALS_METHOD_V1") + frame(id) + frame(parent)
                + frame(contract) + frame(method));
    }

    private static String frame(String value) {
        return value == null ? "-1:" : value.length() + ":" + value;
    }

    public static String sha256(String value) {
        return sha256(value.getBytes(StandardCharsets.UTF_8));
    }

    public static String sha256(byte[] value) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256")
                    .digest(value));
        } catch (NoSuchAlgorithmException impossible) {
            throw new IllegalStateException("JVM does not support SHA-256", impossible);
        }
    }

    private static void requireId(String id) {
        if (id == null || !id.matches("[a-z0-9][a-z0-9-]{0,79}")) {
            throw new IllegalArgumentException("方法包 ID 只允许小写字母、数字和连字符，长度为 1 至 80。");
        }
    }
}
