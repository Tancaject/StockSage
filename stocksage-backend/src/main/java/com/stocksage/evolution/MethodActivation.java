package com.stocksage.evolution;

import com.fasterxml.jackson.databind.ObjectMapper;

import java.nio.file.Path;
import java.time.Instant;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;

import static com.stocksage.evolution.ApprovedMethodArtifact.*;

/** A separately pinned publisher authorization; approval of the method package alone cannot activate it. */
public record MethodActivation(String authorizationSha256, String activationId, String mode,
                               Set<String> internalAccountIds, Instant approvedAt, Instant expiresAt) {
    public MethodActivation {
        internalAccountIds = Set.copyOf(internalAccountIds);
    }

    static MethodActivation load(Path path, String pinnedHash, byte[] key, ApprovedMethodArtifact approved, boolean drillProfile) {
        try {
            if (approved == null) throw new IllegalArgumentException("缺少方法包");
            var root = readSignedPayload(path, pinnedHash, key);
            fields(root, "kind", "schemaVersion", "mode", "approvedArtifactSha256", "shadowAcceptanceSha256", "activationId",
                    "approver", "approvedAt", "expiresAt", "reason", "internalAccountIds", "checks", "packageSha256",
                    "approvalSha256", "rollbackAcceptanceSha256", "scope", "expectedComparisonIdentity");
            String mode = text(root, "mode");
            boolean drill = "DRILL".equals(mode);
            if (!"METHOD_ACTIVATION".equals(text(root, "kind")) || !root.path("schemaVersion").isInt()
                    || root.path("schemaVersion").asInt() != 1 || !(drill || "SERVING".equals(mode))
                    || drill && !drillProfile || !approved.artifactSha256().equals(hash(root, "approvedArtifactSha256"))
                    || !approved.packageSha256().equals(hash(root, "packageSha256"))
                    || !approved.approvalSha256().equals(hash(root, "approvalSha256"))) {
                throw new IllegalArgumentException("授权用途、演练环境或批准制品不一致");
            }
            hash(root, "shadowAcceptanceSha256");
            if (drill) {
                if (!root.path("rollbackAcceptanceSha256").isNull()) throw new IllegalArgumentException("演练不能冒充回滚验收");
            } else {
                hash(root, "rollbackAcceptanceSha256");
            }
            ObjectMapper mapper = new ObjectMapper();
            var scope = approved.scope();
            if (!mapper.valueToTree(approved.comparisonIdentity()).equals(root.path("expectedComparisonIdentity"))
                    || !mapper.valueToTree(Map.of("route", "ORDINARY_FUNDAMENTALS", "taskTags", scope.taskTags(),
                    "requiredEvidence", scope.requiredEvidence(), "requiredCapabilities", scope.requiredCapabilities(),
                    "applicabilityBoundary", scope.applicabilityBoundary())).equals(root.path("scope"))) {
                throw new IllegalArgumentException("授权改变了已验证条件或适用范围");
            }
            text(root, "approver");
            text(root, "reason");
            String[] checks = drill ? new String[]{"internalAccounts", "preproductionIsolation", "runtimeConditions", "applicability", "retention"}
                    : new String[]{"internalAccounts", "runtimeConditions", "applicability", "monitoring", "retention"};
            fields(root.path("checks"), checks);
            for (String name : checks) {
                var check = root.path("checks").path(name);
                fields(check, "status", "evidenceSha256");
                if (!"PASS".equals(text(check, "status"))) throw new IllegalArgumentException("授权审阅未通过");
                hash(check, "evidenceSha256");
            }
            var accounts = root.path("internalAccountIds");
            Set<String> ids = new HashSet<>();
            if (!accounts.isArray() || accounts.isEmpty()) throw new IllegalArgumentException("账号范围为空");
            for (var account : accounts) {
                if (!account.isTextual() || account.asText().isBlank() || account.asText().length() > 32
                        || !account.asText().equals(account.asText().trim()) || "*".equals(account.asText())
                        || !ids.add(account.asText())) throw new IllegalArgumentException("账号范围无效");
            }
            Instant start = Instant.parse(text(root, "approvedAt")), end = Instant.parse(text(root, "expiresAt"));
            if (!start.isBefore(end)) throw new IllegalArgumentException("授权时间范围无效");
            return new MethodActivation(pinnedHash, text(root, "activationId"), mode, ids, start, end);
        } catch (Exception invalid) {
            throw new IllegalArgumentException("基本面启用授权加载失败：签名、部署固定哈希、范围或前置验收不匹配（"
                    + invalid.getClass().getSimpleName() + "）。请恢复发布者签发的授权；DRILL 仅可用于 evolution-drill 环境。");
        }
    }

    boolean effectiveAt(Instant now) { return !now.isBefore(approvedAt) && now.isBefore(expiresAt); }
}
