package com.stocksage.research;

import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.stocksage.agent.AgentRuntimeConfiguration;
import com.stocksage.agent.DebateDecisionPolicy;
import com.stocksage.capability.CapabilityDescriptor;
import com.stocksage.capability.CapabilityRegistry;
import com.stocksage.harness.DeepResearchCompletionPolicy;
import com.stocksage.rag.RagService;
import com.stocksage.knowledge.EdgarIngestionService;
import com.stocksage.config.RuntimeArtifactIdentity;
import com.stocksage.config.ModelPricingProperties;
import com.stocksage.repository.ResearchTaskCheckpointRepository;
import com.stocksage.repository.ResearchTaskRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.Map;
import java.util.Comparator;
import java.util.HexFormat;
import java.security.MessageDigest;

import static java.util.Map.entry;

/** Freezes execution configuration independently of disposable checkpoints; invocation facts remain separate. */
@Service
@RequiredArgsConstructor
public class ResearchRunManifestService {
    private final ResearchTaskRepository taskRepository;
    private final ResearchTaskCheckpointRepository checkpointRepository;
    private final AgentRuntimeConfiguration runtimeConfiguration;
    private final ObjectMapper objectMapper;
    private final CapabilityRegistry capabilityRegistry;
    private final RagService ragService;
    private final DeepEvidenceReplanService replanService;
    private final EdgarIngestionService edgarIngestionService;
    private final RuntimeArtifactIdentity artifactIdentity;
    private final ModelPricingProperties pricing;

    @Transactional
    public void verifyOrFreeze(Long taskId, String leaseToken) {
        try {
            if (checkpointRepository.lockOwnedRunningTask(taskId, leaseToken).isEmpty()) {
                throw new ManifestException(taskId, "当前执行者不再持有运行中任务");
            }
            // Native reads bypass entities loaded before startAttempt/takeover native updates.
            String stored = taskRepository.readModelConfiguration(taskId);
            if (!"KNOWN".equals(artifactIdentity.snapshot().get("status"))) {
                throw new ManifestException(taskId, "运行制品身份无法确认，请检查应用加载来源");
            }
            // Compare persisted JSON types: small Java longs decode as JSON integer nodes.
            JsonNode current = objectMapper.readTree(objectMapper.writeValueAsString(Map.ofEntries(
                    entry("schemaVersion", 6), entry("scope", "EXECUTION_CONFIGURATION"),
                    entry("configuration", runtimeConfiguration.snapshot()),
                    entry("chatProvider", runtimeConfiguration.chatProviderSnapshot()),
                    entry("pricing", pricing.snapshot()),
                    entry("policies", Map.of(
                            "completion", policy(DeepResearchCompletionPolicy.class,
                                    DeepResearchCompletionPolicy.POLICY_ID, DeepResearchCompletionPolicy.POLICY_VERSION),
                            "decision", policy(DebateDecisionPolicy.class,
                                    DebateDecisionPolicy.POLICY_ID, DebateDecisionPolicy.POLICY_VERSION))),
                    entry("capabilities", capabilityRegistry.descriptors().stream()
                            .sorted(Comparator.comparing(CapabilityDescriptor::id)).toList()),
                    entry("rag", ragService.runtimeConfiguration()),
                    entry("replan", replanService.runtimeConfiguration()),
                    entry("edgarIngestion", edgarIngestionService.runtimeConfiguration()),
                    entry("artifact", artifactIdentity.snapshot()),
                    entry("consumption", Map.of("ragDocuments", "NOT_USED", "researchMemory", "NOT_USED")))));
            if (stored == null) {
                Integer attempts = taskRepository.readAttempts(taskId);
                if (attempts == null || attempts > 1) {
                    throw new ManifestException(taskId, "历史尝试缺少执行配置，无法确认原执行配置");
                }
                if (taskRepository.freezeModelConfigurationForOwner(taskId, leaseToken,
                        objectMapper.writeValueAsString(current)) != 1) {
                    throw new ManifestException(taskId, "配置冻结的所有权或首次写入条件已变化");
                }
                return;
            }
            JsonNode saved = objectMapper.reader().with(DeserializationFeature.FAIL_ON_TRAILING_TOKENS)
                    .readTree(stored);
            if (saved == null || !saved.isObject() || !saved.path("schemaVersion").isIntegralNumber()
                    || !saved.path("schemaVersion").canConvertToInt()
                    || saved.path("schemaVersion").intValue() != 6) {
                throw new ManifestException(taskId, "执行配置清单格式或版本不受支持");
            }
            if (!saved.equals(current)) {
                throw new ManifestException(taskId, "当前执行配置与首次执行清单不一致");
            }
        } catch (ManifestException rejected) {
            throw rejected;
        } catch (Exception failure) {
            throw new ManifestException(taskId, "执行配置清单读取、校验或持久化失败", failure);
        }
    }

    private Map<String, Object> policy(Class<?> type, String id, int version) throws Exception {
        // This fingerprints the packaged policy class, not the whole deployment or instrumented JVM bytes.
        try (var bytes = type.getResourceAsStream(type.getSimpleName() + ".class")) {
            if (bytes == null) {
                throw new IllegalStateException("Missing policy class resource: " + type.getName());
            }
            return Map.of("id", id, "version", version, "hashScope", "CLASS_RESOURCE",
                    "sha256", HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256")
                            .digest(bytes.readAllBytes())));
        }
    }

    public static class ManifestException extends IllegalStateException {
        public ManifestException(Long taskId, String reason) {
            this(taskId, reason, null);
        }

        public ManifestException(Long taskId, String reason, Throwable cause) {
            super("研究任务 " + taskId + " 的执行配置清单无法确认（" + reason
                    + "）；原清单与检查点已保留，请使用匹配版本检查原记录，或另行发起新的研究。", cause);
        }
    }
}
