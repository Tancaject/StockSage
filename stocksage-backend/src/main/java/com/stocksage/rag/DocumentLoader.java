package com.stocksage.rag;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.stocksage.client.DataServiceClient;
import com.stocksage.model.dto.KnowledgeIngestionResult;
import com.stocksage.service.KnowledgeIngestionService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.ai.document.Document;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;
import java.util.stream.Stream;

/**
 * 从配置的 docs 目录加载本地知识库文档。
 *
 * <p>PDF 解析委托给 Python 数据服务，因为 PyMuPDF 能提供比简单 Java 文本抽取更好的章节和页码元数据。
 * 文本和 Markdown 文件则留在 Java 侧，按段落切片。</p>
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class DocumentLoader {

    private final DataServiceClient dataServiceClient;
    private final ObjectMapper objectMapper;
    private final KnowledgeIngestionService knowledgeIngestionService;

    @Value("${stocksage.rag.docs-path:docs}")
    private String docsPath;

    /**
     * 加载演示知识库约定使用的 docs 子目录：reports、articles 和 glossary。
     */
    public int loadAll() {
        Path basePath = Path.of(docsPath);
        if (!Files.exists(basePath)) {
            log.warn("Document directory does not exist: {}", basePath.toAbsolutePath());
            return 0;
        }

        int totalChunks = 0;
        totalChunks += loadDirectory(basePath.resolve("reports"), "report");
        totalChunks += loadDirectory(basePath.resolve("articles"), "article");
        totalChunks += loadDirectory(basePath.resolve("glossary"), "glossary");

        log.info("Document loading finished, newly ingested chunks={}", totalChunks);
        return totalChunks;
    }

    /**
     * 解析单个源文件，并把切片交给 KnowledgeIngestionService。
     * 如果解析结果为零切片，不会删除旧索引，以保护知识库免受临时解析失败影响。
     */
    public int loadSingleFile(Path filePath, String docType) {
        if (!Files.exists(filePath)) {
            log.warn("Document file does not exist: {}", filePath);
            return 0;
        }

        List<Document> chunks = parseFile(filePath, docType);
        if (chunks.isEmpty()) {
            log.warn("Document parsed into zero chunks, keeping any previous index untouched: {}", filePath);
            return 0;
        }

        String sourceId = filePath.toAbsolutePath().normalize().toString();
        KnowledgeIngestionResult result = knowledgeIngestionService.ingestDocuments(
                sourceId,
                sha256File(filePath),
                chunks,
                "manual",
                null
        );
        log.info("Document ingest result: file={}, status={}, parsed={}, added={}, duplicates={}",
                filePath.getFileName(),
                result.status(),
                result.chunksParsed(),
                result.chunksIngested(),
                result.chunksSkippedAsDuplicates());
        return result.chunksIngested();
    }

    /**
     * 加载某个文档类型目录下的所有支持文件。
     */
    private int loadDirectory(Path dir, String docType) {
        if (!Files.exists(dir)) {
            log.debug("Document directory missing, skipped: {}", dir);
            return 0;
        }

        try (Stream<Path> files = Files.list(dir)) {
            return files
                    .filter(this::isSupportedFile)
                    .mapToInt(file -> loadSingleFile(file, docType))
                    .sum();
        } catch (Exception e) {
            log.error("Failed to read document directory: {}", dir, e);
            return 0;
        }
    }

    /**
     * 根据文件扩展名选择解析方式。
     */
    private List<Document> parseFile(Path file, String docType) {
        String name = file.getFileName().toString().toLowerCase();
        if (name.endsWith(".pdf")) {
            return parsePdfViaPython(file, docType);
        }
        return parseTextFile(file, docType);
    }

    /**
     * PDF 使用 Python 解析器，使每个向量切片保留章节标题和页码范围元数据，便于引用。
     */
    private List<Document> parsePdfViaPython(Path file, String docType) {
        try {
            String response = dataServiceClient.parsePdf(file, 3000, 300);
            JsonNode root = objectMapper.readTree(response);

            if (root.has("error") && root.get("error").asBoolean()) {
                log.error("Python PDF parsing failed: {} - {}", file.getFileName(), root.path("message").asText());
                return List.of();
            }

            JsonNode chunks = root.path("chunks");
            if (!chunks.isArray()) {
                log.warn("Python PDF parser returned non-array chunks: {}", file.getFileName());
                return List.of();
            }

            List<Document> docs = new ArrayList<>();
            for (JsonNode chunk : chunks) {
                String content = chunk.path("content").asText("").trim();
                if (content.isEmpty()) {
                    continue;
                }
                docs.add(new Document(content, Map.of(
                        "source", file.getFileName().toString(),
                        "doc_type", docType,
                        "section_title", chunk.path("section_title").asText(""),
                        "page_start", chunk.path("page_start").asInt(0),
                        "page_end", chunk.path("page_end").asInt(0),
                        "parser", "pymupdf"
                )));
            }
            return docs;
        } catch (Exception e) {
            log.error("PDF parsing failed: {}", file.getFileName(), e);
            return List.of();
        }
    }

    /**
     * 解析纯文本、Markdown、HTML 或 docx 导出的文本类文件。
     *
     * <p>当前实现按空行切片，复杂版式文档仍建议优先走 Python/PDF 解析路径。</p>
     */
    private List<Document> parseTextFile(Path file, String docType) {
        try {
            String content = Files.readString(file);
            String[] entries = content.split("\\n\\s*\\n+");
            List<Document> docs = new ArrayList<>();
            for (int i = 0; i < entries.length; i++) {
                String trimmed = entries[i].trim();
                if (!trimmed.isEmpty()) {
                    docs.add(new Document(trimmed, Map.of(
                            "source", file.getFileName().toString(),
                            "doc_type", docType,
                            "parser", "text",
                            "chunk_index", i
                    )));
                }
            }
            return docs;
        } catch (Exception e) {
            log.error("Failed to read text document: {}", file, e);
            return List.of();
        }
    }

    /**
     * 判断本地知识库加载器支持的文件类型。
     */
    private boolean isSupportedFile(Path path) {
        String name = path.getFileName().toString().toLowerCase();
        return name.endsWith(".pdf") || name.endsWith(".txt") || name.endsWith(".md")
                || name.endsWith(".docx") || name.endsWith(".html");
    }

    /**
     * 计算文件 SHA-256，用于判断本地文档是否发生变化。
     */
    private String sha256File(Path path) {
        try (InputStream input = Files.newInputStream(path)) {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            byte[] buffer = new byte[8192];
            int read;
            while ((read = input.read(buffer)) >= 0) {
                digest.update(buffer, 0, read);
            }
            return HexFormat.of().formatHex(digest.digest());
        } catch (Exception e) {
            throw new IllegalStateException("Failed to calculate file hash: " + path, e);
        }
    }
}
