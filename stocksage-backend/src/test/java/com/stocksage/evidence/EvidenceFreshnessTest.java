package com.stocksage.evidence;

import com.stocksage.agent.intent.TimeSensitivity;
import com.stocksage.evidence.EvidenceFreshness.Assessment;
import com.stocksage.evidence.EvidenceFreshness.Status;
import com.stocksage.evidence.EvidenceModels.EvidenceDimension;
import com.stocksage.evidence.EvidenceModels.EvidenceEnvelope;
import com.stocksage.evidence.EvidenceModels.EvidenceStatus;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.time.LocalDate;
import java.time.temporal.ChronoUnit;

import static org.assertj.core.api.Assertions.assertThat;

class EvidenceFreshnessTest {
    private static final Instant EVALUATED_AT = Instant.parse("2026-09-26T12:00:00Z");

    @Test void refreshingFetchAndObservationCannotRefreshAnOldPublication() {
        var oldPublications = search("w", "w", null, EVALUATED_AT.minus(8, ChronoUnit.DAYS),
                EVALUATED_AT.minus(1, ChronoUnit.DAYS), 2, 0, 0);
        var original = evidence(new EvidenceTiming(1, EVALUATED_AT.minus(1, ChronoUnit.DAYS), null, null, oldPublications));
        var refetched = evidence(new EvidenceTiming(1, EVALUATED_AT, null, null, oldPublications));
        var replayed = new EvidenceEnvelope(refetched.evidenceId(), refetched.dimension(), refetched.capabilityId(),
                refetched.targetKey(), refetched.status(), refetched.sourceRef(), refetched.provider(), EVALUATED_AT,
                EVALUATED_AT, refetched.payloadHash(), true, refetched.timing());
        Assessment expected = new Assessment(Status.STALE, "PUBLICATION_OUTSIDE_WINDOW");
        assertThat(EvidenceFreshness.assess(original, TimeSensitivity.RECENT, EVALUATED_AT)).isEqualTo(expected);
        assertThat(EvidenceFreshness.assess(replayed, TimeSensitivity.RECENT, EVALUATED_AT)).isEqualTo(expected);
        assertThat(replayed.status()).isEqualTo(EvidenceStatus.AVAILABLE);
    }

    @Test void mixedDateAndUnknownNewsCannotClaimThatTheWholeBatchIsFresh() {
        var mixed = search("w", "w", null, EVALUATED_AT.minus(1, ChronoUnit.DAYS), EVALUATED_AT, 2, 1, 1);
        assertThat(assess(mixed, TimeSensitivity.RECENT)).isEqualTo(new Assessment(Status.UNKNOWN, "PUBLICATION_TIME_UNVERIFIED"));
        var onlyDates = search("w", "w", null, null, null, 0, 2, 0);
        assertThat(assess(onlyDates, TimeSensitivity.RECENT)).isEqualTo(new Assessment(Status.UNKNOWN, "PUBLICATION_TIME_UNVERIFIED"));
        var future = search("w", "w", null, EVALUATED_AT, EVALUATED_AT.plusSeconds(1), 2, 0, 0);
        assertThat(assess(future, TimeSensitivity.RECENT)).isEqualTo(new Assessment(Status.UNKNOWN, "FUTURE_PUBLICATION"));
    }

    @Test void fallbackAndRoundedProviderWindowsCannotPretendToApplyTheRequestedDays() {
        var fallback = search("y", null, null, EVALUATED_AT, EVALUATED_AT, 1, 0, 0);
        assertThat(assess(fallback, TimeSensitivity.RECENT)).isEqualTo(new Assessment(Status.UNKNOWN, "SEARCH_WINDOW_NOT_APPLIED"));
        var rounded = search("w", "w", 5, EVALUATED_AT, EVALUATED_AT, 1, 0, 0);
        assertThat(assess(rounded, TimeSensitivity.RECENT)).isEqualTo(new Assessment(Status.UNKNOWN, "EXACT_NEWS_WINDOW_UNVERIFIED"));
        var exact = search("w", "w", 7, EVALUATED_AT.minus(7, ChronoUnit.DAYS), EVALUATED_AT, 2, 0, 0);
        assertThat(assess(exact, TimeSensitivity.RECENT)).isEqualTo(new Assessment(Status.FRESH, "PUBLICATION_WITHIN_WINDOW"));
    }

    @Test void weekendDailyBarsAndUnknownIbkrDelayStayUnverifiedWithoutATradingCalendar() {
        var daily = market("DATE", LocalDate.parse("2026-09-25"), null, null);
        assertThat(EvidenceFreshness.assess(daily, TimeSensitivity.RECENT, EVALUATED_AT))
                .isEqualTo(new Assessment(Status.UNKNOWN, "MARKET_SESSION_UNVERIFIED"));
        assertThat(EvidenceFreshness.assess(daily, TimeSensitivity.HISTORICAL, EVALUATED_AT))
                .isEqualTo(new Assessment(Status.UNKNOWN, "HISTORICAL_RANGE_UNVERIFIED"));
        var intraday = market("INSTANT", null, EVALUATED_AT, null);
        assertThat(EvidenceFreshness.assess(intraday, TimeSensitivity.REAL_TIME, EVALUATED_AT))
                .isEqualTo(new Assessment(Status.UNKNOWN, "MARKET_DELAY_UNVERIFIED"));
        assertThat(EvidenceFreshness.assess(market("INSTANT", null, EVALUATED_AT, true), TimeSensitivity.REAL_TIME, EVALUATED_AT))
                .isEqualTo(new Assessment(Status.STALE, "DELAYED_MARKET_DATA"));
        assertThat(EvidenceFreshness.assess(market("INSTANT", null, EVALUATED_AT, false), TimeSensitivity.REAL_TIME, EVALUATED_AT))
                .isEqualTo(new Assessment(Status.UNKNOWN, "MARKET_SESSION_UNVERIFIED"));
    }

    @Test void financialPeriodsAreFactsButCannotProveThatLatestDisclosuresAreComplete() {
        var financial = evidence(new EvidenceTiming(1, EVALUATED_AT, null,
                new EvidenceTiming.Financial("annual", LocalDate.parse("2025-12-31"), LocalDate.parse("2026-09-26")), null));
        assertThat(EvidenceFreshness.assess(financial, TimeSensitivity.HISTORICAL, EVALUATED_AT))
                .isEqualTo(new Assessment(Status.NOT_APPLICABLE, "HISTORICAL_PERIOD_FACTS"));
        assertThat(EvidenceFreshness.assess(financial, TimeSensitivity.UNSPECIFIED, EVALUATED_AT))
                .isEqualTo(new Assessment(Status.NOT_APPLICABLE, "PERIOD_FACTS"));
        for (var requirement : new TimeSensitivity[]{TimeSensitivity.RECENT, TimeSensitivity.REAL_TIME}) {
            assertThat(EvidenceFreshness.assess(financial, requirement, EVALUATED_AT))
                    .isEqualTo(new Assessment(Status.UNKNOWN, "LATEST_REPORT_UNVERIFIED"));
        }
    }

    @Test void fixedEvaluationTimeReplaysInclusiveWindowsWithoutConsultingTheWallClock() {
        for (var window : new String[]{"d", "w", "m", "y"}) {
            int days = switch (window) { case "d" -> 1; case "w" -> 7; case "m" -> 30; default -> 365; };
            var envelope = evidence(new EvidenceTiming(1, EVALUATED_AT, null, null,
                    search(window, window, null, EVALUATED_AT.minus(days, ChronoUnit.DAYS), EVALUATED_AT, 2, 0, 0)));
            Assessment original = EvidenceFreshness.assess(envelope, TimeSensitivity.RECENT, EVALUATED_AT);
            assertThat(original).isEqualTo(new Assessment(Status.FRESH, "PUBLICATION_WITHIN_WINDOW"));
            assertThat(EvidenceFreshness.assess(envelope, TimeSensitivity.RECENT, EVALUATED_AT)).isEqualTo(original);
            assertThat(EvidenceFreshness.assess(envelope, TimeSensitivity.RECENT, EVALUATED_AT.plusSeconds(1)))
                    .isEqualTo(new Assessment(Status.STALE, "PUBLICATION_OUTSIDE_WINDOW"));
        }
    }

    @Test void missingContractsEmptySearchesAndUnrequestedWindowsRemainExplicit() {
        var legacy = evidence(null);
        assertThat(EvidenceFreshness.assess(legacy, TimeSensitivity.RECENT, null))
                .isEqualTo(new Assessment(Status.UNKNOWN, "EVALUATION_TIME_MISSING"));
        assertThat(EvidenceFreshness.assess(legacy, TimeSensitivity.RECENT, EVALUATED_AT))
                .isEqualTo(new Assessment(Status.UNKNOWN, "TIME_CONTRACT_MISSING"));
        assertThat(EvidenceFreshness.assess(legacy, TimeSensitivity.NONE, EVALUATED_AT))
                .isEqualTo(new Assessment(Status.NOT_APPLICABLE, "TIME_NOT_REQUIRED"));
        var empty = search("w", "w", null, null, null, 0, 0, 0);
        assertThat(assess(empty, TimeSensitivity.RECENT)).isEqualTo(new Assessment(Status.NOT_APPLICABLE, "NO_RESULTS"));
        var noWindow = search(null, null, null, EVALUATED_AT, EVALUATED_AT, 1, 0, 0);
        assertThat(assess(noWindow, TimeSensitivity.RECENT)).isEqualTo(new Assessment(Status.UNKNOWN, "SEARCH_WINDOW_UNSPECIFIED"));
        assertThat(assess(noWindow, TimeSensitivity.UNSPECIFIED)).isEqualTo(new Assessment(Status.NOT_APPLICABLE, "TIME_WINDOW_NOT_REQUESTED"));
        assertThat(assess(noWindow, TimeSensitivity.HISTORICAL)).isEqualTo(new Assessment(Status.UNKNOWN, "HISTORICAL_RANGE_UNVERIFIED"));
    }

    private Assessment assess(EvidenceTiming.Search search, TimeSensitivity requirement) {
        return EvidenceFreshness.assess(evidence(new EvidenceTiming(1, EVALUATED_AT, null, null, search)), requirement, EVALUATED_AT);
    }

    private EvidenceEnvelope market(String kind, LocalDate date, Instant instant, Boolean delayed) {
        return evidence(new EvidenceTiming(1, EVALUATED_AT,
                new EvidenceTiming.Market("US", "DATE".equals(kind) ? "1d" : "1h", kind, date, instant, delayed), null, null));
    }

    private EvidenceTiming.Search search(String requested, String effective, Integer days, Instant earliest, Instant latest,
                                          int instants, int dates, int unknown) {
        return new EvidenceTiming.Search(requested, effective, days, earliest, latest,
                dates == 0 ? null : LocalDate.parse("2026-09-24"), dates == 0 ? null : LocalDate.parse("2026-09-25"),
                instants, dates, unknown);
    }

    private EvidenceEnvelope evidence(EvidenceTiming timing) {
        EvidenceDimension dimension = timing == null || timing.search() != null ? EvidenceDimension.NEWS
                : timing.market() != null ? EvidenceDimension.MARKET : EvidenceDimension.FUNDAMENTALS;
        return new EvidenceEnvelope("evidence-1", dimension, "read-only-capability", "TEST", EvidenceStatus.AVAILABLE,
                "https://example.org/source", "provider", EVALUATED_AT.minus(1, ChronoUnit.DAYS), EVALUATED_AT,
                "payload-hash", true, timing);
    }
}
