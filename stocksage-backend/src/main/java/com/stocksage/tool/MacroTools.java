package com.stocksage.tool;

import com.stocksage.client.MacroDataClient;
import lombok.RequiredArgsConstructor;
import org.springframework.ai.tool.annotation.Tool;
import org.springframework.ai.tool.annotation.ToolParam;
import org.springframework.stereotype.Component;

@Component
@RequiredArgsConstructor
public class MacroTools {
    private final MacroDataClient macroDataClient;

    @Tool(description = "读取 FRED 官方宏观序列。可用 FEDFUNDS、CPIAUCSL、UNRATE、DGS10 等代码。空 asOf 取当前可得修订值；历史 asOf 通过 FRED/ALFRED API 且必须配置 FRED_API_KEY。无 key 仅可取官方当期 CSV，单位与 vintage 缺口明确返回")
    public String getMacroSeries(
            @ToolParam(description = "FRED 序列代码，不能传 URL") String seriesId,
            @ToolParam(description = "观察起始日期 YYYY-MM-DD，空为近五年") String startDate,
            @ToolParam(description = "观察结束日期 YYYY-MM-DD，空为今天") String endDate,
            @ToolParam(description = "历史已知时点 YYYY-MM-DD；空为最新修订值，不能把观察日期当 vintage") String asOf) {
        return macroDataClient.series(seriesId, startDate, endDate, asOf);
    }
}
