package com.stocksage.capability;

import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.regex.Pattern;

/**
 * DEEP 取证工具适配器共用的参数校验。
 *
 * <p>工具菜单已在调用前校验一次；适配器再校验一次，保证绕过菜单直接调用网关时同样零执行。
 * 任何多余字段、越界取值或非法标的都以 {@link CapabilityException.Reason#DENIED} 拒绝。</p>
 */
final class ResearchCapabilityArguments {

    /** 美股代码、港股/A 股结构化代码；不接受空格、通配符或过滤表达式字符。 */
    private static final Pattern SAFE_TICKER = Pattern.compile("^[A-Z0-9]{1,6}(\\.[A-Z]{1,2})?$|^(SH|SZ|BJ)\\.\\d{6}$");

    private ResearchCapabilityArguments() {
    }

    static void requireOnlyKeys(Map<String, Object> arguments, Set<String> allowed) {
        for (String key : arguments.keySet()) {
            if (!allowed.contains(key)) {
                throw denied("Unexpected argument: " + key);
            }
        }
    }

    static String requireTicker(Map<String, Object> arguments) {
        Object raw = arguments.get("ticker");
        String ticker = raw instanceof String text ? text.strip().toUpperCase(Locale.ROOT) : "";
        if (!SAFE_TICKER.matcher(ticker).matches()) {
            throw denied("Argument ticker is missing or invalid");
        }
        return ticker;
    }

    static String requireEnum(Map<String, Object> arguments, String key, Set<String> allowed) {
        Object raw = arguments.get(key);
        if (!(raw instanceof String value) || !allowed.contains(value)) {
            throw denied("Argument " + key + " must be one of " + allowed);
        }
        return value;
    }

    static String requireText(Map<String, Object> arguments, String key, int maxLength) {
        Object raw = arguments.get(key);
        String value = raw instanceof String text ? text.replaceAll("\\p{Cntrl}+", " ").replaceAll("\\s+", " ").strip() : "";
        if (value.isBlank() || value.length() > maxLength) {
            throw denied("Argument " + key + " is blank or longer than " + maxLength);
        }
        return value;
    }

    static int requireInt(Map<String, Object> arguments, String key, int min, int max) {
        Object raw = arguments.get(key);
        if (!(raw instanceof Integer || raw instanceof Long)) {
            throw denied("Argument " + key + " must be an integer");
        }
        long value = ((Number) raw).longValue();
        if (value < min || value > max) {
            throw denied("Argument " + key + " must be within [" + min + ", " + max + "]");
        }
        return (int) value;
    }

    private static CapabilityException denied(String message) {
        return new CapabilityException(CapabilityException.Reason.DENIED, message);
    }
}
