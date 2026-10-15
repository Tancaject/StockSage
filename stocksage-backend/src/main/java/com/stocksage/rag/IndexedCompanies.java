package com.stocksage.rag;

import com.stocksage.repository.VectorDocumentRepository;
import org.springframework.stereotype.Component;

import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * 知识库已入库的公司，用来从问题里识别检索过滤用的 ticker。
 *
 * <p>只认已入库的公司：用不在库里的代码过滤只会得到空结果，而全量代码表会把 AI、IT、ON 这类普通词误认成代码。
 * 公司名取自入库时记录的 SEC 公司名，入库新公司后最多一个缓存周期即生效。</p>
 */
@Component
public class IndexedCompanies {

    private static final Duration REFRESH_INTERVAL = Duration.ofMinutes(5);
    /** 中文等非 ASCII 字符紧挨代码时也要能识别，所以不用 \b。 */
    private static final Pattern TICKER_CANDIDATE = Pattern.compile("(?<![A-Za-z0-9])([A-Z]{1,5}(?:\\.[A-Z])?)(?![A-Za-z0-9])");
    /** SEC 公司名里的法律形式和泛化后缀，不参与名称匹配。 */
    private static final Set<String> NAME_SUFFIXES = Set.of("inc", "corp", "corporation", "co", "company", "plc",
            "ltd", "holdings", "com");
    /** 多词公司名的首词若是普通词，单独出现时不算提到这家公司（如 Western Digital 的 Western）。 */
    private static final Set<String> GENERIC_FIRST_WORDS = Set.of("western", "applied", "johnson", "general", "american",
            "united", "first", "international", "national", "the");

    private static final Pattern NEGATION = Pattern.compile(
            "(?i)(?<![A-Za-z])(?:not|rather than|instead of|excluding|except|other than)(?![A-Za-z])|而不是|不要|不是|而非|排除|除了");
    private static final Pattern NEGATION_FILLER = Pattern.compile(
            "(?i)(?:[\\s,，、/()（）]|(?<![A-Za-z])(?:use|using|from|the|of|or|and|nor|filings?|documents?|reports?|data)(?![A-Za-z])"
                    + "|用|使用|参考|的|和|或|与|及|资料|文件|财报)*");

    private final VectorDocumentRepository repository;
    private volatile Snapshot snapshot;

    public IndexedCompanies(VectorDocumentRepository repository) {
        this.repository = repository;
    }

    /** 按在问题中出现的顺序返回问题要查的 ticker，去重；被否定的公司（"not XOM"、"不要用 MSFT"）不算。 */
    public List<String> tickersIn(String query) {
        if (query == null || query.isBlank()) return List.of();
        return match(current(), query);
    }

    static List<String> match(Snapshot current, String query) {
        List<Mention> mentions = mentions(current, query);
        Set<String> wanted = new LinkedHashSet<>();
        for (Mention mention : mentions) {
            if (!negated(query, mention, mentions)) wanted.add(mention.ticker());
        }
        return List.copyOf(wanted);
    }

    private static List<Mention> mentions(Snapshot current, String query) {
        // 同一起点只保留最长的命中，"Seagate Technology" 不再拆出 "Seagate"。
        Map<Integer, Mention> byStart = new TreeMap<>();
        Matcher code = TICKER_CANDIDATE.matcher(query);
        while (code.find()) {
            if (current.tickers().contains(code.group(1))) keepLongest(byStart, new Mention(code.start(), code.end(), code.group(1)));
        }
        for (Map.Entry<Pattern, String> alias : current.aliases().entrySet()) {
            Matcher name = alias.getKey().matcher(query);
            while (name.find()) {
                // 只认首字母大写的写法，避免 "a coherent plan" 这类普通用词命中 Coherent。
                if (Character.isUpperCase(query.charAt(name.start()))) {
                    keepLongest(byStart, new Mention(name.start(), name.end(), alias.getValue()));
                }
            }
        }
        return List.copyOf(byStart.values());
    }

    private static void keepLongest(Map<Integer, Mention> byStart, Mention mention) {
        byStart.merge(mention.start(), mention, (a, b) -> a.end() >= b.end() ? a : b);
    }

    /**
     * 否定词与公司之间只隔着连接词、"use/filings" 之类的虚词或其他公司名时，视为排除这家公司：
     * "not AMZN or MSFT" 两家都排除，"not Western Digital" 排除 WDC。
     */
    private static boolean negated(String query, Mention mention, List<Mention> all) {
        Matcher cue = NEGATION.matcher(query);
        while (cue.find() && cue.end() <= mention.start()) {
            StringBuilder gap = new StringBuilder(query.substring(cue.end(), mention.start()));
            for (Mention other : all) {
                if (other.start() >= cue.end() && other.end() <= mention.start()) {
                    int from = other.start() - cue.end();
                    gap.replace(from, other.end() - cue.end(), " ".repeat(other.end() - other.start()));
                }
            }
            if (NEGATION_FILLER.matcher(gap).matches()) return true;
        }
        return false;
    }

    private Snapshot current() {
        Snapshot loaded = snapshot;
        if (loaded == null || loaded.loadedAt().plus(REFRESH_INTERVAL).isBefore(Instant.now())) {
            loaded = load(repository.findIndexedCompanies());
            snapshot = loaded;
        }
        return loaded;
    }

    static Snapshot load(List<Object[]> rows) {
        Set<String> tickers = new LinkedHashSet<>();
        Map<Pattern, String> aliases = new LinkedHashMap<>();
        for (Object[] row : rows) {
            if (row[0] == null) continue;
            String ticker = row[0].toString().strip().toUpperCase(Locale.ROOT);
            tickers.add(ticker);
            if (row[1] == null) continue;
            List<String> words = nameWords(row[1].toString());
            if (words.isEmpty()) continue;
            aliases.putIfAbsent(namePattern(words), ticker);
            if (words.size() > 1 && words.get(0).length() >= 4 && !GENERIC_FIRST_WORDS.contains(words.get(0))) {
                aliases.putIfAbsent(namePattern(List.of(words.get(0))), ticker);
            }
        }
        return new Snapshot(Set.copyOf(tickers), aliases, Instant.now());
    }

    /** "PROCTER & GAMBLE Co" -> [procter, gamble]；"AMAZON COM INC" -> [amazon]。 */
    static List<String> nameWords(String company) {
        List<String> words = new ArrayList<>();
        for (String word : company.toLowerCase(Locale.ROOT).split("[^a-z0-9]+")) {
            if (!word.isEmpty()) words.add(word);
        }
        while (!words.isEmpty() && NAME_SUFFIXES.contains(words.get(words.size() - 1))) {
            words.remove(words.size() - 1);
        }
        return words;
    }

    /** 词之间允许空格、连字符、& 等分隔，匹配 "Coca-Cola"、"Procter & Gamble"。 */
    private static Pattern namePattern(List<String> words) {
        String body = String.join("[^A-Za-z0-9]+", words.stream().map(Pattern::quote).toList());
        return Pattern.compile("(?<![A-Za-z0-9])" + body + "(?![A-Za-z0-9])", Pattern.CASE_INSENSITIVE);
    }

    private record Mention(int start, int end, String ticker) {
    }

    record Snapshot(Set<String> tickers, Map<Pattern, String> aliases, Instant loadedAt) {
    }
}
