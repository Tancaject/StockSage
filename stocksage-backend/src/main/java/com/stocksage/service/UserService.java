package com.stocksage.service;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.stocksage.model.dto.UserProfileDTO;
import com.stocksage.model.entity.UserProfile;
import com.stocksage.repository.UserProfileRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Optional;

/**
 * 长期用户画像的增删改查和合并逻辑。
 *
 * <p>画像刻意使用简单的 JSON 数组和字符串，让演示系统无需引入完整账号体系也能保留个性化信息。</p>
 */
@Service
@RequiredArgsConstructor
public class UserService {

    private static final TypeReference<List<String>> STRING_LIST = new TypeReference<>() {
    };

    private final UserProfileRepository userProfileRepository;
    private final ObjectMapper objectMapper;

    /**
     * 加载画像；首次使用时生成默认提示词画像。
     */
    @Transactional(readOnly = true)
    public UserProfileDTO getUserProfile(String userId) {
        UserProfile profile = userProfileRepository.findById(userId)
                .orElseGet(() -> defaultProfile(userId));
        return toDto(profile);
    }

    /**
     * 将新事实合并进已有持仓和关注列表，而不是直接替换，从而保留早前会话学到的记忆。
     */
    @Transactional
    public UserProfileDTO updateUserProfile(String userId, UserProfileDTO dto) {
        UserProfile profile = userProfileRepository.findById(userId)
                .orElseGet(() -> defaultProfile(userId));

        if (dto.getHoldings() != null) {
            profile.setHoldings(writeJsonArray(merge(readJsonArray(profile.getHoldings()), dto.getHoldings())));
        }
        if (dto.getWatchList() != null) {
            profile.setWatchList(writeJsonArray(merge(readJsonArray(profile.getWatchList()), dto.getWatchList())));
        }
        if (hasText(dto.getRiskPreference())) {
            profile.setRiskPreference(dto.getRiskPreference().trim());
        }
        if (hasText(dto.getProfileSummary())) {
            profile.setProfileSummary(dto.getProfileSummary().trim());
        }

        return toDto(userProfileRepository.save(profile));
    }

    /**
     * 将画像实体转换为前端 DTO。
     */
    private UserProfileDTO toDto(UserProfile profile) {
        UserProfileDTO dto = new UserProfileDTO();
        dto.setUserId(profile.getUserId());
        dto.setHoldings(readJsonArray(profile.getHoldings()));
        dto.setWatchList(readJsonArray(profile.getWatchList()));
        dto.setRiskPreference(Optional.ofNullable(profile.getRiskPreference()).orElse("moderate"));
        dto.setProfileSummary(Optional.ofNullable(profile.getProfileSummary()).orElse(""));
        return dto;
    }

    /**
     * 构造默认画像实体。
     *
     * <p>默认风险偏好使用 moderate，其余列表为空，表示还没有从对话中学习到个性化事实。</p>
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
     */
    private String writeJsonArray(List<String> values) {
        try {
            return objectMapper.writeValueAsString(values);
        } catch (JsonProcessingException ex) {
            throw new IllegalStateException("Unable to write user profile JSON array", ex);
        }
    }

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
        List<String> hold = readJsonArray(profile.getHoldings());
        hold.removeIf(t -> canonicalTicker(t).equals(target));
        profile.setHoldings(writeJsonArray(hold));
        userProfileRepository.save(profile);
    }

    /**
     * 把某 ticker 加入关注列表并持久化；已存在（按归一形式判重）则跳过。
     *
     * <p>此前"添加自选"只改前端本地状态、没落库，刷新时被服务端 profile 覆盖而消失；
     * 这里补齐与删除对称的持久化，让服务端 profile 成为自选的唯一真源。</p>
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

    /**
     * 合并已有列表和新增列表，保持顺序并去重。
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
