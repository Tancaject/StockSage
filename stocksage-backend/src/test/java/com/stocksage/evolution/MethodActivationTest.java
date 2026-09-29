package com.stocksage.evolution;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.stocksage.agent.FundamentalsAgent;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.chat.messages.AssistantMessage;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.ai.chat.model.ChatResponse;
import org.springframework.ai.chat.model.Generation;
import org.springframework.ai.chat.prompt.Prompt;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.Base64;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

class MethodActivationTest {
    @TempDir Path directory;
    private static final byte[] KEY = "unit-test-only-independent-publisher-secret".getBytes(StandardCharsets.UTF_8);
    private final ObjectMapper mapper = new ObjectMapper();
    private final AgentPolicyBundle candidate = AgentPolicyBundle.create("approved-one", "baseline-v1", "测试候选：先核对期间与单位。");
    private final Map<String, String> conditions = Map.of("modelConfigSha256", "1".repeat(64), "fixedFinalPromptSha256", "2".repeat(64),
            "memorySnapshotSha256", "3".repeat(64), "runtimeBuildSha256", "4".repeat(64));
    private final ApprovedMethodArtifact approved = new ApprovedMethodArtifact(candidate, "5".repeat(64), "6".repeat(64),
            "7".repeat(64), conditions, new ApprovedMethodArtifact.Scope(List.of("comparison"), List.of("dated-values"),
            List.of("evidence-reading"), "只比较已提供的财报期间"), "8".repeat(64), 1);

    @Test void onlySeparatePurposeBoundSignedAuthorizationCanSelectTheApprovedPackage() throws Exception {
        var payload = payload("DRILL");
        String digest = sign(payload);
        assertThatThrownBy(() -> MethodActivation.load(directory.resolve("activation.json"), digest, KEY, approved, false))
                .isInstanceOf(IllegalArgumentException.class).hasMessageContaining("DRILL");
        var loaded = MethodActivation.load(directory.resolve("activation.json"), digest, KEY, approved, true);
        var registry = new FundamentalsMethodRegistry("baseline-v1", approved, directory, loaded);
        assertThat(select(registry, "internal-one", conditions).bundle()).isEqualTo(candidate);
        assertThat(select(registry, "other-user", conditions).attributes()).containsEntry("reason", "ACCOUNT_OUTSIDE_SCOPE");
        assertThat(registry.active()).isEqualTo(AgentPolicyBundle.baseline());
        assertThatThrownBy(() -> registry.require(candidate.bundleId())).isInstanceOf(IllegalArgumentException.class);
        assertThat(registry.select("internal-one", Set.of("news"), Set.of("dated-values"), Set.of("evidence-reading"), conditions, "a".repeat(64))
                .attributes()).containsEntry("reason", "REQUEST_OUTSIDE_SCOPE");
        assertThat(registry.select("internal-one", Set.of("comparison"), Set.of(), Set.of("evidence-reading"), conditions, "a".repeat(64))
                .attributes()).containsEntry("reason", "REQUEST_OUTSIDE_SCOPE");
        assertThatThrownBy(() -> MethodActivation.load(directory.resolve("activation.json"), "0".repeat(64), KEY, approved, true))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> MethodActivation.load(directory.resolve("activation.json"), digest, new byte[32], approved, true))
                .isInstanceOf(IllegalArgumentException.class);
        payload.put("mode", "SERVING");
        String noRollback = sign(payload);
        assertThatThrownBy(() -> MethodActivation.load(directory.resolve("activation.json"), noRollback, KEY, approved, false))
                .isInstanceOf(IllegalArgumentException.class);
        payload.put("rollbackAcceptanceSha256", "b".repeat(64));
        payload.put("checks", checks("SERVING"));
        String servingDigest = sign(payload);
        assertThat(MethodActivation.load(directory.resolve("activation.json"), servingDigest, KEY, approved, false).mode()).isEqualTo("SERVING");
        for (String field : List.of("approvedArtifactSha256", "packageSha256", "approvalSha256")) {
            var changed = new LinkedHashMap<>(payload);
            changed.put(field, "0".repeat(64));
            String wrong = sign(changed);
            assertThatThrownBy(() -> MethodActivation.load(directory.resolve("activation.json"), wrong, KEY, approved, false))
                    .isInstanceOf(IllegalArgumentException.class);
        }
    }

    @Test void withdrawalKeepsPinnedInvocationAndMakesSubsequentCallsUseBaselineWithoutAutomaticReactivation() throws Exception {
        String hash = sign(payload("DRILL"));
        var auth = MethodActivation.load(directory.resolve("activation.json"), hash, KEY, approved, true);
        var registry = new FundamentalsMethodRegistry("baseline-v1", approved, directory, auth);
        var pinned = select(registry, "internal-one", conditions);
        Files.createFile(directory.resolve("STABLE_ONLY"));
        var next = select(registry, "internal-one", conditions);
        assertThat(next.attributes()).containsEntry("reason", "BUNDLE_WITHDRAWN");
        assertThat(next.bundle()).isEqualTo(AgentPolicyBundle.baseline());
        ChatModel model = mock(ChatModel.class);
        when(model.call(any(Prompt.class))).thenAnswer(call -> new ChatResponse(List.of(new Generation(new AssistantMessage(
                call.<Prompt>getArgument(0).getInstructions().get(0).getText().contains(candidate.method()) ? "candidate" : "baseline")))));
        var agent = new FundamentalsAgent(ChatClient.builder(model).build(), registry);
        assertThat(agent.analyze("fixed question", "fixed evidence", pinned.bundle())).isEqualTo("candidate");
        assertThat(agent.analyze("fixed question", "fixed evidence", next.bundle())).isEqualTo("baseline");
        verify(model, times(2)).call(any(Prompt.class));
        Files.delete(directory.resolve("STABLE_ONLY"));
        assertThat(select(registry, "internal-one", conditions).attributes()).containsEntry("reason", "BUNDLE_WITHDRAWN");
        assertThat(pinned.bundle()).isEqualTo(candidate);
    }

    @Test void controlsConditionsAndAuthorizationLifetimeAreEnforcedIndependently() throws Exception {
        String hash = sign(payload("DRILL"));
        var auth = MethodActivation.load(directory.resolve("activation.json"), hash, KEY, approved, true);
        var registry = new FundamentalsMethodRegistry("baseline-v1", approved, directory, auth);
        Files.createFile(directory.resolve("STOP_SHADOW"));
        assertThat(select(registry, "internal-one", conditions).bundle()).isEqualTo(candidate);
        assertThat(registry.shadowBlockReason(candidate.bundleId(), false)).isEqualTo("SHADOW_STOPPED_BY_OPERATOR");
        Files.delete(directory.resolve("STOP_SHADOW"));
        Files.createFile(directory.resolve("PROHIBIT_ACTIVATION"));
        assertThat(select(registry, "internal-one", conditions).attributes()).containsEntry("reason", "ACTIVATION_PROHIBITED");
        assertThat(registry.shadowBlockReason(candidate.bundleId(), false)).isNull();
        Files.delete(directory.resolve("PROHIBIT_ACTIVATION"));
        assertThat(select(registry, "internal-one", conditions).bundle()).isEqualTo(candidate);
        var changed = new LinkedHashMap<>(conditions);
        changed.put("memorySnapshotSha256", "0".repeat(64));
        assertThat(select(registry, "internal-one", changed).attributes()).containsEntry("reason", "REQUEST_MEMORY_OUTSIDE_SCOPE");
        assertThat(select(registry, "internal-one", conditions).bundle()).isEqualTo(candidate);
        changed.put("memorySnapshotSha256", conditions.get("memorySnapshotSha256"));
        changed.put("modelConfigSha256", "0".repeat(64));
        assertThat(select(registry, "internal-one", changed).attributes()).containsEntry("reason", "VALIDATION_CONDITIONS_CHANGED");
        assertThat(select(registry, "internal-one", conditions).attributes()).containsEntry("reason", "BUNDLE_WITHDRAWN");
        var missing = new FundamentalsMethodRegistry("baseline-v1", approved, directory.resolve("missing"), auth);
        assertThat(select(missing, "internal-one", conditions).attributes()).containsEntry("reason", "CONTROL_UNAVAILABLE");
        var expired = new FundamentalsMethodRegistry("baseline-v1", approved, directory, new MethodActivation(hash, "old", "DRILL",
                Set.of("internal-one"), Instant.now().minusSeconds(20), Instant.now().minusSeconds(10)));
        assertThat(select(expired, "internal-one", conditions).attributes()).containsEntry("reason", "AUTHORIZATION_NOT_EFFECTIVE");
    }

    @Test void concurrentAccountsKeepTheirOwnMethodWhenWithdrawalOccursDuringBothCalls() throws Exception {
        String hash = sign(payload("DRILL"));
        var auth = MethodActivation.load(directory.resolve("activation.json"), hash, KEY, approved, true);
        var registry = new FundamentalsMethodRegistry("baseline-v1", approved, directory, auth);
        var entered = new java.util.concurrent.CountDownLatch(2);
        var release = new java.util.concurrent.CountDownLatch(1);
        ChatModel model = mock(ChatModel.class);
        when(model.call(any(Prompt.class))).thenAnswer(call -> {
            Prompt prompt = call.getArgument(0);
            entered.countDown();
            assertThat(release.await(5, java.util.concurrent.TimeUnit.SECONDS)).isTrue();
            return new ChatResponse(List.of(new Generation(new AssistantMessage(
                    prompt.getInstructions().get(0).getText().contains(candidate.method()) ? "candidate" : "baseline"))));
        });
        var agent = new FundamentalsAgent(ChatClient.builder(model).build(), registry);
        var pool = java.util.concurrent.Executors.newFixedThreadPool(2);
        try {
            var allowed = pool.submit(() -> agent.analyzeObserved("same question", "same evidence",
                    select(registry, "internal-one", conditions).bundle()));
            var outside = pool.submit(() -> agent.analyzeObserved("same question", "same evidence",
                    select(registry, "outside-account", conditions).bundle()));
            assertThat(entered.await(5, java.util.concurrent.TimeUnit.SECONDS)).isTrue();
            Files.createFile(directory.resolve("REVOKE." + candidate.bundleId()));
            assertThat(select(registry, "internal-one", conditions).bundle()).isEqualTo(AgentPolicyBundle.baseline());
            release.countDown();
            var first = allowed.get(5, java.util.concurrent.TimeUnit.SECONDS);
            var second = outside.get(5, java.util.concurrent.TimeUnit.SECONDS);
            assertThat(first.content()).isEqualTo("candidate");
            assertThat(first.methodBundle()).containsEntry("bundleId", candidate.bundleId());
            assertThat(first.systemPrompt()).contains(candidate.method());
            assertThat(second.content()).isEqualTo("baseline");
            assertThat(second.methodBundle()).containsEntry("bundleId", AgentPolicyBundle.BASELINE_ID);
            assertThat(second.systemPrompt()).doesNotContain(candidate.method());
            verify(model, times(2)).call(any(Prompt.class));
        } finally {
            release.countDown();
            pool.shutdownNow();
            assertThat(pool.awaitTermination(5, java.util.concurrent.TimeUnit.SECONDS)).isTrue();
        }
    }

    private FundamentalsMethodRegistry.Selection select(FundamentalsMethodRegistry registry, String user, Map<String, String> identity) {
        return registry.select(user, Set.of("comparison"), Set.of("dated-values"), Set.of("evidence-reading"), identity, "a".repeat(64));
    }

    private Map<String, Object> payload(String mode) {
        var root = new LinkedHashMap<String, Object>();
        root.put("kind", "METHOD_ACTIVATION"); root.put("schemaVersion", 1); root.put("mode", mode);
        root.put("approvedArtifactSha256", approved.artifactSha256()); root.put("packageSha256", approved.packageSha256());
        root.put("approvalSha256", approved.approvalSha256()); root.put("shadowAcceptanceSha256", "a".repeat(64));
        root.put("rollbackAcceptanceSha256", null); root.put("activationId", "unit-drill");
        root.put("approver", "unit-publisher"); root.put("reason", "fictional unit check");
        root.put("internalAccountIds", List.of("internal-one"));
        root.put("approvedAt", Instant.now().minusSeconds(20).toString()); root.put("expiresAt", Instant.now().plusSeconds(300).toString());
        root.put("scope", Map.of("route", "ORDINARY_FUNDAMENTALS", "taskTags", approved.scope().taskTags(),
                "requiredEvidence", approved.scope().requiredEvidence(), "requiredCapabilities", approved.scope().requiredCapabilities(),
                "applicabilityBoundary", approved.scope().applicabilityBoundary()));
        root.put("expectedComparisonIdentity", conditions); root.put("checks", checks(mode));
        return root;
    }

    private Map<String, Object> checks(String mode) {
        var checks = new LinkedHashMap<String, Object>();
        for (String name : "DRILL".equals(mode) ? List.of("internalAccounts", "preproductionIsolation", "runtimeConditions", "applicability", "retention")
                : List.of("internalAccounts", "runtimeConditions", "applicability", "monitoring", "retention")) {
            checks.put(name, Map.of("status", "PASS", "evidenceSha256", "c".repeat(64)));
        }
        return checks;
    }

    private String sign(Map<String, Object> payload) throws Exception {
        byte[] raw = mapper.writeValueAsBytes(payload);
        String hash = AgentPolicyBundle.sha256(raw);
        Mac mac = Mac.getInstance("HmacSHA256"); mac.init(new SecretKeySpec(KEY, "HmacSHA256"));
        mapper.writeValue(directory.resolve("activation.json").toFile(), Map.of("schema", "fundamentals_publisher_artifact_v1",
                "payloadBase64", Base64.getEncoder().encodeToString(raw), "payloadSha256", hash, "signature", HexFormat.of().formatHex(mac.doFinal(raw))));
        return hash;
    }
}
