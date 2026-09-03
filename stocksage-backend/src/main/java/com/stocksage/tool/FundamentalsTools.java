package com.stocksage.tool;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.stocksage.client.DataServiceClient;
import com.stocksage.service.EdgarIngestionService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.ai.tool.annotation.Tool;
import org.springframework.ai.tool.annotation.ToolParam;
import org.springframework.stereotype.Component;

import java.util.Map;

/**
 * 基本面与公告能力。
 *
 * <p>SEC 摄取只供后端受控流程调用；暴露给模型的仅是只读查询能力。</p>
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class FundamentalsTools {

    /** 调用 Python 数据服务获取跨市场财报和搜索结果。 */
    private final DataServiceClient dataServiceClient;
    /** 执行美股 SEC 文件下载、切分和知识库摄取。 */
    private final EdgarIngestionService edgarIngestionService;
    /** 将 SEC 摄取结果转换为模型可消费的 JSON。 */
    private final ObjectMapper objectMapper;

    /**
     * 将美股 SEC 财报摄取到 RAG 知识库。
     *
     * <p>该后端动作只面向 EDGAR，不注册为模型工具；A 股和港股公告检索应走报告搜索或财报数据服务。</p>
     */
    public String ingestCompanyFilings(String ticker, String filingType, int count) {
        try {
            // 受控研究流程以结构化文本记录摄取结果；异常不会中断后续证据降级。
            Map<String, Object> result = edgarIngestionService.ingestFilings(ticker, filingType, count);
            return objectMapper.writeValueAsString(result);
        } catch (Exception e) {
            return "{\"error\":true,\"message\":\"Filing ingestion failed: " + e.getMessage() + "\"}";
        }
    }

    /**
     * 获取美股 SEC XBRL 结构化财务数据。
     */
    @Tool(description = "获取美股公司的 SEC XBRL 结构化财务数据：营收、净利润、总资产、负债、EPS、经营现金流等。仅适用于美股 SEC EDGAR；港股/A 股请调用 getFinancialReports")
    public String getStructuredFinancials(
            @ToolParam(description = "美股 ticker，如 AAPL、MSFT、NVDA") String ticker) {
        return dataServiceClient.getEdgarXbrl(ticker);
    }

    /**
     * 获取 A 股、港股或美股的结构化财报数据。
     *
     * <p>市场路由由 Python 数据服务解析，后端保持统一工具入口。</p>
     */
    @Tool(description = "获取 A 股、港股、美股的结构化财报/财务报表数据。A 股走 baostock 财务表，港股走 AKShare 财务报表，美股走 SEC EDGAR XBRL；适合用户问年报、季报、营收、净利润、资产负债表、现金流时使用")
    public String getFinancialReports(
            @ToolParam(description = "股票代码或名称，可传完整问题；如 贵州茅台、sh.600519、腾讯、0700.HK、00700、AAPL") String code,
            @ToolParam(description = "annual 或 quarterly；用户问年报用 annual，问季报用 quarterly") String period,
            @ToolParam(description = "返回最近几年，建议 3-5") int years) {
        // DataServiceClient 再按解析出的市场路由到 BaoStock、AKShare 或 SEC。
        return dataServiceClient.getFinancialReports(code, period, years);
    }

    /**
     * 搜索公司公告、年报或季报原文。
     *
     * <p>适用于缺少 SEC 文件的 A 股和港股，也可作为美股财报原文的补充检索。</p>
     */
    @Tool(description = "搜索公司年报、季报、中期报告、业绩公告等原文网页/PDF。适用于 A 股、港股、美股，尤其是港股/A 股没有 SEC 10-K/10-Q 时")
    public String searchCompanyReports(
            @ToolParam(description = "股票代码、公司名或完整问题，如 腾讯、0700.HK、贵州茅台、600519、AAPL") String codeOrName,
            @ToolParam(description = "报告类型，如 年报、季报、中期报告、业绩公告；不确定可传 财报") String reportType,
            @ToolParam(description = "返回结果数量，默认 5") int maxResults) {
        return dataServiceClient.webSearch(buildReportSearchQuery(codeOrName, reportType), maxResults);
    }

    /**
     * 添加市场特定的来源提示，让网页搜索更可能返回官方公告，而不是泛财经页面。
     */
    private String buildReportSearchQuery(String codeOrName, String reportType) {
        String target = codeOrName == null || codeOrName.isBlank() ? "" : codeOrName.trim();
        String type = reportType == null || reportType.isBlank() ? "财报" : reportType.trim();
        String upper = target.toUpperCase();

        if (target.contains("港股")
                || upper.contains(".HK")
                || upper.contains("HKEX")
                || upper.matches(".*\\b0?\\d{1,5}\\b.*")) {
            return target + " " + type + " 年报 中期报告 业绩公告 HKEXnews annual report PDF";
        }
        if (target.contains("A股")
                || upper.matches(".*\\b(SH|SZ|BJ)\\.\\d{6}\\b.*")
                || upper.matches(".*\\b\\d{6}\\b.*")) {
            return target + " " + type + " 年报 季报 财报 巨潮资讯 上交所 深交所 PDF";
        }
        return target + " " + type + " 财报 年报 季报 annual report interim report HKEXnews 巨潮资讯";
    }
}
