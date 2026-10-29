package com.stocksage.tool;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.stocksage.workbench.PortfolioAnalysisService;
import org.springframework.ai.tool.annotation.Tool;
import org.springframework.ai.tool.annotation.ToolParam;
import org.springframework.stereotype.Component;
import java.util.function.Function;

@Component
public class PortfolioTools {
    private final PortfolioAnalysisService service;
    private final ObjectMapper mapper;
    public PortfolioTools(PortfolioAnalysisService service, ObjectMapper mapper) { this.service = service; this.mapper = mapper; }

    @Tool(description = "对用户提供的持仓表计算分币种权重、集中度、现金、明确价格情景损益和共同日期收益相关性。不会读取券商账户或补造缺失数据。inputJson 契约见组合分析 Skill。")
    public String analyzePortfolio(@ToolParam(description = "JSON对象: asOf日期, positions[{symbol,currency,quantity,price,kind:SECURITY|CASH}], 可选scenarios[{name,returns:{证券代码:小数变化率}}], prices{证券代码:[{date,close}]}。现金price=1。") String inputJson) {
        return execute(inputJson, service::analyzePortfolio);
    }
    @Tool(description = "按用户提供的带日期ETF成分表，计算直接持仓加单层穿透暴露、披露覆盖率及ETF重叠。缺失成分保持未知，分币种报告。")
    public String lookThroughEtfs(@ToolParam(description = "JSON对象: asOf, positions[{symbol,currency,marketValue,isEtf}], funds[{symbol,asOf,holdings:[{symbol,weight}]}]。weight为0到1的小数，marketValue非负。") String inputJson) {
        return execute(inputJson, service::lookThroughEtfs);
    }
    @Tool(description = "对明确给出的证券范围和指标表进行AND条件筛选，返回逐规则PASS/FAIL/UNKNOWN；缺失指标或未来日期为UNKNOWN，不代表不符合。不会搜索全市场。")
    public String screenStocks(@ToolParam(description = "JSON对象: asOf, universe:[证券代码], rows:[{symbol,asOf,metrics:{指标名:数字或null}}], rules:[{metric,operator:GT|GTE|LT|LTE|EQ|BETWEEN,value,maxValue}]。BETWEEN包含上下界。") String inputJson) {
        return execute(inputJson, service::screenStocks);
    }
    private String execute(String input, Function<JsonNode, JsonNode> calculation) {
        try {
            if (input == null || input.length() > 500_000) throw new IllegalArgumentException("inputJson 必须为 JSON 对象，最多 500000 字符");
            JsonNode parsed = mapper.reader().with(DeserializationFeature.FAIL_ON_TRAILING_TOKENS).readTree(input);
            return calculation.apply(parsed).toString();
        } catch (JsonProcessingException | IllegalArgumentException ex) {
            String message = ex instanceof JsonProcessingException ? "inputJson 不是有效的单个 JSON 对象，请检查引号、逗号和括号。" : ex.getMessage();
            return mapper.createObjectNode().put("error", true).put("status", "ERROR").put("errorCode", "INVALID_INPUT").put("message", message).toString();
        }
    }
}
