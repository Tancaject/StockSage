package com.stocksage.memory;

import com.stocksage.util.JsonText;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.extern.slf4j.Slf4j;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * 持久化的用户投资画像记忆。
 *
 * <p>每次助手回答后，ChatService 会在后台调用该组件提取持仓、风险偏好和画像摘要。
 * 关注列表只接受用户显式管理；持仓只接受显式撤销，风险偏好和摘要由 UserService 应用查询时遗忘。</p>
 */
@Slf4j
@Component
public class LongTermMemory {

    /** 识别大写美股代码、类别股代码和带市场后缀的数字代码。 */
    private static final Pattern TICKER_PATTERN = Pattern.compile(
            "(?<![A-Z0-9.-])([A-Z]{1,5}(?:[.-][A-Z])?|[0-9]{4,6}\\.(?:HK|SH|SZ))(?![A-Z0-9.-])");

    /** 规则兜底按分句判断问句；调用模型前不判，问句与假设由模型区分。 */
    private static final Pattern QUESTION = Pattern.compile(
            "[?？]|吗|要不要|该不该|是否|会不会|如何|怎么样|怎样|\\b(?:should\\s+I|would|how)\\b",
            Pattern.CASE_INSENSITIVE);
    /** 假设语气可能跨分句，规则兜底遇到即整条放弃。 */
    private static final Pattern HYPOTHESIS = Pattern.compile(
            "如果|假设|假如|要是|\\bif\\b", Pattern.CASE_INSENSITIVE);
    /** 允许“目前我持有”这类时间状语前缀。 */
    private static final String TIME_PREFIX = "(?:目前|现在|最近|已经|之前|刚刚|刚)?\\s*";
    private static final Pattern SELF_REPORT = Pattern.compile(
            "(?:我(?:持有|买了|买入|拿着|的持仓|(?:的)?仓位里|不再持有|不持有|全部卖出|全卖了|(?:已)?清仓|比较|偏|是|属于|主要|喜欢|倾向|的风险偏好|的投资偏好|的投资期限|保守|稳健|激进|进取)"
                    + "|\\bI(?:\\s+(?:own|hold|bought|sold|fully\\s+sold|no\\s+longer|do\\s+not|don't|closed|exited|am|prefer|mainly|invest)\\b|['’]m\\b)"
                    + "|\\bmy\\s+(?:position|holdings|risk\\s+preference|investment)\\b)",
            Pattern.CASE_INSENSITIVE);
    private static final Pattern HOLDING_STATEMENT = Pattern.compile(
            "^" + TIME_PREFIX + "(?:我(?:持有|买了|买入|拿着)|我(?:的持仓|(?:的)?仓位里)\\s*(?:是|有|包括|包含|为|[:：])"
                    + "|I\\s+(?:own|hold|bought)\\b|my\\s+(?:position|holdings)\\s*(?:(?:is|are|includes?|contains?|consists?\\s+of)\\b|:))",
            Pattern.CASE_INSENSITIVE);
    private static final Pattern HOLDING_REVOCATION = Pattern.compile(
            "^" + TIME_PREFIX + "(?:我(?:不再持有|不持有|全部卖出|全卖了|(?:已)?清仓)|I\\s+(?:no\\s+longer\\s+(?:hold|own)|(?:don't|do\\s+not)\\s+(?:hold|own)|sold\\s+all|fully\\s+sold|closed\\s+my\\s+position|exited\\s+my\\s+position)\\b)",
            Pattern.CASE_INSENSITIVE);
    private static final Pattern COMPLEX_HOLDING_STATEMENT = Pattern.compile(
            "但|不过|然而|却|仍|还持有|还剩|剩余|剩下|除|期权|看涨|看跌"
                    + "|\\b(?:but|however|still|except|unless|remaining|remain|left|calls?|puts?|options?)\\b",
            Pattern.CASE_INSENSITIVE);
    private static final Pattern RISK_STATEMENT = Pattern.compile(
            "^(?:我(?:比较|偏|是|属于)?\\s*(保守|稳健|低风险|激进|进取|高风险|中等风险|平衡|均衡)"
                    + "|我的风险偏好\\s*(?:是|为)?\\s*(保守|稳健|低风险|激进|进取|高风险|中等风险|平衡|均衡)"
                    + "|(?:I(?:\\s+am|['’]m)|my\\s+risk\\s+preference\\s+is)\\s+(conservative|moderate|aggressive)\\b)"
                    + "\\s*(?:型(?:投资者)?|的投资者)?\\s*$",
            Pattern.CASE_INSENSITIVE);

    /** 看似 ticker、实际是常见词或技术缩写的过滤表。 */
    private static final Set<String> NOISE_WORDS = Set.of(
            "I", "A", "THE", "AND", "OR", "NOT", "MY", "FOR", "IS", "IT", "AT", "TO",
            "IN", "ON", "OF", "DO", "IF", "SO", "UP", "BY", "AN", "AM", "US", "OK",
            "NO", "YES", "ALL", "ANY", "BUT", "CAN", "HAS", "HAD", "HER", "HIS",
            "HOW", "ITS", "LET", "MAY", "NEW", "NOW", "OLD", "OUR", "OUT", "OWN",
            "SAY", "SHE", "TOO", "USE", "WAY", "WHO", "BOY", "DID", "GET", "HIM",
            "MAN", "RUN", "SET", "TRY", "TWO", "WAS", "ARE", "BEEN", "CALL", "COME",
            "EACH", "FIND", "GIVE", "HAVE", "JUST", "KNOW", "LIKE", "LONG", "LOOK",
            "MAKE", "MANY", "MUCH", "MUST", "NAME", "ONLY", "OVER", "SUCH", "TAKE",
            "THAN", "THEM", "THEN", "VERY", "WHEN", "WILL", "WITH", "ALSO", "BACK",
            "BOTH", "FROM", "GOOD", "HERE", "HIGH", "HOLD", "KEEP",
            "LAST", "MADE", "MORE", "MOST", "MOVE", "NEXT", "SOME", "THAT",
            "THEY", "THIS", "WHAT", "YEAR", "YOUR", "ABOUT", "COULD", "EVERY",
            "THEIR", "THINK", "THOSE", "WHICH", "WOULD", "AFTER", "BEING",
            "PE", "PB", "ROE", "EPS", "GDP", "ETF", "IPO", "CEO", "CFO", "API",
            "SSE", "RAG", "LLM", "JSON", "HTML", "HTTP", "URL"
    );

    /** 负责从数据库读取并合并持久化用户画像。 */
    private final UserService userService;
    /** 专用于画像抽取的低成本模型客户端。 */
    private final ChatClient memoryChatClient;
    /** 解析记忆模型返回的严格 JSON。 */
    private final ObjectMapper objectMapper;

    /** 有明确自述时优先调用模型；关闭或失败时才使用严格规则。 */
    @Value("${stocksage.memory.llm-profile.enabled:true}")
    private boolean llmProfileEnabled;

    /**
     * 注入画像持久化服务、记忆模型客户端和 JSON 解析器。
     *
     * @param userService 用户画像持久化服务
     * @param memoryChatClient 画像抽取专用 ChatClient
     * @param objectMapper JSON 解析器
     */
    public LongTermMemory(UserService userService,
                          @Qualifier("memoryChatClient") ChatClient memoryChatClient,
                          ObjectMapper objectMapper) {
        this.userService = userService;
        this.memoryChatClient = memoryChatClient;
        this.objectMapper = objectMapper;
    }

    /**
     * 构造可注入聊天提示词的长期画像上下文。
     *
     * <p>画像为空时返回空字符串，避免无意义的默认信息干扰模型回答。</p>
     *
     * @param userId 当前用户 ID
     * @return 由 ChatPromptAssembler 包装为不可信资料区段的画像文本；空画像返回空串
     */
    public String buildPromptContext(String userId) {
        UserProfileDTO profile = userService.getPromptProfile(userId);
        List<String> facts = new ArrayList<>();
        if (profile.getHoldings() != null && !profile.getHoldings().isEmpty()) {
            facts.add("- 持仓 (Holdings): " + profile.getHoldings().stream()
                    .map(ticker -> ticker + "（最后确认 " + profile.getHoldingConfirmedDates().get(ticker) + "）")
                    .collect(java.util.stream.Collectors.joining(", "))
                    + "。持仓来自用户自述，可能已变化。");
        }
        if (profile.getWatchList() != null && !profile.getWatchList().isEmpty()) {
            facts.add("- 关注列表 (Watch list): " + formatList(profile.getWatchList()));
        }
        if (profile.getRiskPreference() != null && !profile.getRiskPreference().isBlank()) {
            facts.add("- 风险偏好 (Risk preference): " + profile.getRiskPreference());
        }
        if (profile.getProfileSummary() != null && !profile.getProfileSummary().isBlank()) {
            facts.add("- 画像摘要 (Summary): " + profile.getProfileSummary());
        }
        if (facts.isEmpty()) {
            return "";
        }
        return "以下是该用户的长期画像。当用户提到\"我的持仓\"\"我的关注\"或涉及个人投资偏好的问题时，请结合此信息回答。\n"
                + String.join("\n", facts);
    }

    /**
     * 从用户消息中提取持仓、风险偏好和画像摘要并更新长期画像。
     * 只处理明确自述；模型成功时采用其完整判断，关闭或失败时才使用严格规则。
     *
     * @param userId 当前用户 ID
     * @param assistantResponse 本轮助手回答，供可选 LLM 抽取补充上下文
     * @param userMessage 本轮用户消息
     * @param sourceMessageId 持久化后的用户消息主键
     * @param observedAt 用户消息的持久化时间
     */
    public void extractAndUpdate(
            String userId,
            String assistantResponse,
            String userMessage,
            Long sourceMessageId,
            LocalDateTime observedAt
    ) {
        if (userMessage == null || userMessage.isBlank()
                || !SELF_REPORT.matcher(userMessage).find()) {
            return;
        }

        UserProfileDTO update = new UserProfileDTO();
        List<String> revokedHoldings = new ArrayList<>();
        if (!llmProfileEnabled || !mergeLlmProfile(update, revokedHoldings, userMessage, assistantResponse,
                userService.getPromptProfile(userId).getProfileSummary())) {
            extractRuleProfile(update, revokedHoldings, userMessage);
        }

        if (!revokedHoldings.isEmpty()) {
            userService.revokeHoldings(userId, revokedHoldings, sourceMessageId, observedAt);
            if (update.getHoldings() != null) {
                update.getHoldings().removeIf(revokedHoldings::contains);
            }
        }

        if (hasProfileUpdate(update)) {
            userService.updateUserProfile(userId, update, sourceMessageId, observedAt);
            log.info("Extracted and updated long-term memory for userId={}, holdings={}, risk={}",
                    userId, update.getHoldings(), update.getRiskPreference());
        }
    }

    private void extractRuleProfile(UserProfileDTO update, List<String> revokedHoldings, String userMessage) {
        List<String> holdings = new ArrayList<>();
        if (HYPOTHESIS.matcher(userMessage).find()) {
            update.setHoldings(holdings);
            return;
        }
        boolean simpleHoldings = !COMPLEX_HOLDING_STATEMENT.matcher(userMessage).find();
        for (String part : userMessage.split("[，,。;；！!\\n]|\\.(?=\\s|$)")) {
            String statement = part.trim();
            if (QUESTION.matcher(statement).find()) {
                continue;
            }
            String lower = statement.toLowerCase(java.util.Locale.ROOT);
            Matcher revocation = HOLDING_REVOCATION.matcher(statement);
            Matcher holding = HOLDING_STATEMENT.matcher(statement);
            // shortcut: 转折、期权和剩余仓位只交给模型，出现真实需求后再扩展规则解析。
            if (simpleHoldings && revocation.find()
                    && !containsAny(lower, "bought", "buy back", "买入", "买回")) {
                revokedHoldings.addAll(extractTickers(statement.substring(revocation.end())));
            } else if (simpleHoldings && holding.find()
                    && !containsAny(lower, "sold", "sell", "not", "don't", "卖", "清仓", "不", "没")) {
                holdings.addAll(extractTickers(statement.substring(holding.end())));
            }
            Matcher risk = RISK_STATEMENT.matcher(statement);
            if (risk.find()) {
                String preference = risk.group(1) != null ? risk.group(1)
                        : risk.group(2) != null ? risk.group(2) : risk.group(3).toLowerCase(java.util.Locale.ROOT);
                update.setRiskPreference(switch (preference) {
                    case "保守", "稳健", "低风险", "conservative" -> "conservative";
                    case "激进", "进取", "高风险", "aggressive" -> "aggressive";
                    default -> "moderate";
                });
            }
        }
        update.setHoldings(holdings);
    }

    /**
     * 使用记忆模型从对话中抽取画像字段。
     *
     * <p>成功返回的空数组也是权威判断，调用方不得再用规则补写。</p>
     *
     * @param update 待合并的本轮画像增量
     * @param revokedHoldings 接收用户明确表示已经全部退出的持仓
     * @param userMessage 用户原话
     * @param assistantResponse 助手回答
     */
    private boolean mergeLlmProfile(
            UserProfileDTO update,
            List<String> revokedHoldings,
            String userMessage,
            String assistantResponse,
            String currentSummary
    ) {
        try {
            // 调用独立记忆模型，避免主聊天模型承担后台画像抽取工作。
            String content = memoryChatClient.prompt()
                    .user("""
                            请从本轮对话中提取用户长期投资画像，输出严格 JSON：
                            只依据用户明确自述更新画像；助手回答仅帮助理解指代，不能作为用户持仓或偏好的事实来源。
                            提问、假设、建议和引用他人的情况不代表用户自身情况；无新增信息的字段输出空数组或空字符串。
                            {
                              "holdings": ["用户明确表示当前仍持有的 ticker"],
                              "revokedHoldings": ["用户明确表示已全部退出、不再持有的 ticker；部分卖出、期权交易或建议不要写入"],
                              "riskPreference": "conservative|moderate|aggressive|",
                              "profileSummary": "在现有摘要上合并本轮明确自述后的画像，不超过 80 字；保留未被本轮纠正的旧信息；没有变化则空字符串"
                            }

                            现有画像摘要：
                            %s

                            用户消息：
                            %s

                            助手回答：
                            %s
                            """.formatted(currentSummary == null ? "" : currentSummary,
                            userMessage, assistantResponse == null ? "" : assistantResponse))
                    .call()
                    .content();
            JsonNode root = content == null ? null : objectMapper.readTree(JsonText.extractObject(content));
            if (root == null || !root.isObject()
                    || root.has("riskPreference") && !root.path("riskPreference").isTextual()
                    || root.has("profileSummary") && !root.path("profileSummary").isTextual()) {
                throw new IllegalArgumentException("画像模型返回的 JSON 字段类型不符合约定");
            }
            String riskPreference = root.path("riskPreference").asText("").trim();
            if (!Set.of("", "conservative", "moderate", "aggressive").contains(riskPreference)) {
                throw new IllegalArgumentException("画像模型返回未知风险偏好");
            }
            String profileSummary = root.path("profileSummary").asText("").trim();
            if (profileSummary.length() > 80) {
                profileSummary = profileSummary.substring(0, 80);
            }
            List<String> holdings = readStringArray(root.path("holdings"));
            List<String> revoked = readStringArray(root.path("revokedHoldings"));
            update.setHoldings(holdings);
            update.setRiskPreference(riskPreference);
            update.setProfileSummary(profileSummary);
            revokedHoldings.addAll(revoked);
            return true;
        } catch (Exception e) {
            log.warn("LLM long-term profile extraction failed, using deterministic extraction only: {}", e.getMessage());
            return false;
        }
    }

    /**
     * 读取并归一化 JSON 字符串数组。
     *
     * <p>ticker 会转为大写，便于长期画像中去重和展示。</p>
     *
     * @param node 模型 JSON 中的数组节点
     * @return 去除空值并转为大写的字符串列表
     */
    private List<String> readStringArray(JsonNode node) {
        if (node.isMissingNode()) {
            return new ArrayList<>();
        }
        if (!node.isArray()) {
            throw new IllegalArgumentException("画像模型的持仓字段不是数组");
        }
        List<String> values = new ArrayList<>();
        for (JsonNode item : node) {
            if (!item.isTextual()) {
                throw new IllegalArgumentException("画像模型的持仓数组包含非字符串");
            }
            String value = item.asText().trim().toUpperCase(java.util.Locale.ROOT);
            if (!value.isBlank()) {
                values.add(value);
            }
        }
        return values;
    }

    /**
     * 从文本中提取看起来像美股 ticker 的大写词。
     *
     * <p>会过滤常见英文噪声词和技术缩写，减少把普通单词写入持仓或关注列表的概率。</p>
     *
     * @param text 用户消息
     * @return 候选 ticker 列表
     */
    private List<String> extractTickers(String text) {
        Matcher matcher = TICKER_PATTERN.matcher(text);
        List<String> tickers = new ArrayList<>();
        while (matcher.find()) {
            String candidate = matcher.group(1);
            if (!NOISE_WORDS.contains(candidate)) {
                tickers.add(candidate);
            }
        }
        return tickers;
    }

    /** 判断文本中是否包含任意关键词。 */
    private boolean containsAny(String text, String... keywords) {
        for (String keyword : keywords) {
            if (text.contains(keyword)) {
                return true;
            }
        }
        return false;
    }

    /** 判断增量画像中是否仍有需要确认的事实。 */
    private boolean hasProfileUpdate(UserProfileDTO update) {
        return update.getHoldings() != null && !update.getHoldings().isEmpty()
                || update.getRiskPreference() != null && !update.getRiskPreference().isBlank()
                || update.getProfileSummary() != null && !update.getProfileSummary().isBlank();
    }

    /** 将列表格式化为提示词可读文本。 */
    private String formatList(List<String> values) {
        return values == null || values.isEmpty() ? "none" : String.join(", ", values);
    }
}
