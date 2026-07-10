package com.stocksage.ibkr;

import org.springframework.stereotype.Component;

import java.util.Locale;

/**
 * 将股票代码输入转换为 IBKR 合约搜索符号。
 * 主要面向美股，保留港股解析用于兼容少量边界场景。
 */
@Component
public class IbkrInstrumentResolver {

    /**
     * 将用户输入归一化为 IBKR 可搜索的标的描述。
     *
     * <p>当前主要支持美股 ticker 和港股数字代码；A 股仍交给 Python 数据服务处理。
     * 解析失败时返回 unsupported 对象，而不是抛异常，方便上层工具把原因直接反馈给模型。</p>
     */
    public IbkrInstrument resolve(String input) {
        String raw = input == null ? "" : input.trim();
        String compact = raw.replaceAll("\\s+", "");
        String upper = compact.toUpperCase(Locale.ROOT);

        if (compact.isBlank()) {
            return IbkrInstrument.unsupported(raw, "IBKR symbol is empty");
        }

        if (upper.matches("\\d{1,5}\\.HK") || upper.matches("HK[:.]?\\d{1,5}")) {
            String numeric = upper.replace(".HK", "").replace("HK:", "").replace("HK.", "");
            return IbkrInstrument.hk(raw, stripLeadingZeros(numeric));
        }

        if (upper.matches("\\d{1,5}")) {
            return IbkrInstrument.hk(raw, stripLeadingZeros(upper));
        }

        if (upper.matches("[A-Z][A-Z0-9.-]{0,9}(\\.US)?")) {
            String symbol = upper.replace(".US", "").replace(".", "-");
            return IbkrInstrument.us(raw, symbol);
        }

        if (upper.matches("(SH|SZ|BJ)\\.\\d{6}") || upper.matches("\\d{6}")) {
            return IbkrInstrument.unsupported(raw, "A-share quotes are not routed to IBKR yet; use the Python data service for A-shares.");
        }

        return IbkrInstrument.unsupported(raw, "Unable to resolve IBKR stock symbol. Use standard US tickers like AAPL, TSLA, NVDA.");
    }

    /**
     * 去掉港股代码前导零。
     *
     * <p>IBKR 搜索港股时通常使用不带前导零的数字符号；全为零时保留单个 0，避免返回空字符串。</p>
     */
    private String stripLeadingZeros(String value) {
        String stripped = value.replaceFirst("^0+", "");
        return stripped.isBlank() ? "0" : stripped;
    }
}
