package com.stocksage.evolution;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.nio.file.Path;
import java.nio.file.Files;
import java.io.IOException;
import java.util.Map;
import java.util.Arrays;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * 普通 FUNDAMENTALS 分析方法的选择器：默认基线，已批准方法包按灰度配置、控制标记和适用条件选用。
 *
 * <p>灰度 {@code rollout}：OFF 只用基线；ALLOWLIST 仅对 {@code rollout-accounts} 中的账号启用；ALL 对全部账号启用。
 * 控制目录中的 {@code STABLE_ONLY} 或 {@code REVOKE.<bundleId>} 标记无需重启即可让新请求回到基线。</p>
 */
@Component
public final class FundamentalsMethodRegistry {
    public enum Rollout { OFF, ALLOWLIST, ALL }

    private final AgentPolicyBundle active;
    private final ApprovedMethodArtifact staged;
    private final Path controlDirectory;
    private final Rollout rollout;
    private final Set<String> rolloutAccounts;
    private final Set<String> withdrawn = java.util.concurrent.ConcurrentHashMap.newKeySet();

    public FundamentalsMethodRegistry(
            @Value("${stocksage.evolution.fundamentals.approved-artifact:}") String artifact,
            @Value("${stocksage.evolution.fundamentals.approved-artifact-sha256:}") String pinnedHash,
            @Value("${stocksage.evolution.fundamentals.revoked-bundle-ids:}") String revoked,
            @Value("${stocksage.evolution.control-directory:}") String controlDirectory,
            @Value("${stocksage.evolution.fundamentals.rollout:OFF}") Rollout rollout,
            @Value("${stocksage.evolution.fundamentals.rollout-accounts:}") String rolloutAccounts) {
        this.active = AgentPolicyBundle.baseline();
        this.staged = artifact.isBlank() ? null : ApprovedMethodArtifact.load(Path.of(artifact), pinnedHash, split(revoked));
        this.controlDirectory = controlDirectory.isBlank() ? null : Path.of(controlDirectory);
        this.rollout = rollout;
        this.rolloutAccounts = split(rolloutAccounts);
        if (rollout != Rollout.OFF && (staged == null || this.controlDirectory == null)) {
            throw new IllegalStateException("启用方法灰度需要同时配置已批准方法包和控制目录（用于免重启撤回）。");
        }
        if (rollout == Rollout.ALLOWLIST && this.rolloutAccounts.isEmpty()) {
            throw new IllegalStateException("rollout=ALLOWLIST 需要配置 stocksage.evolution.fundamentals.rollout-accounts。");
        }
    }

    public AgentPolicyBundle active() { return active; }

    public boolean hasApproved() { return staged != null; }

    public AgentPolicyBundle require(String id) {
        if (active.bundleId().equals(id)) return active;
        if (staged != null && staged.bundle().bundleId().equals(id)) return staged.bundle();
        throw new IllegalArgumentException("未登记的方法包，请使用基线或启动时加载的已批准包 bundleId。");
    }

    public Map<String, String> identity() { return active.identity(); }

    /**
     * 为一次普通 FUNDAMENTALS 请求选择方法；只接受服务器推导的标签与运行条件，不接受请求指定的方法。
     *
     * @param conditions 本次请求的模型配置与最终回答规则哈希；不适用（图片、非 STANDARD 等）时为 null
     */
    public Selection select(String userId, Set<String> taskTags, Set<String> evidenceTags,
                            Set<String> capabilities, Map<String, String> conditions) {
        String reason = selectionReason(userId, taskTags, evidenceTags, capabilities, conditions);
        AgentPolicyBundle selected = "APPROVED_SCOPE".equals(reason) ? staged.bundle() : active;
        Map<String, Object> attributes = new java.util.LinkedHashMap<>();
        attributes.put("reason", reason);
        attributes.put("rollout", rollout.name());
        attributes.put("selectedBundleId", selected.bundleId());
        attributes.put("approvedArtifactSha256", staged == null ? "" : staged.artifactSha256());
        attributes.put("taskTags", sorted(taskTags));
        attributes.put("evidenceTags", sorted(evidenceTags));
        attributes.put("pinnedAt", java.time.Instant.now().toString());
        return new Selection(selected, Map.copyOf(attributes));
    }

    private String selectionReason(String userId, Set<String> taskTags, Set<String> evidenceTags,
                                   Set<String> capabilities, Map<String, String> conditions) {
        if (staged == null) return "NO_APPROVED_METHOD";
        if (rollout == Rollout.OFF) return "ROLLOUT_OFF";
        if (rollout == Rollout.ALLOWLIST && (userId == null || !rolloutAccounts.contains(userId))) return "ACCOUNT_OUTSIDE_ROLLOUT";
        Set<String> markers;
        try {
            markers = controlMarkers();
        } catch (IOException | java.nio.file.DirectoryIteratorException | SecurityException unavailable) {
            return "CONTROL_UNAVAILABLE";
        }
        String bundleId = staged.bundle().bundleId();
        if (markers.contains("STABLE_ONLY") || markers.contains("REVOKE." + bundleId)) withdrawn.add(bundleId);
        if (withdrawn.contains(bundleId)) return "BUNDLE_WITHDRAWN";
        var scope = staged.scope();
        if (!taskTags.containsAll(scope.taskTags()) || !evidenceTags.containsAll(scope.requiredEvidence())
                || !capabilities.containsAll(scope.requiredCapabilities())) return "REQUEST_OUTSIDE_SCOPE";
        if (conditions == null) return "REQUEST_CONDITIONS_UNAVAILABLE";
        if (!staged.comparisonIdentity().equals(conditions)) return "RUNTIME_CONDITIONS_CHANGED";
        return "APPROVED_SCOPE";
    }

    private Set<String> controlMarkers() throws IOException {
        Set<String> markers = new java.util.HashSet<>();
        try (var entries = Files.newDirectoryStream(controlDirectory)) {
            for (Path entry : entries) markers.add(entry.getFileName().toString());
        }
        return markers;
    }

    private static Set<String> split(String values) {
        return Arrays.stream(values.split(",")).map(String::trim).filter(value -> !value.isEmpty())
                .collect(Collectors.toUnmodifiableSet());
    }

    private static java.util.List<String> sorted(Set<String> values) {
        return values.stream().sorted().toList();
    }

    public record Selection(AgentPolicyBundle bundle, Map<String, Object> attributes) {}
}
