package com.stocksage.service;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

/**
 * 用户级研究记忆的功能开关和检索上限。
 *
 * <p>{@link ResearchMemoryService} 控制捕获、检索和提示词注入，
 * {@link ResearchMemoryVectorIndex} 控制向量索引；默认值在 getter 中收敛到安全范围。</p>
 */
@Component
@ConfigurationProperties(prefix = "stocksage.research-memory")
public class ResearchMemoryProperties {

    /** 是否从完成的投资报告中捕获研究记忆。 */
    private boolean capture;
    /** 是否把捕获结果写入向量集合。 */
    private boolean index;
    /** 是否为新问题检索历史研究记忆。 */
    private boolean retrieve;
    /** 是否把检索结果注入 Agent 提示词。 */
    private boolean inject;
    /** Milvus 中用户研究记忆的集合名。 */
    private String collectionName = "stocksage_user_research_memory_v1";
    /** 每次检索最多返回的记忆数，getter 限制在 1 至 3。 */
    private int topK = 3;
    /** 注入提示词的最大字符数，getter 限制在 200 至 2400。 */
    private int maxPromptChars = 2400;

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
    public int getTopK() { return Math.max(1, Math.min(3, topK)); }
    public void setTopK(int topK) { this.topK = topK; }
    public int getMaxPromptChars() { return Math.max(200, Math.min(2400, maxPromptChars)); }
    public void setMaxPromptChars(int maxPromptChars) { this.maxPromptChars = maxPromptChars; }
}
