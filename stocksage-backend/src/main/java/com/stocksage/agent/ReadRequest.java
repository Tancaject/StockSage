package com.stocksage.agent;

import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/** 用户约束的有限取数契约；工具名称与执行顺序仍由服务端决定。 */
public record ReadRequest(String period, String bar, String reportPeriod, int reportCount,
                          boolean barsOnly, String clarification) {
    private static final Pattern RANGE = Pattern.compile(
            "(?:最近|近|过去|近来|last\\s+|past\\s+)([0-9一二两三四五六七八九十]+)(?:个)?\\s*(小时|天|日|周|星期|个月|月|年|hours?|days?|weeks?|months?|years?)",
            Pattern.CASE_INSENSITIVE);
    private static final Pattern BAR = Pattern.compile("([0-9一二两三四五六七八九十]+)?\\s*(分钟|小时|minutes?|hours?)\\s*(?:线|级|bars?|candles?)?", Pattern.CASE_INSENSITIVE);
    private static final Pattern REPORTS = Pattern.compile("(?:最近|近|过去|last\\s+)([0-9一二两三四五六七八九十]+)(?:个)?\\s*(季度|季|年|quarters?|years?)", Pattern.CASE_INSENSITIVE);

    public static ReadRequest parse(PlanRoute route, String query, Map<String, String> entities) {
        String text = query == null ? "" : query.toLowerCase(Locale.ROOT);
        Map<String, String> values = entities == null ? Map.of() : entities;
        if (route != PlanRoute.MARKET && route != PlanRoute.FUNDAMENTALS) {
            return new ReadRequest("3m", "1d", "annual", 5, false, "");
        }
        String period = values.getOrDefault("period", "3m");
        String bar = values.getOrDefault("bar", "1d");
        String reportPeriod = values.getOrDefault("reportPeriod", "annual");
        int count = 5;
        try {
            count = Integer.parseInt(values.getOrDefault("reportCount", "5"));
            Matcher range = RANGE.matcher(text);
            if (range.find()) {
                period = number(range.group(1)) + unit(range.group(2));
            }
            // 范围中的“一周/24小时”不是 K 线粒度，先移除已识别的范围。
            Matcher interval = BAR.matcher(RANGE.matcher(text).replaceAll(""));
            if (interval.find()) {
                bar = (interval.group(1) == null ? 1 : number(interval.group(1)))
                        + (interval.group(2).startsWith("分") || interval.group(2).startsWith("min") ? "min" : "h");
            } else if (text.contains("周线") || text.contains("weekly")) {
                bar = "1w";
            } else if (text.contains("月线") || text.contains("monthly")) {
                bar = "1m";
            } else if (text.contains("日线") || text.contains("daily")) {
                bar = "1d";
            }
            if (text.contains("季报") || text.contains("季度") || text.contains("10-q") || text.contains("quarter")) {
                reportPeriod = "quarterly";
            } else if (text.contains("年报") || text.contains("annual") || text.contains("10-k")) {
                reportPeriod = "annual";
            }
            Matcher reports = REPORTS.matcher(text);
            if (reports.find()) {
                count = number(reports.group(1));
                if (reportPeriod.equals("quarterly") && (reports.group(2).equals("年") || reports.group(2).startsWith("year"))) {
                    count *= 4;
                }
            }
            if (route == PlanRoute.MARKET && (!period.matches("[1-9][0-9]{0,2}[hdwmy]")
                    || days(period) > 365 || !Set.of("1min", "5min", "10min", "15min", "30min", "1h", "2h", "4h", "1d", "1w", "1m").contains(bar))) {
                return invalid("行情查询的范围或粒度不支持。请指定最近 1 年以内的范围，以及分钟、小时、日、周或月线。");
            }
            if (!Set.of("annual", "quarterly").contains(reportPeriod) || count < 1 || count > 40
                    || (reportPeriod.equals("annual") && count > 10)) {
                return invalid("财报查询参数不支持。请指定年度或季度，以及最多 10 年或 40 个季度。");
            }
            if (text.matches("(?s).*20[0-9]{2}[-/年].*") || text.contains("去年") || text.contains("上周") || text.contains("上个月")
                    || "true".equals(values.get("unsupportedTimeRange"))) {
                return invalid("当前取数支持相对最近范围，尚不能保证指定历史起止区间。请改用“最近一周”等范围。");
            }
            boolean barsOnly = route == PlanRoute.MARKET
                    && (Boolean.parseBoolean(values.get("barsOnly")) || text.contains("k线") || text.contains("日线") || text.contains("周线") || text.contains("月线") || text.contains("小时线") || text.contains("分钟线") || text.contains("candles") || text.contains("bars"))
                    && !text.matches("(?s).*(分析|原因|为什么|估值|指标|风险|建议|走势|analysis|why|risk|valuation|rsi|macd).*" );
            return new ReadRequest(period, bar, reportPeriod, count, barsOnly, "");
        } catch (IllegalArgumentException error) {
            return invalid("无法解析本次取数的周期或数量，请明确范围和粒度，例如“最近一周的小时线”。");
        }
    }

    /**
     * 多标的共用一份取数参数；问题里出现两种不同的范围、粒度或年报/季报口径时，无法判断哪个标的用哪份，
     * 只能请用户统一，不能任选一份套给所有标的。
     */
    public static boolean hasConflictingParameters(String query) {
        String text = query == null ? "" : query.toLowerCase(Locale.ROOT);
        Set<String> ranges = new java.util.HashSet<>();
        Matcher range = RANGE.matcher(text);
        while (range.find()) ranges.add(range.group(1) + range.group(2));
        Set<String> bars = new java.util.HashSet<>();
        Matcher bar = BAR.matcher(RANGE.matcher(text).replaceAll(""));
        while (bar.find()) bars.add(bar.group(0).replaceAll("\\s", ""));
        for (String named : java.util.List.of("日线", "周线", "月线")) if (text.contains(named)) bars.add(named);
        boolean annual = text.contains("年报") || text.contains("annual") || text.contains("10-k");
        boolean quarterly = text.contains("季报") || text.contains("季度") || text.contains("10-q") || text.contains("quarter");
        // 问题同时要几类数据时（如营收和股价），各类数据各带一个范围不是标的间的参数不对称。
        int dataKinds = Math.max(1, DataRequirement.explicitIn(query).size());
        return ranges.size() > dataKinds || bars.size() > 1 || (annual && quarterly);
    }

    /** 用户原话里明确写出的取数参数；规划器给出的这些字段一律以原话解析结果为准。 */
    public static Set<String> explicitFields(String query) {
        String text = query == null ? "" : query.toLowerCase(Locale.ROOT);
        Set<String> fields = new java.util.HashSet<>();
        if (RANGE.matcher(text).find()) fields.add("period");
        if (BAR.matcher(RANGE.matcher(text).replaceAll("")).find()
                || text.matches("(?s).*(日线|周线|月线|daily|weekly|monthly).*")) fields.add("bar");
        if (text.matches("(?s).*(年报|季报|季度|annual|quarter|10-k|10-q).*")) fields.add("reportPeriod");
        if (REPORTS.matcher(text).find()) fields.add("reportCount");
        return Set.copyOf(fields);
    }

    /** K 线步骤的范围和粒度；按与 {@link #parse} 相同的约束校验，越界时返回带澄清信息的请求。 */
    public ReadRequest withBars(String period, String bar) {
        String newPeriod = period == null ? this.period : period;
        String newBar = bar == null ? this.bar : bar;
        if (!isValidBars(newPeriod, newBar)) {
            return invalid("行情查询的范围或粒度不支持。请指定最近 1 年以内的范围，以及分钟、小时、日、周或月线。");
        }
        return new ReadRequest(newPeriod, newBar, reportPeriod, reportCount, barsOnly, clarification);
    }

    /** 财报步骤的年报/季报口径和期数；越界时返回带澄清信息的请求。 */
    public ReadRequest withReports(String reportPeriod, Integer reportCount) {
        String newReportPeriod = reportPeriod == null ? this.reportPeriod : reportPeriod;
        int newCount = reportCount == null ? this.reportCount : reportCount;
        if (!Set.of("annual", "quarterly").contains(newReportPeriod) || newCount < 1 || newCount > 40
                || (newReportPeriod.equals("annual") && newCount > 10)) {
            return invalid("财报查询参数不支持。请指定年度或季度，以及最多 10 年或 40 个季度。");
        }
        return new ReadRequest(period, bar, newReportPeriod, newCount, barsOnly, clarification);
    }

    /** 范围和粒度是否是 K 线取数支持的值。 */
    public static boolean isValidBars(String period, String bar) {
        return period != null && bar != null && period.matches("[1-9][0-9]{0,2}[hdwmy]") && days(period) <= 365
                && Set.of("1min", "5min", "10min", "15min", "30min", "1h", "2h", "4h", "1d", "1w", "1m").contains(bar);
    }

    /** 只携带澄清信息的请求；执行阶段看到它会直接返回澄清，不取证。 */
    public static ReadRequest clarify(String message) {
        return invalid(message);
    }

    private static ReadRequest invalid(String message) {
        return new ReadRequest("3m", "1d", "annual", 5, false, message);
    }

    private static int number(String text) {
        if (text.matches("[0-9]+")) return Integer.parseInt(text);
        if (text.equals("十")) return 10;
        if (text.equals("两")) return 2;
        int value = "零一二三四五六七八九".indexOf(text);
        if (value <= 0 || text.length() != 1) throw new IllegalArgumentException("unsupported number");
        return value;
    }

    private static String unit(String text) {
        if (text.equals("小时") || text.startsWith("hour")) return "h";
        if (text.equals("周") || text.equals("星期") || text.startsWith("week")) return "w";
        if (text.contains("月") || text.startsWith("month")) return "m";
        if (text.equals("年") || text.startsWith("year")) return "y";
        return "d";
    }

    public int days() { return days(period); }
    private static int days(String value) {
        int amount = Integer.parseInt(value.substring(0, value.length() - 1));
        return switch (value.charAt(value.length() - 1)) {
            case 'h' -> Math.max(1, (amount + 23) / 24);
            case 'w' -> amount * 7;
            case 'm' -> amount * 30;
            case 'y' -> amount * 365;
            default -> amount;
        };
    }
    public int reportYears() { return reportPeriod.equals("quarterly") ? (reportCount + 3) / 4 : reportCount; }
    public String klinePeriod() { return switch (bar) { case "1w" -> "weekly"; case "1m" -> "monthly"; default -> "daily"; }; }
    public boolean intraday() { return !Set.of("1d", "1w", "1m").contains(bar); }
    public Map<String, Object> attributes() {
        return Map.of("period", period, "bar", bar, "reportPeriod", reportPeriod,
                "reportCount", reportCount, "barsOnly", barsOnly, "clarification", clarification);
    }
}
