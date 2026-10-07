package com.stocksage.evolution;

import com.fasterxml.jackson.core.JsonParser;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.core.io.ClassPathResource;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * 人工批准的方法包：内容由部署配置固定的 sha256 保证不被替换，适用标签必须来自共享标签目录。
 *
 * @param comparisonIdentity 批准时验证过的运行条件，只含模型配置与最终回答规则两项
 */
public record ApprovedMethodArtifact(AgentPolicyBundle bundle, String artifactSha256,
                                     Map<String, String> comparisonIdentity, Scope scope,
                                     String experienceSha256, int experienceVersion,
                                     String approver, String approvedAt) {
    public record Scope(List<String> taskTags, List<String> requiredEvidence,
                        List<String> requiredCapabilities, String applicabilityBoundary) {}

    /** Java 与 evals 共用的标签目录；经验适用条件只能引用这里登记的标签。 */
    public static final TagCatalog TAG_CATALOG = TagCatalog.load();

    public static ApprovedMethodArtifact load(Path path, String pinnedSha256, Set<String> revokedIds) {
        try {
            if (pinnedSha256 == null || !pinnedSha256.matches("[0-9a-f]{64}")) throw invalid("部署配置缺少方法包的固定 sha256");
            if (Files.size(path) > 1_048_576) throw invalid("方法包超过 1 MiB 上限");
            byte[] bytes = Files.readAllBytes(path);
            String actual = AgentPolicyBundle.sha256(bytes);
            if (!actual.equals(pinnedSha256)) throw invalid("方法包内容与部署配置固定的 sha256 不一致");
            ObjectMapper mapper = new ObjectMapper().enable(JsonParser.Feature.STRICT_DUPLICATE_DETECTION);
            JsonNode root = mapper.readTree(bytes);
            fields(root, "kind", "schemaVersion", "bundle", "scope", "expectedConditions", "experience", "evidence", "approval");
            if (!"APPROVED_METHOD".equals(text(root, "kind")) || !root.path("schemaVersion").isInt()
                    || root.path("schemaVersion").asInt() != 2) throw invalid("不是 schemaVersion=2 的已批准方法包");
            JsonNode bundleNode = root.path("bundle");
            fields(bundleNode, "bundleId", "parentBundleId", "method", "fixedContractSha256", "contentSha256");
            AgentPolicyBundle bundle = mapper.treeToValue(bundleNode, AgentPolicyBundle.class);
            AgentPolicyBundle baseline = AgentPolicyBundle.baseline();
            if (bundle.bundleId().equals(baseline.bundleId()) || revokedIds.contains(bundle.bundleId())
                    || !baseline.bundleId().equals(bundle.parentBundleId())) throw invalid("方法包已撤回，或不是以当前基线为父版本");
            JsonNode conditions = root.path("expectedConditions");
            fields(conditions, "modelConfigSha256", "fixedFinalPromptSha256");
            Map<String, String> comparison = Map.of("modelConfigSha256", hash(conditions, "modelConfigSha256"),
                    "fixedFinalPromptSha256", hash(conditions, "fixedFinalPromptSha256"));
            JsonNode scope = root.path("scope");
            fields(scope, "route", "taskTags", "requiredEvidence", "requiredCapabilities", "applicabilityBoundary");
            if (!"ORDINARY_FUNDAMENTALS".equals(text(scope, "route"))) throw invalid("方法包超出普通基本面路线");
            List<String> taskTags = tags(scope, "taskTags", TAG_CATALOG.taskTags());
            List<String> evidence = tags(scope, "requiredEvidence", TAG_CATALOG.evidenceTags());
            List<String> capabilities = tags(scope, "requiredCapabilities", TAG_CATALOG.capabilities());
            JsonNode experience = root.path("experience");
            fields(experience, "recordSha256", "version");
            if (!experience.path("version").isInt() || experience.path("version").asInt() < 1) throw invalid("经验版本无效");
            JsonNode proof = root.path("evidence");
            fields(proof, "releaseEvidenceSha256", "acceptanceGateSha256", "evaluatorSha256");
            for (String name : List.of("releaseEvidenceSha256", "acceptanceGateSha256", "evaluatorSha256")) hash(proof, name);
            JsonNode approval = root.path("approval");
            fields(approval, "approver", "approvedAt", "reason");
            text(approval, "reason");
            java.time.Instant.parse(text(approval, "approvedAt"));
            return new ApprovedMethodArtifact(bundle, actual, comparison,
                    new Scope(taskTags, evidence, capabilities, text(scope, "applicabilityBoundary")),
                    hash(experience, "recordSha256"), experience.path("version").asInt(),
                    text(approval, "approver"), text(approval, "approvedAt"));
        } catch (Exception error) {
            // 启动错误不回显文件路径或方法正文。
            throw new IllegalArgumentException("基本面方法包加载失败：" + error.getMessage()
                    + "。请核对方法包文件、部署配置中的 sha256 与撤回清单。");
        }
    }

    private static List<String> tags(JsonNode node, String name, Set<String> catalog) {
        JsonNode values = node.path(name);
        if (!values.isArray() || values.isEmpty()) throw invalid("适用条件 " + name + " 缺失");
        java.util.ArrayList<String> result = new java.util.ArrayList<>();
        values.forEach(value -> {
            if (!value.isTextual() || !catalog.contains(value.asText())) throw invalid("适用条件 " + name + " 含标签目录之外的值");
            result.add(value.asText());
        });
        if (new HashSet<>(result).size() != result.size()) throw invalid("适用条件 " + name + " 重复");
        return List.copyOf(result);
    }

    static String text(JsonNode node, String name) {
        JsonNode value = node.path(name);
        if (!value.isTextual() || value.asText().isBlank()) throw invalid("必要文本 " + name + " 缺失");
        return value.asText();
    }

    static String hash(JsonNode node, String name) {
        String value = text(node, name);
        if (!value.matches("[0-9a-f]{64}")) throw invalid(name + " 不是 sha256");
        return value;
    }

    static void fields(JsonNode node, String... expected) {
        Set<String> names = new HashSet<>();
        node.fieldNames().forEachRemaining(names::add);
        if (!node.isObject() || !names.equals(Set.of(expected))) throw invalid("包含未知或缺失字段");
    }

    private static IllegalArgumentException invalid(String reason) { return new IllegalArgumentException(reason); }

    /** 共享标签目录 {@code evolution/tag-catalog.json}。 */
    public record TagCatalog(Set<String> taskTags, Set<String> evidenceTags, Set<String> capabilities) {
        static TagCatalog load() {
            try (var input = new ClassPathResource("evolution/tag-catalog.json").getInputStream()) {
                JsonNode root = new ObjectMapper().readTree(input);
                return new TagCatalog(set(root, "taskTags"), set(root, "evidenceTags"), set(root, "capabilities"));
            } catch (java.io.IOException failure) {
                throw new IllegalStateException("无法读取 evolution/tag-catalog.json", failure);
            }
        }

        private static Set<String> set(JsonNode root, String name) {
            Set<String> values = new HashSet<>();
            root.path(name).forEach(value -> values.add(value.asText()));
            return Set.copyOf(values);
        }
    }
}
