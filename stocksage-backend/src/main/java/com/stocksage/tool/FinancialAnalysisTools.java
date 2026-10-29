package com.stocksage.tool;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.stocksage.client.DataServiceClient;
import com.stocksage.research.finance.FinancialCalculations;
import lombok.RequiredArgsConstructor;
import org.springframework.ai.tool.annotation.Tool;
import org.springframework.ai.tool.annotation.ToolParam;
import org.springframework.stereotype.Component;

/** 面向明确财务问题的只读工具；请求口径和纯计算分别交给现有 SEC 客户端及固定计算函数。 */
@Component
@RequiredArgsConstructor
public class FinancialAnalysisTools {
    private final DataServiceClient dataServiceClient;
    private final ObjectMapper objectMapper;

    @Tool(description = "计算美股 SEC 连续年度 CAGR 和同日净债务，返回固定公式、实际输入事实、申报来源及不适用原因。CAGR 不用于季度；缺少债务分项或受限现金口径不明时不补零。asOf 为空读当前，否则只用截止日前已申报事实")
    public String getFinancialDiagnostics(@ToolParam(description = "已确认的美股代码") String ticker,
            @ToolParam(description = "annual 或 quarterly") String period,
            @ToolParam(description = "1至10年，CAGR至少需要两个年度") int years,
            @ToolParam(description = "申报信息截止日 YYYY-MM-DD；当前查询传空字符串") String asOf) {
        return objectMapper.valueToTree(FinancialCalculations.diagnostics(
                dataServiceClient.getEdgarXbrl(ticker, period, years, asOf, true))).toString();
    }

    @Tool(description = "按相同 SEC concept、单位及实际 start/end 比较两家美股公司的财务数值；选择最近共同期间，附两侧原始事实与差额。不同币种、不同财年或不同概念留缺，不按财年标签硬配。asOf为共同申报信息截止日，可传空")
    public String compareCompanyFinancials(@ToolParam(description = "第一家已确认美股代码") String tickerA,
            @ToolParam(description = "第二家已确认美股代码") String tickerB,
            @ToolParam(description = "annual 或 quarterly") String period,
            @ToolParam(description = "1至10年回看窗口") int years,
            @ToolParam(description = "共同申报信息截止日 YYYY-MM-DD；当前查询传空字符串") String asOf) {
        if (tickerA == null || tickerB == null || tickerA.equalsIgnoreCase(tickerB)) {
            return objectMapper.createObjectNode().put("error", true).put("status", "ERROR")
                    .put("errorCode", "DISTINCT_COMPANIES_REQUIRED").put("message", "请提供两个不同且已确认的美股代码。").toString();
        }
        var left = dataServiceClient.getEdgarXbrl(tickerA, period, years, asOf, true);
        var right = dataServiceClient.getEdgarXbrl(tickerB, period, years, asOf, true);
        return objectMapper.valueToTree(FinancialCalculations.compare(left, right)).toString();
    }

    @Tool(description = "计算适用于半导体等库存驱动企业的库存/营收、存货周转次数、库存天数及年度营收CAGR。仅用SEC同期间财务事实；不推断库存数量、销量、产能或公司行业。平均库存需要真实期初/期末值，缺项不替代")
    public String getSemiconductorOperatingMetrics(@ToolParam(description = "已确认的美股代码") String ticker,
            @ToolParam(description = "annual 或 quarterly") String period,
            @ToolParam(description = "1至10年；平均库存通常需要至少两年期末数据") int years,
            @ToolParam(description = "申报信息截止日 YYYY-MM-DD；当前查询传空字符串") String asOf) {
        return objectMapper.valueToTree(FinancialCalculations.semiconductor(
                dataServiceClient.getEdgarXbrl(ticker, period, years, asOf, true))).toString();
    }

    @Tool(description = "按历史申报信息截止日查询美股SEC年度或单季财务：先筛filed<=asOf，再择最新版本及派生季度。保留每条来源与季度累计输入；不带今天的报表附件，不宣称完整重建当年数据档案")
    public String getHistoricalFinancials(@ToolParam(description = "已确认的美股代码") String ticker,
            @ToolParam(description = "annual 或 quarterly") String period,
            @ToolParam(description = "1至10年回看窗口") int years,
            @ToolParam(description = "必填申报信息截止日 YYYY-MM-DD，不能晚于今天") String asOf) {
        if (asOf == null || asOf.isBlank()) return objectMapper.createObjectNode().put("error", true).put("status", "ERROR")
                .put("errorCode", "INFORMATION_CUTOFF_REQUIRED").put("message", "历史查询需要明确的申报信息截止日 YYYY-MM-DD。").toString();
        return objectMapper.valueToTree(dataServiceClient.getEdgarXbrl(ticker, period, years, asOf, true)).toString();
    }
}
