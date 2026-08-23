package com.stocksage.service;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

/**
 * 用户级研究记忆的功能开关、分阶段候选上限和时间衰减参数。
 *
 * <p>{@link ResearchMemoryService} 控制捕获、检索和提示词注入，
 * {@link ResearchMemoryVectorIndex} 控制向量索引；默认值在 getter 中收敛到安全范围。</p>
 */
@Component
@ConfigurationProperties(prefix = "stocksage.research-memory")
public class ResearchMemoryProperties {

    /** 是否从完成的投资报告中捕获研究记忆。 */
    private boolean capture = true;
    /** 是否把捕获结果写入向量集合。 */
    private boolean index = true;
    /** 是否为新问题检索历史研究记忆。 */
    private boolean retrieve = true;
    /** 是否把检索结果注入 Agent 提示词。 */
    private boolean inject = true;
    /** Milvus 中用户研究记忆的集合名。 */
    private String collectionName = "stocksage_user_research_memory_v1";
    /** Milvus 初始语义召回候选数。 */
    private int candidateK = 30;
    /** 真源、冲突和衰减处理后的短名单上限。 */
    private int shortlistK = 12;
    /** BRIEF 分析最多使用的记忆数。 */
    private int briefTopK = 4;
    /** STANDARD 或未指定分析深度最多使用的记忆数。 */
    private int topK = 6;
    /** DEEP 或明确历史分析最多使用的记忆数。 */
    private int deepTopK = 8;
    /** 注入提示词的总字符预算。 */
    private int maxPromptChars = 4800;
    /** 检索时指数时间衰减的统一半衰期天数。 */
    private double decayHalfLifeDays = 90.0;
    /** 有效分最低阈值；live 数据校准前保留 0.0。 */
    private double minEffectiveScore;

    public boolean isCapture() { return capture; }
    public void setCapture(boolean capture) { this.capture = capture; }
    public boolean isIndex() { return index; }
    public void setIndex(boolean index) { this.index = index; }
    public boolean isRetrieve() { return retrieve; }
    public void setRetrieve(boolean retrieve) { this.retrieve = retrieve; }
    public boolean isInject() { return inject; }
    public void setInject(boolean inject) { this.inject = inject; }
    public String getCollectionName() { return collectionName; }
    public void setCollectionName(String collectionName) { this.collectionName = collectionName; }
    public int getCandidateK() { return clamp(candidateK, 1, 100); }
    public void setCandidateK(int candidateK) { this.candidateK = candidateK; }
    public int getShortlistK() { return Math.min(clamp(shortlistK, 1, 50), getCandidateK()); }
    public void setShortlistK(int shortlistK) { this.shortlistK = shortlistK; }
    public int getBriefTopK() { return Math.min(clamp(briefTopK, 1, 20), getShortlistK()); }
    public void setBriefTopK(int briefTopK) { this.briefTopK = briefTopK; }
    public int getTopK() { return Math.min(clamp(topK, 1, 20), getShortlistK()); }
    public void setTopK(int topK) { this.topK = topK; }
    public int getDeepTopK() { return Math.min(clamp(deepTopK, 1, 20), getShortlistK()); }
    public void setDeepTopK(int deepTopK) { this.deepTopK = deepTopK; }
    public int getMaxPromptChars() { return clamp(maxPromptChars, 200, 12000); }
    public void setMaxPromptChars(int maxPromptChars) { this.maxPromptChars = maxPromptChars; }
    public double getDecayHalfLifeDays() {
        return clampFinite(decayHalfLifeDays, 1.0, 3650.0, 90.0);
    }
    public void setDecayHalfLifeDays(double decayHalfLifeDays) {
        this.decayHalfLifeDays = decayHalfLifeDays;
    }
    public double getMinEffectiveScore() {
        return clampFinite(minEffectiveScore, 0.0, 1.0, 0.0);
    }
    public void setMinEffectiveScore(double minEffectiveScore) {
        this.minEffectiveScore = minEffectiveScore;
    }

    private static int clamp(int value, int minimum, int maximum) {
        return Math.max(minimum, Math.min(maximum, value));
    }

    private static double clampFinite(
            double value,
            double minimum,
            double maximum,
            double fallback
    ) {
        if (!Double.isFinite(value)) {
            return fallback;
        }
        return Math.max(minimum, Math.min(maximum, value));
    }
}
