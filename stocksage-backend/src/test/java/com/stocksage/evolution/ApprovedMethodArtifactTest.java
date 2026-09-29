package com.stocksage.evolution;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Base64;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class ApprovedMethodArtifactTest {
    private static final byte[] KEY = "unit-test-only-independent-publisher-secret".getBytes(StandardCharsets.UTF_8);
    private static final String HASH = "c".repeat(64);
    private final ObjectMapper mapper = new ObjectMapper();
    @TempDir Path directory;

    @Test void approvedPackageLoadsForValidationWhileServingStaysPinnedToBaseline() throws Exception {
        ObjectNode artifact = signed(payload());
        ApprovedMethodArtifact loaded = load(artifact, artifact.path("payloadSha256").asText(), KEY, Set.of(), runtime(HASH));
        var registry = new FundamentalsMethodRegistry("baseline-v1", loaded);
        assertThat(registry.approvedForValidation("candidate-approved").bundle().method()).contains("期间");
        assertThat(registry.active()).isEqualTo(AgentPolicyBundle.baseline());
        assertThatThrownBy(() -> registry.require("candidate-approved")).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> registry.approvedForValidation("unknown")).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new FundamentalsMethodRegistry("candidate-approved", loaded)).isInstanceOf(IllegalArgumentException.class);
    }

    @Test void rejectsTamperingUnpinnedArtifactsWrongKeysRevocationsAndBuildDrift() throws Exception {
        ObjectNode artifact = signed(payload());
        String pinned = artifact.path("payloadSha256").asText();
        assertThatThrownBy(() -> load(artifact, "0".repeat(64), KEY, Set.of(), runtime(HASH))).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> load(artifact, pinned, new byte[32], Set.of(), runtime(HASH))).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> load(artifact, pinned, KEY, Set.of("candidate-approved"), runtime(HASH))).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> load(artifact, pinned, KEY, Set.of(), runtime("d".repeat(64)))).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> load(artifact, pinned, KEY, Set.of(), Map.of("status", "UNKNOWN"))).isInstanceOf(IllegalArgumentException.class);
        artifact.put("signature", "0".repeat(64));
        assertThatThrownBy(() -> load(artifact, pinned, KEY, Set.of(), runtime(HASH))).isInstanceOf(IllegalArgumentException.class);
    }

    @Test void publisherSignatureCannotOverrideFixedContractRollbackOrScope() throws Exception {
        for (String mutation : List.of("contract", "method", "rollback", "scope", "state", "extra")) {
            ObjectNode payload = payload();
            ObjectNode pack = (ObjectNode) payload.path("package");
            switch (mutation) {
                case "contract" -> ((ObjectNode) pack.path("bundle")).put("fixedContractSha256", HASH);
                case "method" -> ((ObjectNode) pack.path("bundle")).put("method", "被替换的正文");
                case "rollback" -> ((ObjectNode) pack.path("rollback")).put("bundleSha256", HASH);
                case "scope" -> ((ObjectNode) pack.path("scope")).put("route", "DEEP");
                case "state" -> payload.put("status", "ACTIVE");
                case "extra" -> pack.put("tools", "write");
            }
            ObjectNode artifact = signed(payload);
            assertThatThrownBy(() -> load(artifact, artifact.path("payloadSha256").asText(), KEY, Set.of(), runtime(HASH)))
                    .as(mutation).isInstanceOf(IllegalArgumentException.class).hasMessageContaining("待验证包不能直接激活");
        }
    }

    private ApprovedMethodArtifact load(ObjectNode artifact, String pinned, byte[] key, Set<String> revoked,
                                         Map<String, Object> runtime) throws Exception {
        Path file = directory.resolve("approved.json");
        Files.write(file, mapper.writeValueAsBytes(artifact));
        return ApprovedMethodArtifact.load(file, pinned, key, revoked, runtime);
    }

    private Map<String, Object> runtime(String digest) {
        return Map.of("status", "KNOWN", "scope", "CODE_SOURCE_FILE", "sha256", digest);
    }

    private ObjectNode payload() {
        AgentPolicyBundle baseline = AgentPolicyBundle.baseline();
        AgentPolicyBundle candidate = AgentPolicyBundle.create("candidate-approved", baseline.bundleId(), "先核对期间与单位，再比较指标。😀");
        ObjectNode pack = mapper.createObjectNode();
        pack.put("kind", "METHOD_RELEASE_PACKAGE").put("schemaVersion", 1);
        pack.set("bundle", mapper.valueToTree(candidate));
        pack.set("scope", mapper.valueToTree(Map.of("route", "ORDINARY_FUNDAMENTALS", "taskTags", List.of("period-comparison"),
                "requiredEvidence", List.of("dated-values"), "requiredCapabilities", List.of("evidence-reading"), "applicabilityBoundary", "已提供期间和单位")));
        pack.set("experience", mapper.valueToTree(Map.of("recordSha256", HASH, "version", 1, "registrySha256", HASH)));
        for (String name : List.of("candidateSha256", "releaseEvidenceSha256", "acceptanceGateSha256", "evaluatorSha256")) pack.put(name, HASH);
        pack.set("buildIdentity", mapper.valueToTree(Map.of("status", "VERIFIED_ARTIFACT", "runtimeArtifact", runtime(HASH),
                "manifest", Map.of("artifactSha256", HASH, "artifactFormat", "SPRING_BOOT_JAR"))));
        pack.set("expectedComparisonIdentity", mapper.valueToTree(Map.of("modelConfigSha256", HASH, "fixedFinalPromptSha256", HASH,
                "memorySnapshotSha256", HASH, "runtimeBuildSha256", FundamentalsRuntimeIdentity.hash(mapper.convertValue(pack.path("buildIdentity"), Map.class)))));
        pack.set("rollback", mapper.valueToTree(Map.of("bundleId", baseline.bundleId(), "bundleSha256", baseline.contentSha256())));
        ObjectNode payload = mapper.createObjectNode();
        payload.put("kind", "APPROVED_METHOD_PACKAGE").put("schemaVersion", 1).put("status", "APPROVED_PENDING_VALIDATION")
                .put("packageSha256", HASH).put("approvalSha256", HASH);
        payload.set("package", pack);
        return payload;
    }

    private ObjectNode signed(ObjectNode payload) throws Exception {
        byte[] bytes = mapper.writeValueAsBytes(payload);
        Mac mac = Mac.getInstance("HmacSHA256");
        mac.init(new SecretKeySpec(KEY, "HmacSHA256"));
        return mapper.createObjectNode().put("schema", "fundamentals_publisher_artifact_v1")
                .put("payloadBase64", Base64.getEncoder().encodeToString(bytes))
                .put("payloadSha256", AgentPolicyBundle.sha256(bytes))
                .put("signature", HexFormat.of().formatHex(mac.doFinal(bytes)));
    }
}
