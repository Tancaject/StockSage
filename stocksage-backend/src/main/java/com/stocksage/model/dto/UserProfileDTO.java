package com.stocksage.model.dto;

import lombok.Data;
import java.util.List;

/**
 * 注入提示词的长期用户画像。
 *
 * <p>持仓、关注列表和风险偏好会被合并而不是替换，
 * 这样无需登录也能在后续对话中个性化分析。</p>
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
