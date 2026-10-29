package com.stocksage.research.deep;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.stocksage.agent.TickerResolutionService;
import com.stocksage.capability.LocalFilingTextCapabilityAdapter;
import com.stocksage.capability.LocalDisclosureReadCapabilityAdapter;
import com.stocksage.capability.LocalFinancialReportsCapabilityAdapter;
import com.stocksage.capability.LocalNewsSearchCapabilityAdapter;
import com.stocksage.capability.LocalPriceHistoryCapabilityAdapter;
import com.stocksage.capability.LocalRelationQueryCapabilityAdapter;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;

/**
 * DEEP 取证循环的工具菜单（docs/architecture/deep-react-plan.md M1）。
 *
 * <p>模型只能从这里列出的工具中选择，并且只能给出枚举参数和简短主题。标的永远由服务端填入；
 * 菜单把一次模型工具调用校验为确切的能力调用，任何未知工具、多余字段、越界取值、
 * 不适用于当前市场的工具或出现其他标的的主题，都在执行前被拒绝。</p>
 */
@Component
public class DeepEvidenceToolMenu {

    /** 取证循环调用网关时使用的 allowlist；菜单之外的能力即使被误调用也会被网关拒绝。 */
    public static final Set<String> ALLOWED_CAPABILITIES = Set.of(
            LocalNewsSearchCapabilityAdapter.ID,
            LocalFilingTextCapabilityAdapter.ID,
            LocalDisclosureReadCapabilityAdapter.ID,
            LocalRelationQueryCapabilityAdapter.ID,
            LocalFinancialReportsCapabilityAdapter.ID,
            LocalPriceHistoryCapabilityAdapter.ID
    );

    public static final String FOCUSED_NEWS_SEARCH = "focused_news_search";
    public static final String FILING_TEXT_SEARCH = "filing_text_search";
    public static final String EVIDENCE_READ = "evidence_read";
    public static final String FINANCIAL_STATEMENTS = "financial_statements";
    public static final String PRICE_HISTORY = "price_history";
    public static final String RELATION_LOOKUP = "relation_lookup";
    public static final String FINISH_EVIDENCE = "finish_evidence";
    public static final String DECLARE_FACETS = "declare_facets";
    /** 声明必答维度只在循环内记录，不经网关、不取数。 */
    public static final String DECLARE_FACETS_CAPABILITY = "loop.declareFacets";
    private static final int MAX_FACET_LENGTH = 30;
    private static final int MAX_COVERAGE_LENGTH = 2000;

    static final Set<String> FINISH_REASONS = Set.of("SUFFICIENT", "NO_SAFE_ACTION");
    private static final int MAX_TOPIC_LENGTH = 80;
    private static final int MAX_NEWS_RESULTS = 5;

    private final TickerResolutionService tickerResolutionService;
    private final ObjectMapper objectMapper;

    public DeepEvidenceToolMenu(TickerResolutionService tickerResolutionService, ObjectMapper objectMapper) {
        this.tickerResolutionService = tickerResolutionService;
        this.objectMapper = objectMapper;
    }

    /** 一次模型工具调用的校验结果。 */
    public sealed interface Decision permits Call, Finish, Rejected {
    }

    /** 校验通过、可交给网关执行的确切调用。 */
    public record Call(String tool, String capabilityId, Map<String, Object> arguments) implements Decision {
        public Call {
            arguments = Map.copyOf(arguments);
        }
    }

    /** 模型主动结束取证；coverage 是模型对各必答维度覆盖情况的说明。 */
    public record Finish(String reasonCode, String coverage) implements Decision {
    }

    /** 未通过校验，零执行。 */
    public record Rejected(String tool, String reasonCode) implements Decision {
    }

    /** 提供给 function calling 的工具定义；parameters 为 JSON Schema。 */
    public record ToolDefinition(String name, String description, String parameters) {
    }

    /** 当前标的可用的工具；财报原文和关系图谱只覆盖 SEC 美股。 */
    public List<ToolDefinition> definitions(String targetTicker) {
        boolean sec = tickerResolutionService.isLikelySecTicker(targetTicker);
        List<ToolDefinition> tools = new ArrayList<>();
        tools.add(new ToolDefinition(DECLARE_FACETS, "第一轮先调用：列出回答用户问题必须覆盖的 2～4 个维度，用分号分隔，"
                + "每个不超过 30 字（如：监管进展;对广告收入的影响;估值影响）。不取数，只用于规划和结束时核对。",
                schema(Map.of("facets", "{\"type\":\"string\",\"maxLength\":160}"))));
        tools.add(new ToolDefinition(FOCUSED_NEWS_SEARCH, "搜索与当前标的和用户关注点直接相关的近期新闻。query 只写主题，不写其他公司。",
                schema(Map.of("query", "{\"type\":\"string\",\"maxLength\":" + MAX_TOPIC_LENGTH + "}"))));
        tools.add(new ToolDefinition(EVIDENCE_READ, "展开当前观察中的一条 evidenceId 的明确官方公告来源，可指定章节或 block:<块ID>、只读表格。"
                + "混合来源包只展开已标明的首段来源，selectedSource/otherSourceCount说明范围；不能传入自由URL。用nextOffset继续读取。",
                schema(Map.of("evidenceId", "{\"type\":\"string\",\"maxLength\":128}",
                        "section", "{\"type\":\"string\",\"maxLength\":250}", "tablesOnly", "{\"type\":\"boolean\"}",
                        "offset", "{\"type\":\"integer\",\"minimum\":0,\"maximum\":5000000}",
                        "maxChars", "{\"type\":\"integer\",\"minimum\":256,\"maximum\":24000}"))));
        if (sec) {
            tools.add(new ToolDefinition(FILING_TEXT_SEARCH, "在当前标的的 SEC 10-K/10-Q 原文中检索与主题相关的段落。",
                    schema(Map.of("form", "{\"type\":\"string\",\"enum\":[\"10-K\",\"10-Q\"]}",
                            "topic", "{\"type\":\"string\",\"maxLength\":" + MAX_TOPIC_LENGTH + "}"))));
            tools.add(new ToolDefinition(RELATION_LOOKUP, "查询从当前标的 10-K 抽取的竞争对手、客户、供应商或合作方（带原文出处）。",
                    schema(Map.of("relationType", "{\"type\":\"string\",\"enum\":[\"COMPETITOR\",\"CUSTOMER\",\"SUPPLIER\",\"PARTNER\"]}"))));
        }
        tools.add(new ToolDefinition(FINANCIAL_STATEMENTS, sec
                ? "获取当前标的的 SEC 结构化财报。annual 为 10-K 年度数据（基础证据已含最近 5 个财年）；"
                        + "quarterly 为 10-Q 单季数据，不含第四财季；现金流可由同财年累计差额计算并附来源，缺前期或版本无法对齐时留空。"
                : "获取当前标的的结构化财报。",
                schema(Map.of("period", "{\"type\":\"string\",\"enum\":[\"annual\",\"quarterly\"]}",
                        "periods", "{\"type\":\"integer\",\"minimum\":1,\"maximum\":5}"))));
        tools.add(new ToolDefinition(PRICE_HISTORY, "获取当前标的的历史 K 线。",
                schema(Map.of("window", "{\"type\":\"string\",\"enum\":[\"1m\",\"3m\",\"6m\",\"1y\"]}",
                        "bar", "{\"type\":\"string\",\"enum\":[\"1d\",\"1w\"]}"))));
        tools.add(new ToolDefinition(FINISH_EVIDENCE, "证据已足够，或没有安全可行的下一步时结束取证。coverage 用分号逐项写"
                + "原声明维度:已覆盖/缺失/无法确认；已覆盖项附观察中的 evidenceId，缺失或无法确认项写原因。"
                + "例如：监管进展:已覆盖 <完整evidenceId>;估值影响:缺失 未找到估值依据。引用存在不等于实质覆盖。",
                schema(Map.of("reasonCode", "{\"type\":\"string\",\"enum\":[\"SUFFICIENT\",\"NO_SAFE_ACTION\"]}",
                        "coverage", "{\"type\":\"string\",\"maxLength\":" + MAX_COVERAGE_LENGTH + "}"))));
        return List.copyOf(tools);
    }

    /**
     * 把模型给出的工具名和参数 JSON 校验为确切的能力调用。
     *
     * @param toolName 模型选择的工具名
     * @param argumentsJson 模型给出的参数 JSON 对象
     * @param targetTicker 服务端确定的当前标的
     */
    public Decision validate(String toolName, String argumentsJson, String targetTicker) {
        String tool = toolName == null ? "" : toolName.strip();
        String target = tickerResolutionService.normalizeStructuredTicker(targetTicker);
        if (target.isBlank()) {
            return new Rejected(tool, "TARGET_UNRESOLVED");
        }
        if (definitions(target).stream().noneMatch(definition -> definition.name().equals(tool))) {
            boolean known = Set.of(FOCUSED_NEWS_SEARCH, FILING_TEXT_SEARCH, FINANCIAL_STATEMENTS, PRICE_HISTORY,
                    RELATION_LOOKUP, FINISH_EVIDENCE, DECLARE_FACETS, EVIDENCE_READ).contains(tool);
            return new Rejected(tool, known ? "TOOL_NOT_AVAILABLE_FOR_TARGET" : "UNKNOWN_TOOL");
        }
        JsonNode args;
        try {
            args = objectMapper.readTree(argumentsJson == null || argumentsJson.isBlank() ? "{}" : argumentsJson);
        } catch (Exception error) {
            return new Rejected(tool, "INVALID_ARGUMENTS");
        }
        if (args == null || !args.isObject()) {
            return new Rejected(tool, "INVALID_ARGUMENTS");
        }
        try {
            return switch (tool) {
                case FOCUSED_NEWS_SEARCH -> {
                    requireFields(args, "query");
                    String focus = topic(args, "query", target);
                    yield new Call(tool, LocalNewsSearchCapabilityAdapter.ID,
                            Map.of("query", target + " " + focus, "maxResults", MAX_NEWS_RESULTS));
                }
                case FILING_TEXT_SEARCH -> {
                    requireFields(args, "form", "topic");
                    yield new Call(tool, LocalFilingTextCapabilityAdapter.ID, Map.of(
                            "ticker", target,
                            "form", enumValue(args, "form", Set.of("10-K", "10-Q")),
                            "topic", topic(args, "topic", target)));
                }
                case EVIDENCE_READ -> {
                    requireFields(args, "evidenceId", "section", "tablesOnly", "offset", "maxChars");
                    if (!args.path("section").isTextual() || args.path("section").asText().length() > 250
                            || !args.path("tablesOnly").isBoolean()) throw new InvalidArgument("INVALID_ARGUMENTS");
                    yield new Call(tool, LocalDisclosureReadCapabilityAdapter.ID, Map.of("ticker", target,
                            "evidenceId", shortText(args, "evidenceId", 128), "section", args.path("section").asText(),
                            "tablesOnly", args.path("tablesOnly").asBoolean(), "offset", integer(args, "offset", 0, 5_000_000),
                            "maxChars", integer(args, "maxChars", 256, 24000)));
                }
                case RELATION_LOOKUP -> {
                    requireFields(args, "relationType");
                    yield new Call(tool, LocalRelationQueryCapabilityAdapter.ID, Map.of(
                            "ticker", target,
                            "relationType", enumValue(args, "relationType",
                                    Set.of("COMPETITOR", "CUSTOMER", "SUPPLIER", "PARTNER"))));
                }
                case FINANCIAL_STATEMENTS -> {
                    requireFields(args, "period", "periods");
                    yield new Call(tool, LocalFinancialReportsCapabilityAdapter.ID, Map.of(
                            "ticker", target,
                            "period", enumValue(args, "period", Set.of("annual", "quarterly")),
                            "periods", integer(args, "periods", 1, 5)));
                }
                case PRICE_HISTORY -> {
                    requireFields(args, "window", "bar");
                    yield new Call(tool, LocalPriceHistoryCapabilityAdapter.ID, Map.of(
                            "ticker", target,
                            "window", enumValue(args, "window", Set.of("1m", "3m", "6m", "1y")),
                            "bar", enumValue(args, "bar", Set.of("1d", "1w"))));
                }
                case FINISH_EVIDENCE -> {
                    if (args.has("coverage")) requireFields(args, "reasonCode", "coverage");
                    else requireFields(args, "reasonCode");
                    String coverage = args.path("coverage").isTextual()
                            ? args.path("coverage").textValue().strip() : "";
                    // 缺失或无法解析的自评作为研究缺口，不能据此中断已有证据的研究。
                    // 超长时截到最后一个完整条目：每个 evidenceId 就有 64 字符，整段丢弃会让所有维度都变成"未解析"。
                    if (coverage.length() > MAX_COVERAGE_LENGTH) {
                        String head = coverage.substring(0, MAX_COVERAGE_LENGTH);
                        int cut = Math.max(head.lastIndexOf(';'), head.lastIndexOf('；'));
                        coverage = cut > 0 ? head.substring(0, cut) : "";
                    }
                    yield new Finish(enumValue(args, "reasonCode", FINISH_REASONS),
                            coverage);
                }
                case DECLARE_FACETS -> {
                    requireFields(args, "facets");
                    List<String> facets = java.util.Arrays.stream(shortText(args, "facets", 160).split("[;；]"))
                            .map(String::strip).filter(facet -> !facet.isEmpty()).toList();
                    if (facets.size() < 2 || facets.size() > 4
                            || facets.stream().anyMatch(facet -> facet.length() > MAX_FACET_LENGTH)) {
                        throw new InvalidArgument("INVALID_ARGUMENTS");
                    }
                    yield new Call(tool, DECLARE_FACETS_CAPABILITY, Map.of("facets", String.join(";", facets)));
                }
                default -> new Rejected(tool, "UNKNOWN_TOOL");
            };
        } catch (InvalidArgument error) {
            return new Rejected(tool, error.reasonCode);
        }
    }

    /** 参数字段必须与定义完全一致；模型传 ticker、limit、timeout 等任何额外字段都拒绝。 */
    private static void requireFields(JsonNode args, String... fields) {
        Set<String> actual = new TreeSet<>();
        for (Iterator<String> names = args.fieldNames(); names.hasNext(); ) {
            actual.add(names.next());
        }
        if (!actual.equals(new TreeSet<>(List.of(fields)))) {
            throw new InvalidArgument("INVALID_ARGUMENTS");
        }
    }

    private static String enumValue(JsonNode args, String field, Set<String> allowed) {
        JsonNode value = args.get(field);
        if (value == null || !value.isTextual() || !allowed.contains(value.textValue())) {
            throw new InvalidArgument("INVALID_ARGUMENTS");
        }
        return value.textValue();
    }

    private static int integer(JsonNode args, String field, int min, int max) {
        JsonNode value = args.get(field);
        if (value == null || !value.isIntegralNumber() || !value.canConvertToInt()
                || value.intValue() < min || value.intValue() > max) {
            throw new InvalidArgument("INVALID_ARGUMENTS");
        }
        return value.intValue();
    }

    /** 维度清单这类给人读的短文本：非空、无控制字符、不超长。 */
    private static String shortText(JsonNode args, String field, int maxLength) {
        JsonNode value = args.get(field);
        if (value == null || !value.isTextual()) throw new InvalidArgument("INVALID_ARGUMENTS");
        String text = value.textValue().replaceAll("\\s+", " ").strip();
        if (text.isEmpty() || text.length() > maxLength || text.chars().anyMatch(Character::isISOControl)) {
            throw new InvalidArgument("INVALID_ARGUMENTS");
        }
        return text;
    }

    /** 主题只能是短文本，不能含控制字符，也不能点名当前标的之外的公司。 */
    private String topic(JsonNode args, String field, String target) {
        JsonNode value = args.get(field);
        if (value == null || !value.isTextual()) {
            throw new InvalidArgument("INVALID_ARGUMENTS");
        }
        String text = value.textValue();
        if (text.chars().anyMatch(Character::isISOControl)) {
            throw new InvalidArgument("INVALID_ARGUMENTS");
        }
        String normalized = text.replaceAll("\\s+", " ").strip();
        if (normalized.isBlank() || normalized.length() > MAX_TOPIC_LENGTH) {
            throw new InvalidArgument("INVALID_ARGUMENTS");
        }
        if (tickerResolutionService.hasConflictingExplicitTicker(normalized, target)) {
            throw new InvalidArgument("CONFLICTING_TICKER");
        }
        return normalized;
    }

    private static String schema(Map<String, String> properties) {
        Map<String, String> ordered = new LinkedHashMap<>(new java.util.TreeMap<>(properties));
        StringBuilder json = new StringBuilder("{\"type\":\"object\",\"properties\":{");
        ordered.forEach((name, definition) -> json.append('"').append(name).append("\":").append(definition).append(','));
        json.setLength(json.length() - 1);
        json.append("},\"required\":[");
        ordered.keySet().forEach(name -> json.append('"').append(name).append("\","));
        json.setLength(json.length() - 1);
        return json.append("],\"additionalProperties\":false}").toString();
    }

    private static final class InvalidArgument extends RuntimeException {
        private final String reasonCode;

        private InvalidArgument(String reasonCode) {
            super(reasonCode, null, false, false);
            this.reasonCode = reasonCode;
        }
    }
}
