package com.stocksage.trace;

import com.stocksage.agent.AgentStep;
import com.stocksage.config.PhoenixTraceProperties;
import io.opentelemetry.api.common.AttributeKey;
import io.opentelemetry.sdk.common.CompletableResultCode;
import io.opentelemetry.sdk.trace.SdkTracerProvider;
import io.opentelemetry.sdk.trace.data.SpanData;
import io.opentelemetry.sdk.trace.export.SimpleSpanProcessor;
import io.opentelemetry.sdk.trace.export.SpanExporter;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyCollection;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class PhoenixTraceServiceTest {

    @Test
    void allExportedContentRequiresExplicitOptIn() {
        String privateText = "private-account-balance-123";
        for (boolean captureContent : List.of(false, true)) {
            PhoenixTraceProperties properties = new PhoenixTraceProperties();
            assertThat(properties.isCaptureContent()).isFalse();
            properties.setEnabled(true);
            properties.setCaptureContent(captureContent);
            List<SpanData> exported = new ArrayList<>();
            SpanExporter exporter = mock(SpanExporter.class);
            doAnswer(call -> {
                exported.addAll(call.<Collection<SpanData>>getArgument(0));
                return CompletableResultCode.ofSuccess();
            }).when(exporter).export(anyCollection());
            when(exporter.shutdown()).thenReturn(CompletableResultCode.ofSuccess());

            try (SdkTracerProvider provider = SdkTracerProvider.builder()
                    .addSpanProcessor(SimpleSpanProcessor.create(exporter)).build()) {
                PhoenixTraceService service = new PhoenixTraceService(provider.get("privacy-test"), properties);
                service.startTrace("trace-test", "private-user-id", 23L, privateText);
                service.addStep("trace-test", AgentStep.builder()
                        .action("tool-" + privateText).actionInput(privateText).observation(privateText)
                        .attributes(Map.of("kind", "routing-decision", "intentSummary", privateText,
                                "rationale", privateText, "rawRoute", privateText,
                                "fallbackReason", privateText, "confidence", 0.9)).build());
                service.recordRetrieval(privateText, 2, 10, privateText);
                service.endTrace("trace-test", privateText, 5, 20);
            }
            assertThat(exported).hasSize(3);
            for (SpanData span : exported) {
                assertThat(span.getName()).doesNotContain(privateText);
                assertThat(span.getStatus().getDescription()).isEmpty();
                assertThat(span.getAttributes().get(AttributeKey.stringKey("stocksage.user_id"))).isNull();
                assertThat(span.getAttributes().get(AttributeKey.longKey("stocksage.conversation_id"))).isNull();
                assertThat(span.getAttributes().get(AttributeKey.longKey("input.value.length")))
                        .isEqualTo((long) privateText.length());
                assertThat(span.getAttributes().get(AttributeKey.stringKey("input.value")))
                        .isEqualTo(captureContent ? privateText : null);
                if (!captureContent) assertThat(span.toString()).doesNotContain(privateText, "private-user-id");
            }
            SpanData step = exported.stream().filter(span -> span.getName().equals("stocksage.tool")).findFirst().orElseThrow();
            assertThat(step.getAttributes().get(AttributeKey.stringKey("stocksage.routing.rationale")))
                    .isEqualTo(captureContent ? privateText : null);
        }
    }
}
