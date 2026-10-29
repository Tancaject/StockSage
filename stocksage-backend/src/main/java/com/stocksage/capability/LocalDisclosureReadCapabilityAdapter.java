package com.stocksage.capability;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.stocksage.knowledge.DisclosureDocumentService;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

import java.time.Instant;
import java.util.Map;
import java.util.Set;

/** URL and accession are resolved from this run's ledger before the gateway is called. */
@Component
@RequiredArgsConstructor
public class LocalDisclosureReadCapabilityAdapter implements CapabilityAdapter {
    public static final String ID = "local.filing.readDisclosure";
    private static final Set<String> KEYS = Set.of("ticker", "form", "documentUrl", "accession", "evidenceId", "section",
            "tablesOnly", "offset", "maxChars", "otherSourceCount");
    private final DisclosureDocumentService disclosureDocumentService;
    private final ObjectMapper objectMapper;

    @Override public String capabilityId() { return ID; }
    @Override public boolean isAvailable() { return true; }

    @Override public String invoke(Map<String, Object> arguments, CapabilityInvocationContext context) throws Exception {
        ResearchCapabilityArguments.requireOnlyKeys(arguments, KEYS);
        String ticker = ResearchCapabilityArguments.requireTicker(arguments);
        String form = ResearchCapabilityArguments.requireText(arguments, "form", 30);
        String evidenceId = ResearchCapabilityArguments.requireText(arguments, "evidenceId", 128);
        int offset = ResearchCapabilityArguments.requireInt(arguments, "offset", 0, 5_000_000);
        int maxChars = ResearchCapabilityArguments.requireInt(arguments, "maxChars", 256, 24000);
        int otherSources = ResearchCapabilityArguments.requireInt(arguments, "otherSourceCount", -1, 100);
        if (!(arguments.get("tablesOnly") instanceof Boolean tablesOnly) || !(arguments.get("section") instanceof String section)
                || section.length() > 250 || !(arguments.get("documentUrl") instanceof String url)
                || !(arguments.get("accession") instanceof String accession)) {
            throw new CapabilityException(CapabilityException.Reason.DENIED, "Invalid resolved disclosure source");
        }
        if (url.isBlank() && !accession.isBlank()) url = disclosureDocumentService.resolveSecDocumentUrl(ticker, form, accession);
        ObjectNode result;
        if (url.isBlank()) {
            result = objectMapper.createObjectNode().put("schemaVersion", 1).put("status", "EMPTY")
                    .put("provider", "OFFICIAL_DISCLOSURE").put("fetchedAt", Instant.now().toString())
                    .put("message", "The observed filing accession could not be located; no newer filing was substituted.");
        } else {
            result = (ObjectNode) objectMapper.readTree(disclosureDocumentService.read(url, form, section, tablesOnly, offset, maxChars));
        }
        result.put("ticker", ticker);
        result.put("sourceEvidenceId", evidenceId);
        result.put("otherSourceCount", otherSources);
        result.put("sourceSetPartial", otherSources != 0);
        result.set("selectedSource", objectMapper.valueToTree(Map.of("documentUrl", url, "accession", accession,
                "filingType", form, "selection", "FIRST_OBSERVED_SOURCE")));
        return result.toString();
    }
}
