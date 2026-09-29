package com.stocksage.rag;

import com.stocksage.repository.VectorDocumentRepository;
import com.stocksage.knowledge.KnowledgeService;
import lombok.RequiredArgsConstructor;
import org.springframework.ai.document.Document;
import org.springframework.stereotype.Service;

import java.time.LocalDateTime;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * RAG 检索回归夹具服务。
 *
 * <p>该服务先确保知识库存在一条稳定的 NVIDIA AI 战略资料，再用正反查询验证检索边界：
 * 相关问题必须命中该资料，无关估值/技术分析问题不应误召回它。</p>
 */
@Service
@RequiredArgsConstructor
public class RagRegressionService {

    /** 数据库中识别固定回归资料的标题。 */
    private static final String REGRESSION_TITLE = "RAG_REGRESSION_NVIDIA_AI_STRATEGY_2026";

    /** 正文中的稳定标记，用于元数据缺失时识别夹具切片。 */
    private static final String REGRESSION_MARKER = "stocksage-rag-regression-marker-nvidia-ai-strategy";

    /** 正向召回文档必须同时保留的关键概念。 */
    private static final List<String> EXPECTED_TERMS = List.of("CUDA", "AI Enterprise", "GB200");

    /** 应召回固定 NVIDIA 资料的查询。 */
    private static final List<String> POSITIVE_QUERIES = List.of(
            "英伟达 AI 战略为什么强调 CUDA 和全栈平台？",
            "NVIDIA AI Enterprise 在英伟达 AI 战略中扮演什么角色？",
            "GB200 和网络互联为什么是 NVIDIA 数据中心战略的一部分？"
    );

    /** 不应召回该公司资料的通用估值和技术分析查询。 */
    private static final List<String> NEGATIVE_QUERIES = List.of(
            "PE-TTM 和 PB 估值指标有什么区别？",
            "如何用布林带判断短线超买超卖？"
    );

    /** 首次运行时写入知识库的稳定英文夹具正文。 */
    private static final String REGRESSION_CONTENT = """
            %s

            NVIDIA's AI strategy centers on full-stack accelerated computing rather than selling GPUs alone.
            The durable knowledge point for regression is that NVIDIA combines data-center GPUs, CUDA,
            networking, DGX and GB200 systems, AI Enterprise software, and cloud partnerships into one
            platform. This makes the company harder to analyze as a simple semiconductor vendor because
            its moat includes developer ecosystem, software libraries, reference systems, and enterprise
            deployment channels.

            For investment research, the key reusable framework is:
            1. Hardware acceleration: GPU architecture and GB200-class systems drive training and inference capacity.
            2. Software ecosystem: CUDA and AI Enterprise improve switching costs and enterprise adoption.
            3. Systems and networking: NVLink, InfiniBand/Ethernet networking, and DGX-style integrated systems
               make cluster-level performance part of the product.
            4. Cloud and enterprise route-to-market: partnerships with cloud providers and OEMs turn the stack
               into deployable infrastructure.

            This fixture is intentionally stable and should be recalled by questions about NVIDIA AI strategy,
            CUDA, AI Enterprise, GB200, and full-stack accelerated computing. It should not be recalled for
            unrelated valuation glossary or generic technical-analysis questions.
            """.formatted(REGRESSION_MARKER);

    /** 首次运行时保存固定学习型知识。 */
    private final KnowledgeService knowledgeService;

    /** 使用生产同款检索链路执行正反查询。 */
    private final RagService ragService;

    /** 检查夹具是否已入库，保证重复运行幂等。 */
    private final VectorDocumentRepository vectorDocumentRepository;

    /**
     * 执行默认回归套件，并返回适合接口直接展示的诊断结果。
     *
     * @return 夹具写入状态、逐查询结果、总耗时与最终状态
     */
    public Map<String, Object> runDefaultRegression() {
        long startedAt = System.currentTimeMillis();
        boolean alreadySeeded = vectorDocumentRepository.existsByDocName(REGRESSION_TITLE);
        int chunksSaved = 0;
        if (!alreadySeeded) {
            // saveLearnedKnowledge 走正式入库路径，使夹具覆盖真实切片、向量和镜像逻辑。
            chunksSaved = knowledgeService.saveLearnedKnowledge(
                    REGRESSION_TITLE,
                    REGRESSION_CONTENT,
                    "rag-regression-fixture",
                    "company"
            );
        }

        List<Map<String, Object>> positiveChecks = POSITIVE_QUERIES.stream()
                .map(this::runPositiveCheck)
                .toList();
        List<Map<String, Object>> negativeChecks = NEGATIVE_QUERIES.stream()
                .map(this::runNegativeCheck)
                .toList();

        boolean passed = positiveChecks.stream().allMatch(item -> Boolean.TRUE.equals(item.get("passed")))
                && negativeChecks.stream().allMatch(item -> Boolean.TRUE.equals(item.get("passed")));

        Map<String, Object> result = new LinkedHashMap<>();
        result.put("status", passed ? "passed" : "failed");
        result.put("caseTitle", REGRESSION_TITLE);
        result.put("seeded", !alreadySeeded);
        result.put("chunksSaved", chunksSaved);
        result.put("expectedTerms", EXPECTED_TERMS);
        result.put("positiveChecks", positiveChecks);
        result.put("negativeChecks", negativeChecks);
        result.put("durationMs", System.currentTimeMillis() - startedAt);
        result.put("checkedAt", LocalDateTime.now().toString());
        return result;
    }

    /**
     * 校验相关查询是否能召回回归夹具，并包含关键概念词。
     *
     * @param query 应命中夹具的自然语言问题
     * @return 命中数量、预览和关键概念检查
     */
    private Map<String, Object> runPositiveCheck(String query) {
        List<Document> results = ragService.retrieve(query);
        boolean matchedRegressionDoc = results.stream().anyMatch(this::isRegressionDoc);
        String matchedText = results.stream()
                .filter(this::isRegressionDoc)
                .map(Document::getText)
                .findFirst()
                .orElse("");
        boolean containsExpectedTerms = EXPECTED_TERMS.stream().allMatch(matchedText::contains);

        Map<String, Object> check = baseCheck(query, results);
        check.put("passed", matchedRegressionDoc && containsExpectedTerms);
        check.put("matchedRegressionDoc", matchedRegressionDoc);
        check.put("containsExpectedTerms", containsExpectedTerms);
        return check;
    }

    /**
     * 校验无关查询不会误召回回归夹具，用来捕捉检索过宽的问题。
     *
     * @param query 不应命中夹具的自然语言问题
     * @return 命中数量、预览和隔离检查
     */
    private Map<String, Object> runNegativeCheck(String query) {
        List<Document> results = ragService.retrieve(query);
        boolean matchedRegressionDoc = results.stream().anyMatch(this::isRegressionDoc);

        Map<String, Object> check = baseCheck(query, results);
        check.put("passed", !matchedRegressionDoc);
        check.put("matchedRegressionDoc", matchedRegressionDoc);
        return check;
    }

    /**
     * 生成正反检查共用的命中摘要，便于接口直接返回诊断信息。
     *
     * @param query 本次检查查询
     * @param results 生产检索返回的文档
     * @return 查询、命中数和前三条预览
     */
    private Map<String, Object> baseCheck(String query, List<Document> results) {
        Map<String, Object> check = new LinkedHashMap<>();
        check.put("query", query);
        check.put("hitCount", results.size());
        check.put("topHits", results.stream()
                .limit(3)
                .map(this::summarizeHit)
                .toList());
        return check;
    }

    /**
     * 把检索命中压缩成稳定的小对象，避免回归接口返回过长正文。
     *
     * @param document 单个检索命中
     * @return 来源、标题、类型和正文预览
     */
    private Map<String, Object> summarizeHit(Document document) {
        Map<String, Object> hit = new LinkedHashMap<>();
        hit.put("source", document.getMetadata().getOrDefault("source", "unknown"));
        hit.put("title", document.getMetadata().getOrDefault("title", ""));
        hit.put("docType", document.getMetadata().getOrDefault("doc_type", ""));
        hit.put("preview", preview(document.getText(), 180));
        return hit;
    }

    /**
     * 判断命中文档是否属于本回归夹具。
     *
     * @param document 待检查文档
     * @return 标题或正文标记是否匹配夹具
     */
    private boolean isRegressionDoc(Document document) {
        Object title = document.getMetadata().get("title");
        // 标题和正文标记双重判断，兼容不同切片策略下标题元数据缺失的情况。
        return REGRESSION_TITLE.equals(title) || document.getText().contains(REGRESSION_MARKER);
    }

    /**
     * 生成单行预览文本，保留足够上下文但限制接口输出长度。
     *
     * @param text 原始切片正文
     * @param maxLength 最大字符数
     * @return 压缩空白并按上限截断的预览
     */
    private String preview(String text, int maxLength) {
        if (text == null) {
            return "";
        }
        String normalized = text.replaceAll("\\s+", " ").trim();
        return normalized.length() > maxLength ? normalized.substring(0, maxLength) + "..." : normalized;
    }
}
