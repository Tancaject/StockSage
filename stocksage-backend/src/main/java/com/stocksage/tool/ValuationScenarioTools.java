package com.stocksage.tool;

import com.fasterxml.jackson.databind.ObjectMapper;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import lombok.RequiredArgsConstructor;
import org.springframework.ai.tool.annotation.Tool;
import org.springframework.ai.tool.annotation.ToolParam;
import org.springframework.stereotype.Component;

/** 只计算用户明确提供的假设，不把情景输出包装成已核验的内在价值。 */
@Component
@RequiredArgsConstructor
public class ValuationScenarioTools {
    private final ObjectMapper objectMapper;

    public record ValuationInput(String currency,
                                 @ToolParam(required = false, description = "PE法使用的正EPS；只算FCF法时可省略") BigDecimal eps,
                                 @ToolParam(required = false, description = "FCF法使用的正股数；只算PE法时可省略") BigDecimal dilutedShares,
                                 @ToolParam(required = false, description = "用户声明的股权自由现金流；只算PE法时可省略") BigDecimal equityFreeCashFlow,
                                 List<Scenario> scenarios) {}
    public record Scenario(String name,
                           @ToolParam(required = false, description = "PE法的正倍数；和fcfYield至少提供一种") BigDecimal peMultiple,
                           @ToolParam(required = false, description = "FCF收益率小数，如0.05；和peMultiple至少提供一种") BigDecimal fcfYield) {}

    @Tool(description = "计算1至5套显式估值情景，仅支持EPS×PE与股权自由现金流/(股数×FCF收益率)。输入必须同币种、同时间口径；fcfYield用小数如0.05。equityFreeCashFlow必须是用户明确声明的股权口径，不自动把企业自由现金流或任意OCF差额当成FCFE。不抓取价格或财务，不预测，不平均不同方法的估值。输出回显假设、金额两位小数及缺口")
    public String calculateValuationScenarios(@ToolParam(description = "currency、eps、dilutedShares、equityFreeCashFlow、scenarios；每情景name及peMultiple或fcfYield，至少一种") ValuationInput input) {
        String invalid = validate(input);
        if (invalid != null) return objectMapper.createObjectNode().put("schemaVersion", 1).put("kind", "valuation_scenarios")
                .put("status", "ERROR").put("error", true).put("errorCode", "INVALID_SCENARIO_INPUT").put("message", invalid).toString();
        var result = new LinkedHashMap<String, Object>();
        result.put("schemaVersion", 1); result.put("kind", "valuation_scenarios"); result.put("provider", "USER_DECLARED_INPUTS");
        result.put("currency", input.currency()); result.put("inputs", input); result.put("error", false);
        result.put("basis", "输入的币种、期间与股权FCF口径由用户声明，未从外部来源核验；结果是这些假设下的每股价格，不是价格预测。");
        var scenarios = new ArrayList<java.util.Map<String, Object>>();
        var pePrices = new ArrayList<BigDecimal>();
        var fcfPrices = new ArrayList<BigDecimal>();
        for (Scenario scenario : input.scenarios()) {
            var row = new LinkedHashMap<String, Object>();
            row.put("name", scenario.name()); row.put("assumptions", scenario);
            row.put("pePrice", null); row.put("fcfPrice", null);
            if (scenario.peMultiple() != null) {
                row.put("peFormula", "eps * peMultiple");
                if (input.eps() == null || input.eps().signum() <= 0) row.put("peGap", "EPS 缺失或非正，PE 法不适用。");
                else {
                    BigDecimal price = input.eps().multiply(scenario.peMultiple()).setScale(2, RoundingMode.HALF_UP);
                    row.put("pePrice", price); pePrices.add(price);
                }
            }
            if (scenario.fcfYield() != null) {
                row.put("fcfFormula", "equityFreeCashFlow / (dilutedShares * fcfYield)");
                if (input.equityFreeCashFlow() == null || input.equityFreeCashFlow().signum() <= 0) {
                    row.put("fcfGap", "股权自由现金流缺失或非正，正收益率情景不能给出适用估值。");
                } else if (input.dilutedShares() == null) {
                    row.put("fcfGap", "股数缺失，不能输出每股价格。");
                } else {
                    BigDecimal price = input.equityFreeCashFlow().divide(input.dilutedShares().multiply(scenario.fcfYield()), 2, RoundingMode.HALF_UP);
                    row.put("fcfPrice", price); fcfPrices.add(price);
                }
            }
            row.put("status", row.get("pePrice") == null && row.get("fcfPrice") == null ? "UNAVAILABLE"
                    : row.containsKey("peGap") || row.containsKey("fcfGap") ? "PARTIAL" : "AVAILABLE");
            scenarios.add(row);
        }
        result.put("scenarios", scenarios);
        if (!pePrices.isEmpty()) result.put("peRange", range(pePrices));
        if (!fcfPrices.isEmpty()) result.put("fcfRange", range(fcfPrices));
        result.put("status", pePrices.isEmpty() && fcfPrices.isEmpty() ? "EMPTY"
                : scenarios.stream().allMatch(row -> "AVAILABLE".equals(row.get("status"))) ? "SUCCESS" : "PARTIAL");
        return objectMapper.valueToTree(result).toString();
    }

    private static String validate(ValuationInput input) {
        if (input == null || input.currency() == null || !input.currency().matches("[A-Z]{3}")) return "请声明一个三位大写币种代码，并统一所有金额的币种与期间。";
        if (input.scenarios() == null || input.scenarios().isEmpty() || input.scenarios().size() > 5) return "请提供1至5个情景。";
        for (BigDecimal value : new BigDecimal[]{input.eps(), input.dilutedShares(), input.equityFreeCashFlow()}) {
            if (!bounded(value)) return "输入数值最多30位有效数字、18位小数；请检查数量单位和指数。";
        }
        if (input.dilutedShares() != null && input.dilutedShares().signum() <= 0) return "股数必须为正，不能使用零或负股数作分母。";
        var names = new HashSet<String>();
        for (Scenario scenario : input.scenarios()) {
            if (scenario == null || scenario.name() == null || scenario.name().isBlank() || scenario.name().length() > 80
                    || !names.add(scenario.name().strip())) return "每个情景需要不重复的1至80字名称。";
            if (scenario.peMultiple() == null && scenario.fcfYield() == null) return "每个情景至少需要PE或FCF收益率一种假设。";
            for (BigDecimal value : new BigDecimal[]{scenario.peMultiple(), scenario.fcfYield()}) {
                if (!bounded(value) || value != null && value.signum() <= 0) return "PE和FCF收益率必须是有限正数；收益率用小数，例如0.05。";
            }
        }
        return null;
    }

    private static boolean bounded(BigDecimal value) {
        return value == null || value.precision() <= 30 && Math.abs((long) value.scale()) <= 18;
    }
    private static java.util.Map<String, BigDecimal> range(List<BigDecimal> values) {
        return java.util.Map.of("min", values.stream().min(BigDecimal::compareTo).orElseThrow(),
                "max", values.stream().max(BigDecimal::compareTo).orElseThrow());
    }
}
