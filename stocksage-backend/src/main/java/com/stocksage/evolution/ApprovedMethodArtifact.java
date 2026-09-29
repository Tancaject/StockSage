package com.stocksage.evolution;

import com.fasterxml.jackson.core.JsonParser;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.util.Base64;
import java.util.HashSet;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;
import java.util.Set;

/** Publisher authentication is separate from a candidate's self-reported content hash. */
public record ApprovedMethodArtifact(AgentPolicyBundle bundle, String packageSha256, String approvalSha256,
                                     String artifactSha256, Map<String, String> comparisonIdentity,
                                     Scope scope, String experienceSha256, int experienceVersion) {
    public record Scope(List<String> taskTags, List<String> requiredEvidence,
                        List<String> requiredCapabilities, String applicabilityBoundary) {}

    public static ApprovedMethodArtifact load(Path path, String pinnedPayloadHash, byte[] publisherKey,
                                               Set<String> revokedIds, Map<String, Object> runtimeArtifact) {
        try {
            ObjectMapper mapper = new ObjectMapper().enable(JsonParser.Feature.STRICT_DUPLICATE_DETECTION);
            JsonNode root = readSignedPayload(path, pinnedPayloadHash, publisherKey);
            fields(root, "kind", "schemaVersion", "status", "package", "packageSha256", "approvalSha256");
            if (!"APPROVED_METHOD_PACKAGE".equals(text(root, "kind")) || !root.path("schemaVersion").isInt() || root.path("schemaVersion").asInt() != 1
                    || !"APPROVED_PENDING_VALIDATION".equals(text(root, "status"))) throw invalid("不是已批准待验证的方法包");
            JsonNode pack = root.path("package");
            fields(pack, "kind", "schemaVersion", "bundle", "scope", "experience", "candidateSha256",
                    "releaseEvidenceSha256", "acceptanceGateSha256", "evaluatorSha256", "buildIdentity",
                    "expectedComparisonIdentity", "rollback");
            if (!"METHOD_RELEASE_PACKAGE".equals(text(pack, "kind")) || !pack.path("schemaVersion").isInt() || pack.path("schemaVersion").asInt() != 1) {
                throw invalid("方法包用途或版本不匹配");
            }
            for (String name : List.of("candidateSha256", "releaseEvidenceSha256", "acceptanceGateSha256", "evaluatorSha256")) hash(pack, name);
            JsonNode bundleNode = pack.path("bundle");
            fields(bundleNode, "bundleId", "parentBundleId", "method", "fixedContractSha256", "contentSha256");
            AgentPolicyBundle bundle = mapper.treeToValue(bundleNode, AgentPolicyBundle.class);
            AgentPolicyBundle baseline = AgentPolicyBundle.baseline();
            JsonNode rollback = pack.path("rollback");
            fields(rollback, "bundleId", "bundleSha256");
            if (bundle.bundleId().equals(baseline.bundleId()) || revokedIds.contains(bundle.bundleId())
                    || !baseline.bundleId().equals(bundle.parentBundleId())
                    || !baseline.bundleId().equals(text(rollback, "bundleId"))
                    || !baseline.contentSha256().equals(hash(rollback, "bundleSha256"))) throw invalid("包已撤回或稳定回滚版本不匹配");
            JsonNode build = pack.path("buildIdentity");
            String buildHash = hash(build.path("manifest"), "artifactSha256");
            if (!"VERIFIED_ARTIFACT".equals(text(build, "status"))
                    || !"SPRING_BOOT_JAR".equals(text(build.path("manifest"), "artifactFormat"))
                    || !"CODE_SOURCE_FILE".equals(text(build.path("runtimeArtifact"), "scope"))
                    || !buildHash.equals(hash(build.path("runtimeArtifact"), "sha256"))
                    || !"KNOWN".equals(runtimeArtifact.get("status"))
                    || !"CODE_SOURCE_FILE".equals(runtimeArtifact.get("scope"))
                    || !buildHash.equals(runtimeArtifact.get("sha256"))
                    || !mapper.valueToTree(runtimeArtifact).equals(build.path("runtimeArtifact"))) throw invalid("实际运行构建与已验证的 JAR 不一致");
            JsonNode identity = pack.path("expectedComparisonIdentity");
            fields(identity, "modelConfigSha256", "fixedFinalPromptSha256", "memorySnapshotSha256", "runtimeBuildSha256");
            if (!FundamentalsRuntimeIdentity.hash(mapper.convertValue(build, Map.class)).equals(hash(identity, "runtimeBuildSha256"))) {
                throw invalid("比较身份中的运行构建哈希不匹配");
            }
            Map<String, String> comparison = Map.of("modelConfigSha256", hash(identity, "modelConfigSha256"),
                    "fixedFinalPromptSha256", hash(identity, "fixedFinalPromptSha256"),
                    "memorySnapshotSha256", hash(identity, "memorySnapshotSha256"), "runtimeBuildSha256", hash(identity, "runtimeBuildSha256"));
            JsonNode scope = pack.path("scope");
            fields(scope, "route", "taskTags", "requiredEvidence", "requiredCapabilities", "applicabilityBoundary");
            if (!"ORDINARY_FUNDAMENTALS".equals(text(scope, "route"))) throw invalid("方法包超出普通基本面路线");
            List<String> capabilities = tags(scope, "requiredCapabilities");
            if (!Set.of("evidence-reading", "period-comparison", "unit-comparison", "arithmetic").containsAll(capabilities)) {
                throw invalid("方法包声明了不支持的能力");
            }
            JsonNode experience = pack.path("experience");
            fields(experience, "recordSha256", "version", "registrySha256");
            if (!experience.path("version").isInt() || experience.path("version").asInt() < 1) throw invalid("经验版本无效");
            hash(experience, "registrySha256");
            return new ApprovedMethodArtifact(bundle, hash(root, "packageSha256"), hash(root, "approvalSha256"), pinnedPayloadHash,
                    comparison, new Scope(tags(scope, "taskTags"), tags(scope, "requiredEvidence"), capabilities,
                    text(scope, "applicabilityBoundary")), hash(experience, "recordSha256"), experience.path("version").asInt());
        } catch (Exception error) {
            // Do not include source bytes, filesystem paths or credential material in startup errors.
            throw new IllegalArgumentException("基本面批准包加载失败：签名、部署固定哈希、撤回清单、固定契约或构建不匹配（"
                    + error.getClass().getSimpleName() + "）。请恢复已批准制品及其部署配置；待验证包不能直接激活。");
        }
    }

    private static List<String> tags(JsonNode node, String name) {
        JsonNode values = node.path(name);
        if (!values.isArray() || values.isEmpty()) throw invalid("适用条件缺失");
        java.util.ArrayList<String> result = new java.util.ArrayList<>();
        values.forEach(value -> {
            if (!value.isTextual() || !value.asText().matches("[A-Za-z0-9][A-Za-z0-9._-]{0,119}")) throw invalid("适用条件格式无效");
            result.add(value.asText());
        });
        if (new HashSet<>(result).size() != result.size()) throw invalid("适用条件重复");
        return List.copyOf(result);
    }

    static JsonNode readSignedPayload(Path path, String pinnedPayloadHash, byte[] publisherKey) throws Exception {
        if (publisherKey.length < 32 || pinnedPayloadHash == null || !pinnedPayloadHash.matches("[0-9a-f]{64}")) {
            throw invalid("缺少独立发布密钥或部署清单固定的制品哈希");
        }
        if (Files.size(path) > 1_048_576) throw invalid("发布制品超过 1 MiB 上限");
        ObjectMapper mapper = new ObjectMapper().enable(JsonParser.Feature.STRICT_DUPLICATE_DETECTION);
        JsonNode envelope = mapper.readTree(Files.readAllBytes(path));
        fields(envelope, "schema", "payloadBase64", "payloadSha256", "signature");
        if (!"fundamentals_publisher_artifact_v1".equals(text(envelope, "schema"))) throw invalid("不支持的发布格式");
        byte[] payload = Base64.getDecoder().decode(text(envelope, "payloadBase64"));
        String payloadHash = AgentPolicyBundle.sha256(payload);
        Mac mac = Mac.getInstance("HmacSHA256");
        mac.init(new SecretKeySpec(publisherKey, "HmacSHA256"));
        if (!payloadHash.equals(hash(envelope, "payloadSha256")) || !payloadHash.equals(pinnedPayloadHash)
                || !MessageDigest.isEqual(mac.doFinal(payload), HexFormat.of().parseHex(hash(envelope, "signature")))) {
            throw invalid("发布签名或部署清单哈希不匹配");
        }
        return mapper.readTree(payload);
    }

    static String text(JsonNode node, String name) {
        JsonNode value = node.path(name);
        if (!value.isTextual() || value.asText().isBlank()) throw invalid("必要文本缺失");
        return value.asText();
    }

    static String hash(JsonNode node, String name) {
        String value = text(node, name);
        if (!value.matches("[0-9a-f]{64}")) throw invalid("哈希格式无效");
        return value;
    }

    static void fields(JsonNode node, String... expected) {
        Set<String> names = new HashSet<>();
        node.fieldNames().forEachRemaining(names::add);
        if (!node.isObject() || !names.equals(Set.of(expected))) throw invalid("包含未知或缺失字段");
    }

    private static IllegalArgumentException invalid(String reason) { return new IllegalArgumentException(reason); }
}
