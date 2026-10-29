package com.stocksage.knowledge;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.stocksage.client.DataServiceClient;
import com.stocksage.exception.ResearchBudgetExceededException;
import com.stocksage.exception.ResearchCapacityExceededException;
import com.stocksage.tool.ToolCallContext;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

import java.util.Map;
import java.util.Set;

/** Exact-source disclosure reads; URL/network safety and deterministic extraction live in data-service. */
@Service
@RequiredArgsConstructor
public class DisclosureDocumentService {
    private final DataServiceClient dataServiceClient;
    private final ObjectMapper objectMapper;

    public String read(String url, String filingType, String section, boolean tablesOnly, int offset, int maxChars) {
        if (url == null || url.isBlank() || offset < 0 || maxChars < 256 || maxChars > 24000) {
            return error("公告链接不能为空；offset 须非负，maxChars 须为 256–24000。");
        }
        try {
            return validate(dataServiceClient.readDisclosure(Map.of("url", url, "filing_type", filingType == null ? "DISCLOSURE" : filingType,
                    "section", section == null ? "" : section, "tables_only", tablesOnly, "offset", offset, "max_chars", maxChars)), false);
        } catch (Exception failure) {
            rethrowBudget(failure);
            return error("公告读取失败；请核对官方公告链接、章节和数据服务状态。");
        }
    }

    public String compare(String olderUrl, String newerUrl, String filingType, String section, int maxChanges) {
        if (olderUrl == null || olderUrl.isBlank() || newerUrl == null || newerUrl.isBlank() || maxChanges < 1 || maxChanges > 50) {
            return error("比较需要两份明确的官方公告链接；maxChanges 须为 1–50。");
        }
        try {
            return validate(dataServiceClient.compareDisclosures(Map.of("older_url", olderUrl, "newer_url", newerUrl,
                    "filing_type", filingType == null ? "DISCLOSURE" : filingType, "section", section == null ? "" : section,
                    "max_changes", maxChanges)), true);
        } catch (Exception failure) {
            rethrowBudget(failure);
            return error("披露比较失败；请确认两份原文可读取并选择明确章节。");
        }
    }

    /** Old KB rows lack URLs: resolve only the exact accession, never substitute the latest filing. */
    public String resolveSecDocumentUrl(String ticker, String form, String accession) {
        if (ticker == null || form == null || accession == null || accession.isBlank()) return "";
        try {
            for (JsonNode filing : objectMapper.readTree(dataServiceClient.getEdgarFilings(ticker, form, 10)).path("filings")) {
                if (accession.equals(filing.path("accession_number").asText())) return filing.path("document_url").asText("");
            }
            return "";
        } catch (Exception failure) {
            rethrowBudget(failure);
            return "";
        }
    }

    private String validate(String json, boolean comparison) throws Exception {
        JsonNode root = objectMapper.readTree(json);
        if (root == null || !root.path("schemaVersion").isIntegralNumber() || root.path("schemaVersion").asInt() != 1
                || !(comparison ? "OFFICIAL_DISCLOSURE_DIFF" : "OFFICIAL_DISCLOSURE").equals(root.path("provider").asText())
                || !Set.of("SUCCESS", "EMPTY", "ERROR", "AMBIGUOUS_SECTION").contains(root.path("status").asText())) {
            throw new IllegalArgumentException("Invalid disclosure response");
        }
        if ("SUCCESS".equals(root.path("status").asText())) {
            if (!root.path(comparison ? "changes" : "blocks").isArray() || !root.path("truncated").isBoolean()) {
                throw new IllegalArgumentException("Missing disclosure content/reduction status");
            }
            for (JsonNode source : comparison ? java.util.List.of(root.path("older"), root.path("newer")) : java.util.List.of(root)) {
                if (!source.path("documentUrl").isTextual() || !source.path("documentHash").asText().matches("[a-f0-9]{64}")) {
                    throw new IllegalArgumentException("Missing disclosure source");
                }
            }
        }
        return root.toString();
    }

    private String error(String message) {
        return objectMapper.valueToTree(Map.of("schemaVersion", 1, "status", "ERROR", "error", true,
                "provider", "OFFICIAL_DISCLOSURE", "message", message)).toString();
    }

    private static void rethrowBudget(Exception failure) {
        ResearchBudgetExceededException.rethrowIfPresent(failure);
        ResearchCapacityExceededException.rethrowIfPresent(failure);
        ToolCallContext.checkRunDeadline();
    }
}
