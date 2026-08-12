package com.stocksage.ibkr;

/**
 * 只读行情工具使用的已解析 IBKR 合约目标。
 *
 * <p>该记录会同时保留用户原始输入、归一化代码和交易所，
 * 便于工具响应解释为什么某个请求被支持或拒绝。</p>
 *
 * @param input 用户输入的原始符号
 * @param market 归一化市场，例如 US、HK 或 UNKNOWN
 * @param symbol IBKR 搜索使用的符号
 * @param exchange IBKR 交易所或路由，例如 SMART、SEHK
 * @param currency 合约币种
 * @param supported 是否可由当前 IBKR 工具处理
 * @param message 不支持或解析失败时返回给上层的说明
 */
public record IbkrInstrument(
        String input,
        String market,
        String symbol,
        String exchange,
        String currency,
        boolean supported,
        String message
) {

    /**
     * 构造美股合约目标，默认走 SMART 路由和 USD。
     *
     * @param input 用户原始输入
     * @param symbol 归一化美股 ticker
     * @return 可用于 IBKR 合约搜索的美股描述
     */
    public static IbkrInstrument us(String input, String symbol) {
        return new IbkrInstrument(input, "US", symbol, "SMART", "USD", true, "");
    }

    /**
     * 构造港股合约目标，默认使用 SEHK 和 HKD。
     *
     * @param input 用户原始输入
     * @param symbol 去除前导零的港股代码
     * @return 可用于 IBKR 合约搜索的港股描述
     */
    public static IbkrInstrument hk(String input, String symbol) {
        return new IbkrInstrument(input, "HK", symbol, "SEHK", "HKD", true, "");
    }

    /**
     * 构造不支持的合约目标。
     *
     * <p>上层服务会把 message 放入错误响应，帮助用户改用受支持的符号格式或数据服务。</p>
     *
     * @param input 用户原始输入
     * @param message 拒绝原因和建议
     * @return supported=false 的描述对象
     */
    public static IbkrInstrument unsupported(String input, String message) {
        return new IbkrInstrument(input, "UNKNOWN", "", "", "", false, message);
    }
}
