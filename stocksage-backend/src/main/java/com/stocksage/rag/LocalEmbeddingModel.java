package com.stocksage.rag;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.extern.slf4j.Slf4j;
import org.springframework.ai.document.Document;
import org.springframework.ai.embedding.Embedding;
import org.springframework.ai.embedding.EmbeddingModel;
import org.springframework.ai.embedding.EmbeddingRequest;
import org.springframework.ai.embedding.EmbeddingResponse;
import org.springframework.web.reactive.function.client.WebClient;
import org.springframework.web.reactive.function.client.WebClientResponseException;

import java.text.Normalizer;
import java.time.Duration;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.regex.Pattern;

/**
 * 本地向量模型 —— 通过 Ollama 接口调用 bge-m3。
 *
 * 为什么不用 DashScope 向量服务？
 * - DashScope 有额度限制，超出收费
 * - bge-m3 可以本地运行在 GPU 上，零成本
 * - bge-m3 的中英跨语言能力与 text-embedding-v4 接近
 *
 * 为什么不用 spring-ai-ollama 依赖？
 * - 避免与 spring-ai-alibaba 的自动配置冲突
 * - Ollama 向量接口非常简单，直接调 HTTP 即可
 *
 * NaN 防护：
 * Ollama 的 llama.cpp 后端处理 BERT embedding 模型时，某些输入文本
 * 会导致输出向量包含 NaN，Go 的 JSON 编码器无法序列化 NaN 会返回 500。
 * 防护策略：预处理文本 + 截断 + 批量失败时逐条重试。
 */
@Slf4j
public class LocalEmbeddingModel implements EmbeddingModel {

    private static final Pattern CONTROL_CHARS = Pattern.compile("[\\x00-\\x08\\x0B\\x0C\\x0E-\\x1F\\x7F]");
    private static final Pattern WHITESPACE = Pattern.compile("\\s+");
    private static final Pattern NON_ASCII_PRINTABLE = Pattern.compile("[^\\x20-\\x7E]");
    private static final int NAN_WARNING_SAMPLE_RATE = 100;

    private final WebClient webClient;
    private final String model;
    private final ObjectMapper objectMapper;
    private final Duration timeout;
    private final int fallbackDimension;
    private final AtomicInteger nanFallbackCount = new AtomicInteger();
    private volatile int observedDimension;

    /**
     * 使用默认 1024 维兜底向量初始化本地向量模型。
     */
    public LocalEmbeddingModel(String baseUrl, String model, Duration timeout) {
        this(baseUrl, model, timeout, 1024);
    }

    /**
     * 初始化 Ollama 向量模型客户端。
     *
     * @param baseUrl Ollama 服务地址
     * @param model 向量模型名称
     * @param timeout 单次请求超时时间
     * @param fallbackDimension 生成零向量时使用的兜底维度
     */
    public LocalEmbeddingModel(String baseUrl, String model, Duration timeout, int fallbackDimension) {
        this.webClient = WebClient.builder().baseUrl(baseUrl).build();
        this.model = model;
        this.timeout = timeout;
        this.fallbackDimension = fallbackDimension;
        this.observedDimension = fallbackDimension;
        this.objectMapper = new ObjectMapper();
        log.info("LocalEmbeddingModel initialized: base={}, model={}, fallbackDimension={}",
                baseUrl, model, fallbackDimension);
    }

    /**
     * 对单个 Spring AI Document 生成向量。
     */
    @Override
    public float[] embed(Document document) {
        return embed(document.getText());
    }

    /**
     * 批量生成向量。
     *
     * <p>请求前会先做文本清洗；如果批量调用出现 NaN 或数量不匹配，则自动退回逐条向量化。</p>
     */
    @Override
    public EmbeddingResponse call(EmbeddingRequest request) {
        List<String> texts = request.getInstructions();
        if (texts == null || texts.isEmpty()) {
            return new EmbeddingResponse(List.of());
        }

        // 预处理：去控制字符，替换空白文本
        List<String> sanitized = texts.stream()
                .map(this::sanitizeText)
                .toList();

        try {
            EmbeddingResponse response = doEmbed(sanitized);
            if (response.getResults().size() != sanitized.size()) {
                log.warn("Ollama embedding returned {}/{} vectors; falling back to one-by-one",
                        response.getResults().size(), sanitized.size());
                return embedOneByOne(sanitized);
            }
            return response;
        } catch (WebClientResponseException e) {
            String body = e.getResponseBodyAsString();
            if (isInvalidEmbeddingError(body)) {
                log.debug("Ollama invalid embedding error on batch of {} texts, falling back to one-by-one: {}",
                        sanitized.size(), summarizeError(body));
                return embedOneByOne(sanitized);
            }
            log.error("Ollama embedding HTTP error: status={}, body={}", e.getStatusCode(), body);
            throw new RuntimeException("Embedding generation failed: " + e.getStatusCode() + " - " + body, e);
        } catch (InvalidEmbeddingException e) {
            log.debug("Ollama returned invalid embedding values on batch of {} texts, falling back to one-by-one: {}",
                    sanitized.size(), e.getMessage());
            return embedOneByOne(sanitized);
        } catch (Exception e) {
            log.error("Ollama embedding call failed: {}", e.getMessage(), e);
            throw new RuntimeException("Embedding generation failed: " + e.getMessage(), e);
        }
    }

    /**
     * 判断 Ollama 响应是否属于无效向量错误。
     */
    private boolean isInvalidEmbeddingError(String body) {
        if (body == null) {
            return false;
        }
        String normalized = body.toLowerCase();
        return normalized.contains("nan")
                || normalized.contains("embedding contains")
                || normalized.contains("unsupported value")
                || normalized.contains("invalid floating");
    }

    /**
     * 压缩错误响应，避免日志输出过长 HTML 或 JSON。
     */
    private String summarizeError(String body) {
        if (body == null || body.isBlank()) {
            return "";
        }
        String singleLine = body.replace('\r', ' ').replace('\n', ' ').strip();
        return singleLine.substring(0, Math.min(180, singleLine.length()));
    }

    /**
     * 调用 Ollama /api/embed 接口并解析响应。
     */
    private EmbeddingResponse doEmbed(List<String> texts) {
        int totalChars = texts.stream().mapToInt(String::length).sum();
        int maxChars = texts.stream().mapToInt(String::length).max().orElse(0);
        log.debug("Ollama embedding request: {} texts, totalChars={}, maxChars={}", texts.size(), totalChars, maxChars);

        Map<String, Object> body = new HashMap<>();
        body.put("model", model);
        body.put("input", texts);
        body.put("truncate", true);

        String response = webClient.post()
                .uri("/api/embed")
                .header("Content-Type", "application/json")
                .bodyValue(body)
                .retrieve()
                .bodyToMono(String.class)
                .timeout(timeout)
                .block();

        return parseEmbeddingResponse(response, texts.size());
    }

    /**
     * 批量向量化产生 NaN 时，逐条重试。
     * 对产生 NaN 的文本记录警告并跳过（返回零向量），不阻塞整体入库流程。
     */
    private EmbeddingResponse embedOneByOne(List<String> texts) {
        List<Embedding> embeddings = new ArrayList<>();

        for (int i = 0; i < texts.size(); i++) {
            try {
                EmbeddingResponse resp = doEmbed(List.of(texts.get(i)));
                if (resp.getResults().size() == 1) {
                    Embedding emb = resp.getResults().get(0);
                    rememberDimension(emb.getOutput());
                    embeddings.add(new Embedding(emb.getOutput(), i));
                } else {
                    log.warn("Ollama embedding returned {} vectors for one text[{}]; using zero vector",
                            resp.getResults().size(), i);
                    embeddings.add(new Embedding(zeroVector(), i));
                }
            } catch (WebClientResponseException e) {
                String body = e.getResponseBodyAsString();
                if (isInvalidEmbeddingError(body)) {
                    embeddings.add(new Embedding(retryInvalidTextOrZero(texts.get(i), i), i));
                } else {
                    throw new RuntimeException("Embedding failed for text[" + i + "]: " + body, e);
                }
            } catch (InvalidEmbeddingException e) {
                embeddings.add(new Embedding(retryInvalidTextOrZero(texts.get(i), i), i));
            }
        }

        log.info("One-by-one embedding done: {}/{} succeeded", embeddings.size(), texts.size());
        return new EmbeddingResponse(embeddings);
    }

    /**
     * 解析 Ollama 返回的 embeddings 数组。
     *
     * <p>任何 NaN 或 Inf 都会抛出 InvalidEmbeddingException，交由上层降级逻辑处理。</p>
     */
    private EmbeddingResponse parseEmbeddingResponse(String response, int expectedCount) {
        try {
            JsonNode root = objectMapper.readTree(response);
            JsonNode embeddingsNode = root.path("embeddings");

            List<Embedding> embeddings = new ArrayList<>();
            for (int i = 0; i < embeddingsNode.size(); i++) {
                JsonNode embNode = embeddingsNode.get(i);
                float[] values = new float[embNode.size()];
                for (int j = 0; j < embNode.size(); j++) {
                    float value = (float) embNode.get(j).asDouble();
                    if (!Float.isFinite(value)) {
                        throw new InvalidEmbeddingException("embedding contains NaN or Inf values at vector "
                                + i + ", dimension " + j);
                    }
                    values[j] = value;
                }
                rememberDimension(values);
                embeddings.add(new Embedding(values, i));
            }

            log.debug("Ollama embedding: {} texts, dimension={}", embeddings.size(),
                    embeddings.isEmpty() ? 0 : embeddings.get(0).getOutput().length);
            return new EmbeddingResponse(embeddings);
        } catch (InvalidEmbeddingException e) {
            throw e;
        } catch (Exception e) {
            throw new RuntimeException("Failed to parse Ollama embedding response", e);
        }
    }

    /**
     * 记录实际观测到的向量维度，供后续零向量兜底使用。
     */
    private void rememberDimension(float[] values) {
        if (values != null && values.length > 0) {
            observedDimension = values.length;
        }
    }

    /**
     * 生成与当前模型维度一致的零向量。
     */
    private float[] zeroVector() {
        int dimension = observedDimension > 0 ? observedDimension : fallbackDimension;
        return new float[dimension];
    }

    /**
     * 对无效文本尝试多种降级版本，仍失败时返回零向量。
     */
    private float[] retryInvalidTextOrZero(String text, int index) {
        for (String candidate : fallbackCandidates(text)) {
            try {
                EmbeddingResponse response = doEmbed(List.of(candidate));
                if (response.getResults().size() == 1) {
                    Embedding embedding = response.getResults().get(0);
                    rememberDimension(embedding.getOutput());
                    log.debug("Ollama invalid embedding recovered with text fallback on text[{}]", index);
                    return embedding.getOutput();
                }
            } catch (WebClientResponseException retryError) {
                if (!isInvalidEmbeddingError(retryError.getResponseBodyAsString())) {
                    throw new RuntimeException("Embedding retry failed for text[" + index + "]: "
                            + retryError.getResponseBodyAsString(), retryError);
                }
            } catch (InvalidEmbeddingException ignored) {
                // 继续落到下面的零向量兜底逻辑。
            }
        }

        logInvalidEmbeddingFallback(text, index);
        return zeroVector();
    }

    /**
     * 构造一组更保守的文本候选，尽量绕过 Ollama 后端的 NaN 边界输入。
     */
    private List<String> fallbackCandidates(String text) {
        LinkedHashSet<String> candidates = new LinkedHashSet<>();
        addFallbackCandidate(candidates, text, dropTrailingWords(text, 1));
        addFallbackCandidate(candidates, text, dropTrailingWords(text, 2));
        addFallbackCandidate(candidates, text, dropTrailingWords(text, 3));
        addFallbackCandidate(candidates, text, truncateAtWordBoundary(text, 0.9));
        addFallbackCandidate(candidates, text, truncateAtWordBoundary(text, 0.75));
        addFallbackCandidate(candidates, text, firstSentenceOrWindow(text));

        String ascii = asciiFallbackText(text);
        addFallbackCandidate(candidates, text, ascii);
        addFallbackCandidate(candidates, text, dropTrailingWords(ascii, 1));
        addFallbackCandidate(candidates, text, truncateAtWordBoundary(ascii, 0.9));
        addFallbackCandidate(candidates, text, firstSentenceOrWindow(ascii));
        return new ArrayList<>(candidates);
    }

    /**
     * 添加非空且不同于原文的兜底候选。
     */
    private void addFallbackCandidate(LinkedHashSet<String> candidates, String original, String candidate) {
        if (candidate == null) {
            return;
        }
        String cleaned = candidate.strip();
        if (!cleaned.isBlank() && !cleaned.equals(original)) {
            candidates.add(cleaned);
        }
    }

    /**
     * 从文本末尾删除指定数量的词。
     */
    private String dropTrailingWords(String text, int wordsToDrop) {
        String result = text == null ? "" : text.stripTrailing();
        for (int i = 0; i < wordsToDrop; i++) {
            int lastSpace = result.lastIndexOf(' ');
            if (lastSpace <= 0) {
                return "";
            }
            result = result.substring(0, lastSpace).stripTrailing();
        }
        return result;
    }

    /**
     * 按比例截断文本，并尽量落在词边界。
     */
    private String truncateAtWordBoundary(String text, double ratio) {
        if (text == null || text.length() < 40) {
            return text;
        }
        int target = Math.max(20, (int) Math.floor(text.length() * ratio));
        int cut = text.lastIndexOf(' ', target);
        if (cut < 20) {
            cut = target;
        }
        return text.substring(0, cut).stripTrailing();
    }

    /**
     * 取首句或一个较短窗口作为兜底文本。
     */
    private String firstSentenceOrWindow(String text) {
        if (text == null || text.length() <= 120) {
            return text;
        }
        int sentenceEnd = findSentenceEnd(text);
        if (sentenceEnd >= 40) {
            return text.substring(0, sentenceEnd + 1).stripTrailing();
        }
        return truncateAtWordBoundary(text, Math.min(0.65, 240.0 / text.length()));
    }

    /**
     * 查找英文句子结束符位置。
     */
    private int findSentenceEnd(String text) {
        int period = text.indexOf('.');
        int question = text.indexOf('?');
        int exclamation = text.indexOf('!');
        int end = Integer.MAX_VALUE;
        if (period >= 0) end = Math.min(end, period);
        if (question >= 0) end = Math.min(end, question);
        if (exclamation >= 0) end = Math.min(end, exclamation);
        return end == Integer.MAX_VALUE ? -1 : end;
    }

    /**
     * 记录无效向量兜底事件。
     *
     * <p>前几次和采样点输出 warn，其余输出 debug，避免大量脏文本时日志刷屏。</p>
     */
    private void logInvalidEmbeddingFallback(String text, int index) {
        int count = nanFallbackCount.incrementAndGet();
        String preview = text.substring(0, Math.min(100, text.length()));
        if (count <= 5 || count % NAN_WARNING_SAMPLE_RATE == 0) {
            log.warn("Ollama invalid embedding on text[{}] (len={}), using zero vector. totalInvalidFallbacks={}, preview={}",
                    index, text.length(), count, preview);
        } else {
            log.debug("Ollama invalid embedding on text[{}] (len={}), using zero vector. totalInvalidFallbacks={}, preview={}",
                    index, text.length(), count, preview);
        }
    }

    /**
     * 预处理文本：去除控制字符，确保非空。
     * 某些 PDF 提取的文本包含 \x00 等字节，会导致 bge-m3 产生 NaN。
     */
    private String sanitizeText(String text) {
        if (text == null || text.isBlank()) {
            return "empty";
        }
        String cleaned = Normalizer.normalize(text, Normalizer.Form.NFKC)
                .replace('\u00A0', ' ')
                .replace('\u2018', '\'')
                .replace('\u2019', '\'')
                .replace('\u201A', '\'')
                .replace('\u201B', '\'')
                .replace('\u201C', '"')
                .replace('\u201D', '"')
                .replace('\u201E', '"')
                .replace('\u201F', '"')
                .replace('\u2010', '-')
                .replace('\u2011', '-')
                .replace('\u2012', '-')
                .replace('\u2013', '-')
                .replace('\u2014', '-')
                .replace('\u2212', '-')
                .replace('\u2022', ' ');
        cleaned = CONTROL_CHARS.matcher(cleaned).replaceAll(" ");
        cleaned = WHITESPACE.matcher(cleaned).replaceAll(" ");
        cleaned = cleaned.strip();
        return cleaned.isEmpty() ? "empty" : cleaned;
    }

    /**
     * 构造仅保留 ASCII 可打印字符的兜底文本。
     */
    private String asciiFallbackText(String text) {
        String cleaned = NON_ASCII_PRINTABLE.matcher(text).replaceAll(" ");
        cleaned = WHITESPACE.matcher(cleaned).replaceAll(" ");
        cleaned = cleaned.strip();
        return cleaned.isEmpty() ? "empty" : cleaned;
    }

    /**
     * 表示 Ollama 返回的向量包含 NaN 或 Inf。
     */
    private static class InvalidEmbeddingException extends RuntimeException {
        InvalidEmbeddingException(String message) {
            super(message);
        }
    }
}
