package com.stocksage.service;

import com.stocksage.evidence.adapter.EvidenceEnvelopeMapper;

import com.stocksage.evidence.EvidenceModels.EvidenceDimension;
import com.stocksage.evidence.EvidenceModels.EvidenceStatus;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.stocksage.agent.PlanRoute;
import com.stocksage.agent.ReadRequest;
import com.stocksage.ibkr.IbkrHistoricalResponse;
import com.stocksage.harness.DeepResearchCompletionPolicy;
import com.stocksage.harness.HarnessObserver;
import com.stocksage.harness.ResearchHarness;
import com.stocksage.research.DeepEvidenceCollector;
import com.stocksage.tool.ChatStreamEmitter;
import com.stocksage.tool.FundamentalsTools;
import com.stocksage.tool.MarketTools;
import com.stocksage.tool.NewsTools;
import org.junit.jupiter.api.Test;
import org.springframework.core.task.support.TaskExecutorAdapter;
import org.springframework.test.util.ReflectionTestUtils;
import java.time.Instant;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class EvidenceEnvelopeMapperTest {
    @Test void ordinaryAndPublicDeepCollectionShareTheSameFinancialEnvelope() {
        String payload = """
                {"cik":"320193","period":"annual","metrics":{"revenue":{"data":[{"filed":"2025-02-01","value":100}]}}}
                """;
        var tickerResolution = mock(TickerResolutionService.class);
        when(tickerResolution.isLikelySecTicker("AAPL")).thenReturn(true);
        when(tickerResolution.resolveSectorForTicker("AAPL")).thenReturn("");
        var fundamentals = mock(FundamentalsTools.class);
        when(fundamentals.getFinancialReports("AAPL", "annual", 5)).thenReturn(payload);
        var mapper = new EvidenceEnvelopeMapper(tickerResolution);
        var gateway = mock(com.stocksage.capability.CapabilityGateway.class);
        when(gateway.invoke(any(), any(), any())).thenAnswer(call ->
                new com.stocksage.capability.CapabilityResult(call.getArgument(0), "local",
                        com.stocksage.capability.CapabilityResult.Status.SUCCESS, "", 0, 0));
        var collector = new DeepEvidenceCollector(mock(MarketTools.class), mock(NewsTools.class),
                fundamentals, tickerResolution, mock(ChatStreamEmitter.class),
                new TaskExecutorAdapter(Runnable::run), new ResearchHarness(mock(HarnessObserver.class)),
                new DeepResearchCompletionPolicy(), mapper, gateway);
        ReflectionTestUtils.setField(collector, "toolPrefetchMaxSearchResults", 5);
        ReflectionTestUtils.setField(collector, "toolPrefetchPerToolTimeoutSeconds", 1L);

        var collected = collector.collect("AAPL", "财报", "trace-shared-financial-envelope", 20L);
        var deep = collected.evidenceLedger().evidence().stream()
                .filter(item -> item.capabilityId().equals("getFinancialReports")).findFirst().orElseThrow();
        var ordinary = new OrdinaryEvidence("AAPL", ReadRequest.parse(PlanRoute.FUNDAMENTALS, "财报", Map.of()),
                new ObjectMapper(), mapper);
        ordinary.add("getFinancialReports", EvidenceDimension.FUNDAMENTALS, payload);

        verify(fundamentals).getFinancialReports("AAPL", "annual", 5);
        assertThat(deep.status()).isEqualTo(EvidenceStatus.AVAILABLE);
        assertThat(deep.evidenceId()).isNotBlank();
        assertThat(deep.payloadHash()).isNotBlank();
        assertThat(deep.provider()).isEqualTo("SEC EDGAR XBRL");
        assertThat(deep.sourceRef()).isEqualTo("https://data.sec.gov/api/xbrl/companyfacts/CIK0000320193.json");
        assertThat(deep.asOf()).isEqualTo(Instant.parse("2025-02-01T00:00:00Z"));
        assertThat(ordinary.ledger().evidence().get(0)).usingRecursiveComparison()
                .ignoringFields("observedAt").isEqualTo(deep);
    }

    @Test void typedSearchKeepsSourceTimePairedAndLimitsTextWithoutDroppingPublicationMetadata() throws Exception {
        var json = new ObjectMapper();
        var mapper = new EvidenceEnvelopeMapper(new TickerResolutionService(null, null, json));
        ObjectNode fixture = (ObjectNode) json.readTree(Files.readString(
                Path.of("../stocksage-data-service/tests/fixtures/search-news-v1.json")));
        JsonNode first = fixture.path("results").get(0);
        var envelope = mapper.map(EvidenceDimension.NEWS, "TEST", "local.news.searchNews", fixture.toString(),
                EvidenceStatus.AVAILABLE, Instant.now(), true);
        assertThat(envelope.status()).isEqualTo(EvidenceStatus.AVAILABLE);
        assertThat(envelope.sourceRef()).isEqualTo(first.path("link").asText());
        assertThat(envelope.asOf()).isEqualTo(Instant.parse(first.path("publishedAt").asText()));
        assertThat(envelope.timing().search().instantCount()).isEqualTo(1);
        assertThat(envelope.timing().search().dateCount()).isEqualTo(1);
        assertThat(envelope.timing().search().unknownCount()).isEqualTo(1);
        var reordered = json.createArrayNode();
        for (JsonNode row : fixture.path("results")) if (row.path("publishedTimeKind").asText().equals("UNKNOWN")) reordered.add(row);
        for (JsonNode row : fixture.path("results")) if (!row.path("publishedTimeKind").asText().equals("UNKNOWN")) reordered.add(row);
        ObjectNode unknownFirst = fixture.deepCopy();
        unknownFirst.set("results", reordered);
        unknownFirst.put("asOf", "2099-01-01T00:00:00Z");
        var unknown = mapper.map(EvidenceDimension.NEWS, "TEST", "searchNews", unknownFirst.toString(),
                EvidenceStatus.AVAILABLE, Instant.now(), true);
        assertThat(unknown.sourceRef()).isEqualTo(reordered.get(0).path("link").asText());
        assertThat(unknown.asOf()).isNull();
        ObjectNode invalid = fixture.deepCopy();
        ((ObjectNode) invalid.path("results").get(0)).put("link", "javascript:alert(1)");
        assertThat(mapper.inspectStatus("searchNews", invalid.toString())).isEqualTo(EvidenceStatus.FAILED);

        ObjectNode expanded = fixture.deepCopy();
        ((ObjectNode) expanded.path("results").get(0)).put("snippet", "Provider excerpt. ".repeat(1000));
        var ordinary = new OrdinaryEvidence("TEST", new ReadRequest("3m", "1d", "annual", 5, false, ""), json, mapper);
        ordinary.add("searchNews", EvidenceDimension.NEWS, expanded.toString());
        assertThat(ordinary.observations().get(0)).containsEntry("gap", "PUBLICATION_TIME_UNVERIFIED")
                .containsEntry("citable", true).containsEntry("reduced", true);
        assertThat(ordinary.observations().get(0).get("timing")).isEqualTo(envelope.timing());
        var reduced = json.readTree(ordinary.context().substring(ordinary.context().indexOf('{')));
        assertThat(reduced.toString().length()).isLessThanOrEqualTo(2200);
        var visible = reduced.path("results").get(0);
        for (String key : List.of("link", "title", "date", "source", "publishedTimeKind", "publishedAt", "publishedDate", "publishedTimeBasis"))
            assertThat(visible.path(key)).isEqualTo(first.path(key));
        assertThat(visible.path("snippetTruncated").asBoolean()).isTrue();
        assertThat(expanded.path("results").get(0).path("snippet").asText()).startsWith(visible.path("snippet").asText());

        ((ObjectNode) expanded.path("results").get(0)).put("link", "https://example.com/" + "x".repeat(6000));
        var omitted = new OrdinaryEvidence("TEST", new ReadRequest("3m", "1d", "annual", 5, false, ""), json, mapper);
        omitted.add("searchNews", EvidenceDimension.NEWS, expanded.toString());
        assertThat(omitted.observations().get(0)).containsEntry("gap", "CONTEXT_BUDGET").containsEntry("citable", false);
        assertThat(omitted.citationIds()).isEmpty();
    }

    @Test void typedSearchFallbackAndEmptyResultsKeepActualWindowAndFailureSemantics() throws Exception {
        var json = new ObjectMapper();
        var mapper = new EvidenceEnvelopeMapper(new TickerResolutionService(null, null, json));
        ObjectNode fixture = (ObjectNode) json.readTree(Files.readString(
                Path.of("../stocksage-data-service/tests/fixtures/search-fallback-v1.json")));
        var ordinary = new OrdinaryEvidence("TEST", new ReadRequest("3m", "1d", "annual", 5, false, ""), json, mapper);
        ordinary.add("local.news.searchNews", EvidenceDimension.NEWS, fixture.toString());
        assertThat(ordinary.observations().get(0)).containsEntry("gap", "SEARCH_WINDOW_NOT_APPLIED")
                .containsEntry("status", "AVAILABLE").containsEntry("citable", true);
        assertThat(ordinary.allIncluded()).isFalse();
        assertThat(ordinary.evolutionEvidenceTags()).isEmpty();
        fixture.put("status", "EMPTY").put("count", 0);
        fixture.set("results", json.createArrayNode());
        assertThat(mapper.inspectStatus("searchNews", fixture.toString())).isEqualTo(EvidenceStatus.NO_RESULTS);
        var empty = mapper.map(EvidenceDimension.NEWS, "TEST", "searchNews", fixture.toString(),
                EvidenceStatus.AVAILABLE, Instant.now(), true);
        assertThat(empty.provider()).isEqualTo("ddg");
        assertThat(empty.asOf()).isNull();
        fixture.put("error", true).put("status", "ERROR").put("errorCode", "UPSTREAM_ERROR");
        assertThat(mapper.inspectStatus("searchNews", fixture.toString())).isEqualTo(EvidenceStatus.FAILED);
    }

    @Test void typedHkFinancialsRetainPartialFactsWithActualPeriodsAndBoundedCompleteItems() throws Exception {
        var json = new ObjectMapper();
        var mapper = new EvidenceEnvelopeMapper(new TickerResolutionService(null, null, json));
        ObjectNode fixture = (ObjectNode) json.readTree(Files.readString(
                Path.of("../stocksage-data-service/tests/fixtures/hk-financials-v1.json")));
        String ticker = fixture.path("resolvedCode").asText();
        var envelope = mapper.map(EvidenceDimension.FUNDAMENTALS, ticker, "getFinancialReports",
                fixture.toString(), EvidenceStatus.AVAILABLE, Instant.now(), true);
        assertThat(envelope.status()).isEqualTo(EvidenceStatus.AVAILABLE);
        assertThat(envelope.targetKey()).isEqualTo(ticker);
        assertThat(envelope.asOf()).isEqualTo(Instant.parse(fixture.path("asOf").asText() + "T00:00:00Z"));
        assertThat(envelope.sourceRef()).startsWith("provider://akshare/");
        assertThat(mapper.inspectStatus("getFinancialReports", fixture.deepCopy().put("asOf", "2099-12-31").toString()))
                .isEqualTo(EvidenceStatus.FAILED);

        var request = new ReadRequest("3m", "1d", "annual", fixture.path("requestedYears").asInt(), false, "");
        var ordinary = new OrdinaryEvidence(ticker, request, json, mapper);
        ordinary.add("getFinancialReports", EvidenceDimension.FUNDAMENTALS,
                fixture.deepCopy().put("unusedProviderDetail", "x".repeat(5000)).toString());
        assertThat(ordinary.observations().get(0)).containsEntry("gap", "REPORT_TABLES_INCOMPLETE")
                .containsEntry("status", "AVAILABLE").containsEntry("citable", true);
        assertThat(ordinary.allIncluded()).isFalse();
        JsonNode reduced = json.readTree(ordinary.context().substring(ordinary.context().indexOf('{')));
        assertThat(reduced.toString().length()).isLessThanOrEqualTo(2200);
        assertThat(reduced.path("contextReduced").asBoolean()).isTrue();
        assertThat(reduced.path("numericPrecision").asText()).isEqualTo("PROVIDER_VALUE");
        assertThat(reduced.path("currency").isNull()).isTrue();
        assertThat(reduced.path("aggregationBasis").asText()).isEqualTo("UNKNOWN");
        int retained = 0;
        for (String name : List.of("balanceSheet", "incomeStatement", "cashFlow", "indicators")) {
            JsonNode source = name.equals("indicators") ? fixture.path(name) : fixture.path("statements").path(name);
            JsonNode view = name.equals("indicators") ? reduced.path(name) : reduced.path("statements").path(name);
            assertThat(view.path("status")).isEqualTo(source.path("status"));
            for (JsonNode row : view.path("data")) {
                var originals = new java.util.ArrayList<JsonNode>();
                source.path("data").forEach(originals::add);
                assertThat(originals).contains(row);
                retained++;
            }
        }
        assertThat(retained).isPositive();
        var wrongRequest = new OrdinaryEvidence(ticker,
                new ReadRequest("3m", "1d", "annual", request.reportCount() + 1, false, ""), json, mapper);
        wrongRequest.add("getFinancialReports", EvidenceDimension.FUNDAMENTALS, fixture.toString());
        assertThat(wrongRequest.observations().get(0)).containsEntry("gap", "REPORT_REQUEST_MISMATCH")
                .containsEntry("citable", false);
    }

    @Test void hkCoverageUsesValuedAlignedDatesAndNeverCallsReportPeriodsStandaloneQuarters() throws Exception {
        var json = new ObjectMapper();
        var mapper = new EvidenceEnvelopeMapper(new TickerResolutionService(null, null, json));
        ObjectNode fixture = (ObjectNode) json.readTree(Files.readString(
                Path.of("../stocksage-data-service/tests/fixtures/hk-financials-v1.json")));
        ((ObjectNode) fixture.path("statements")).set("cashFlow", fixture.path("statements").path("balanceSheet").deepCopy());
        int count = fixture.path("indicators").path("data").size();
        for (JsonNode table : fixture.path("statements")) count += table.path("data").size();
        fixture.put("status", "SUCCESS").put("statementCount", count);
        var nullPeriod = new OrdinaryEvidence(fixture.path("resolvedCode").asText(),
                new ReadRequest("3m", "1d", "annual", 2, false, ""), json, mapper);
        nullPeriod.add("getFinancialReports", EvidenceDimension.FUNDAMENTALS, fixture.toString());
        assertThat(nullPeriod.observations().get(0)).containsEntry("gap", "REPORT_COUNT_INCOMPLETE")
                .containsEntry("status", "AVAILABLE").containsEntry("citable", true);
        ObjectNode statement = (ObjectNode) fixture.path("statements").path("balanceSheet").deepCopy();
        var valued = json.createArrayNode();
        for (JsonNode row : statement.path("data")) if (!row.path("AMOUNT").isNull()) valued.add(row);
        String latest = fixture.path("asOf").asText();
        var onePeriod = json.createArrayNode();
        for (JsonNode row : valued) if (row.path("REPORT_DATE").asText().equals(latest)) onePeriod.add(row);
        statement.set("data", onePeriod);
        statement.put("status", "SUCCESS").putNull("errorCode").putNull("message").putNull("retryable");
        for (String name : List.of("balanceSheet", "incomeStatement", "cashFlow"))
            ((ObjectNode) fixture.path("statements")).set(name, statement.deepCopy());
        ObjectNode indicators = (ObjectNode) fixture.path("indicators");
        var indicatorRows = json.createArrayNode();
        for (JsonNode row : indicators.path("data"))
            if (row.path("REPORT_DATE").asText().equals(latest)) indicatorRows.add(row);
        indicators.set("data", indicatorRows);
        fixture.put("status", "SUCCESS").put("requestedYears", 2)
                .put("statementCount", onePeriod.size() * 3 + indicatorRows.size());
        String ticker = fixture.path("resolvedCode").asText();
        var two = new OrdinaryEvidence(ticker, new ReadRequest("3m", "1d", "annual", 2, false, ""), json, mapper);
        two.add("getFinancialReports", EvidenceDimension.FUNDAMENTALS, fixture.toString());
        assertThat(two.observations().get(0)).containsEntry("gap", "REPORT_COUNT_INCOMPLETE").containsEntry("citable", true);
        assertThat(two.evolutionEvidenceTags()).isEmpty();
        assertThat(two.evolutionTaskTags()).doesNotContain("period-comparison");
        fixture.put("requestedYears", 1);
        var one = new OrdinaryEvidence(ticker, new ReadRequest("3m", "1d", "annual", 1, false, ""), json, mapper);
        one.add("getFinancialReports", EvidenceDimension.FUNDAMENTALS, fixture.toString());
        assertThat(one.observations().get(0)).containsEntry("gap", "").containsEntry("citable", true);
        fixture.put("requestedPeriod", "quarterly").put("period", "report_period");
        var quarterly = new OrdinaryEvidence(ticker, new ReadRequest("3m", "1d", "quarterly", 1, false, ""), json, mapper);
        quarterly.add("getFinancialReports", EvidenceDimension.FUNDAMENTALS, fixture.toString());
        assertThat(quarterly.observations().get(0)).containsEntry("gap", "REPORT_QUARTER_BASIS_UNVERIFIED")
                .containsEntry("status", "AVAILABLE").containsEntry("citable", true);
        fixture.put("requestedPeriod", "annual").put("period", "annual");
        indicatorRows.forEach(row -> ((ObjectNode) row).put("REPORT_DATE", "2023-12-31").putNull("START_DATE"));
        var unaligned = new OrdinaryEvidence(ticker, new ReadRequest("3m", "1d", "annual", 1, false, ""), json, mapper);
        unaligned.add("getFinancialReports", EvidenceDimension.FUNDAMENTALS, fixture.toString());
        assertThat(unaligned.observations().get(0)).containsEntry("gap", "REPORT_PERIODS_UNALIGNED").containsEntry("citable", true);
    }

    @Test void typedASharePartialReportsKeepActualPeriodsAndCompleteStatementContext() throws Exception {
        var json = new ObjectMapper();
        var mapper = new EvidenceEnvelopeMapper(new TickerResolutionService(null, null, json));
        ObjectNode fixture = (ObjectNode) json.reader().with(DeserializationFeature.USE_BIG_DECIMAL_FOR_FLOATS).readTree(Files.readString(
                Path.of("../stocksage-data-service/tests/fixtures/a-share-financials-v1.json")));
        String ticker = fixture.path("code").asText();
        var envelope = mapper.map(EvidenceDimension.FUNDAMENTALS, ticker, "Fundamentals Agent/getFinancialReports(annual,1)",
                fixture.toString(), EvidenceStatus.AVAILABLE, Instant.parse("2026-09-26T12:00:00Z"), true);
        assertThat(envelope.status()).isEqualTo(EvidenceStatus.AVAILABLE);
        assertThat(envelope.provider()).isEqualTo("baostock");
        assertThat(envelope.asOf()).isEqualTo(Instant.parse(fixture.path("asOf").asText() + "T00:00:00Z"));
        assertThat(envelope.sourceRef()).startsWith("provider://baostock/");
        assertThat(mapper.inspectStatus("getFinancialReports", fixture.deepCopy().put("asOf", "2099-12-31").toString()))
                .isEqualTo(EvidenceStatus.FAILED);
        ObjectNode invalid = fixture.deepCopy();
        ((ObjectNode) invalid.path("reports").get(0).path("statements").path("profit").path("data")).put("netProfit", true);
        assertThat(mapper.inspectStatus("getFinancialReports", invalid.toString())).isEqualTo(EvidenceStatus.FAILED);

        var request = new ReadRequest("3m", "1d", "annual", fixture.path("requestedYears").asInt(), false, "");
        var ordinary = new OrdinaryEvidence(ticker, request, json, mapper);
        ordinary.add("getFinancialReports", EvidenceDimension.FUNDAMENTALS,
                fixture.deepCopy().put("unusedProviderDetail", "x".repeat(5000)).toString());
        assertThat(ordinary.observations().get(0)).containsEntry("gap", "REPORT_TABLES_INCOMPLETE")
                .containsEntry("status", "AVAILABLE").containsEntry("citable", true);
        assertThat(ordinary.allIncluded()).isFalse();
        String context = ordinary.context();
        JsonNode reduced = json.reader().with(DeserializationFeature.USE_BIG_DECIMAL_FOR_FLOATS)
                .readTree(context.substring(context.indexOf('{')));
        assertThat(reduced.toString().length()).isLessThanOrEqualTo(2200);
        assertThat(reduced.path("contextReduced").asBoolean()).isTrue();
        assertThat(reduced.path("valueScale").asText()).isEqualTo("PROVIDER_RAW");
        assertThat(reduced.path("valueEncoding").asText()).isEqualTo("DECIMAL_STRING");
        assertThat(reduced.path("aggregationBasis").asText()).isEqualTo("UNKNOWN");
        assertThat(reduced.path("reports").get(0).path("statements").path("profit"))
                .isEqualTo(fixture.path("reports").get(0).path("statements").path("profit"));

        var wrongRequest = new OrdinaryEvidence(ticker,
                new ReadRequest("3m", "1d", "annual", request.reportCount() + 1, false, ""), json, mapper);
        wrongRequest.add("getFinancialReports", EvidenceDimension.FUNDAMENTALS, fixture.toString());
        assertThat(wrongRequest.observations().get(0)).containsEntry("gap", "REPORT_REQUEST_MISMATCH")
                .containsEntry("citable", false);
    }

    @Test void secReportCoverageCountsDistinctSharedPeriodsAndKeepsPartialFactsQualified() throws Exception {
        var json = new ObjectMapper();
        var mapper = new EvidenceEnvelopeMapper(new TickerResolutionService(null, null, json));
        ObjectNode fixture = (ObjectNode) json.readTree(Files.readString(
                Path.of("../stocksage-data-service/tests/fixtures/sec-financials-v1.json")));
        fixture.put("requestedPeriod", "annual").put("requestedYears", 2);
        // 同一年度的多条事实与多个指标都不能补足第二个年度。
        fixture.path("metrics").forEach(metric -> {
            var rows = (com.fasterxml.jackson.databind.node.ArrayNode) metric.path("data");
            rows.add(rows.get(0).deepCopy());
        });
        var partial = new OrdinaryEvidence("TEST", new ReadRequest("3m", "1d", "annual", 2, false, ""), json, mapper);
        partial.add("getFinancialReports", EvidenceDimension.FUNDAMENTALS, fixture.toString());
        assertThat(partial.observations().get(0)).containsEntry("gap", "REPORT_COUNT_INCOMPLETE")
                .containsEntry("status", "AVAILABLE").containsEntry("citable", true);
        assertThat(partial.allIncluded()).isFalse();

        fixture.put("requestedYears", 1);
        var enough = new OrdinaryEvidence("TEST", new ReadRequest("3m", "1d", "annual", 1, false, ""), json, mapper);
        enough.add("getFinancialReports", EvidenceDimension.FUNDAMENTALS, fixture.toString());
        assertThat(enough.observations().get(0)).containsEntry("gap", "").containsEntry("citable", true);
        var wrongRequest = new OrdinaryEvidence("TEST", new ReadRequest("3m", "1d", "annual", 2, false, ""), json, mapper);
        wrongRequest.add("getFinancialReports", EvidenceDimension.FUNDAMENTALS, fixture.toString());
        assertThat(wrongRequest.observations().get(0)).containsEntry("gap", "REPORT_REQUEST_MISMATCH")
                .containsEntry("citable", false);

        fixture.path("metrics").path("Revenue").path("data").forEach(fact -> {
            ((ObjectNode) fact).put("start", "2024-02-01").put("end", "2025-01-31");
        });
        var unaligned = new OrdinaryEvidence("TEST", new ReadRequest("3m", "1d", "annual", 1, false, ""), json, mapper);
        unaligned.add("getFinancialReports", EvidenceDimension.FUNDAMENTALS, fixture.toString());
        assertThat(unaligned.observations().get(0)).containsEntry("gap", "REPORT_PERIODS_UNALIGNED")
                .containsEntry("citable", true);
    }

    @Test void typedSecFinancialsUseActualPeriodsAndKeepCompleteFactsWhenContextIsReduced() throws Exception {
        var json = new ObjectMapper();
        var mapper = new EvidenceEnvelopeMapper(new TickerResolutionService(null, null, json));
        ObjectNode fixture = (ObjectNode) json.reader().with(DeserializationFeature.USE_BIG_DECIMAL_FOR_FLOATS).readTree(Files.readString(
                Path.of("../stocksage-data-service/tests/fixtures/sec-financials-v1.json")));
        ((ObjectNode) fixture.path("metrics").path("EPS").path("data").get(0))
                .put("value", new java.math.BigDecimal("2.7500000000000001"));
        String ticker = fixture.path("ticker").asText();
        var envelope = mapper.map(EvidenceDimension.FUNDAMENTALS, ticker, "Fundamentals Agent/getStructuredFinancials",
                fixture.toString(), EvidenceStatus.AVAILABLE, Instant.parse("2026-09-26T12:00:00Z"), true);
        assertThat(envelope.status()).isEqualTo(EvidenceStatus.AVAILABLE);
        assertThat(envelope.provider()).isEqualTo("SEC_EDGAR");
        assertThat(envelope.asOf()).isEqualTo(Instant.parse(fixture.path("asOf").asText() + "T00:00:00Z"));
        assertThat(envelope.sourceRef()).startsWith("https://data.sec.gov/api/xbrl/companyfacts/CIK");

        ObjectNode invalid = fixture.deepCopy().put("asOf", "2099-01-01");
        assertThat(mapper.inspectStatus("getStructuredFinancials", invalid.toString())).isEqualTo(EvidenceStatus.FAILED);
        ObjectNode empty = fixture.deepCopy().put("status", "EMPTY").put("metric_count", 0).putNull("asOf");
        empty.set("metrics", json.createObjectNode());
        empty.set("diagnostic", fixture);
        assertThat(mapper.inspectStatus("getStructuredFinancials", empty.toString())).isEqualTo(EvidenceStatus.EMPTY);

        ObjectNode comparable = fixture.deepCopy().put("metric_count", 1).put("requestedPeriod", "annual").put("requestedYears", 2);
        ObjectNode revenue = (ObjectNode) fixture.path("metrics").path("Revenue").deepCopy();
        ((com.fasterxml.jackson.databind.node.ArrayNode) revenue.path("data")).add(
                ((ObjectNode) revenue.path("data").get(0)).deepCopy().put("start", "2024-02-01").put("end", "2025-01-31"));
        comparable.putObject("metrics").set("Revenue", revenue);
        var qualified = new OrdinaryEvidence(ticker, new ReadRequest("3m", "1d", "annual", 2, false, ""), json, mapper);
        qualified.add("getFinancialReports", EvidenceDimension.FUNDAMENTALS, comparable.toString());
        assertThat(qualified.allIncluded()).isTrue();
        assertThat(qualified.evolutionEvidenceTags()).containsExactlyInAnyOrder("financial-evidence", "dated-values", "structured-financials");
        assertThat(qualified.evolutionTaskTags()).containsExactlyInAnyOrder("fundamentals", "annual", "period-comparison");
        var incomplete = new OrdinaryEvidence(ticker, new ReadRequest("3m", "1d", "annual", 3, false, ""), json, mapper);
        comparable.put("triggerTags", "period-comparison").put("requiredEvidence", "dated-values");
        incomplete.add("getFinancialReports", EvidenceDimension.FUNDAMENTALS, comparable.toString());
        assertThat(incomplete.evolutionEvidenceTags()).isEmpty();
        assertThat(incomplete.evolutionTaskTags()).doesNotContain("period-comparison");

        ObjectNode expanded = fixture.deepCopy().put("unusedProviderDetail", "x".repeat(5000));
        var ordinary = new OrdinaryEvidence(ticker, new ReadRequest("3m", "1d", "annual", 5, false, ""), json, mapper);
        ordinary.add("getStructuredFinancials", EvidenceDimension.FUNDAMENTALS, expanded.toString());
        assertThat(ordinary.citationIds()).containsExactly("E1");
        assertThat(ordinary.allIncluded()).isFalse();
        String context = ordinary.context();
        JsonNode reduced = json.reader().with(DeserializationFeature.USE_BIG_DECIMAL_FOR_FLOATS)
                .readTree(context.substring(context.indexOf('{')));
        assertThat(reduced.path("contextReduced").asBoolean()).isTrue();
        assertThat(reduced.toString().length()).isLessThanOrEqualTo(2200);
        assertThat(reduced.path("metrics").isEmpty()).isFalse();
        assertThat(reduced.path("metrics").path("EPS").path("data").get(0).path("value").decimalValue())
                .isEqualByComparingTo("2.7500000000000001");
        reduced.path("metrics").fields().forEachRemaining(entry -> {
            JsonNode original = fixture.path("metrics").path(entry.getKey());
            assertThat(entry.getValue().path("unit")).isEqualTo(original.path("unit"));
            assertThat(entry.getValue().path("concept")).isEqualTo(original.path("concept"));
            JsonNode expectedLatest = null;
            for (JsonNode fact : original.path("data")) {
                if (expectedLatest == null || fact.path("end").asText().compareTo(expectedLatest.path("end").asText()) > 0) expectedLatest = fact;
            }
            assertThat(entry.getValue().path("data").get(0)).isEqualTo(expectedLatest);
        });
    }

    @Test void typedIbkrHistoryKeepsActualHkTargetAndBarTimeInOrdinaryEvidence() throws Exception {
        ObjectMapper json = new ObjectMapper();
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
        String raw = json.valueToTree(IbkrHistoricalResponse.fromJson(payload)).toString();
        var mapper = new EvidenceEnvelopeMapper(new TickerResolutionService(null, null, json));
        var envelope = mapper.map(EvidenceDimension.MARKET, "0700.HK", "Market Agent/getIbkrHistoricalBars(1w,1h)",
                raw, EvidenceStatus.AVAILABLE, Instant.now(), true);
        assertThat(envelope.status()).isEqualTo(EvidenceStatus.AVAILABLE);
        assertThat(envelope.targetKey()).isEqualTo("0700.HK");
        assertThat(envelope.asOf()).isEqualTo(Instant.ofEpochMilli(1747229700000L));
        assertThat(envelope.sourceRef()).contains("conid=12345", "period=1w", "bar=1h");
        var ordinary = new OrdinaryEvidence("0700.HK", new ReadRequest("1w", "1h", "annual", 5, true, ""), json, mapper);
        ordinary.add("getIbkrHistoricalBars", EvidenceDimension.MARKET, raw);
        assertThat(ordinary.citationIds()).containsExactly("E1");
        assertThat(ordinary.context()).contains("EPOCH_MILLIS", "UNKNOWN", "priceFactor", "mktDataDelay");

        ObjectNode contradictory = payload.deepCopy().put("asOf", "2026-09-25T01:00:00Z");
        assertThat(mapper.inspectStatus("getIbkrHistoricalBars", contradictory.toString())).isEqualTo(EvidenceStatus.FAILED);
        assertThat(new KLinePayloadMapper(json).toChartPayload("getIbkrHistoricalBars", contradictory, new Object[0])).isEmpty();
    }

    @Test void versionedKlineUsesBusinessDateAndRejectsContradictorySuccessWithoutGuessingNestedData() throws Exception {
        var json = new ObjectMapper();
        var mapper = new EvidenceEnvelopeMapper(new TickerResolutionService(null, null, json));
        var chartMapper = new KLinePayloadMapper(json);
        ObjectNode fixture = (ObjectNode) json.readTree(Files.readString(Path.of("../stocksage-data-service/tests/fixtures/kline-v1.json")));
        var envelope = mapper.map(EvidenceDimension.MARKET, "sh.600519", "Market Agent/getStockKLine(daily,60)",
                fixture.toString(), EvidenceStatus.AVAILABLE, Instant.parse("2026-09-26T12:00:00Z"), true);
        assertThat(envelope.status()).isEqualTo(EvidenceStatus.AVAILABLE);
        assertThat(envelope.asOf()).isEqualTo(Instant.parse("2026-09-24T00:00:00Z"));
        assertThat(envelope.provider()).isEqualTo("baostock");
        assertThat(envelope.sourceRef()).contains("provider://baostock/").contains("sh.600519");
        var chart = chartMapper.toChartPayload("getStockKLine", fixture, new Object[0]).orElseThrow();
        assertThat(chart).containsEntry("asOf", "2026-09-24").containsEntry("fetchedAt", "2026-09-25T01:02:03Z")
                .containsEntry("timeKind", "DATE").containsEntry("volumeUnit", "UNKNOWN")
                .containsEntry("currency", null).containsEntry("adjustment", "FORWARD_ADJUSTED");

        ObjectNode missingPrice = fixture.deepCopy();
        ((ObjectNode) missingPrice.path("data").get(1)).putNull("close");
        for (ObjectNode invalid : List.of(missingPrice, fixture.deepCopy().put("count", 1),
                fixture.deepCopy().put("schemaVersion", 4294967297L), fixture.deepCopy().put("asOf", "2026-09-25"),
                fixture.deepCopy().put("provider", ""), fixture.deepCopy().put("period", "intraday"),
                fixture.deepCopy().put("currency", "USD"),
                fixture.deepCopy().put("resolvedCode", ""), fixture.deepCopy().put("status", "EMPTY"))) {
            assertThat(mapper.inspectStatus("getStockKLine", invalid.toString())).isEqualTo(EvidenceStatus.FAILED);
            assertThat(chartMapper.toChartPayload("getStockKLine", invalid, new Object[0])).isEmpty();
        }
        ObjectNode empty = fixture.deepCopy().put("status", "EMPTY").put("count", 0).putNull("asOf");
        empty.set("data", json.createArrayNode());
        empty.set("diagnostic", fixture);
        assertThat(mapper.inspectStatus("getStockKLine", empty.toString())).isEqualTo(EvidenceStatus.EMPTY);
        assertThat(chartMapper.toChartPayload("getStockKLine", empty, new Object[0])).isEmpty();
        ObjectNode error = fixture.deepCopy().put("status", "ERROR").put("error", true).putNull("asOf");
        assertThat(mapper.inspectStatus("getStockKLine", error.toString())).isEqualTo(EvidenceStatus.FAILED);
        assertThat(chartMapper.toChartPayload("getStockKLine", error, new Object[0])).isEmpty();
    }

    @Test void ordinaryAndDeepRejectMetadataWithoutBusinessDataAndKeepZeroValues() {
        var mapper = new EvidenceEnvelopeMapper(new TickerResolutionService(null, null, new ObjectMapper()));
        String sec = "{\"ticker\":\"AAPL\",\"cik\":\"320193\",\"metric_count\":0,\"metrics\":{}}";
        for (String payload : List.of(sec, sec.replace("{}", "{\"revenue\":{\"data\":[{\"year\":2025,\"value\":null}]}}"),
                "{\"provider\":\"SEC\",\"reports\":[{\"year\":2025,\"quarter\":4}]}")) {
            assertThat(mapper.map(EvidenceDimension.FUNDAMENTALS, "AAPL", "getFinancialReports", payload,
                    EvidenceStatus.AVAILABLE, Instant.now(), true).status()).isEqualTo(EvidenceStatus.EMPTY);
            var ordinary = new OrdinaryEvidence("AAPL", ReadRequest.parse(PlanRoute.FUNDAMENTALS, "财报", Map.of()), new ObjectMapper(), mapper);
            ordinary.add("getFinancialReports", EvidenceDimension.FUNDAMENTALS, payload);
            assertThat(ordinary.hasUsefulResult()).isFalse();
        }
        for (String payload : List.of("{\"data\":{\"data\":[]}}", "{\"data\":[{\"t\":1788000000000}]}",
                "{\"data\":[{\"o\":\"NaN\",\"h\":2,\"l\":0,\"c\":1}]}")) {
            assertThat(mapper.inspectStatus("Market Agent/getIbkrHistoricalBars(3m,1d)", payload)).isEqualTo(EvidenceStatus.EMPTY);
        }
        assertThat(mapper.inspectStatus("getStructuredFinancials", "{\"cik\":\"320193\",\"metrics\":{\"revenue\":{\"data\":[{\"value\":0}]}}}"))
                .isEqualTo(EvidenceStatus.AVAILABLE);
        for (String tool : List.of("getStructuredFinancials", "getFinancialReports")) {
            String metadataOnly = "{\"cik\":\"320193\",\"metrics\":{\"revenue\":{\"data\":[{\"filing_fiscal_year\":2025,\"value\":null}]}}}";
            assertThat(mapper.inspectStatus(tool, metadataOnly)).isEqualTo(EvidenceStatus.EMPTY);
            assertThat(mapper.inspectStatus(tool, metadataOnly.replace("null", "0"))).isEqualTo(EvidenceStatus.AVAILABLE);
            var ordinary = new OrdinaryEvidence("AAPL", ReadRequest.parse(PlanRoute.FUNDAMENTALS, "财报", Map.of()), new ObjectMapper(), mapper);
            ordinary.add(tool, EvidenceDimension.FUNDAMENTALS, metadataOnly);
            assertThat(ordinary.hasUsefulResult()).isFalse();
        }
        assertThat(mapper.inspectStatus("getFinancialReports", "{\"reports\":[{\"year\":2025,\"statements\":{\"profit\":{\"roe\":\"0\"}}}]}"))
                .isEqualTo(EvidenceStatus.AVAILABLE);
        assertThat(mapper.inspectStatus("getTechnicalIndicators", "{\"indicators\":{\"RSI14\":null}}"))
                .isEqualTo(EvidenceStatus.EMPTY);
        assertThat(mapper.inspectStatus("getIbkrHistoricalBars", "{\"message\":\"unavailable\", \"error\": true}"))
                .isEqualTo(EvidenceStatus.FAILED);
        assertThat(mapper.map(EvidenceDimension.NEWS, "AAPL", "searchNews", "{\"provider\":\"tavily\",\"results\":[]}",
                EvidenceStatus.AVAILABLE, Instant.now(), true).status()).isEqualTo(EvidenceStatus.NO_RESULTS);
    }
}
