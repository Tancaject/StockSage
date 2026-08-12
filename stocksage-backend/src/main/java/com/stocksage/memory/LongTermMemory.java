package com.stocksage.memory;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.stocksage.model.dto.UserProfileDTO;
import com.stocksage.service.UserService;
import lombok.extern.slf4j.Slf4j;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * 持久化的用户投资画像记忆。
 *
 * <p>每次助手回答后，ChatService 会在后台调用该组件提取持仓、关注列表、风险偏好和画像摘要。
 * 只有画像非空时，才会注入后续提示词。</p>
 */
@Slf4j
@Component
public class LongTermMemory {

    /** 识别消息中 1 至 5 位大写美股代码的保守模式。 */
    private static final Pattern TICKER_PATTERN = Pattern.compile("\\b([A-Z]{1,5})\\b");

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

    /** 控制是否在确定性规则之后再调用模型补充画像。 */
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
     * 读取用户长期画像。
     *
     * @param userId 当前用户 ID
     * @return 数据库中的画像；不存在时由 UserService 返回默认画像
     */
    public UserProfileDTO loadUserProfile(String userId) {
        return userService.getUserProfile(userId);
    }

    /**
     * 合并更新用户长期画像，未提供的字段保持原值。
     *
     * @param userId 当前用户 ID
     * @param update 本轮提取出的增量画像
     * @return 持久化后的完整画像
     */
    public UserProfileDTO mergeUserProfile(String userId, UserProfileDTO update) {
        return userService.updateUserProfile(userId, update);
    }

    /**
     * 构造可注入聊天提示词的长期画像上下文。
     *
     * <p>画像为空时返回空字符串，避免无意义的默认信息干扰模型回答。</p>
     *
     * @param userId 当前用户 ID
     * @return 可直接注入 SystemMessage 的画像文本；空画像返回空串
     */
    public String buildPromptContext(String userId) {
        UserProfileDTO profile = loadUserProfile(userId);
        if (isProfileEmpty(profile)) {
            return "";
        }
        return """
                以下是该用户的长期画像。当用户提到"我的持仓""我的关注"或涉及个人投资偏好的问题时，请结合此信息回答。
                - 持仓 (Holdings): %s
                - 关注列表 (Watch list): %s
                - 风险偏好 (Risk preference): %s
                - 画像摘要 (Summary): %s
                """.formatted(
                formatList(profile.getHoldings()),
                formatList(profile.getWatchList()),
                emptyToDefault(profile.getRiskPreference(), "moderate"),
                emptyToDefault(profile.getProfileSummary(), "none")
        ).trim();
    }

    /**
     * 从用户消息中提取持仓、关注列表、风险偏好等信息并更新长期画像。
     * 使用关键词触发 + 正则提取美股代码的确定性方案，零 LLM 调用开销。
     *
     * @param userId 当前用户 ID
     * @param assistantResponse 本轮助手回答，供可选 LLM 抽取补充上下文
     * @param userMessage 本轮用户消息
     */
    public void extractAndUpdate(String userId, String assistantResponse, String userMessage) {
        if (userMessage == null || userMessage.isBlank()) {
            return;
        }

        UserProfileDTO update = new UserProfileDTO();
        boolean hasUpdate = false;
        String lower = userMessage.toLowerCase();

        // 提取持仓
        if (containsAny(lower, "hold", "holding", "bought", "own", "持有", "买了", "买入", "持仓", "仓位")) {
            List<String> tickers = extractTickers(userMessage);
            if (!tickers.isEmpty()) {
                update.setHoldings(tickers);
                hasUpdate = true;
            }
        }

        // watchList 由用户在前端手动管理，不从对话中自动提取

        // 提取风险偏好
        if (containsAny(lower, "conservative", "保守", "稳健", "低风险")) {
            update.setRiskPreference("conservative");
            hasUpdate = true;
        } else if (containsAny(lower, "aggressive", "激进", "进取", "高风险")) {
            update.setRiskPreference("aggressive");
            hasUpdate = true;
        } else if (containsAny(lower, "moderate", "中等风险", "平衡", "均衡")) {
            update.setRiskPreference("moderate");
            hasUpdate = true;
        }

        if (llmProfileEnabled) {
            // 调用 memoryChatClient 补充规则难以识别的摘要，同时保留前面的确定性结果。
            hasUpdate = mergeLlmProfile(update, userMessage, assistantResponse) || hasUpdate;
        }

        if (hasUpdate) {
            mergeUserProfile(userId, update);
            log.info("Extracted and updated long-term memory for userId={}, holdings={}, risk={}",
                    userId, update.getHoldings(), update.getRiskPreference());
        }
    }

    /**
     * 使用记忆模型从对话中抽取画像字段。
     *
     * <p>抽取失败时返回 false，调用方保留正则/关键词规则已经得到的更新结果。</p>
     *
     * @param update 待合并的本轮画像增量
     * @param userMessage 用户原话
     * @param assistantResponse 助手回答
     * @return 是否抽取到了至少一个可持久化字段
     */
    private boolean mergeLlmProfile(UserProfileDTO update, String userMessage, String assistantResponse) {
        try {
            // 调用独立记忆模型，避免主聊天模型承担后台画像抽取工作。
            String content = memoryChatClient.prompt()
                    .user("""
                            请从本轮对话中提取用户长期投资画像，输出严格 JSON：
                            {
                              "holdings": ["用户明确持有的 ticker"],
                              "riskPreference": "conservative|moderate|aggressive|",
                              "profileSummary": "一句话画像摘要；无新增信息则空字符串"
                            }

                            用户消息：
                            %s

                            助手回答：
                            %s
                            """.formatted(userMessage, assistantResponse == null ? "" : assistantResponse))
                    .call()
                    .content();
            JsonNode root = objectMapper.readTree(extractJson(content));
            boolean changed = false;

            List<String> holdings = readStringArray(root.path("holdings"));
            if (!holdings.isEmpty()) {
                update.setHoldings(mergeLists(update.getHoldings(), holdings));
                changed = true;
            }

            // watchList 由用户在前端手动管理，不从 LLM 提取

            String riskPreference = root.path("riskPreference").asText("").trim();
            if (Set.of("conservative", "moderate", "aggressive").contains(riskPreference)) {
                update.setRiskPreference(riskPreference);
                changed = true;
            }

            String profileSummary = root.path("profileSummary").asText("").trim();
            if (!profileSummary.isBlank()) {
                update.setProfileSummary(profileSummary);
                changed = true;
            }
            return changed;
        } catch (Exception e) {
            log.warn("LLM long-term profile extraction failed, using deterministic extraction only: {}", e.getMessage());
            return false;
        }
    }

    /**
     * 从模型输出中提取 JSON 对象正文。
     *
     * @param content 可能带 Markdown 代码围栏的模型输出
     * @return 可交给 ObjectMapper 解析的 JSON 文本
     */
    private String extractJson(String content) {
        if (content == null) {
            return "{}";
        }
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
     * 读取并归一化 JSON 字符串数组。
     *
     * <p>ticker 会转为大写，便于长期画像中去重和展示。</p>
     *
     * @param node 模型 JSON 中的数组节点
     * @return 去除空值并转为大写的字符串列表
     */
    private List<String> readStringArray(JsonNode node) {
        if (node == null || !node.isArray()) {
            return List.of();
        }
        List<String> values = new ArrayList<>();
        for (JsonNode item : node) {
            String value = item.asText("").trim();
            if (!value.isBlank()) {
                values.add(value.toUpperCase());
            }
        }
        return values;
    }

    /**
     * 合并已有列表和新增列表，并保持插入顺序去重。
     *
     * @param current 已有值
     * @param incoming 本轮新增值
     * @return 保持原顺序的去重列表
     */
    private List<String> mergeLists(List<String> current, List<String> incoming) {
        LinkedHashSet<String> merged = new LinkedHashSet<>();
        if (current != null) {
            current.stream().filter(value -> value != null && !value.isBlank()).map(String::trim).forEach(merged::add);
        }
        if (incoming != null) {
            incoming.stream().filter(value -> value != null && !value.isBlank()).map(String::trim).forEach(merged::add);
        }
        return new ArrayList<>(merged);
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

    /** 判断用户画像是否仍处于默认空状态。 */
    private boolean isProfileEmpty(UserProfileDTO profile) {
        boolean noHoldings = profile.getHoldings() == null || profile.getHoldings().isEmpty();
        boolean noWatchList = profile.getWatchList() == null || profile.getWatchList().isEmpty();
        boolean defaultRisk = "moderate".equals(profile.getRiskPreference());
        boolean noSummary = profile.getProfileSummary() == null || profile.getProfileSummary().isBlank();
        return noHoldings && noWatchList && defaultRisk && noSummary;
    }

    /** 将列表格式化为提示词可读文本。 */
    private String formatList(List<String> values) {
        return values == null || values.isEmpty() ? "none" : String.join(", ", values);
    }

    /** 空字符串兜底。 */
    private String emptyToDefault(String value, String fallback) {
        return value == null || value.isBlank() ? fallback : value;
    }
}
