package com.stocksage.knowledge;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.stocksage.agent.runtime.AgentStep;
import com.stocksage.client.DataServiceClient;
import com.stocksage.trace.TraceService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.core.task.AsyncTaskExecutor;
import org.springframework.stereotype.Service;

import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.RejectedExecutionException;
import java.util.regex.Pattern;

/**
 * 知识库没有某家美股公司的财报原文时，直接读 SEC 最新申报作本轮证据，并在后台把全文入库。
 *
 * <p>全文入库要切块、向量化，一份 10-K 在 CPU 上约 4 分钟、GPU 上约 30–45 秒，不能让回答等待；
 * 读取并解析原文约 1–2 秒。本轮证据按问题选章节：风险因素只列分类标题和每条风险的标题句，
 * 管理层讨论取开头的概览；入库完成后，后续问题由知识库检索提供原文。</p>
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class FilingTextService {
    /** 原文摘录上限：多数公司的全部风险标题在 1 万字左右；普通路线证据总预算为 2 万字。 */
    static final int BUDGET_CHARS = 12000;
    /** 风险因素里的标题段落都是单句短段落，正文段落远长于此。 */
    private static final int HEADING_MAX_CHARS = 350;
    private static final Pattern RISK = Pattern.compile("风险|risk");
    private static final Pattern MDNA = Pattern.compile("管理层讨论|经营讨论|md&a|management'?s discussion");

    private final DataServiceClient dataServiceClient;
    private final EdgarIngestionService edgarIngestionService;
    private final ObjectMapper objectMapper;
    private final AsyncTaskExecutor backgroundTaskExecutor;
    private final TraceService traceService;
    private final Set<String> ingesting = ConcurrentHashMap.newKeySet();

    /**
     * 读取最新一份申报的相关章节，成功时提交后台全文入库。
     *
     * @return 带 schemaVersion 的证据 JSON；取不到申报为 EMPTY，调用失败为 error
     */
    public String fetch(String ticker, String filingType, String query, String traceId) {
        try {
            JsonNode filings = objectMapper.readTree(dataServiceClient.getEdgarFilings(ticker, filingType, 1));
            if (filings == null || filings.has("error")) return error("SEC 申报索引读取失败");
            JsonNode filing = filings.path("filings").path(0);
            ObjectNode payload = objectMapper.createObjectNode();
            payload.put("schemaVersion", 1);
            payload.put("provider", "SEC_EDGAR");
            payload.put("ticker", ticker.toUpperCase(Locale.ROOT));
            payload.put("companyName", filings.path("company_name").asText(ticker));
            payload.put("filingType", filingType);
            payload.put("fetchedAt", Instant.now().toString());
            if (filing.isMissingNode() || filing.path("document_url").asText("").isBlank()) {
                payload.put("status", "EMPTY");
                payload.put("message", "SEC 没有该公司的 " + filingType + " 申报");
                return payload.toString();
            }
            String documentUrl = filing.path("document_url").asText();
            payload.put("filingDate", filing.path("filing_date").asText(null));
            payload.put("reportDate", filing.path("report_date").asText(null));
            payload.put("accessionNumber", filing.path("accession_number").asText(null));
            payload.put("documentUrl", documentUrl);
            JsonNode content = objectMapper.readTree(dataServiceClient.getEdgarFilingContent(documentUrl, filingType));
            if (content == null || content.has("error")) return error("SEC 申报正文读取失败");
            ArrayNode sections = payload.putArray("sections");
            boolean truncated = false;
            List<String> wanted = wantedSections(query, filingType);
            for (String prefix : wanted) {
                JsonNode section = findSection(content.path("sections"), prefix);
                if (section == null) continue;
                Excerpt excerpt = excerpt(prefix, section.path("content").asText(""), BUDGET_CHARS / wanted.size());
                ObjectNode row = sections.addObject();
                row.put("section", section.path("section").asText(prefix));
                row.put("mode", excerpt.mode());
                row.put("sourceChars", section.path("content").asText("").length());
                row.put("truncated", excerpt.truncated());
                row.put("content", excerpt.text());
                truncated |= excerpt.truncated();
            }
            payload.put("truncated", truncated);
            payload.put("status", sections.isEmpty() ? "EMPTY" : "SUCCESS");
            if (sections.isEmpty()) payload.put("message", "申报中未找到问题对应的章节");
            else submitIngestion(ticker, filingType, documentUrl, traceId);
            return payload.toString();
        } catch (Exception failure) {
            log.warn("Filing text fallback failed, ticker={}, type={}: {}", ticker, filingType, failure.getMessage());
            return error("SEC 申报原文读取失败");
        }
    }

    /** 问题提到风险或管理层讨论时只取对应章节，否则两者都取。 */
    static List<String> wantedSections(String query, String filingType) {
        String text = query == null ? "" : query.toLowerCase(Locale.ROOT);
        String mdna = "10-Q".equals(filingType) ? "Item 2." : "Item 7.";
        List<String> wanted = new ArrayList<>();
        if (RISK.matcher(text).find()) wanted.add("Item 1A.");
        if (MDNA.matcher(text).find()) wanted.add(mdna);
        return wanted.isEmpty() ? List.of("Item 1A.", mdna) : wanted;
    }

    private static JsonNode findSection(JsonNode sections, String prefix) {
        for (JsonNode section : sections) {
            if (section.path("section").asText("").startsWith(prefix)) return section;
        }
        return null;
    }

    record Excerpt(String mode, String text, boolean truncated) {}

    private static final Pattern PAGE_NOISE = Pattern.compile("(?i)\\d{1,3}|table of contents|page \\d+|\\d{1,3} \\| table of contents");
    private static final int CATEGORY_MAX_CHARS = 150;

    /** 分类标题是没有句末标点的短行，如 "Risks Related to Our Supply Chain"。 */
    private static boolean isCategory(String paragraph) {
        return paragraph.length() <= CATEGORY_MAX_CHARS && !paragraph.matches(".*[.?!:;,]$")
                && paragraph.chars().anyMatch(Character::isLetter);
    }

    /**
     * 风险因素按标题段落摘录：分类标题（不以句号结尾）全部保留，各条风险的标题句按分类轮流选入，
     * 预算不够时每一类都还有代表，再按原文顺序输出。没有可识别的标题时与其他章节一样取开头。
     */
    static Excerpt excerpt(String prefix, String content, int budget) {
        if ("Item 1A.".equals(prefix)) {
            List<String> headings = new ArrayList<>();
            String[] paragraphs = content.strip().split("\\n\\s*\\n");
            for (int i = 1; i < paragraphs.length; i++) {
                String paragraph = paragraphs[i].strip().replaceAll("\\s+", " ");
                // 页码、目录链接、列表项和引出列表的句子都不是标题。
                if (paragraph.isEmpty() || paragraph.length() > HEADING_MAX_CHARS || PAGE_NOISE.matcher(paragraph).matches()
                        || paragraph.startsWith("•") || paragraph.startsWith("·") || paragraph.startsWith("-")
                        || paragraph.endsWith(":") || paragraph.endsWith(";") || paragraph.endsWith(",")) continue;
                if (paragraph.endsWith(".") || isCategory(paragraph)) headings.add(paragraph);
            }
            if (!headings.isEmpty()) {
                // 每个元素是一组：[分类标题下标, 风险标题下标...]；第一个分类之前的风险归入无标题组。
                List<List<Integer>> groups = new ArrayList<>();
                List<Integer> current = new ArrayList<>(List.of(-1));
                for (int i = 0; i < headings.size(); i++) {
                    if (isCategory(headings.get(i))) {
                        groups.add(current);
                        current = new ArrayList<>(List.of(i));
                    } else {
                        current.add(i);
                    }
                }
                groups.add(current);
                java.util.TreeSet<Integer> chosen = new java.util.TreeSet<>();
                int used = 0;
                for (List<Integer> group : groups) {
                    if (group.get(0) >= 0 && used + headings.get(group.get(0)).length() + 3 <= budget) {
                        chosen.add(group.get(0));
                        used += headings.get(group.get(0)).length() + 3;
                    }
                }
                boolean added = true;
                for (int round = 1; added; round++) {
                    added = false;
                    for (List<Integer> group : groups) {
                        if (round >= group.size()) continue;
                        int index = group.get(round);
                        if (used + headings.get(index).length() + 3 > budget) continue;
                        chosen.add(index);
                        used += headings.get(index).length() + 3;
                        added = true;
                    }
                }
                StringBuilder text = new StringBuilder();
                for (int index : chosen) {
                    text.append(isCategory(headings.get(index)) ? "## " : "- ").append(headings.get(index)).append('\n');
                }
                return new Excerpt("RISK_HEADINGS", text.toString().strip(), chosen.size() < headings.size());
            }
        }
        String body = content.strip();
        if (body.length() <= budget) return new Excerpt("LEADING_TEXT", body, false);
        int cut = body.lastIndexOf("\n\n", budget);
        return new Excerpt("LEADING_TEXT", body.substring(0, cut > budget / 2 ? cut : budget).strip(), true);
    }

    /** 同一标的同类申报同时只入库一次；入库结果只写日志，Trace 只记提交。 */
    private void submitIngestion(String ticker, String filingType, String documentUrl, String traceId) {
        String key = ticker.toUpperCase(Locale.ROOT) + "|" + filingType;
        Map<String, Object> observation = new LinkedHashMap<>();
        observation.put("kind", "filing-ingest");
        observation.put("target", ticker.toUpperCase(Locale.ROOT));
        observation.put("filingType", filingType);
        observation.put("source", documentUrl);
        if (!ingesting.add(key)) {
            observation.put("status", "ALREADY_RUNNING");
        } else {
            try {
                backgroundTaskExecutor.execute(() -> {
                    try {
                        var result = edgarIngestionService.ingestFilings(ticker, filingType, 1);
                        log.info("Background filing ingestion finished, ticker={}, type={}, chunks={}",
                                ticker, filingType, result.get("total_chunks"));
                    } catch (Exception failure) {
                        log.warn("Background filing ingestion failed, ticker={}, type={}: {}", ticker, filingType, failure.getMessage());
                    } finally {
                        ingesting.remove(key);
                    }
                });
                observation.put("status", "SUBMITTED");
            } catch (RejectedExecutionException rejected) {
                ingesting.remove(key);
                observation.put("status", "NOT_SUBMITTED");
                observation.put("reason", "CAPACITY_REJECTED");
            }
        }
        if (traceId == null || traceId.isBlank()) return;
        try {
            traceService.addStep(traceId, AgentStep.builder().action("Filing Ingestion")
                    .observation("SEC 申报全文后台入库：" + observation.get("status")).attributes(observation).build());
        } catch (Exception error) {
            log.warn("Filing ingestion trace not recorded, traceId={}: {}", traceId, error.getMessage());
        }
    }

    private String error(String message) {
        ObjectNode payload = objectMapper.createObjectNode();
        payload.put("error", true);
        payload.put("message", message);
        return payload.toString();
    }
}
