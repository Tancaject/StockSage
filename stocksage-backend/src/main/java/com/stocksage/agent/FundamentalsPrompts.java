package com.stocksage.agent;

import org.springframework.core.io.ClassPathResource;
import org.springframework.core.io.Resource;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;

/** Keeps the role contract fixed while allowing only its analysis method to vary. */
public final class FundamentalsPrompts {
    private static final String METHOD_SLOT = "{{fundamentals.method}}";
    private static final String SYSTEM = read(new ClassPathResource("prompts/fundamentals-system.txt"));
    private static final String BASELINE_METHOD = read(new ClassPathResource("prompts/fundamentals-method.txt"))
            .stripTrailing();
    private static final String TASK = read(new ClassPathResource("prompts/fundamentals-task.txt"));

    static {
        if (SYSTEM.indexOf(METHOD_SLOT) < 0 || SYSTEM.indexOf(METHOD_SLOT) != SYSTEM.lastIndexOf(METHOD_SLOT)) {
            throw new IllegalStateException("基本面系统提示词必须且只能包含一个方法占位符；请检查 prompts/fundamentals-system.txt");
        }
    }

    private FundamentalsPrompts() {}

    public static String baselineMethod() {
        return BASELINE_METHOD;
    }

    public static String system(String method) {
        if (method == null) {
            throw new IllegalArgumentException("基本面分析方法不能为 null；请加载有效的方法版本");
        }
        return SYSTEM.replace(METHOD_SLOT, method);
    }

    public static String task(String query, String context) {
        return TASK.formatted(query, context == null ? "" : context);
    }

    static String read(Resource resource) {
        try (var input = resource.getInputStream()) {
            // Java text blocks use LF even when a Windows checkout uses CRLF.
            String text = StandardCharsets.UTF_8.newDecoder().decode(ByteBuffer.wrap(input.readAllBytes()))
                    .toString().replace("\r\n", "\n");
            if (text.isBlank()) {
                throw new IllegalStateException("基本面提示词资源为空：" + resource.getDescription()
                        + "；请恢复有效资源后重新启动");
            }
            return text;
        } catch (IOException failure) {
            throw new IllegalStateException("无法读取基本面 UTF-8 提示词资源：" + resource.getDescription()
                    + "；请检查资源是否存在、编码是否正确后重新启动", failure);
        }
    }
}
