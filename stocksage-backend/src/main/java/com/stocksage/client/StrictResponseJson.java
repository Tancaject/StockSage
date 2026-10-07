package com.stocksage.client;

import com.fasterxml.jackson.core.JsonGenerator;
import com.fasterxml.jackson.core.JsonParser;
import com.fasterxml.jackson.core.JsonToken;
import com.fasterxml.jackson.databind.DeserializationContext;
import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.JsonDeserializer;
import com.fasterxml.jackson.databind.JsonSerializer;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializerProvider;
import com.fasterxml.jackson.databind.cfg.CoercionAction;
import com.fasterxml.jackson.databind.cfg.CoercionInputShape;
import com.fasterxml.jackson.databind.module.SimpleModule;
import com.fasterxml.jackson.databind.type.LogicalType;

import java.io.IOException;
import java.math.BigDecimal;
import java.util.List;

/** 共享响应契约的 JSON 类型规则；十进制字符串仅用于明确声明该编码的财务响应。 */
final class StrictResponseJson {
    private StrictResponseJson() {}

    static ObjectMapper newMapper() {
        ObjectMapper mapper = new ObjectMapper()
                .disable(DeserializationFeature.ACCEPT_FLOAT_AS_INT)
                .enable(DeserializationFeature.FAIL_ON_NUMBERS_FOR_ENUMS)
                .enable(DeserializationFeature.FAIL_ON_NULL_FOR_PRIMITIVES);
        for (LogicalType type : List.of(LogicalType.Textual, LogicalType.Integer, LogicalType.Boolean)) {
            var coercions = mapper.coercionConfigFor(type);
            if (type != LogicalType.Textual) {
                coercions.setCoercion(CoercionInputShape.String, CoercionAction.Fail)
                        .setCoercion(CoercionInputShape.EmptyString, CoercionAction.Fail);
            }
            if (type != LogicalType.Boolean) coercions.setCoercion(CoercionInputShape.Boolean, CoercionAction.Fail);
            if (type == LogicalType.Textual || type == LogicalType.Boolean) {
                coercions.setCoercion(CoercionInputShape.Integer, CoercionAction.Fail)
                        .setCoercion(CoercionInputShape.Float, CoercionAction.Fail);
            }
        }
        return mapper;
    }

    static ObjectMapper newDecimalStringMapper() {
        SimpleModule decimals = new SimpleModule();
        decimals.addDeserializer(BigDecimal.class, new JsonDeserializer<>() {
            @Override public BigDecimal deserialize(JsonParser parser, DeserializationContext context) throws IOException {
                if (!parser.hasToken(JsonToken.VALUE_STRING) || !parser.getText().matches("-?(0|[1-9][0-9]*)(\\.[0-9]+)?")) {
                    throw context.weirdStringException(parser.getValueAsString(), BigDecimal.class, "Expected a plain decimal string");
                }
                return new BigDecimal(parser.getText());
            }
        });
        decimals.addSerializer(BigDecimal.class, new JsonSerializer<>() {
            @Override public void serialize(BigDecimal value, JsonGenerator generator, SerializerProvider serializers) throws IOException {
                generator.writeString(value.toPlainString());
            }
        });
        return newMapper().registerModule(decimals);
    }
}
