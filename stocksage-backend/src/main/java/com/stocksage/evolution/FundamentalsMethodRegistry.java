package com.stocksage.evolution;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;
import com.stocksage.config.RuntimeArtifactIdentity;

import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.nio.file.Files;
import java.io.IOException;
import java.util.Map;
import java.util.Arrays;
import java.util.stream.Collectors;

/** Only application-owned, approved artifacts may be selected; requests cannot register a method. */
@Component
public final class FundamentalsMethodRegistry {
    private final Map<String, AgentPolicyBundle> approved;
    private final AgentPolicyBundle active;
    private final ApprovedMethodArtifact staged;
    private final Path controlDirectory;
    private MethodActivation activation;
    private final java.util.Set<String> withdrawn = java.util.concurrent.ConcurrentHashMap.newKeySet();

    public FundamentalsMethodRegistry(String activeId) {
        this(activeId, null);
    }

    @Autowired
    public FundamentalsMethodRegistry(
            @Value("${stocksage.evolution.fundamentals.bundle-id:baseline-v1}") String activeId,
            @Value("${stocksage.evolution.fundamentals.approved-artifact:}") String artifact,
            @Value("${stocksage.evolution.fundamentals.approved-artifact-sha256:}") String pinnedHash,
            @Value("${stocksage.evolution.fundamentals.revoked-bundle-ids:}") String revoked,
            @Value("${STOCKSAGE_EVOLUTION_PUBLISHER_KEY:}") String publisherKey,
            @Value("${stocksage.evolution.control-directory:}") String controlDirectory,
            @Value("${stocksage.evolution.fundamentals.activation-artifact:}") String activationArtifact,
            @Value("${stocksage.evolution.fundamentals.activation-artifact-sha256:}") String activationHash,
            org.springframework.core.env.Environment environment,
            RuntimeArtifactIdentity runtime) {
        this(activeId, artifact.isBlank() ? null : ApprovedMethodArtifact.load(Path.of(artifact), pinnedHash,
                publisherKey.getBytes(StandardCharsets.UTF_8), Arrays.stream(revoked.split(",")).map(String::trim)
                        .filter(value -> !value.isEmpty()).collect(Collectors.toUnmodifiableSet()), runtime.snapshot()),
                controlDirectory.isBlank() ? null : Path.of(controlDirectory));
        if (!activationArtifact.isBlank()) {
            activation = MethodActivation.load(Path.of(activationArtifact), activationHash,
                    publisherKey.getBytes(StandardCharsets.UTF_8), staged,
                    environment.acceptsProfiles(org.springframework.core.env.Profiles.of("evolution-drill")));
        }
    }

    FundamentalsMethodRegistry(String activeId, ApprovedMethodArtifact staged) {
        this(activeId, staged, null);
    }

    FundamentalsMethodRegistry(String activeId, ApprovedMethodArtifact staged, Path controlDirectory) {
        AgentPolicyBundle baseline = AgentPolicyBundle.baseline();
        approved = Map.of(baseline.bundleId(), baseline);
        active = require(activeId);
        this.staged = staged;
        this.controlDirectory = controlDirectory;
    }

    FundamentalsMethodRegistry(String activeId, ApprovedMethodArtifact staged, Path controls, MethodActivation activation) {
        this(activeId, staged, controls);
        this.activation = activation;
    }

    public AgentPolicyBundle active() { return active; }

    public boolean hasAuthorization() { return activation != null && staged != null; }

    /** The loader binds this hash to the actual code-source bytes and the complete approved build identity. */
    public String verifiedBuildHash() {
        return staged == null ? "UNKNOWN" : staged.comparisonIdentity().get("runtimeBuildSha256");
    }

    public AgentPolicyBundle require(String id) {
        AgentPolicyBundle bundle = id == null ? null : approved.get(id);
        if (bundle == null) {
            throw new IllegalArgumentException("未登记的已批准方法包，请使用当前批准清单中的 bundleId。");
        }
        return bundle;
    }

    public Map<String, String> identity() { return active.identity(); }

    /** Operator-owned files are checked at admission and between model calls, never supplied by HTTP callers. */
    public String shadowBlockReason(String bundleId, boolean running) {
        java.util.Set<String> markers;
        try {
            markers = controlMarkers();
        } catch (IOException | java.nio.file.DirectoryIteratorException | SecurityException unavailable) {
            return "SHADOW_CONTROL_UNAVAILABLE";
        }
        if (markers.contains("CANCEL_SHADOW")) return "SHADOW_CANCELLED_BY_OPERATOR";
        if (!running && markers.contains("STOP_SHADOW")) return "SHADOW_STOPPED_BY_OPERATOR";
        if (!running && withdrawn.contains(bundleId)) return "SHADOW_BUNDLE_WITHDRAWN";
        return null;
    }

    private java.util.Set<String> controlMarkers() throws IOException {
        if (controlDirectory == null) throw new IOException("Control directory is not configured");
        java.util.Set<String> markers = new java.util.HashSet<>();
        try (var entries = Files.newDirectoryStream(controlDirectory)) {
            for (Path entry : entries) markers.add(entry.getFileName().toString());
        }
        if (staged != null && markers.contains("REVOKE." + staged.bundle().bundleId())) {
            withdrawn.add(staged.bundle().bundleId());
        }
        return markers;
    }

    /** Only trusted server-derived facts may call this selector; it accepts no requested candidate ID. */
    public Selection select(String userId, java.util.Set<String> taskTags, java.util.Set<String> evidenceTags,
                            java.util.Set<String> capabilities, Map<String, String> comparisonIdentity, String caseSha256) {
        String reason = selectionReason(userId, taskTags, evidenceTags, capabilities, comparisonIdentity, caseSha256);
        AgentPolicyBundle selected = "APPROVED_SCOPE".equals(reason) ? staged.bundle() : active;
        Map<String, Object> attributes = new java.util.LinkedHashMap<>();
        attributes.put("reason", reason);
        attributes.put("mode", activation == null ? "BASELINE" : activation.mode());
        attributes.put("authorizationSha256", activation == null ? "" : activation.authorizationSha256());
        attributes.put("activationId", activation == null ? "" : activation.activationId());
        attributes.put("pinnedAt", java.time.Instant.now().toString());
        attributes.put("caseSha256", caseSha256 == null ? "" : caseSha256);
        attributes.put("comparisonIdentity", comparisonIdentity == null ? Map.of() : Map.copyOf(comparisonIdentity));
        return new Selection(selected, Map.copyOf(attributes));
    }

    private String selectionReason(String userId, java.util.Set<String> taskTags, java.util.Set<String> evidenceTags,
                                   java.util.Set<String> capabilities, Map<String, String> identity, String caseHash) {
        if (activation == null || staged == null) return "NOT_AUTHORIZED";
        if (userId == null || !activation.internalAccountIds().contains(userId)) return "ACCOUNT_OUTSIDE_SCOPE";
        if (!activation.effectiveAt(java.time.Instant.now())) return "AUTHORIZATION_NOT_EFFECTIVE";
        java.util.Set<String> markers;
        try {
            markers = controlMarkers();
        } catch (IOException | java.nio.file.DirectoryIteratorException | SecurityException unavailable) {
            return "CONTROL_UNAVAILABLE";
        }
        if (markers.contains("STABLE_ONLY")) withdrawn.add(staged.bundle().bundleId());
        if (withdrawn.contains(staged.bundle().bundleId())) return "BUNDLE_WITHDRAWN";
        if (markers.contains("PROHIBIT_ACTIVATION")) return "ACTIVATION_PROHIBITED";
        if (taskTags == null || evidenceTags == null || capabilities == null || identity == null
                || caseHash == null || !caseHash.matches("[a-f0-9]{64}")) return "REQUEST_CONDITIONS_UNAVAILABLE";
        var scope = staged.scope();
        if (!taskTags.containsAll(scope.taskTags()) || !evidenceTags.containsAll(scope.requiredEvidence())
                || !capabilities.containsAll(scope.requiredCapabilities())) return "REQUEST_OUTSIDE_SCOPE";
        if (!staged.comparisonIdentity().keySet().equals(identity.keySet())) return "REQUEST_CONDITIONS_UNAVAILABLE";
        if (!staged.comparisonIdentity().get("memorySnapshotSha256").equals(identity.get("memorySnapshotSha256"))) {
            return "REQUEST_MEMORY_OUTSIDE_SCOPE";
        }
        if (!staged.comparisonIdentity().equals(identity)) {
            withdrawn.add(staged.bundle().bundleId());
            return "VALIDATION_CONDITIONS_CHANGED";
        }
        return "APPROVED_SCOPE";
    }

    public record Selection(AgentPolicyBundle bundle, Map<String, Object> attributes) {}

    /** Validation access alone grants no serving authority; selection requires separate activation. */
    public ApprovedMethodArtifact approvedForValidation(String id) {
        if (staged == null || !staged.bundle().bundleId().equals(id)) {
            throw new IllegalArgumentException("未加载该已批准待验证方法包，请核对启动时的发布制品与撤回清单。");
        }
        return staged;
    }
}
