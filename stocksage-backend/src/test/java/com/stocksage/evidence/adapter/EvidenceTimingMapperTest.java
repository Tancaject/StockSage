package com.stocksage.evidence.adapter;

import com.stocksage.service.TickerResolutionService;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.stocksage.evidence.EvidenceTiming;
import com.stocksage.evidence.EvidenceModels.EvidenceDimension;
import com.stocksage.evidence.EvidenceModels.EvidenceEnvelope;
import com.stocksage.evidence.EvidenceModels.EvidenceStatus;
import org.junit.jupiter.api.Test;

import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.time.LocalDate;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class EvidenceTimingMapperTest {
    private static final Instant OBSERVED_AT = Instant.parse("2026-09-26T02:00:00Z");
    private final ObjectMapper json = new ObjectMapper();
    private final EvidenceEnvelopeMapper mapper = new EvidenceEnvelopeMapper(new TickerResolutionService(null, null, json));

    @Test void klinePreservesBusinessDateAndRequestedGrainWithoutInventingAnInstant() throws Exception {
        for (var grain : Map.of("daily", "1d", "weekly", "1w", "monthly", "1m").entrySet()) {
            ObjectNode payload = fixture("kline-v1.json").put("period", grain.getKey());
            var envelope = map(EvidenceDimension.MARKET, "getStockKLine", payload);
            var timing = envelope.timing();
            assertThat(timing).isNotNull();
            assertThat(timing.fetchedAt()).isEqualTo(Instant.parse("2026-09-25T01:02:03Z"));
            assertThat(timing.fetchedAt()).isNotEqualTo(envelope.observedAt());
            assertThat(timing.market()).isEqualTo(new EvidenceTiming.Market("A_SHARE", grain.getValue(), "DATE",
                    LocalDate.parse("2026-09-24"), null, null));
            assertThat(timing.financial()).isNull();
            assertThat(timing.search()).isNull();
        }
    }

    @Test void ibkrUsesActualBarInstantAndDoesNotInferDelayFromGatewayMetadata() throws Exception {
        ObjectNode payload = ibkrPayload();
        var unknownDelay = map(EvidenceDimension.MARKET, "getIbkrHistoricalBars", payload).timing();
        assertThat(unknownDelay.market()).isEqualTo(new EvidenceTiming.Market("HK", "1h", "INSTANT", null,
                Instant.ofEpochMilli(1747229700000L), null));
        assertThat(unknownDelay.fetchedAt()).isEqualTo(Instant.parse("2026-09-25T01:00:00Z"));
        payload.put("delayed", true);
        assertThat(map(EvidenceDimension.MARKET, "getIbkrHistoricalBars", payload).timing().market().delayed()).isTrue();
        payload.put("delayed", false);
        assertThat(map(EvidenceDimension.MARKET, "getIbkrHistoricalBars", payload).timing().market().delayed()).isFalse();
    }

    @Test void financialPeriodsAndDisclosuresRemainSeparateAcrossTheThreeTypedProviders() throws Exception {
        var sec = map(EvidenceDimension.FUNDAMENTALS, "getStructuredFinancials", fixture("sec-financials-v1.json")).timing();
        assertThat(sec.financial()).isEqualTo(new EvidenceTiming.Financial("annual",
                LocalDate.parse("2026-01-31"), LocalDate.parse("2026-04-01")));

        var aShare = map(EvidenceDimension.FUNDAMENTALS, "getFinancialReports", fixture("a-share-financials-v1.json")).timing();
        assertThat(aShare.financial()).isEqualTo(new EvidenceTiming.Financial("annual",
                LocalDate.parse("2025-12-31"), LocalDate.parse("2026-03-31")));

        // The fixture retains all-null rows for 2025, but only 2024 has financial values.
        var hk = map(EvidenceDimension.FUNDAMENTALS, "getFinancialReports", fixture("hk-financials-v1.json")).timing();
        assertThat(hk.financial()).isEqualTo(new EvidenceTiming.Financial("annual", LocalDate.parse("2024-12-31"), null));
        assertThat(hk.fetchedAt()).isEqualTo(Instant.parse("2026-09-25T01:02:03Z"));
        assertThat(hk.market()).isNull();
        assertThat(hk.search()).isNull();
    }

    @Test void searchAggregatesEveryResultByPrecisionEvenWhenFirstSourceHasUnknownDate() throws Exception {
        ObjectNode payload = fixture("search-news-v1.json");
        ObjectNode laterInstant = ((ObjectNode) payload.path("results").get(0)).deepCopy()
                .put("link", "https://example.org/later-instant").put("date", "2026-09-26T00:00:00Z")
                .put("publishedAt", "2026-09-26T00:00:00Z");
        ObjectNode laterDate = ((ObjectNode) payload.path("results").get(1)).deepCopy()
                .put("link", "https://example.org/later-date").put("date", "2026-09-25").put("publishedDate", "2026-09-25");
        var results = json.createArrayNode().add(payload.path("results").get(2))
                .add(laterInstant).add(payload.path("results").get(0))
                .add(laterDate).add(payload.path("results").get(1));
        payload.set("results", results);
        payload.put("count", 5).put("requestedDays", 7);

        var envelope = map(EvidenceDimension.NEWS, "searchNews", payload);
        assertThat(envelope.sourceRef()).isEqualTo("https://example.org/unknown");
        assertThat(envelope.asOf()).isNull();
        assertThat(envelope.timing().fetchedAt()).isEqualTo(Instant.parse("2026-09-26T01:02:03Z"));
        assertThat(envelope.timing().search()).isEqualTo(new EvidenceTiming.Search("w", "w", 7,
                Instant.parse("2026-09-24T15:30:00Z"), Instant.parse("2026-09-26T00:00:00Z"),
                LocalDate.parse("2026-09-23"), LocalDate.parse("2026-09-25"), 2, 2, 1));
    }

    @Test void typedEmptyResultsKeepFetchAndWindowFactsWithoutManufacturingBusinessTime() throws Exception {
        ObjectNode search = fixture("search-fallback-v1.json").put("status", "EMPTY").put("count", 0);
        search.set("results", json.createArrayNode());
        var emptySearch = map(EvidenceDimension.NEWS, "searchNews", search);
        assertThat(emptySearch.status()).isEqualTo(EvidenceStatus.NO_RESULTS);
        assertThat(emptySearch.timing().search()).isEqualTo(new EvidenceTiming.Search("y", null, null,
                null, null, null, null, 0, 0, 0));

        ObjectNode kline = fixture("kline-v1.json").put("status", "EMPTY").put("count", 0).putNull("asOf");
        kline.set("data", json.createArrayNode());
        var emptyKline = map(EvidenceDimension.MARKET, "getStockKLine", kline);
        assertThat(emptyKline.status()).isEqualTo(EvidenceStatus.EMPTY);
        assertThat(emptyKline.timing().market()).isEqualTo(new EvidenceTiming.Market("A_SHARE", "1d", "DATE", null, null, null));
    }

    @Test void legacyMalformedAndFailedEvidenceNeverAcquireTypedTiming() throws Exception {
        ObjectNode legacy = json.createObjectNode().put("asOf", "2026-09-25T00:00:00Z")
                .put("fetchedAt", "2026-09-26T01:02:03Z").put("close", 100);
        assertThat(map(EvidenceDimension.MARKET, "getStockKLine", legacy).timing()).isNull();

        ObjectNode malformed = fixture("kline-v1.json").put("asOf", "2099-01-01");
        var invalid = map(EvidenceDimension.MARKET, "getStockKLine", malformed);
        assertThat(invalid.status()).isEqualTo(EvidenceStatus.FAILED);
        assertThat(invalid.timing()).isNull();

        ObjectNode valid = fixture("search-news-v1.json");
        for (var status : new EvidenceStatus[]{EvidenceStatus.FAILED, EvidenceStatus.TIMED_OUT, EvidenceStatus.NOT_COLLECTED}) {
            assertThat(mapper.map(EvidenceDimension.NEWS, "TEST", "searchNews", valid.toString(), status, OBSERVED_AT, true).timing()).isNull();
        }
        ObjectNode unsupported = fixture("kline-v1.json").put("status", "UNSUPPORTED").put("error", true)
                .put("errorCode", "UNSUPPORTED_MARKET").put("count", 0).putNull("asOf");
        unsupported.set("data", json.createArrayNode());
        assertThat(map(EvidenceDimension.MARKET, "getStockKLine", unsupported).timing()).isNull();
    }

    @Test void timingRejectsAmbiguousDomainsAndPublicationRanges() {
        var market = new EvidenceTiming.Market("US", "1h", "INSTANT", null, OBSERVED_AT, null);
        var financial = new EvidenceTiming.Financial("annual", LocalDate.parse("2025-12-31"), null);
        assertThatThrownBy(() -> new EvidenceTiming(1, OBSERVED_AT, null, null, null)).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new EvidenceTiming(1, OBSERVED_AT, market, financial, null)).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new EvidenceTiming(2, OBSERVED_AT, market, null, null)).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new EvidenceTiming.Market("US", "1d", "DATE", LocalDate.parse("2026-09-25"), OBSERVED_AT, null))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new EvidenceTiming.Search("w", "w", null, OBSERVED_AT, OBSERVED_AT, null, null, 0, 0, 0))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new EvidenceTiming.Search("w", "w", null, null, null,
                LocalDate.parse("2026-09-25"), LocalDate.parse("2026-09-24"), 0, 2, 0)).isInstanceOf(IllegalArgumentException.class);
    }

    private EvidenceEnvelope map(EvidenceDimension dimension, String capabilityId, ObjectNode payload) {
        return mapper.map(dimension, "TEST", capabilityId, payload.toString(), EvidenceStatus.AVAILABLE, OBSERVED_AT, true);
    }

    private ObjectNode fixture(String name) throws Exception {
        return (ObjectNode) json.readTree(Files.readString(Path.of("../stocksage-data-service/tests/fixtures", name)));
    }

    private ObjectNode ibkrPayload() throws Exception {
        ObjectNode provider;
        try (var input = getClass().getResourceAsStream("/fixtures/ibkr-history.json")) {
            provider = (ObjectNode) json.readTree(input);
        }
        ObjectNode payload = json.createObjectNode();
        payload.put("schemaVersion", 1).put("status", "SUCCESS").put("provider", "IBKR_WEB_API")
                .put("input", "0700.HK").put("market", "HK").put("symbol", "700").put("conid", "12345")
                .put("exchange", "SEHK").put("period", "1w").put("bar", "1h").put("source", "Trades")
                .put("requestedOutsideRth", true).put("fetchedAt", "2026-09-25T01:00:00Z")
                .put("asOf", Instant.ofEpochMilli(1747229700000L).toString()).put("timeKind", "EPOCH_MILLIS")
                .put("volumeUnit", "UNKNOWN").put("count", 2).put("error", false);
        payload.set("data", provider.get("data"));
        payload.set("historyMetadata", provider);
        return payload;
    }
}
