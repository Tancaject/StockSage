package com.stocksage.memory;

import com.fasterxml.jackson.annotation.JsonIgnore;
import lombok.Data;
import java.time.LocalDate;
import java.util.List;
import java.util.Map;

/**
 * 注入提示词的长期用户画像。
 *
 * <p>持仓和关注列表由 {@code UserService} 增量合并而不是直接替换，
 * 后续对话可据此生成当前用户专属的个性化提示词。</p>
 */
@Data
public class UserProfileDTO {

    /** 用户标识，也是长期画像持久化记录的主键。 */
    private String userId;

    /** 用户当前关注的持仓列表，可从对话中增量合并。 */
    private List<String> holdings;

    /** 仅供 Prompt 展示事实来源时间，不增加画像 API 的可写字段。 */
    @JsonIgnore
    private Map<String, LocalDate> holdingConfirmedDates = Map.of();

    /** 用户想持续观察但未必已经持有的股票或资产列表。 */
    private List<String> watchList;

    /** 用户风险偏好描述，例如稳健、均衡或进取。 */
    private String riskPreference;

    /** 对用户长期偏好和背景事实的自然语言摘要。 */
    private String profileSummary;
}
