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
import java.util.List;
import java.util.Map;
import java.util.regex.Pattern;

/**
 * 通过 Ollama 生成本地向量；维度必须与向量库配置相符。
 * 批量失败时仅按原文本逐条重试，无效响应不能伪装为可检索向量。
 */
@Slf4j
public class LocalEmbeddingModel implements EmbeddingModel {

    /** Ollama tokenizer 前先移除的不可见控制字符。 */
    private static final Pattern CONTROL_CHARS = Pattern.compile("[\\x00-\\x08\\x0B\\x0C\\x0E-\\x1F\\x7F]");

    /** 将连续空白压成单个空格，稳定哈希和 tokenizer 输入。 */
    private static final Pattern WHITESPACE = Pattern.compile("\\s+");

    /** 调用 Ollama {@code /api/embed} 的响应式 HTTP 客户端。 */
    private final WebClient webClient;

    /** Ollama 中实际执行 embedding 的模型名。 */
    private final String model;

    /** 解析 Ollama JSON 响应。 */
    private final ObjectMapper objectMapper;

    /** 单次 HTTP 请求的最大等待时间。 */
    private final Duration timeout;

    /** 与向量库配置一致的维度，响应不符时必须拒绝入库。 */
    private final int dimension;

    /**
     * 使用默认 1024 维初始化本地向量模型。
     *
     * @param baseUrl Ollama 服务地址
     * @param model 向量模型名称
     * @param timeout 单次请求超时
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
     * @param dimension 与向量库一致的配置维度
     */
    public LocalEmbeddingModel(String baseUrl, String model, Duration timeout, int dimension) {
        this.webClient = WebClient.builder().baseUrl(baseUrl).build();
        this.model = model;
        this.timeout = timeout;
        if (dimension <= 0) throw new IllegalArgumentException("Embedding dimension must be positive");
        this.dimension = dimension;
        this.objectMapper = new ObjectMapper();
        log.info("LocalEmbeddingModel initialized: base={}, model={}, dimension={}",
                baseUrl, model, dimension);
    }

    /**
     * 对单个 Spring AI Document 生成向量。
     *
     * @param document 待向量化文档
     * @return 文档正文的 embedding
     */
    @Override
    public float[] embed(Document document) {
        return embed(document.getText());
    }

    /**
     * 批量生成向量。
     *
     * <p>请求前会先做文本清洗；如果批量调用出现 NaN 或数量不匹配，则自动退回逐条向量化。</p>
     *
     * @param request Spring AI 提供的文本列表请求
     * @return 与输入顺序、索引对应的向量响应
     * @throws RuntimeException Ollama 非无效向量类错误或网络错误时抛出
     */
    @Override
    public EmbeddingResponse call(EmbeddingRequest request) {
        List<String> texts = request.getInstructions();
        if (texts == null || texts.isEmpty()) {
            return new EmbeddingResponse(List.of());
        }

        // 先去除已知会触发 llama.cpp NaN 的控制字符，并替换空白文本。
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
     *
     * @param body Ollama 错误响应正文
     * @return 是否包含 NaN、Inf 或非法浮点值信号
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
     *
     * @param body 原始错误正文
     * @return 最多 180 字符的单行摘要
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
     *
     * @param texts 已完成清洗的文本批次
     * @return Ollama 返回并通过有限值校验的向量
     */
    private EmbeddingResponse doEmbed(List<String> texts) {
        int totalChars = texts.stream().mapToInt(String::length).sum();
        int maxChars = texts.stream().mapToInt(String::length).max().orElse(0);
        log.debug("Ollama embedding request: {} texts, totalChars={}, maxChars={}", texts.size(), totalChars, maxChars);

        Map<String, Object> body = new HashMap<>();
        body.put("model", model);
        body.put("input", texts);
        body.put("truncate", true);

        // WebClient 发起同步等待的本地 HTTP 调用；timeout 限制 block 的最长时间。
        String response = webClient.post()
                .uri("/api/embed")
                .header("Content-Type", "application/json")
                .bodyValue(body)
                .retrieve()
                .bodyToMono(String.class)
                .timeout(timeout)
                .block();

        return parseEmbeddingResponse(response);
    }

    /** 批量失败后仅按原文本逐条重试；不可用向量必须中止摄取，不能伪造成功结果。 */
    private EmbeddingResponse embedOneByOne(List<String> texts) {
        List<Embedding> embeddings = new ArrayList<>();
        for (int i = 0; i < texts.size(); i++) {
            EmbeddingResponse response = doEmbed(List.of(texts.get(i)));
            if (response.getResults().size() != 1) {
                throw new InvalidEmbeddingException("Ollama did not return one embedding for text[" + i + "]");
            }
            embeddings.add(new Embedding(response.getResults().get(0).getOutput(), i));
        }
        return new EmbeddingResponse(embeddings);
    }

    @Override
    public int dimensions() {
        return dimension;
    }

    /**
     * 解析 Ollama 返回的 embeddings 数组。
     *
     * <p>任何 NaN 或 Inf 都会抛出 InvalidEmbeddingException，交由上层限次重试，仍无效则抛出失败。</p>
     *
     * @param response Ollama JSON 响应
     * @return 已解析的 embedding 列表
     * @throws InvalidEmbeddingException 任一维度不是有限浮点数时抛出
     */
    private EmbeddingResponse parseEmbeddingResponse(String response) {
        try {
            JsonNode root = objectMapper.readTree(response);
            JsonNode embeddingsNode = root.path("embeddings");

            if (!embeddingsNode.isArray() || embeddingsNode.isEmpty()) {
                throw new InvalidEmbeddingException("Ollama returned no embeddings");
            }
            List<Embedding> embeddings = new ArrayList<>();
            for (int i = 0; i < embeddingsNode.size(); i++) {
                JsonNode embNode = embeddingsNode.get(i);
                if (!embNode.isArray() || embNode.size() != dimension) {
                    throw new InvalidEmbeddingException("Ollama embedding dimension does not match configured " + dimension);
                }
                float[] values = new float[embNode.size()];
                boolean nonzero = false;
                for (int j = 0; j < embNode.size(); j++) {
                    float value = (float) embNode.get(j).asDouble();
                    if (!embNode.get(j).isNumber() || !Float.isFinite(value)) {
                        throw new InvalidEmbeddingException("embedding contains NaN or Inf values at vector "
                                + i + ", dimension " + j);
                    }
                    values[j] = value;
                    nonzero |= value != 0;
                }
                if (!nonzero) {
                    throw new InvalidEmbeddingException("Ollama returned an all-zero embedding at vector " + i);
                }
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
     * 预处理文本：去除控制字符，确保非空。
     * 某些 PDF 提取的文本包含 \x00 等字节，会导致 bge-m3 产生 NaN。
     *
     * @param text 原始切片文本
     * @return Unicode 规范化、标点归一和空白压缩后的非空文本
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
     * 表示 Ollama 返回的向量包含 NaN 或 Inf。
     */
    private static class InvalidEmbeddingException extends RuntimeException {

        /** @param message 无效向量的位置或原因 */
        InvalidEmbeddingException(String message) {
            super(message + "; check the Ollama model/dimension and retry the request");
        }
    }
}
