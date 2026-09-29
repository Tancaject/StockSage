package com.stocksage.evolution;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class AgentPolicyBundleTest {
    @Test void pythonExportUsesTheSameUtf16FramingAndUtf8Hash() {
        String fingerprint = org.springframework.test.util.ReflectionTestUtils.invokeMethod(AgentPolicyBundle.class,
                "fingerprint", "candidate-utf16-vector", "baseline-v1", "先比较期间。\nCompare units before judging 😀.",
                "0123456789abcdef0123456789abcdef0123456789abcdef0123456789abcdef");
        assertThat(fingerprint).isEqualTo("a152d60fdf2a3364b59dd42d21077b7d3d8fd5dfc7d758f66c8294786bbd77c0");
    }
    @Test void completeContentAndContractAreBoundBeforeAnyEvaluation() {
        AgentPolicyBundle baseline = AgentPolicyBundle.baseline();
        assertThat(AgentPolicyBundle.baseline()).isEqualTo(baseline);
        var candidate = AgentPolicyBundle.create("candidate-1", baseline.bundleId(), "检查问题要求的证据。");
        assertThat(candidate.contentSha256()).isNotEqualTo(baseline.contentSha256());
        assertThatThrownBy(() -> new AgentPolicyBundle(candidate.bundleId(), candidate.parentBundleId(),
                "已被替换的方法", candidate.fixedContractSha256(), candidate.contentSha256()))
                .isInstanceOf(IllegalArgumentException.class).hasMessageContaining("哈希");
        assertThatThrownBy(() -> new AgentPolicyBundle(candidate.bundleId(), candidate.parentBundleId(),
                candidate.method(), "0".repeat(64), candidate.contentSha256()))
                .isInstanceOf(IllegalArgumentException.class).hasMessageContaining("契约");
    }

    @Test void servingRegistryNeverActivatesAnUnapprovedCandidateOrCallerPath() {
        var registry = new FundamentalsMethodRegistry("baseline-v1");
        var pinned = registry.active();
        AgentPolicyBundle.create("candidate-1", "baseline-v1", "候选方法");
        assertThat(registry.active()).isSameAs(pinned);
        assertThatThrownBy(() -> registry.require("candidate-1")).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new FundamentalsMethodRegistry("../candidate.json"))
                .isInstanceOf(IllegalArgumentException.class);
        assertThat(registry.identity()).containsEntry("bundleSha256", pinned.contentSha256());
    }

    @Test void rejectsUnboundedOrInvalidMethodArtifacts() {
        for (String method : new String[]{null, " ", "x".repeat(AgentPolicyBundle.MAX_METHOD_CHARS + 1)}) {
            assertThatThrownBy(() -> AgentPolicyBundle.create("candidate", "baseline-v1", method))
                    .isInstanceOf(IllegalArgumentException.class);
        }
        assertThatThrownBy(() -> AgentPolicyBundle.create("../file", "baseline-v1", "方法"))
                .isInstanceOf(IllegalArgumentException.class);
    }
}
