package com.stocksage.agent;

import com.stocksage.research.DeepEvidenceCollector;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.stocksage.evidence.EvidenceLedger;
import com.stocksage.model.dto.AnalysisHorizon;
import com.stocksage.model.dto.AnalysisState;
import com.stocksage.model.dto.DebateModels.DebatePoint;
import com.stocksage.model.dto.DebateModels.DebateTurn;
import com.stocksage.model.dto.DebateModels.EvidenceRef;
import com.stocksage.model.dto.DebateModels.PointType;
import com.stocksage.model.dto.DebateModels.Side;
import org.springframework.stereotype.Component;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.text.Normalizer;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.regex.Pattern;

/**
 * 把 Bull/Bear 的严格 JSON 输出转换为可 checkpoint 的辩论契约。
 *
 * <p>本类不从旧 Markdown 或自由文本推断论点；任何结构、证据引用或
 * 反驳链不合法都抛出稳定 {@link DebateContractException}。解析过程只读
 * {@link AnalysisState}，双方结果由调用方在都成功后原子写入。</p>
 */
@Component
public class DebateContractParser {

    private static final String SNAPSHOT_EVIDENCE_BEGIN = "[[STOCKSAGE_EVIDENCE_BEGIN]]";
    private static final String SNAPSHOT_EVIDENCE_END = "[[STOCKSAGE_EVIDENCE_END]]";
    private static final String SNAPSHOT_CONTENT_BEGIN = "[[STOCKSAGE_CONTENT_BEGIN]]";
    private static final String SNAPSHOT_CONTENT_END = "[[STOCKSAGE_CONTENT_END]]";

    private static final int MAX_CONTRACT_LENGTH = 32_000;
    private static final int MAX_CLAIM_LENGTH = 800;
    private static final int MAX_REASONING_LENGTH = 1_600;
    private static final int MAX_ASSUMPTION_LENGTH = 800;
    private static final int MAX_INVALIDATION_LENGTH = 800;
    private static final int MAX_EXCERPT_LENGTH = 600;
    private static final int MIN_NORMALIZED_EXCERPT_LENGTH = 12;
    private static final int MAX_EVIDENCE_REFS = 6;
    private static final int MAX_RESPONSE_REFS = 8;

    private static final Set<String> ROOT_FIELDS = Set.of("points");
    private static final Set<String> POINT_FIELDS = Set.of(
            "type",
            "claim",
            "horizon",
            "evidenceRefs",
            "reasoning",
            "assumption",
            "invalidationCondition",
            "respondsToPointIds"
    );
    private static final Set<String> EVIDENCE_REF_FIELDS = Set.of("evidenceId", "excerpt");
    private static final Pattern WHITESPACE = Pattern.compile("\\s+");

    private final ObjectMapper objectMapper;

    /** @param objectMapper 项目统一 JSON 解析器 */
    public DebateContractParser(ObjectMapper objectMapper) {
        this.objectMapper = Objects.requireNonNull(objectMapper, "objectMapper");
    }

    /**
     * 严格解析单方单轮辩论。
     *
     * @param content 可含自然语言摘要，但首个左花括号到最后一个右花括号必须构成唯一 JSON 对象
     * @param round 从 1 开始的轮次
     * @param side 本次发言方
     * @param state 当前证据账本、有界快照和已完成历史
     * @return 只含服务端生成 pointId 的不可变结构化轮次
     * @throws DebateContractException 合同、证据或反驳链校验失败
     */
    public DebateTurn parse(String content, int round, Side side, AnalysisState state) {
        require(round >= 1, ErrorCode.INVALID_ROUND, "round");
        require(side != null, ErrorCode.SIDE_REQUIRED, "side");
        require(state != null, ErrorCode.STATE_REQUIRED, "state");
        require(content != null && !content.isBlank(), ErrorCode.EMPTY_CONTENT, "content");
        require(content.length() <= MAX_CONTRACT_LENGTH, ErrorCode.CONTENT_TOO_LONG, "content");

        JsonNode root = readStrictRoot(content);
        require(root.isObject(), ErrorCode.INVALID_SCHEMA, "root");
        requireOnlyFields(root, ROOT_FIELDS, "root");
        JsonNode pointsNode = root.get("points");
        require(pointsNode != null && pointsNode.isArray(), ErrorCode.INVALID_SCHEMA, "points");

        int minimumPoints = round == 1 ? 3 : 2;
        int maximumPoints = round == 1 ? 5 : 3;
        require(pointsNode.size() >= minimumPoints && pointsNode.size() <= maximumPoints,
                ErrorCode.INVALID_POINT_COUNT, "points");

        EvidenceLedger ledger = state.getEvidenceLedger();
        require(ledger != null, ErrorCode.LEDGER_REQUIRED, "evidenceLedger");
        Set<String> usableEvidenceIds = ledger.usableEvidenceIds();
        Map<String, String> evidenceContentById = snapshotContentByEvidenceId(state);
        Map<String, Side> priorPointSides = priorPointSides(state, round);

        List<DebatePoint> points = new ArrayList<>(pointsNode.size());
        for (int index = 0; index < pointsNode.size(); index++) {
            points.add(parsePoint(
                    pointsNode.get(index), index, round, side,
                    usableEvidenceIds, evidenceContentById, priorPointSides));
        }
        return new DebateTurn(round, side, points);
    }

    private DebatePoint parsePoint(
            JsonNode node,
            int index,
            int round,
            Side side,
            Set<String> usableEvidenceIds,
            Map<String, String> evidenceContentById,
            Map<String, Side> priorPointSides
    ) {
        String location = "points[" + index + "]";
        require(node != null && node.isObject(), ErrorCode.INVALID_SCHEMA, location);
        requireOnlyFields(node, POINT_FIELDS, location);

        PointType type = strictEnum(
                requiredText(node, "type", 16, location), PointType.class,
                ErrorCode.INVALID_POINT_TYPE, location + ".type");
        PointType expectedType = round == 1 ? PointType.THESIS : PointType.REBUTTAL;
        require(type == expectedType, ErrorCode.INVALID_POINT_TYPE, location + ".type");

        String claim = requiredText(node, "claim", MAX_CLAIM_LENGTH, location);
        AnalysisHorizon horizon = strictEnum(
                requiredText(node, "horizon", 32, location), AnalysisHorizon.class,
                ErrorCode.INVALID_HORIZON, location + ".horizon");
        String reasoning = requiredText(node, "reasoning", MAX_REASONING_LENGTH, location);
        String assumption = requiredText(node, "assumption", MAX_ASSUMPTION_LENGTH, location);
        String invalidationCondition = requiredText(
                node, "invalidationCondition", MAX_INVALIDATION_LENGTH, location);

        List<EvidenceRef> evidenceRefs = parseEvidenceRefs(
                node.get("evidenceRefs"), location,
                usableEvidenceIds, evidenceContentById);
        List<String> respondsToPointIds = parseResponseIds(
                node.get("respondsToPointIds"), location, round, side, priorPointSides);

        String pointId = stablePointId(
                round, side, index, type, claim, horizon, evidenceRefs, respondsToPointIds);
        return new DebatePoint(
                pointId,
                type,
                claim,
                horizon,
                evidenceRefs,
                reasoning,
                assumption,
                invalidationCondition,
                respondsToPointIds
        );
    }

    private List<EvidenceRef> parseEvidenceRefs(
            JsonNode node,
            String pointLocation,
            Set<String> usableEvidenceIds,
            Map<String, String> evidenceContentById
    ) {
        require(node != null && node.isArray(), ErrorCode.INVALID_SCHEMA,
                pointLocation + ".evidenceRefs");
        require(node.size() > 0 && node.size() <= MAX_EVIDENCE_REFS,
                ErrorCode.EVIDENCE_REQUIRED, pointLocation + ".evidenceRefs");

        List<EvidenceRef> refs = new ArrayList<>(node.size());
        LinkedHashSet<String> uniquePairs = new LinkedHashSet<>();
        for (int index = 0; index < node.size(); index++) {
            JsonNode refNode = node.get(index);
            String location = pointLocation + ".evidenceRefs[" + index + "]";
            require(refNode != null && refNode.isObject(), ErrorCode.INVALID_SCHEMA, location);
            requireOnlyFields(refNode, EVIDENCE_REF_FIELDS, location);

            String evidenceId = requiredText(refNode, "evidenceId", 128, location);
            String excerpt = requiredText(refNode, "excerpt", MAX_EXCERPT_LENGTH, location);
            require(usableEvidenceIds.contains(evidenceId),
                    ErrorCode.EVIDENCE_ID_UNUSABLE, location + ".evidenceId");

            String snapshotContent = evidenceContentById.get(evidenceId);
            require(snapshotContent != null, ErrorCode.EVIDENCE_SEGMENT_MISSING,
                    location + ".evidenceId");
            String normalizedExcerpt = normalizeForMatch(excerpt);
            require(normalizedExcerpt.length() >= MIN_NORMALIZED_EXCERPT_LENGTH,
                    ErrorCode.EVIDENCE_EXCERPT_TOO_SHORT, location + ".excerpt");
            require(normalizeForMatch(snapshotContent).contains(normalizedExcerpt),
                    ErrorCode.EVIDENCE_EXCERPT_MISMATCH, location + ".excerpt");

            String pairKey = evidenceId + "\n" + normalizedExcerpt;
            require(uniquePairs.add(pairKey), ErrorCode.DUPLICATE_EVIDENCE_REF, location);
            refs.add(new EvidenceRef(evidenceId, excerpt));
        }
        return List.copyOf(refs);
    }

    private List<String> parseResponseIds(
            JsonNode node,
            String pointLocation,
            int round,
            Side side,
            Map<String, Side> priorPointSides
    ) {
        String location = pointLocation + ".respondsToPointIds";
        require(node != null && node.isArray(), ErrorCode.INVALID_SCHEMA, location);
        require(node.size() <= MAX_RESPONSE_REFS, ErrorCode.FIELD_TOO_LONG, location);
        if (round == 1) {
            require(node.size() == 0, ErrorCode.RESPONSES_NOT_ALLOWED, location);
            return List.of();
        }
        require(node.size() > 0, ErrorCode.RESPONSES_REQUIRED, location);

        LinkedHashSet<String> responseIds = new LinkedHashSet<>();
        for (int index = 0; index < node.size(); index++) {
            JsonNode value = node.get(index);
            require(value != null && value.isTextual(), ErrorCode.INVALID_SCHEMA,
                    location + "[" + index + "]");
            String pointId = value.textValue().strip();
            require(!pointId.isBlank() && pointId.length() <= 128,
                    ErrorCode.INVALID_RESPONSE_POINT, location + "[" + index + "]");
            Side referencedSide = priorPointSides.get(pointId);
            require(referencedSide != null, ErrorCode.RESPONSE_POINT_UNKNOWN,
                    location + "[" + index + "]");
            require(referencedSide != side, ErrorCode.RESPONSE_POINT_NOT_OPPONENT,
                    location + "[" + index + "]");
            require(responseIds.add(pointId), ErrorCode.DUPLICATE_RESPONSE_POINT,
                    location + "[" + index + "]");
        }
        return List.copyOf(responseIds);
    }

    /** 只索引 DeepEvidenceCollector 生成的可引用证据段，说明段不进入映射。 */
    private Map<String, String> snapshotContentByEvidenceId(AnalysisState state) {
        Map<String, String> result = new LinkedHashMap<>();
        indexSnapshot(state.getFundamentalsReport(), result);
        indexSnapshot(state.getMarketReport(), result);
        indexSnapshot(state.getNewsReport(), result);
        return Map.copyOf(result);
    }

    private void indexSnapshot(String snapshot, Map<String, String> result) {
        if (snapshot == null || snapshot.isBlank()) {
            return;
        }
        int cursor = 0;
        while (cursor < snapshot.length()) {
            int begin = snapshot.indexOf(SNAPSHOT_EVIDENCE_BEGIN, cursor);
            if (begin < 0) {
                return;
            }
            int end = snapshot.indexOf(SNAPSHOT_EVIDENCE_END,
                    begin + SNAPSHOT_EVIDENCE_BEGIN.length());
            require(end >= 0, ErrorCode.SNAPSHOT_FORMAT_INVALID, "snapshot.evidenceEnd");
            String block = snapshot.substring(
                    begin + SNAPSHOT_EVIDENCE_BEGIN.length(), end);
            String evidenceId = metadataLine(block, "evidenceId:");
            require(!evidenceId.isBlank(), ErrorCode.SNAPSHOT_FORMAT_INVALID,
                    "snapshot.evidenceId");
            String citable = metadataLine(block, "citable:");
            require("true".equals(citable) || "false".equals(citable),
                    ErrorCode.SNAPSHOT_FORMAT_INVALID, "snapshot.citable");

            int contentBegin = block.indexOf(SNAPSHOT_CONTENT_BEGIN);
            int contentEnd = block.indexOf(SNAPSHOT_CONTENT_END,
                    contentBegin + SNAPSHOT_CONTENT_BEGIN.length());
            require(contentBegin >= 0 && contentEnd >= 0,
                    ErrorCode.SNAPSHOT_FORMAT_INVALID, "snapshot.content");
            String boundedContent = block.substring(
                    contentBegin + SNAPSHOT_CONTENT_BEGIN.length(), contentEnd).strip();
            if ("true".equals(citable)) {
                String previous = result.putIfAbsent(evidenceId, boundedContent);
                require(previous == null || previous.equals(boundedContent),
                        ErrorCode.DUPLICATE_EVIDENCE_SEGMENT, "snapshot.evidenceId");
            }
            cursor = end + SNAPSHOT_EVIDENCE_END.length();
        }
    }

    private String metadataLine(String block, String key) {
        for (String line : block.split("\\R")) {
            String stripped = line.strip();
            if (stripped.startsWith(key)) {
                return stripped.substring(key.length()).strip();
            }
        }
        return "";
    }

    /** 只允许引用已原子写入 state 且轮次更早的对方论点。 */
    private Map<String, Side> priorPointSides(AnalysisState state, int currentRound) {
        Map<String, Side> pointSides = new HashMap<>();
        List<DebateTurn> turns = state.getDebateTurns();
        if (turns == null) {
            return Map.of();
        }
        for (DebateTurn turn : turns) {
            if (turn == null || turn.side() == null || turn.round() <= 0
                    || turn.round() >= currentRound || turn.points() == null) {
                continue;
            }
            for (DebatePoint point : turn.points()) {
                if (point == null || point.pointId() == null || point.pointId().isBlank()) {
                    continue;
                }
                Side previous = pointSides.putIfAbsent(point.pointId(), turn.side());
                require(previous == null || previous == turn.side(),
                        ErrorCode.STATE_POINT_ID_CONFLICT, "state.debateTurns");
            }
        }
        return Map.copyOf(pointSides);
    }

    private JsonNode readStrictRoot(String content) {
        int objectBegin = content.indexOf('{');
        int objectEnd = content.lastIndexOf('}');
        require(objectBegin >= 0 && objectEnd >= objectBegin,
                ErrorCode.INVALID_JSON, "content");
        String jsonObject = content.substring(objectBegin, objectEnd + 1);
        try {
            return objectMapper.readerFor(JsonNode.class)
                    .with(DeserializationFeature.FAIL_ON_TRAILING_TOKENS)
                    .readValue(jsonObject);
        } catch (JsonProcessingException error) {
            throw failure(ErrorCode.INVALID_JSON, "content", error);
        }
    }

    private String requiredText(JsonNode object, String field, int maxLength, String location) {
        JsonNode value = object.get(field);
        require(value != null && value.isTextual(), ErrorCode.FIELD_REQUIRED,
                location + "." + field);
        String text = value.textValue().strip();
        require(!text.isBlank(), ErrorCode.FIELD_REQUIRED, location + "." + field);
        require(text.length() <= maxLength, ErrorCode.FIELD_TOO_LONG, location + "." + field);
        return text;
    }

    private void requireOnlyFields(JsonNode object, Set<String> allowed, String location) {
        object.fieldNames().forEachRemaining(field ->
                require(allowed.contains(field), ErrorCode.UNKNOWN_FIELD, location + "." + field));
    }

    private <E extends Enum<E>> E strictEnum(
            String value,
            Class<E> type,
            ErrorCode code,
            String location
    ) {
        try {
            return Enum.valueOf(type, value);
        } catch (IllegalArgumentException error) {
            throw failure(code, location, error);
        }
    }

    /** 生成不暴露 BULL/BEAR 前缀的稳定不透明 ID。 */
    private String stablePointId(
            int round,
            Side side,
            int index,
            PointType type,
            String claim,
            AnalysisHorizon horizon,
            List<EvidenceRef> evidenceRefs,
            List<String> respondsToPointIds
    ) {
        String evidenceIds = evidenceRefs.stream()
                .map(EvidenceRef::evidenceId)
                .sorted()
                .reduce((left, right) -> left + "|" + right)
                .orElse("");
        String responseIds = respondsToPointIds.stream()
                .sorted()
                .reduce((left, right) -> left + "|" + right)
                .orElse("");
        String canonical = String.join("\n",
                "debate-point-v1",
                Integer.toString(round),
                side.name(),
                Integer.toString(index),
                type.name(),
                normalizeForMatch(claim),
                horizon.name(),
                evidenceIds,
                responseIds);
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            return "pt-" + HexFormat.of().formatHex(
                    digest.digest(canonical.getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException error) {
            throw new IllegalStateException("SHA-256 is unavailable", error);
        }
    }

    private String normalizeForMatch(String value) {
        if (value == null) {
            return "";
        }
        String unicode = Normalizer.normalize(value, Normalizer.Form.NFKC)
                .replace("\u200B", "")
                .replace("\uFEFF", "");
        return WHITESPACE.matcher(unicode).replaceAll(" ").strip().toLowerCase(Locale.ROOT);
    }

    private void require(boolean condition, ErrorCode code, String location) {
        if (!condition) {
            throw failure(code, location, null);
        }
    }

    private DebateContractException failure(ErrorCode code, String location, Throwable cause) {
        String safeLocation = location == null || location.isBlank() ? "contract" : location;
        return new DebateContractException(code, safeLocation, cause);
    }

    /** 可供 Trace/测试稳定匹配、不包含模型正文的错误代码。 */
    public enum ErrorCode {
        EMPTY_CONTENT,
        CONTENT_TOO_LONG,
        INVALID_JSON,
        INVALID_SCHEMA,
        UNKNOWN_FIELD,
        INVALID_ROUND,
        SIDE_REQUIRED,
        STATE_REQUIRED,
        LEDGER_REQUIRED,
        INVALID_POINT_COUNT,
        INVALID_POINT_TYPE,
        INVALID_HORIZON,
        FIELD_REQUIRED,
        FIELD_TOO_LONG,
        EVIDENCE_REQUIRED,
        EVIDENCE_ID_UNUSABLE,
        EVIDENCE_SEGMENT_MISSING,
        EVIDENCE_EXCERPT_TOO_SHORT,
        EVIDENCE_EXCERPT_MISMATCH,
        DUPLICATE_EVIDENCE_REF,
        RESPONSES_REQUIRED,
        RESPONSES_NOT_ALLOWED,
        INVALID_RESPONSE_POINT,
        RESPONSE_POINT_UNKNOWN,
        RESPONSE_POINT_NOT_OPPONENT,
        DUPLICATE_RESPONSE_POINT,
        SNAPSHOT_FORMAT_INVALID,
        DUPLICATE_EVIDENCE_SEGMENT,
        STATE_POINT_ID_CONFLICT
    }

    /** 辩论契约的稳定失败类型；message 只含代码和字段位置。 */
    public static final class DebateContractException extends IllegalArgumentException {

        private final ErrorCode code;
        private final String location;

        private DebateContractException(ErrorCode code, String location, Throwable cause) {
            super(code.name() + " at " + location, cause);
            this.code = code;
            this.location = location;
        }

        public ErrorCode code() {
            return code;
        }

        public ErrorCode getCode() {
            return code;
        }

        public String location() {
            return location;
        }

        public String getLocation() {
            return location;
        }
    }
}
