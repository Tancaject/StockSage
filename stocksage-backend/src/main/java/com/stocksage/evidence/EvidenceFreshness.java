package com.stocksage.evidence;

import com.stocksage.agent.intent.TimeSensitivity;
import com.stocksage.evidence.EvidenceModels.EvidenceEnvelope;

import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.Objects;

/** 根据领域时间事实判断时效；数据是否可用仍由 evidence.status 独立表示。 */
public final class EvidenceFreshness {
    private EvidenceFreshness() { }

    public enum Status { FRESH, STALE, UNKNOWN, NOT_APPLICABLE }

    public record Assessment(Status status, String reason) { }

    /** evaluatedAt 由调用者固定，使在线判断、checkpoint 恢复和回放采用同一时点。 */
    public static Assessment assess(EvidenceEnvelope evidence, TimeSensitivity requirement, Instant evaluatedAt) {
        Objects.requireNonNull(evidence, "evidence");
        Objects.requireNonNull(requirement, "requirement");
        if (evaluatedAt == null) return new Assessment(Status.UNKNOWN, "EVALUATION_TIME_MISSING");
        if (requirement == TimeSensitivity.NONE) return new Assessment(Status.NOT_APPLICABLE, "TIME_NOT_REQUIRED");
        EvidenceTiming timing = evidence.timing();
        if (timing == null) return new Assessment(Status.UNKNOWN, "TIME_CONTRACT_MISSING");

        if (timing.search() != null && timing.search().instantCount() == 0
                && timing.search().dateCount() == 0 && timing.search().unknownCount() == 0) {
            return new Assessment(Status.NOT_APPLICABLE, "NO_RESULTS");
        }
        if (requirement == TimeSensitivity.HISTORICAL) {
            // 期间事实可被引用，但本判定不验证用户所需历史区间是否已完整覆盖。
            return timing.financial() != null
                    ? new Assessment(Status.NOT_APPLICABLE, "HISTORICAL_PERIOD_FACTS")
                    : new Assessment(Status.UNKNOWN, "HISTORICAL_RANGE_UNVERIFIED");
        }
        if (timing.financial() != null) {
            return requirement == TimeSensitivity.UNSPECIFIED
                    ? new Assessment(Status.NOT_APPLICABLE, "PERIOD_FACTS")
                    : new Assessment(Status.UNKNOWN, "LATEST_REPORT_UNVERIFIED");
        }
        if (timing.market() != null) {
            EvidenceTiming.Market market = timing.market();
            if ("INSTANT".equals(market.timeKind()) && requirement == TimeSensitivity.REAL_TIME) {
                if (Boolean.TRUE.equals(market.delayed())) return new Assessment(Status.STALE, "DELAYED_MARKET_DATA");
                if (market.delayed() == null) return new Assessment(Status.UNKNOWN, "MARKET_DELAY_UNVERIFIED");
            }
            // 单根 bar 的日期/时刻不能证明它是最后成交或最新完成的交易周期。
            return new Assessment(Status.UNKNOWN, "MARKET_SESSION_UNVERIFIED");
        }
        return assessSearch(timing.search(), requirement, evaluatedAt);
    }

    private static Assessment assessSearch(EvidenceTiming.Search search, TimeSensitivity requirement, Instant evaluatedAt) {
        if (!Objects.equals(search.requestedWindow(), search.effectiveWindow())) {
            return new Assessment(Status.UNKNOWN, "SEARCH_WINDOW_NOT_APPLIED");
        }
        Integer effectiveDays = windowDays(search.effectiveWindow());
        if (search.requestedDays() != null && !search.requestedDays().equals(effectiveDays)) {
            return new Assessment(Status.UNKNOWN, "EXACT_NEWS_WINDOW_UNVERIFIED");
        }
        Integer days = search.requestedDays() != null ? search.requestedDays() : effectiveDays;
        if (days == null) {
            return requirement == TimeSensitivity.UNSPECIFIED
                    ? new Assessment(Status.NOT_APPLICABLE, "TIME_WINDOW_NOT_REQUESTED")
                    : new Assessment(Status.UNKNOWN, "SEARCH_WINDOW_UNSPECIFIED");
        }
        if (search.dateCount() > 0 || search.unknownCount() > 0) {
            return new Assessment(Status.UNKNOWN, "PUBLICATION_TIME_UNVERIFIED");
        }
        if (search.latestPublishedAt().isAfter(evaluatedAt)) {
            return new Assessment(Status.UNKNOWN, "FUTURE_PUBLICATION");
        }
        if (search.earliestPublishedAt().isBefore(evaluatedAt.minus(days, ChronoUnit.DAYS))) {
            return new Assessment(Status.STALE, "PUBLICATION_OUTSIDE_WINDOW");
        }
        return new Assessment(Status.FRESH, "PUBLICATION_WITHIN_WINDOW");
    }

    private static Integer windowDays(String window) {
        if (window == null) return null;
        return switch (window) {
            case "d" -> 1;
            case "w" -> 7;
            case "m" -> 30;
            case "y" -> 365;
            default -> null;
        };
    }
}
