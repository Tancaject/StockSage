package com.stocksage.model.dto;

/**
 * 知识摄取路径返回的汇总结果。
 *
 * <p>{@code chunksParsed} 表示解析出的源切片数量，
 * {@code chunksIngested} 表示实际写入 Milvus 的向量数量。
 * 父级切片可能会索引到 MySQL，但会刻意跳过向量化。</p>
 *
 * @param sourceId 本次摄取来源的稳定 ID
 * @param status 摄取状态，例如 ingested、skipped_unchanged 或 indexed_without_new_vectors
 * @param chunksParsed 从原始来源解析出的切片数量
 * @param chunksIngested 实际写入向量库的新切片数量
 * @param chunksSkippedAsDuplicates 因重复而跳过的切片数量
 * @param hashUnchanged 来源哈希是否与上次一致
 */
public record KnowledgeIngestionResult(
        String sourceId,
        String status,
        int chunksParsed,
        int chunksIngested,
        int chunksSkippedAsDuplicates,
        boolean hashUnchanged
) {
    /**
     * 构造“来源未变化，因此跳过摄取”的结果。
     *
     * @param sourceId 来源 ID
     * @param existingChunks 已存在的切片数量
     * @return 跳过摄取结果
     */
    public static KnowledgeIngestionResult skippedUnchanged(String sourceId, int existingChunks) {
        return new KnowledgeIngestionResult(sourceId, "skipped_unchanged", existingChunks, 0, 0, true);
    }

    /**
     * 构造完成摄取后的汇总结果。
     *
     * <p>当解析出切片但没有新增向量时，状态会标记为 indexed_without_new_vectors，
     * 方便调用方区分“没有内容”和“内容已索引但无需新增向量”。</p>
     *
     * @param sourceId 来源稳定 ID
     * @param chunksParsed 从来源解析出的切片总数
     * @param chunksIngested 实际新增到向量库的切片数
     * @param chunksSkippedAsDuplicates 因内容重复而跳过的切片数
     * @return 包含状态和计数的摄取汇总
     */
    public static KnowledgeIngestionResult ingested(
            String sourceId,
            int chunksParsed,
            int chunksIngested,
            int chunksSkippedAsDuplicates) {
        return new KnowledgeIngestionResult(
                sourceId,
                chunksIngested > 0 ? "ingested" : "indexed_without_new_vectors",
                chunksParsed,
                chunksIngested,
                chunksSkippedAsDuplicates,
                false
        );
    }
}
