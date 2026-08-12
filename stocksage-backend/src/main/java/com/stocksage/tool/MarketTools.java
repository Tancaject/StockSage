package com.stocksage.tool;

import com.stocksage.client.DataServiceClient;
import com.stocksage.ibkr.IbkrReadOnlyService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.ai.tool.annotation.Tool;
import org.springframework.ai.tool.annotation.ToolParam;
import org.springframework.stereotype.Component;

/**
 * 可由模型调用的市场数据工具。
 *
 * <p>该类刻意保持很薄：只向 Spring AI 暴露安全的工具描述，
 * 并把不同供应商的路由委托给 DataServiceClient 或只读 IBKR 服务。</p>
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class MarketTools {

    /** A 股/港股行情与跨市场代码解析的统一 Python 服务客户端。 */
    private final DataServiceClient dataServiceClient;
    /** 美股和具备权限的港股只读账户/行情入口，明确不提供交易方法。 */
    private final IbkrReadOnlyService ibkrReadOnlyService;

    /**
     * 搜索并解析股票候选。
     *
     * <p>当用户只给出公司名、中文简称或整句问题时，模型应先调用该工具确认市场和 ticker。</p>
     */
    @Tool(description = "搜索并解析股票代码/名称，支持从完整中文或英文问题中识别 A 股、港股、美股候选。用户给出公司名但不确定 ticker 时，应先调用此工具，尤其是港股和 A 股")
    public String searchStocks(
            @ToolParam(description = "股票代码、公司名或完整用户问题，如 腾讯、00700、贵州茅台、Knowledge Atlas Technology 今天为什么暴涨") String query,
            @ToolParam(description = "返回候选数量，默认 5") int maxResults) {
        return dataServiceClient.searchStocks(query, maxResults);
    }

    /**
     * 将单个股票输入解析到具体市场和标准代码。
     *
     * <p>该工具是行情、财务和技术指标调用前的安全护栏，避免把港股或 A 股误当成美股。</p>
     */
    @Tool(description = "解析单个股票代码/名称到具体市场和数据源，支持 A 股、港股、美股。适合在调用行情/财务工具前确认市场")
    public String resolveStock(
            @ToolParam(description = "股票代码或名称，如 sh.600519、600519、0700.HK、00700、腾讯、AAPL") String query) {
        return dataServiceClient.resolveStock(query);
    }

    /**
     * 获取 A 股或港股 K 线数据。
     *
     * <p>美股历史行情优先走 IBKR 工具；这里委托 Python 数据服务处理 BaoStock/AKShare 来源。</p>
     */
    @Tool(description = "获取指定 A 股/港股的 K 线数据，包含开盘、收盘、最高、最低价和成交量。A 股走 BaoStock，港股走 AKShare；美股不要调用此工具，使用 IBKR 历史 K 线工具")
    public String getStockKLine(
            @ToolParam(description = "股票代码或名称。A股可用 sh.600519/600519/贵州茅台；港股可用 0700.HK/00700/腾讯；美股请使用 IBKR 工具") String code,
            @ToolParam(description = "K线周期：daily/weekly/monthly") String period,
            @ToolParam(description = "获取最近多少天的数据") int days) {
        // Python 服务负责市场识别与 AKShare/BaoStock fallback，本层不猜测供应商。
        return dataServiceClient.getKLine(code, period, days);
    }

    /**
     * 获取 A 股或港股财务/估值指标。
     *
     * <p>该方法只负责把工具调用转发到数据服务，具体字段兼容逻辑由 Python 服务处理。</p>
     */
    @Tool(description = "获取 A 股/港股的核心财务指标或行情估值字段。A 股走 BaoStock，港股走 AKShare；美股财务请用 SEC EDGAR 工具，行情请用 IBKR")
    public String getFinancialMetrics(
            @ToolParam(description = "股票代码或名称，如 sh.600519、0700.HK、腾讯、贵州茅台；美股请用 IBKR/SEC 工具") String code) {
        return dataServiceClient.getFinancialMetrics(code);
    }

    /**
     * 计算 A 股或港股技术指标。
     *
     * <p>指标计算由数据服务完成，后端只暴露模型可调用的稳定工具入口。</p>
     */
    @Tool(description = "计算 A 股/港股的技术分析指标，如 MA、MACD、RSI、KDJ 等。A 股走 BaoStock，港股走 AKShare；美股请先用 IBKR 获取 K 线")
    public String getTechnicalIndicators(
            @ToolParam(description = "股票代码或名称，如 sh.600519、0700.HK、腾讯、贵州茅台；美股请用 IBKR 工具") String code,
            @ToolParam(description = "需要的指标列表，逗号分隔，如 MA,MACD,RSI") String indicators) {
        return dataServiceClient.getTechnicalIndicators(code, indicators);
    }

    /**
     * 查询行业或板块整体表现。
     */
    @Tool(description = "获取指定板块/行业的整体表现，包含涨跌幅、成交额、领涨股等")
    public String getSectorPerformance(
            @ToolParam(description = "板块名称，如 白酒、新能源、半导体") String sector) {
        return dataServiceClient.getSectorPerformance(sector);
    }

    /**
     * 对比多只股票的关键维度。
     *
     * <p>跨市场对比时仍应先确认每个标的的数据源覆盖情况，避免不同市场口径混用。</p>
     */
    @Tool(description = "对比多只股票的关键维度：估值、盈利能力、成长性、技术面")
    public String compareStocks(
            @ToolParam(description = "股票代码或名称列表，逗号分隔，可混合 A股/港股；美股请改用 IBKR/SEC，如 sh.600519,0700.HK") String codes,
            @ToolParam(description = "对比维度，逗号分隔，如 PE,ROE,营收增速") String dimensions) {
        return dataServiceClient.compareStocks(codes, dimensions);
    }

    /**
     * 获取大盘行情概览。
     */
    @Tool(description = "获取今日大盘行情概览：上证、深证、创业板指数及涨跌统计")
    public String getMarketOverview() {
        return dataServiceClient.getMarketOverview();
    }

    /**
     * 返回 IBKR 只读接入指南。
     *
     * <p>这是说明型工具，不访问外部接口，用于在用户询问连接方式或能力边界时给模型提供固定事实。</p>
     */
    @Tool(description = "说明当前 IBKR 只读能力、配置检查项、安全边界和降级建议。用户询问如何连接 IBKR 或 IBKR 能做什么时使用")
    public String getIbkrConnectionGuide() {
        return """
                {
                  "provider": "IBKR_CLIENT_PORTAL_WEB_API",
                  "mode": "READ_ONLY",
                  "setup": [
                    "Run IBKR Client Portal Gateway locally, usually on https://localhost:5000.",
                    "Log in through the gateway browser flow and complete any required 2FA.",
                    "Set stocksage.ibkr.enabled=true after the gateway is reachable.",
                    "Use getIbkrAuthStatus first; if authenticated, tickleIbkrSession can keep the session warm."
                  ],
                  "capabilities": [
                    "Authentication status",
                    "Session keepalive tickle",
                    "Portfolio account list",
                    "Account summary",
                    "Current positions",
                    "US realtime or delayed quotes",
                    "US historical bars",
                    "HK quotes/bars if the account has the needed market-data permissions"
                  ],
                  "safety": [
                    "No order placement.",
                    "No account mutation.",
                    "No credential handling inside StockSage."
                  ],
                  "fallbacks": [
                    "If IBKR is disabled or unauthenticated, explain the gateway login requirement.",
                    "If realtime subscriptions are missing, report delayed or no-subscription status.",
                    "For A-share and free HK market data, use the Python data service tools instead of IBKR."
                  ]
                }
                """;
    }

    /**
     * 检查 IBKR Gateway 认证状态。
     */
    @Tool(description = "检查 IBKR Client Portal Gateway 的认证状态。只读工具，不会下单或修改账户")
    public String getIbkrAuthStatus() {
        return ibkrReadOnlyService.getAuthStatus();
    }

    /**
     * 发送 IBKR 会话保活请求。
     */
    @Tool(description = "向 IBKR Client Portal Gateway 发送 tickle keepalive，帮助保持已登录会话。只读工具")
    public String tickleIbkrSession() {
        return ibkrReadOnlyService.tickle();
    }

    /**
     * 读取 IBKR 可访问账户列表。
     */
    @Tool(description = "读取 IBKR 可查看的账户列表。只读工具，不会下单或修改账户")
    public String getIbkrPortfolioAccounts() {
        return ibkrReadOnlyService.getPortfolioAccounts();
    }

    /**
     * 读取指定或默认 IBKR 账户摘要。
     */
    @Tool(description = "读取 IBKR 账户摘要，如净清算值、现金、保证金等。只读工具，不会下单或修改账户")
    public String getIbkrAccountSummary(
            @ToolParam(description = "IBKR 账户 ID；如果用户没有指定，传空字符串，系统会使用默认账户或第一个账户") String accountId) {
        return ibkrReadOnlyService.getAccountSummary(accountId);
    }

    /**
     * 读取指定或默认 IBKR 账户持仓。
     */
    @Tool(description = "读取 IBKR 当前持仓，用于结合用户真实持仓做投资组合分析。只读工具，不会下单或修改账户")
    public String getIbkrPositions(
            @ToolParam(description = "IBKR 账户 ID；如果用户没有指定，传空字符串，系统会使用默认账户或第一个账户") String accountId) {
        return ibkrReadOnlyService.getPositions(accountId);
    }

    /**
     * 获取 IBKR 实时或延迟报价。
     *
     * <p>响应中会标明 REALTIME、DELAYED 或 NO_SUBSCRIPTION，模型需要如实告诉用户数据权限状态。</p>
     */
    @Tool(description = "通过 IBKR Web API 获取美股实时或延迟报价；港股仅在 IBKR 账户具备相应权限时使用。只读工具；返回值会标明 REALTIME/DELAYED/NO_SUBSCRIPTION")
    public String getIbkrRealtimeQuote(
            @ToolParam(description = "美股代码，如 AAPL、TSLA、NVDA；港股如 0700.HK/00700 也可尝试，但免费港股优先用普通股票数据工具；A股继续使用普通股票数据工具") String code) {
        // 只读服务会把 REALTIME/DELAYED/NO_SUBSCRIPTION 状态保留在响应中。
        return ibkrReadOnlyService.getRealtimeQuote(code);
    }

    /**
     * 获取 IBKR 历史 K 线。
     */
    @Tool(description = "通过 IBKR Web API 获取美股历史 K 线；港股仅在 IBKR 账户具备相应权限时使用。只读工具；IBKR 单次最多返回约 1000 个数据点")
    public String getIbkrHistoricalBars(
            @ToolParam(description = "美股代码，如 AAPL、TSLA、NVDA；港股如 0700.HK/00700 也可尝试，但免费港股优先用普通股票数据工具；A股继续使用普通股票数据工具") String code,
            @ToolParam(description = "历史长度，如 1d、1w、1m、6m、1y") String period,
            @ToolParam(description = "K线粒度，如 1min、5min、1h、1d") String bar) {
        return ibkrReadOnlyService.getHistoricalBars(code, period, bar);
    }
}
