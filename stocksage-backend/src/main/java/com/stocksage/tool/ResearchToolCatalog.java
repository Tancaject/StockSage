package com.stocksage.tool;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.springframework.ai.support.ToolCallbacks;
import org.springframework.ai.tool.ToolCallback;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** Explicitly registered read-only research tools shared by the workbench and named Skills. */
@Component
public class ResearchToolCatalog {
    private final ObjectMapper mapper;
    private final Map<String, ToolCallback> callbacks = new LinkedHashMap<>();
    private final JsonNode examples;

    @Autowired
    public ResearchToolCatalog(ObjectMapper mapper, DisclosureTools disclosures, ResearchHistoryTools history,
                               FinancialAnalysisTools financials, ValuationScenarioTools valuation,
                               MarketResearchTools market, PortfolioTools portfolio,
                               EventResearchTools events, MacroTools macro) {
        this(mapper, new Object[]{disclosures, history, financials, valuation, market, portfolio, events, macro});
    }

    public ResearchToolCatalog(ObjectMapper mapper, Object... providers) {
        this.mapper = mapper;
        for (ToolCallback callback : ToolCallbacks.from(providers)) {
            String name = callback.getToolDefinition().name();
            if (callbacks.putIfAbsent(name, callback) != null) throw new IllegalStateException("Duplicate research tool: " + name);
        }
        try (var input = getClass().getResourceAsStream("/research-tool-examples.json")) {
            examples = input == null ? mapper.createObjectNode() : mapper.readTree(input);
        } catch (Exception e) { throw new IllegalStateException("Cannot load research tool examples", e); }
    }

    public List<Map<String, Object>> list() {
        return callbacks.values().stream().map(callback -> {
            var definition = callback.getToolDefinition();
            return Map.<String, Object>of("name", definition.name(), "description", definition.description(),
                    "inputSchema", schema(callback), "example", examples.path(definition.name()).isMissingNode()
                            ? mapper.createObjectNode() : examples.path(definition.name()));
        }).toList();
    }

    public void require(String name) {
        if (!callbacks.containsKey(name)) throw new IllegalArgumentException("未知研究工具：" + name);
    }

    public JsonNode execute(String name, JsonNode arguments) {
        require(name);
        if (arguments == null || !arguments.isObject() || arguments.toString().getBytes(java.nio.charset.StandardCharsets.UTF_8).length > 262144)
            throw new IllegalArgumentException("工具参数必须是大小不超过 256 KiB 的 JSON 对象");
        ToolCallback callback = callbacks.get(name);
        JsonNode schema = schema(callback);
        validate(arguments, schema, schema, "arguments");
        String result = callback.call(arguments.toString());
        if (result == null || result.getBytes(java.nio.charset.StandardCharsets.UTF_8).length > 1048576) throw new IllegalStateException("工具结果为空或超过 1 MiB，请缩小查询范围");
        try {
            JsonNode parsed = mapper.readTree(result);
            if (parsed == null || !parsed.isContainerNode()) throw new IllegalStateException("工具未返回结构化结果：" + name);
            return parsed;
        } catch (com.fasterxml.jackson.core.JsonProcessingException e) {
            throw new IllegalStateException("工具未返回有效 JSON：" + name, e);
        }
    }

    public static ObjectNode bind(ObjectMapper mapper, Map<String, Object> bindings, JsonNode input) {
        ObjectNode result = mapper.createObjectNode();
        bindings.forEach((key, value) -> {
            if (value instanceof String text && text.startsWith("$input.")) {
                String field = text.substring(7);
                if (input == null || !input.hasNonNull(field)) throw new IllegalArgumentException("Skill 缺少输入：" + field);
                result.set(key, input.get(field));
            } else result.set(key, mapper.valueToTree(value));
        });
        return result;
    }

    private JsonNode schema(ToolCallback callback) {
        try { return mapper.readTree(callback.getToolDefinition().inputSchema()); }
        catch (Exception e) { throw new IllegalStateException("Invalid registered tool schema", e); }
    }

    private static void validate(JsonNode value, JsonNode schema, JsonNode root, String path) {
        if (schema.has("$ref")) {
            String ref = schema.path("$ref").asText();
            if (!ref.startsWith("#/")) throw new IllegalStateException("Only local schema references are supported");
            validate(value, root.at(ref.substring(1)), root, path);
            return;
        }
        String type = schema.path("type").asText();
        boolean valid = switch (type) {
            case "object" -> value.isObject(); case "array" -> value.isArray();
            case "string" -> value.isTextual(); case "integer" -> value.isIntegralNumber();
            case "number" -> value.isNumber(); case "boolean" -> value.isBoolean();
            default -> true;
        };
        if (!valid) throw new IllegalArgumentException(path + " 必须为 " + type);
        if (value.isObject()) {
            for (JsonNode key : schema.path("required"))
                if (!value.hasNonNull(key.asText())) throw new IllegalArgumentException(path + " 缺少参数：" + key.asText());
            JsonNode properties = schema.path("properties");
            value.fields().forEachRemaining(entry -> {
                if (!properties.has(entry.getKey())) {
                    if (!schema.path("additionalProperties").asBoolean(false))
                        throw new IllegalArgumentException(path + " 包含未知参数：" + entry.getKey());
                } else validate(entry.getValue(), properties.get(entry.getKey()), root, path + "." + entry.getKey());
            });
        } else if (value.isArray()) {
            for (JsonNode item : value) validate(item, schema.path("items"), root, path + "[]");
        }
    }
}
