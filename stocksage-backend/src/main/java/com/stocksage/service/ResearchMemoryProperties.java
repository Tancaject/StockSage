package com.stocksage.service;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

@Component
@ConfigurationProperties(prefix = "stocksage.research-memory")
public class ResearchMemoryProperties {

    private boolean capture;
    private boolean index;
    private boolean retrieve;
    private boolean inject;
    private String collectionName = "stocksage_user_research_memory_v1";
    private int topK = 3;
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
