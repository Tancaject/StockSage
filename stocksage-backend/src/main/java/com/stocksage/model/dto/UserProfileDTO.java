package com.stocksage.model.dto;

import lombok.Data;
import java.util.List;

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

    /** 用户想持续观察但未必已经持有的股票或资产列表。 */
    private List<String> watchList;

    /** 用户风险偏好描述，例如稳健、均衡或进取。 */
    private String riskPreference;

    /** 对用户长期偏好和背景事实的自然语言摘要。 */
    private String profileSummary;
}
