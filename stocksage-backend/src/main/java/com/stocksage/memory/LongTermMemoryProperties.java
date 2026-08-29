package com.stocksage.memory;

import com.stocksage.model.entity.UserMemoryFact;
import jakarta.validation.constraints.DecimalMax;
import jakarta.validation.constraints.DecimalMin;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;
import org.springframework.validation.annotation.Validated;

/** 长期画像事实的分类型遗忘参数。 */
@Validated
@Component
@ConfigurationProperties(prefix = "stocksage.memory.long-term")
public class LongTermMemoryProperties {

    /** 持仓变化较快，默认 30 天衰减到一半。 */
    @DecimalMin("1.0")
    @DecimalMax("3650.0")
    private double holdingsHalfLifeDays = 30.0;

    /** 风险偏好相对稳定，默认 180 天衰减到一半。 */
    @DecimalMin("1.0")
    @DecimalMax("3650.0")
    private double riskHalfLifeDays = 180.0;

    /** 模型提炼的画像摘要默认 90 天衰减到一半。 */
    @DecimalMin("1.0")
    @DecimalMax("3650.0")
    private double summaryHalfLifeDays = 90.0;

    /** 权重低于该值的事实不再进入用户画像和 Prompt。 */
    @DecimalMin("0.0")
    @DecimalMax("1.0")
    private double minWeight = 0.25;

    public double getHoldingsHalfLifeDays() {
        return holdingsHalfLifeDays;
    }

    public void setHoldingsHalfLifeDays(double holdingsHalfLifeDays) {
        this.holdingsHalfLifeDays = holdingsHalfLifeDays;
    }

    public double getRiskHalfLifeDays() {
        return riskHalfLifeDays;
    }

    public void setRiskHalfLifeDays(double riskHalfLifeDays) {
        this.riskHalfLifeDays = riskHalfLifeDays;
    }

    public double getSummaryHalfLifeDays() {
        return summaryHalfLifeDays;
    }

    public void setSummaryHalfLifeDays(double summaryHalfLifeDays) {
        this.summaryHalfLifeDays = summaryHalfLifeDays;
    }

    public double getMinWeight() {
        return minWeight;
    }

    public void setMinWeight(double minWeight) {
        this.minWeight = minWeight;
    }

    /** 返回指定事实类型的半衰期。 */
    public double halfLifeDays(UserMemoryFact.FactType factType) {
        return switch (factType) {
            case HOLDING -> holdingsHalfLifeDays;
            case RISK_PREFERENCE -> riskHalfLifeDays;
            case PROFILE_SUMMARY -> summaryHalfLifeDays;
        };
    }
}
