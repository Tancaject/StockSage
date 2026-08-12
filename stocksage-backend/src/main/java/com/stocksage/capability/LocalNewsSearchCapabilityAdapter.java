package com.stocksage.capability;

import com.stocksage.tool.NewsTools;
import org.springframework.stereotype.Component;

import java.util.Map;

/**
 * 把现有 {@link NewsTools#searchNews(String, int)} 暴露为统一能力的本地适配器。
 *
 * <p>上游 Skill 可把它作为 MCP 新闻搜索的 fallback；调用仍经过 CapabilityGateway 的授权、
 * 超时和观测。该适配器只做参数校验与范围收敛，不改变新闻工具的数据源逻辑。</p>
 */
@Component
public class LocalNewsSearchCapabilityAdapter implements CapabilityAdapter {

    /** 与能力 YAML 和 Skill fallback 引用一致的稳定 ID。 */
    public static final String ID = "local.news.searchNews";

    /** 复用项目既有新闻工具，避免维护第二套本地搜索实现。 */
    private final NewsTools newsTools;

    public LocalNewsSearchCapabilityAdapter(NewsTools newsTools) {
        this.newsTools = newsTools;
    }

    /** @return 本地新闻能力的稳定 ID */
    @Override
    public String capabilityId() {
        return ID;
    }

    /** 本地 Spring Bean 始终可调；实际数据源错误会在 invoke 阶段返回。 */
    @Override
    public boolean isAvailable() {
        return true;
    }

    /**
     * 校验查询词、限制结果数后调用现有新闻工具。
     *
     * @param arguments 支持 {@code query} 和 {@code maxResults}
     * @param context 当前能力上下文；本适配器不直接使用
     * @return 新闻工具返回的 JSON 文本
     */
    @Override
    public String invoke(Map<String, Object> arguments, CapabilityInvocationContext context) {
        // 调用现有 @Tool；Gateway 会抑制重复的 ToolCallAspect observation。
        return newsTools.searchNews(requiredQuery(arguments), boundedLimit(arguments));
    }

    private String requiredQuery(Map<String, Object> arguments) {
        String query = String.valueOf(arguments.getOrDefault("query", "")).trim();
        if (query.isBlank()) {
            throw new CapabilityException(CapabilityException.Reason.DENIED,
                    "News search query must not be blank");
        }
        return query;
    }

    private int boundedLimit(Map<String, Object> arguments) {
        Object raw = arguments.getOrDefault("maxResults", 5);
        int value;
        try {
            value = raw instanceof Number number ? number.intValue() : Integer.parseInt(String.valueOf(raw));
        } catch (NumberFormatException error) {
            value = 5;
        }
        return Math.max(1, Math.min(10, value));
    }
}
