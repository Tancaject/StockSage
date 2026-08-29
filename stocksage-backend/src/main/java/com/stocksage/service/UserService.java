package com.stocksage.service;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.stocksage.memory.LongTermMemoryProperties;
import com.stocksage.model.dto.UserProfileDTO;
import com.stocksage.model.entity.UserMemoryFact;
import com.stocksage.model.entity.UserProfile;
import com.stocksage.repository.UserMemoryFactRepository;
import com.stocksage.repository.UserProfileRepository;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Clock;
import java.time.Duration;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Objects;
import java.util.Optional;

/**
 * 长期用户画像的增删改查、事实确认和查询时遗忘逻辑。
 *
 * <p>{@code user_memory_facts} 是持仓、风险偏好和画像摘要的可见性真源；
 * {@code user_profiles} 保留用户显式管理的关注列表及兼容投影。</p>
 */
@Service
public class UserService {

    /** ObjectMapper 读取字符串数组时保留泛型类型信息。 */
    private static final TypeReference<List<String>> STRING_LIST = new TypeReference<>() {
    };
    private static final String RISK_PREFERENCE_KEY = "risk_preference";
    private static final String PROFILE_SUMMARY_KEY = "profile_summary";

    /** 读写用户画像实体。 */
    private final UserProfileRepository userProfileRepository;
    /** 保存每条学习事实自己的确认时间和撤销状态。 */
    private final UserMemoryFactRepository userMemoryFactRepository;
    /** 在数据库 JSON 字符串与 Java 列表间转换。 */
    private final ObjectMapper objectMapper;
    /** 分类型半衰期与 Prompt 注入阈值。 */
    private final LongTermMemoryProperties memoryProperties;
    /** 可替换时钟让衰减边界可确定验证。 */
    private final Clock clock;

    @Autowired
    public UserService(
            UserProfileRepository userProfileRepository,
            UserMemoryFactRepository userMemoryFactRepository,
            ObjectMapper objectMapper,
            LongTermMemoryProperties memoryProperties
    ) {
        this(userProfileRepository, userMemoryFactRepository, objectMapper,
                memoryProperties, Clock.systemDefaultZone());
    }

    /** 定向测试可注入固定时钟；生产使用系统默认时区。 */
    UserService(
            UserProfileRepository userProfileRepository,
            UserMemoryFactRepository userMemoryFactRepository,
            ObjectMapper objectMapper,
            LongTermMemoryProperties memoryProperties,
            Clock clock
    ) {
        this.userProfileRepository = Objects.requireNonNull(userProfileRepository, "userProfileRepository");
        this.userMemoryFactRepository = Objects.requireNonNull(userMemoryFactRepository, "userMemoryFactRepository");
        this.objectMapper = Objects.requireNonNull(objectMapper, "objectMapper");
        this.memoryProperties = Objects.requireNonNull(memoryProperties, "memoryProperties");
        this.clock = Objects.requireNonNull(clock, "clock");
    }

    /**
     * 加载画像；首次使用时生成默认提示词画像。
     *
     * @param userId 用户 ID
     * @return 完整画像 DTO；不存在时返回未落库的默认画像
     */
    @Transactional(readOnly = true)
    public UserProfileDTO getUserProfile(String userId) {
        UserProfile profile = userProfileRepository.findById(userId)
                .orElseGet(() -> defaultProfile(userId));
        return toDto(profile, usableFacts(userId), true);
    }

    /** Prompt 专用画像不把已遗忘的风险偏好替换成产品默认值。 */
    @Transactional(readOnly = true)
    public UserProfileDTO getPromptProfile(String userId) {
        UserProfile profile = userProfileRepository.findById(userId)
                .orElseGet(() -> defaultProfile(userId));
        return toDto(profile, usableFacts(userId), false);
    }

    /**
     * 将新事实合并进已有持仓和关注列表，而不是直接替换，从而保留早前会话学到的记忆。
     *
     * @param userId 用户 ID
     * @param dto 增量画像；null 字段表示保持原值
     * @return 合并并持久化后的完整画像
     */
    @Transactional
    public UserProfileDTO updateUserProfile(String userId, UserProfileDTO dto) {
        return updateUserProfile(userId, dto, null, LocalDateTime.now(clock));
    }

    /**
     * 合并画像并记录本轮事实来源；重复出现同一事实即重新确认并恢复其权重。
     *
     * @param userId 用户 ID
     * @param dto 本轮增量画像
     * @param sourceMessageId 产生事实的用户消息；显式 API 更新允许为空
     * @param observedAt 用户表达该事实的时间，用于拒绝乱序异步写入
     * @return 应用当前遗忘策略后的画像
     */
    @Transactional
    public UserProfileDTO updateUserProfile(
            String userId,
            UserProfileDTO dto,
            Long sourceMessageId,
            LocalDateTime observedAt
    ) {
        UserProfile profile = userProfileRepository.findById(userId)
                .orElseGet(() -> defaultProfile(userId));
        LocalDateTime confirmedAt = Objects.requireNonNull(observedAt, "observedAt");

        if (dto.getHoldings() != null) {
            profile.setHoldings(writeJsonArray(merge(readJsonArray(profile.getHoldings()), dto.getHoldings())));
            dto.getHoldings().stream()
                    .filter(this::hasText)
                    .forEach(ticker -> confirmHolding(userId, ticker, confirmedAt, sourceMessageId));
        }
        if (dto.getWatchList() != null) {
            profile.setWatchList(writeJsonArray(merge(readJsonArray(profile.getWatchList()), dto.getWatchList())));
        }
        if (hasText(dto.getRiskPreference())) {
            String riskPreference = dto.getRiskPreference().trim();
            profile.setRiskPreference(riskPreference);
            confirmFact(userId, UserMemoryFact.FactType.RISK_PREFERENCE,
                    RISK_PREFERENCE_KEY, riskPreference, confirmedAt, sourceMessageId);
        }
        if (hasText(dto.getProfileSummary())) {
            String profileSummary = dto.getProfileSummary().trim();
            profile.setProfileSummary(profileSummary);
            confirmFact(userId, UserMemoryFact.FactType.PROFILE_SUMMARY,
                    PROFILE_SUMMARY_KEY, profileSummary, confirmedAt, sourceMessageId);
        }

        UserProfile saved = userProfileRepository.save(profile);
        return toDto(saved, usableFacts(userId), true);
    }

    /**
     * 将画像实体转换为前端 DTO。
     *
     * @param profile 数据库实体
     * @return 解析 JSON 列表后的 DTO
     */
    private UserProfileDTO toDto(
            UserProfile profile,
            List<UserMemoryFact> facts,
            boolean includeDefaultRisk
    ) {
        UserProfileDTO dto = new UserProfileDTO();
        dto.setUserId(profile.getUserId());
        dto.setHoldings(facts.stream()
                .filter(fact -> fact.getFactType() == UserMemoryFact.FactType.HOLDING)
                .map(UserMemoryFact::getFactValue)
                .filter(this::hasText)
                .distinct()
                .toList());
        dto.setWatchList(readJsonArray(profile.getWatchList()));
        dto.setRiskPreference(factValue(facts, UserMemoryFact.FactType.RISK_PREFERENCE)
                .orElse(includeDefaultRisk ? "moderate" : ""));
        dto.setProfileSummary(factValue(facts, UserMemoryFact.FactType.PROFILE_SUMMARY)
                .orElse(""));
        return dto;
    }

    /**
     * 构造默认画像实体。
     *
     * <p>默认风险偏好使用 moderate，其余列表为空，表示还没有从对话中学习到个性化事实。</p>
     *
     * @param userId 用户 ID
     * @return 尚未持久化的默认实体
     */
    private UserProfile defaultProfile(String userId) {
        UserProfile profile = new UserProfile();
        profile.setUserId(userId);
        profile.setHoldings("[]");
        profile.setWatchList("[]");
        profile.setRiskPreference("moderate");
        profile.setProfileSummary("");
        return profile;
    }

    /**
     * 读取 JSON 字符串数组。
     *
     * @param json 数据库中的 JSON 数组文本
     * @return 可修改字符串列表
     */
    private List<String> readJsonArray(String json) {
        if (!hasText(json)) {
            return new ArrayList<>();
        }
        try {
            return objectMapper.readValue(json, STRING_LIST);
        } catch (JsonProcessingException ex) {
            throw new IllegalStateException("Invalid user profile JSON array: " + json, ex);
        }
    }

    /**
     * 将字符串列表写为 JSON 数组。
     *
     * @param values 持仓或关注列表
     * @return 数据库存储使用的 JSON 文本
     */
    private String writeJsonArray(List<String> values) {
        try {
            return objectMapper.writeValueAsString(values);
        } catch (JsonProcessingException ex) {
            throw new IllegalStateException("Unable to write user profile JSON array", ex);
        }
    }

    /** 从用户显式维护的关注列表删除 ticker；不修改独立的持仓事实。 */
    @Transactional
    public void removeFromWatchList(String userId, String ticker) {
        UserProfile profile = userProfileRepository.findById(userId)
                .orElseGet(() -> defaultProfile(userId));
        // 前端 normalizeTicker 会去掉非字母数字（港股 "0700.HK" → "0700HK"），删除时只拿得到归一形式；
        // 这里也按同一规则归一后比较，否则带点/后缀的港股与库里原始值不相等，永远删不掉、刷新又复活。
        String target = canonicalTicker(ticker);
        if (target.isEmpty()) {
            return;
        }
        List<String> watch = readJsonArray(profile.getWatchList());
        watch.removeIf(t -> canonicalTicker(t).equals(target));
        profile.setWatchList(writeJsonArray(watch));
        userProfileRepository.save(profile);
    }

    /**
     * 撤销用户明确表示已经卖出的持仓事实，并同步兼容画像投影。
     *
     * @param userId 用户 ID
     * @param tickers 用户明确否认仍持有的 ticker
     * @param sourceMessageId 负向事实来源消息
     * @param observedAt 用户表达撤销的时间
     */
    @Transactional
    public void revokeHoldings(
            String userId,
            List<String> tickers,
            Long sourceMessageId,
            LocalDateTime observedAt
    ) {
        if (tickers == null || tickers.isEmpty()) {
            return;
        }
        UserProfile profile = userProfileRepository.findById(userId)
                .orElseGet(() -> defaultProfile(userId));
        List<String> holdings = readJsonArray(profile.getHoldings());
        LocalDateTime revokedAt = Objects.requireNonNull(observedAt, "observedAt");

        for (String ticker : tickers) {
            String factKey = canonicalTicker(ticker);
            if (factKey.isEmpty()) {
                continue;
            }
            holdings.removeIf(value -> canonicalTicker(value).equals(factKey));
            userMemoryFactRepository.revokeHolding(
                    userId, factKey, ticker.trim().toUpperCase(), revokedAt, sourceMessageId);
        }
        profile.setHoldings(writeJsonArray(holdings));
        userProfileRepository.save(profile);
    }

    /**
     * 把某 ticker 加入关注列表并持久化；已存在（按归一形式判重）则跳过。
     *
     * <p>此前"添加自选"只改前端本地状态、没落库，刷新时被服务端 profile 覆盖而消失；
     * 这里补齐与删除对称的持久化，让服务端 profile 成为自选的唯一真源。</p>
     *
     * @param userId 用户 ID
     * @param ticker 要保存的展示代码
     */
    @Transactional
    public void addToWatchList(String userId, String ticker) {
        String target = canonicalTicker(ticker);
        if (target.isEmpty()) {
            return;
        }
        UserProfile profile = userProfileRepository.findById(userId)
                .orElseGet(() -> defaultProfile(userId));
        List<String> watch = readJsonArray(profile.getWatchList());
        boolean exists = watch.stream().anyMatch(t -> canonicalTicker(t).equals(target));
        if (!exists) {
            watch.add(ticker.trim());
            profile.setWatchList(writeJsonArray(watch));
            userProfileRepository.save(profile);
        }
    }

    /** 与前端 normalizeTicker 对齐的代码归一：大写并去掉非字母数字，用于宽松匹配删除目标。 */
    private String canonicalTicker(String value) {
        return value == null ? "" : value.trim().toUpperCase().replaceAll("[^A-Z0-9]", "");
    }

    /** 确认一条持仓事实；代码归一后作为类型内唯一键。 */
    private void confirmHolding(
            String userId,
            String ticker,
            LocalDateTime confirmedAt,
            Long sourceMessageId
    ) {
        String factKey = canonicalTicker(ticker);
        if (!factKey.isEmpty()) {
            confirmFact(userId, UserMemoryFact.FactType.HOLDING, factKey,
                    ticker.trim().toUpperCase(), confirmedAt, sourceMessageId);
        }
    }

    /** 原子确认一条事实，重复确认刷新时间并清除撤销。 */
    private void confirmFact(
            String userId,
            UserMemoryFact.FactType factType,
            String factKey,
            String factValue,
            LocalDateTime confirmedAt,
            Long sourceMessageId
    ) {
        userMemoryFactRepository.confirm(userId, factType.name(), factKey, factValue,
                confirmedAt, sourceMessageId);
    }

    /** 查询时应用指数半衰期；低权重事实只退出可见画像，不删除真相行。 */
    private List<UserMemoryFact> usableFacts(String userId) {
        LocalDateTime now = LocalDateTime.now(clock);
        return userMemoryFactRepository.findByUserIdAndRevokedAtIsNullOrderByIdAsc(userId).stream()
                .filter(fact -> forgettingWeight(fact, now) >= memoryProperties.getMinWeight())
                .toList();
    }

    private double forgettingWeight(UserMemoryFact fact, LocalDateTime now) {
        if (fact.getLastConfirmedAt() == null || fact.getFactType() == null) {
            return 0.0;
        }
        long ageDays = fact.getLastConfirmedAt().isAfter(now)
                ? 0L
                : Duration.between(fact.getLastConfirmedAt(), now).toDays();
        return Math.pow(2.0, -(double) ageDays / memoryProperties.halfLifeDays(fact.getFactType()));
    }

    private Optional<String> factValue(List<UserMemoryFact> facts, UserMemoryFact.FactType type) {
        return facts.stream()
                .filter(fact -> fact.getFactType() == type)
                .map(UserMemoryFact::getFactValue)
                .filter(this::hasText)
                .reduce((first, second) -> second);
    }

    /**
     * 合并已有列表和新增列表，保持顺序并去重。
     *
     * @param current 数据库已有列表
     * @param incoming 本轮增量列表
     * @return 保持首次出现顺序的去重结果
     */
    private List<String> merge(List<String> current, List<String> incoming) {
        LinkedHashSet<String> merged = new LinkedHashSet<>();
        current.stream().filter(this::hasText).map(String::trim).forEach(merged::add);
        incoming.stream().filter(this::hasText).map(String::trim).forEach(merged::add);
        return new ArrayList<>(merged);
    }

    /**
     * 判断字符串是否包含非空白内容。
     */
    private boolean hasText(String value) {
        return value != null && !value.trim().isEmpty();
    }
}
