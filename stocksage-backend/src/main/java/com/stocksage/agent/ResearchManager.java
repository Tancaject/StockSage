package com.stocksage.agent;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.stocksage.model.dto.AnalysisState;
import com.stocksage.model.dto.InvestmentReport;
import com.stocksage.tool.ChatStreamEmitter;
import lombok.extern.slf4j.Slf4j;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.stereotype.Service;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * 将分析师证据和多空辩论整理成结构化的 InvestmentReport。
 * 这里的 JSON 契约会由 ChatService 格式化为面向用户的证据优先回答。
 */
@Slf4j
@Service
public class ResearchManager {

    private final ChatClient chatClient;
    private final ObjectMapper objectMapper;

    /**
     * 注入研究经理专用 ChatClient 和 JSON 解析器。
     *
     * <p>研究经理输出以 JSON 为契约，因此 ObjectMapper 是把模型文本落到结构化 DTO 的关键依赖。</p>
     */
    public ResearchManager(@Qualifier("researchManagerChatClient") ChatClient chatClient,
                           ObjectMapper objectMapper) {
        this.chatClient = chatClient;
        this.objectMapper = objectMapper;
    }

    /**
     * 汇总分析师报告、多空辩论和引用信息，生成最终投资研究报告。
     *
     * @param state 深度研究流水线当前状态
     * @return 结构化投资报告；当模型 JSON 不完整时会降级为包含原文摘要的 HOLD 报告
     */
    public InvestmentReport synthesize(AnalysisState state) {
        String content = chatClient.prompt()
                .user(buildPrompt(state))
                .call()
                .content();

        return parseReport(content, state);
    }

    /**
     * 流式版本：逐 token 推送 Research Manager 的自然语言综合分析到推理面板，
     * 在探测到 JSON 起始（```json 围栏或第一个 {）后停止可见输出但继续 buffer，
     * 直至流结束再统一解析结构化 InvestmentReport。
     *
     * <p>解决"辩论流式可见、但 Manager 还要憋 ~140s 才出报告"的体验断点：
     * 让用户在 Manager 思考期间能持续看到中文综合判断逐字生成。</p>
     *
     * @return 完整 InvestmentReport 的 Mono，订阅者通常 {@code block()} 等待
     */
    public Mono<InvestmentReport> synthesizeStreaming(AnalysisState state, String traceId,
                                                     Long conversationId, ChatStreamEmitter emitter) {
        String section = "manager-synthesis";
        String label = "Research Manager · 综合判断";
        StringBuilder buffer = new StringBuilder();
        AtomicBoolean jsonStarted = new AtomicBoolean(false);

        Flux<String> tokens = chatClient.prompt()
                .user(buildPrompt(state))
                .stream()
                .content();

        return tokens
                .doOnNext(token -> {
                    if (token == null || token.isEmpty()) return;
                    int prevLen = buffer.length();
                    buffer.append(token);
                    if (jsonStarted.get()) return;

                    // 探测 JSON 起始：``` 围栏（可能 ```json 也可能就是 ```）或第一个未转义的 {
                    int fenceIdx = buffer.indexOf("```");
                    int braceIdx = buffer.indexOf("{");
                    int jsonAt = -1;
                    if (fenceIdx >= 0 && (braceIdx < 0 || fenceIdx < braceIdx)) {
                        jsonAt = fenceIdx;
                    } else if (braceIdx >= 0) {
                        jsonAt = braceIdx;
                    }

                    if (jsonAt < 0 || jsonAt >= buffer.length()) {
                        // 仍在自然语言阶段：整 token 可见
                        emitter.emitSection(traceId, conversationId, "thought",
                                section, label, token);
                        return;
                    }

                    // 本 token 跨越自然语言→JSON 边界：只推送边界前的部分
                    if (jsonAt > prevLen) {
                        String visiblePart = token.substring(0, jsonAt - prevLen);
                        if (!visiblePart.isEmpty()) {
                            emitter.emitSection(traceId, conversationId, "thought",
                                    section, label, visiblePart);
                        }
                    }
                    jsonStarted.set(true);
                })
                .then(Mono.fromCallable(() -> parseReport(buffer.toString(), state)));
    }

    /**
     * 构造 Research Manager 提示词。
     *
     * <p>要求模型先输出 2-3 段简体中文综合分析，再用 ```json 代码块输出严格 JSON——
     * 自然语言段用于流式推送给用户，JSON 段用于后端结构化解析。</p>
     */
    private String buildPrompt(AnalysisState state) {
        return """
                用户问题：%s

                Fundamentals:
                %s

                Market:
                %s

                News:
                %s

                Bull:
                %s

                Bear:
                %s

                Debate:
                %s

                请按以下两段顺序输出：

                第一段：用 2-3 段简体中文综合阐述你的判断（这一段会展示给用户作为推理过程）。
                - 不要使用大括号 { 或 } 字符（包括转义、注释、示例都不可以），以便系统区分自然语言和 JSON。
                - 不要把 Bull/Bear 原文照抄给用户，只把它们提炼成面向用户的最终判断、理由和风险。
                - 用陈述性、可读性强的语气，不要列点编号。

                第二段：另起一行，输出 ```json 代码块包裹的严格 JSON（不要在代码块外再写任何文字）。
                所有可读文本必须使用简体中文。
                citations 只能写业务可读来源，例如“结构化财务数据”“近 60 日行情与技术指标”“SEC 10-K 知识库片段”“近 7 日新闻搜索结果”。
                citations 里不要出现 Java 方法名、工具函数名、类名或内部 agent 名。
                evidenceItems 必须只写上文已有证据；如果证据不足，不要补编数据，把缺口写入 unknowns。

                ```json
                {
                  "recommendation": "BUY 或 OVERWEIGHT 或 HOLD 或 UNDERWEIGHT 或 SELL",
                  "rationale": ["面向用户的关键理由1", "面向用户的关键理由2"],
                  "riskFactors": ["需要跟踪的风险1", "需要跟踪的风险2"],
                  "analystSummary": "一段中文综合结论，说明是否值得投资以及适合什么仓位/周期",
                  "evidenceItems": [
                    {
                      "dimension": "财务/估值/行情/技术面/新闻/公告/RAG",
                      "evidence": "可核验的关键证据，不写没有出现过的精确数字",
                      "implication": "这条证据对投资判断的含义",
                      "source": "结构化财务数据/近 60 日行情与技术指标/SEC 10-K 知识库片段/近 7 日新闻搜索结果"
                    }
                  ],
                  "bullFactors": ["最重要的利多因素1", "最重要的利多因素2"],
                  "bearFactors": ["最重要的利空因素1", "最重要的利空因素2"],
                  "suitableFor": ["更适合的投资者或持有条件"],
                  "notSuitableFor": ["不适合的投资者或回避条件"],
                  "unknowns": ["当前证据无法确认但会影响判断的事项"],
                  "dataFreshness": "说明本轮数据来源和时点，例如：行情为近 60 日快照，新闻为近 7 日搜索，财报以当前工具返回为准",
                  "citations": ["结构化财务数据", "近 60 日行情与技术指标", "SEC 10-K 知识库片段", "近 7 日新闻搜索结果"]
                }
                ```

                recommendation 只能是以下五档之一（按倾向从强多头到强空头排序）：
                - BUY：强多头信号，多空辩论中看多论据明显占优且证据扎实。
                - OVERWEIGHT：偏多头，看多论据占优但仍有未解风险，建议小仓位/分批参与。
                - HOLD：多空证据基本平衡或都不充分，建议观望、等待新数据。
                - UNDERWEIGHT：偏空头，看空风险占优但尚未到必须离场的程度，建议减仓或回避加仓。
                - SELL：强空头信号，看空风险明显占优且证据扎实。
                只有当多空证据真正势均力敌时才用 HOLD；不要把 HOLD 当成回避判断的避风港。
                始终提醒：仅供参考，不构成投资建议。
                """.formatted(
                state.getQuery(),
                truncate(safe(state.getFundamentalsReport()), 2500),
                truncate(safe(state.getMarketReport()), 2500),
                truncate(safe(state.getNewsReport()), 2500),
                truncate(safe(state.getBullThesis()), 1800),
                truncate(safe(state.getBearThesis()), 1800),
                truncate(String.join("\n\n", state.getDebateRounds()), 3500)
        );
    }

    /**
     * 将研究经理模型输出解析为 {@link InvestmentReport}。
     *
     * <p>解析阶段会校验 recommendation 枚举值、读取数组字段，并为缺失的风险和未知项补默认说明。
     * 如果模型没有返回合法 JSON，则进入兜底分支，保证用户仍能看到原始综合内容。</p>
     */
    private InvestmentReport parseReport(String content, AnalysisState state) {
        try {
            String json = extractJson(content);
            JsonNode root = objectMapper.readTree(json);

            String recommendation = root.path("recommendation").asText("HOLD").toUpperCase();
            if (!List.of("BUY", "OVERWEIGHT", "HOLD", "UNDERWEIGHT", "SELL").contains(recommendation)) {
                recommendation = "HOLD";
            }

            List<String> rationale = readStringArray(root.path("rationale"));
            List<String> riskFactors = readStringArray(root.path("riskFactors"));
            String analystSummary = root.path("analystSummary").asText("");
            List<InvestmentReport.EvidenceItem> evidenceItems = readEvidenceItems(root.path("evidenceItems"));
            List<String> bullFactors = readStringArray(root.path("bullFactors"));
            List<String> bearFactors = readStringArray(root.path("bearFactors"));
            List<String> suitableFor = readStringArray(root.path("suitableFor"));
            List<String> notSuitableFor = readStringArray(root.path("notSuitableFor"));
            List<String> unknowns = readStringArray(root.path("unknowns"));
            String dataFreshness = root.path("dataFreshness").asText("");
            List<String> citations = readStringArray(root.path("citations"));

            if (riskFactors.isEmpty()) {
                riskFactors = List.of("模型输出仍需结合实时数据、仓位和个人风险承受能力复核。");
            }
            if (unknowns.isEmpty()) {
                unknowns = List.of("部分数据源可能存在延迟、缺失或覆盖不完整，需要结合最新公告和行情复核。");
            }

            return InvestmentReport.builder()
                    .recommendation(recommendation)
                    .analystSummary(analystSummary.isBlank() ? content : analystSummary)
                    .bullCase(state.getBullThesis())
                    .bearCase(state.getBearThesis())
                    .rationale(rationale.isEmpty() ? List.of(content) : rationale)
                    .riskFactors(riskFactors)
                    .evidenceItems(evidenceItems)
                    .bullFactors(bullFactors)
                    .bearFactors(bearFactors)
                    .suitableFor(suitableFor)
                    .notSuitableFor(notSuitableFor)
                    .unknowns(unknowns)
                    .dataFreshness(dataFreshness)
                    .citations(citations.isEmpty() ? state.getCitations() : citations)
                    .build();
        } catch (Exception e) {
            log.warn("Failed to parse Research Manager structured output, using raw content: {}", e.getMessage());
            return InvestmentReport.builder()
                    .recommendation("HOLD")
                    .analystSummary(content)
                    .bullCase(state.getBullThesis())
                    .bearCase(state.getBearThesis())
                    .rationale(List.of(content))
                    .riskFactors(List.of("模型输出仍需结合实时数据、仓位和个人风险承受能力复核。"))
                    .unknowns(List.of("Research Manager 未能返回完整结构化 JSON，证据表需要人工复核。"))
                    .citations(state.getCitations())
                    .build();
        }
    }

    /**
     * 从模型输出中提取 JSON 对象正文。
     *
     * <p>模型偶尔会包裹 Markdown 代码块或输出少量前后说明，这里只截取首个大括号对象以提高容错性。</p>
     */
    private String extractJson(String content) {
        if (content == null) return "{}";
        String trimmed = content.trim()
                .replaceAll("(?is)^```json\\s*", "")
                .replaceAll("(?is)^```\\s*", "")
                .replaceAll("(?is)\\s*```$", "")
                .trim();
        int start = trimmed.indexOf('{');
        int end = trimmed.lastIndexOf('}');
        if (start >= 0 && end > start) {
            return trimmed.substring(start, end + 1);
        }
        return trimmed;
    }

    /**
     * 读取 JSON 字符串数组，自动丢弃空字符串。
     */
    private List<String> readStringArray(JsonNode node) {
        if (node == null || !node.isArray()) return List.of();
        List<String> values = new ArrayList<>();
        for (JsonNode item : node) {
            String value = item.asText("").trim();
            if (!value.isBlank()) values.add(value);
        }
        return values;
    }

    /**
     * 读取结构化证据项。
     *
     * <p>优先解析对象数组；如果模型退化为字符串数组，则把字符串包成“综合证据”项，
     * 让前端证据表仍能展示可读内容。</p>
     */
    private List<InvestmentReport.EvidenceItem> readEvidenceItems(JsonNode node) {
        if (node == null || !node.isArray()) return List.of();
        List<InvestmentReport.EvidenceItem> values = new ArrayList<>();
        for (JsonNode item : node) {
            if (item.isObject()) {
                InvestmentReport.EvidenceItem evidenceItem = InvestmentReport.EvidenceItem.builder()
                        .dimension(item.path("dimension").asText("").trim())
                        .evidence(item.path("evidence").asText("").trim())
                        .implication(item.path("implication").asText("").trim())
                        .source(item.path("source").asText("").trim())
                        .build();
                if (!safe(evidenceItem.getEvidence()).isBlank()) {
                    values.add(evidenceItem);
                }
            } else {
                String value = item.asText("").trim();
                if (!value.isBlank()) {
                    values.add(InvestmentReport.EvidenceItem.builder()
                            .dimension("综合证据")
                            .evidence(value)
                            .implication("支持综合判断")
                            .source("Research Manager 结构化输出")
                            .build());
                }
            }
        }
        return values;
    }

    /**
     * 将空值转换为空字符串，供 JSON 解析兜底和提示词拼装复用。
     */
    private String safe(String value) {
        return value == null ? "" : value;
    }

    /**
     * 截断上游长报告，避免研究经理提示词超过模型上下文预算。
     */
    private String truncate(String value, int maxLength) {
        if (value == null || value.length() <= maxLength) {
            return value;
        }
        return value.substring(0, maxLength) + "\n...[truncated]";
    }
}
