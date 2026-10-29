package com.stocksage.evidence;

import com.stocksage.agent.intent.TimeSensitivity;
import com.stocksage.evidence.EvidenceModels.EvidenceEnvelope;

import java.time.Instant;
import java.time.LocalDate;
import java.time.DayOfWeek;
import java.time.ZoneId;
import java.time.ZoneOffset;
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
            if (requirement == TimeSensitivity.UNSPECIFIED) return new Assessment(Status.NOT_APPLICABLE, "PERIOD_FACTS");
            // 允许现有工具缓存（最长 12 小时），旧快照不能仅凭报告期进入披露容忍期。
            if (timing.fetchedAt().isBefore(evaluatedAt.minus(1, ChronoUnit.DAYS))) {
                return new Assessment(Status.UNKNOWN, "FINANCIAL_FETCH_OUTDATED");
            }
            return assessFinancial(timing.financial(), evaluatedAt);
        }
        if (timing.market() != null) {
            return assessMarket(timing.market(), requirement, evaluatedAt);
        }
        return assessSearch(timing.search(), requirement, evaluatedAt);
    }

    /**
     * 财报的"新鲜"指拿到的是最新一份已申报报告，而不是报告期离现在多近：最新报告本身多旧由报告期写在证据里。
     * 有申报索引时直接核对；没有时（港股、A 股、索引读取失败）按各市场披露期限估算下一份报告最晚何时该出。
     */
    private static Assessment assessFinancial(EvidenceTiming.Financial financial, Instant evaluatedAt) {
        if (financial.latestFiledReport() != null) {
            if (financial.latestDisclosureDate() != null && !financial.latestDisclosureDate().isBefore(financial.latestFiledReport())) {
                return new Assessment(Status.FRESH, "LATEST_FILED_REPORT");
            }
            // 结构化财务缺最新申报是真的过时；原文摘录只是没检索到最新申报里的段落，较早申报的内容未必失效。
            return financial.latestPeriodEnd() != null
                    ? new Assessment(Status.STALE, "NEWER_REPORT_FILED")
                    : new Assessment(Status.UNKNOWN, "LATEST_FILING_NOT_IN_PASSAGES");
        }
        LocalDate due = nextReportDue(financial);
        return due != null && evaluatedAt.isBefore(due.atStartOfDay(ZoneOffset.UTC).toInstant())
                ? new Assessment(Status.FRESH, "REPORT_WITHIN_DISCLOSURE_ALLOWANCE")
                : new Assessment(Status.UNKNOWN, "LATEST_REPORT_UNVERIFIED");
    }

    private static Assessment assessMarket(EvidenceTiming.Market market, TimeSensitivity requirement, Instant evaluatedAt) {
        ZoneId zone = switch (market.market() == null ? "" : market.market()) {
            case "US" -> ZoneId.of("America/New_York");
            case "HK" -> ZoneId.of("Asia/Hong_Kong");
            default -> ZoneId.of("Asia/Shanghai");
        };
        boolean realTime = requirement == TimeSensitivity.REAL_TIME;
        if (realTime && sessionOpen(market.market(), evaluatedAt.atZone(zone))) {
            if (Boolean.TRUE.equals(market.delayed())) return new Assessment(Status.STALE, "DELAYED_MARKET_DATA");
            if (market.delayed() == null || market.latestInstant() == null) {
                return new Assessment(Status.UNKNOWN, "MARKET_DELAY_UNVERIFIED");
            }
            if (market.latestInstant().isAfter(evaluatedAt)) return new Assessment(Status.UNKNOWN, "FUTURE_MARKET_DATA");
            return market.latestInstant().isBefore(evaluatedAt.minus(5, ChronoUnit.MINUTES))
                    ? new Assessment(Status.STALE, "MARKET_DATA_OUTSIDE_WINDOW")
                    : new Assessment(Status.FRESH, "RECENT_UNDELAYED_MARKET_DATA");
        }
        // 休市时最后成交就是能拿到的最新价，实时要求也按最近交易日判断。
        String freshReason = realTime ? "MARKET_CLOSED_LATEST_SESSION" : "MARKET_WITHIN_DATE_ALLOWANCE";
        LocalDate today = evaluatedAt.atZone(zone).toLocalDate();
        LocalDate latest = market.latestDate() != null ? market.latestDate()
                : market.latestInstant() == null ? null : market.latestInstant().atZone(zone).toLocalDate();
        if (latest == null) return new Assessment(Status.UNKNOWN, "MARKET_DATE_MISSING");
        if (latest.isAfter(today)) return new Assessment(Status.UNKNOWN, "FUTURE_MARKET_DATA");
        if (market.expectedLatestDate() != null && "1d".equals(market.bar())) {
            // 交易所日历给出了已能取到日线的最近交易日，长假前后都按真实交易日判断。
            return latest.isBefore(market.expectedLatestDate())
                    ? new Assessment(Status.STALE, "MARKET_DATA_BEFORE_LAST_TRADING_DAY")
                    : new Assessment(Status.FRESH, freshReason);
        }
        LocalDate earliest = switch (market.bar() == null ? "" : market.bar()) {
            case "1w" -> today.minusWeeks(1);
            case "1m" -> today.minusMonths(1);
            default -> today;
        };
        // shortcut: 没有交易所日历的市场用两个工作日容忍周末和短假期（美股不会连休两个以上工作日；港股长假可能误判）。
        for (int weekdays = 0; weekdays < 2;) {
            earliest = earliest.minusDays(1);
            if (earliest.getDayOfWeek() != DayOfWeek.SATURDAY && earliest.getDayOfWeek() != DayOfWeek.SUNDAY) weekdays++;
        }
        return latest.isBefore(earliest)
                ? new Assessment(Status.STALE, "MARKET_DATA_OUTSIDE_WINDOW")
                : new Assessment(Status.FRESH, freshReason);
    }

    /** 常规交易时段（不含午休和节假日）：美股 9:30-16:00 纽约，港股 9:30-16:00，A 股 9:30-15:00。 */
    private static boolean sessionOpen(String market, java.time.ZonedDateTime now) {
        if (now.getDayOfWeek() == DayOfWeek.SATURDAY || now.getDayOfWeek() == DayOfWeek.SUNDAY) return false;
        java.time.LocalTime time = now.toLocalTime();
        java.time.LocalTime close = "US".equals(market) || "HK".equals(market)
                ? java.time.LocalTime.of(16, 0) : java.time.LocalTime.of(15, 0);
        return !time.isBefore(java.time.LocalTime.of(9, 30)) && time.isBefore(close);
    }

    /**
     * 新闻按最新一篇的发布日期判断：每篇日期已写在证据里，混入的旧文章或无日期文章不让整条证据作废。
     * 没有请求时间窗口时，近期要求按 30 天、实时要求按 1 天判断。
     */
    private static Assessment assessSearch(EvidenceTiming.Search search, TimeSensitivity requirement, Instant evaluatedAt) {
        Integer days = search.requestedDays() != null ? search.requestedDays() : windowDays(search.effectiveWindow());
        if (days == null) days = windowDays(search.requestedWindow());
        if (days == null) days = switch (requirement) {
            case REAL_TIME -> 1;
            case RECENT -> 30;
            default -> null;
        };
        if (days == null) {
            return requirement == TimeSensitivity.UNSPECIFIED
                    ? new Assessment(Status.NOT_APPLICABLE, "TIME_WINDOW_NOT_REQUESTED")
                    : new Assessment(Status.UNKNOWN, "SEARCH_WINDOW_UNSPECIFIED");
        }
        // 日期精度按北京时间的业务日比较，避免亚洲凌晨被 UTC 判为未来日期。
        LocalDate today = evaluatedAt.atZone(ZoneId.of("Asia/Shanghai")).toLocalDate();
        LocalDate newest = null;
        if (search.instantCount() > 0) {
            if (search.latestPublishedAt().isAfter(evaluatedAt)) return new Assessment(Status.UNKNOWN, "FUTURE_PUBLICATION");
            newest = search.latestPublishedAt().atZone(ZoneId.of("Asia/Shanghai")).toLocalDate();
        }
        if (search.dateCount() > 0) {
            if (search.latestPublishedDate().isAfter(today)) return new Assessment(Status.UNKNOWN, "FUTURE_PUBLICATION");
            if (newest == null || search.latestPublishedDate().isAfter(newest)) newest = search.latestPublishedDate();
        }
        if (newest == null) return new Assessment(Status.UNKNOWN, "PUBLICATION_TIME_UNVERIFIED");
        return newest.isBefore(today.minusDays(days))
                ? new Assessment(Status.STALE, "PUBLICATION_OUTSIDE_WINDOW")
                : new Assessment(Status.FRESH, "PUBLICATION_WITHIN_WINDOW");
    }

    /**
     * 业务容忍窗口，不表示已经查询并确认公司没有发布更新的申报；只在没有申报索引时使用。
     * 美股：10-Q 期末后约 40 天、10-K 约 60 天内申报，留余量。A 股：年报次年 4 月底前、其余定期报告两个月内。
     * 港股多数半年报：中期业绩两个月内、年度业绩三个月内。
     */
    private static LocalDate nextReportDue(EvidenceTiming.Financial financial) {
        LocalDate end = financial.latestPeriodEnd();
        if (end == null) return null;
        if ("annual".equals(financial.period())) return end.plusYears(1).plusDays(120);
        return switch (financial.market() == null ? "US" : financial.market()) {
            case "A_SHARE" -> {
                LocalDate next = end.plusMonths(3);
                yield next.plusDays(next.getMonthValue() == 12 ? 120 : 62);
            }
            case "HK" -> {
                LocalDate next = end.plusMonths(6);
                yield next.plusDays(next.getMonthValue() == 12 ? 92 : 62);
            }
            default -> end.plusMonths(3).plusDays(62);
        };
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
