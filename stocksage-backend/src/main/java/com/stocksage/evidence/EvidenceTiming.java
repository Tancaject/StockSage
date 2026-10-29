package com.stocksage.evidence;

import java.time.Instant;
import java.time.LocalDate;

/** 已验收供应商契约中的时间事实；不在此判断时效，也不把日期补成午夜时刻。 */
public record EvidenceTiming(int schemaVersion, Instant fetchedAt, Market market, Financial financial, Search search) {
    public EvidenceTiming {
        int domains = (market == null ? 0 : 1) + (financial == null ? 0 : 1) + (search == null ? 0 : 1);
        if (schemaVersion != 1 || fetchedAt == null || domains != 1) {
            throw new IllegalArgumentException("Evidence timing requires version 1, fetch time and exactly one domain");
        }
    }

    /** expectedLatestDate：交易所日历给出的、已能取到日线的最近交易日；没有日历的市场为 null。 */
    public record Market(String market, String bar, String timeKind, LocalDate latestDate,
                         Instant latestInstant, Boolean delayed, LocalDate expectedLatestDate) {
        public Market {
            if (!("DATE".equals(timeKind) || "INSTANT".equals(timeKind))
                    || ("DATE".equals(timeKind) && latestInstant != null)
                    || ("INSTANT".equals(timeKind) && latestDate != null)) {
                throw new IllegalArgumentException("Market timing must preserve date or instant precision");
            }
        }
    }

    /**
     * market 决定没有申报索引时按哪种披露节奏估算；latestFiledReport 是申报索引中同类报告最新一份的申报日期，
     * 有它时可直接核对"是否已拿到最新报告"。财报原文摘录没有报告期末，latestPeriodEnd 为 null。
     */
    public record Financial(String period, LocalDate latestPeriodEnd, LocalDate latestDisclosureDate,
                            String market, LocalDate latestFiledReport) { }

    /** 两种发布时间精度分别聚合；未知日期的结果仍计入 unknownCount。 */
    public record Search(String requestedWindow, String effectiveWindow, Integer requestedDays,
                         Instant earliestPublishedAt, Instant latestPublishedAt,
                         LocalDate earliestPublishedDate, LocalDate latestPublishedDate,
                         int instantCount, int dateCount, int unknownCount) {
        public Search {
            if (instantCount < 0 || dateCount < 0 || unknownCount < 0 || (requestedDays != null && requestedDays < 1)
                    || (instantCount == 0 ? earliestPublishedAt != null || latestPublishedAt != null
                    : earliestPublishedAt == null || latestPublishedAt == null || earliestPublishedAt.isAfter(latestPublishedAt))
                    || (dateCount == 0 ? earliestPublishedDate != null || latestPublishedDate != null
                    : earliestPublishedDate == null || latestPublishedDate == null || earliestPublishedDate.isAfter(latestPublishedDate))) {
                throw new IllegalArgumentException("Search publication ranges must agree with precision counts");
            }
        }
    }
}
