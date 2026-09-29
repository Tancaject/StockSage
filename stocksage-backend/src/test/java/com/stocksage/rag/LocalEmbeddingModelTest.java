package com.stocksage.rag;

import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.web.reactive.function.client.ClientResponse;
import org.springframework.web.reactive.function.client.WebClient;
import reactor.core.publisher.Mono;
import reactor.core.publisher.Sinks;
import com.stocksage.tool.ToolCallContext;
import com.stocksage.exception.ResearchBudgetExceededException;
import com.stocksage.exception.ResearchCapacityExceededException;

import java.time.Duration;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class LocalEmbeddingModelTest {
    @Test
    void budgetCannotStartWithAnUnmeteredEmbeddingProvider() {
        var budget = new com.stocksage.config.ModelTokenBudgetProperties(100L, "a".repeat(64), "v", "fixture",
                java.time.Instant.parse("2020-01-01T00:00:00Z"), java.time.Instant.parse("2100-01-01T00:00:00Z"),
                java.util.Map.of("chat", new com.stocksage.config.ModelTokenBudgetProperties.Limit(1, 1, 0)));
        for (String provider : new String[]{"ollama", "dashscope"}) {
            new org.springframework.boot.test.context.runner.ApplicationContextRunner()
                    .withUserConfiguration(com.stocksage.config.EmbeddingConfig.class)
                    .withBean(com.stocksage.config.ModelTokenBudgetProperties.class, () -> budget)
                    .withBean(com.stocksage.research.ModelInvocationStore.class,
                            () -> org.mockito.Mockito.mock(com.stocksage.research.ModelInvocationStore.class))
                    .withPropertyValues("stocksage.embedding.provider=" + provider)
                    .run(context -> {
                        if (provider.equals("ollama")) {
                            assertThat(context.getStartupFailure()).isNull();
                            assertThat(context.getBean(org.springframework.ai.embedding.EmbeddingModel.class)).isInstanceOf(LocalEmbeddingModel.class);
                        } else {
                            assertThat(context.getStartupFailure()).hasRootCauseInstanceOf(IllegalArgumentException.class)
                                    .hasStackTraceContaining("尚未覆盖 embedding provider=dashscope");
                        }
                    });
        }
        assertThat(new com.stocksage.config.EmbeddingConfig("dashscope",
                com.stocksage.config.ModelTokenBudgetProperties.disabled())).isNotNull();
    }

    @Test
    void concurrentRequestsSharePermitUntilResponseBodyCompletes() throws Exception {
        var model = new LocalEmbeddingModel("http://unused", "audit", Duration.ofSeconds(5), 2, 1);
        var subscribed = new java.util.concurrent.CountDownLatch(1);
        var calls = new AtomicInteger();
        Sinks.One<org.springframework.core.io.buffer.DataBuffer> responseBody = Sinks.one();
        ReflectionTestUtils.setField(model, "webClient", WebClient.builder().exchangeFunction(request -> {
            int attempt = calls.incrementAndGet();
            var response = ClientResponse.create(HttpStatus.OK).header("Content-Type", "application/json");
            return Mono.just(attempt == 1
                    ? response.body(responseBody.asMono().flux().doOnSubscribe(ignored -> subscribed.countDown())).build()
                    : response.body("{\"embeddings\":[[1,2]]}").build());
        }).build());
        var executor = java.util.concurrent.Executors.newSingleThreadExecutor();
        try {
            var active = executor.submit(() -> model.embed("held"));
            assertThat(subscribed.await(2, java.util.concurrent.TimeUnit.SECONDS)).isTrue();
            assertThatThrownBy(() -> model.embed("rejected")).isInstanceOf(ResearchCapacityExceededException.class);
            assertThat(calls).hasValue(1);
            responseBody.tryEmitValue(new org.springframework.core.io.buffer.DefaultDataBufferFactory()
                    .wrap("{\"embeddings\":[[1,2]]}".getBytes(java.nio.charset.StandardCharsets.UTF_8)));
            assertThat(active.get(2, java.util.concurrent.TimeUnit.SECONDS)).containsExactly(1f, 2f);
            assertThat(model.embed("after body complete")).containsExactly(1f, 2f);
            assertThat(calls).hasValue(2);
        } finally {
            executor.shutdownNow();
        }
    }

    @Test
    void oneByOneFallbackSharesDeadlineAndStopsBeforeNextText() {
        var store = org.mockito.Mockito.mock(com.stocksage.research.ModelInvocationStore.class);
        org.mockito.Mockito.when(store.begin(org.mockito.ArgumentMatchers.any(), org.mockito.ArgumentMatchers.anyString(),
                org.mockito.ArgumentMatchers.anyMap())).thenReturn("invocation");
        var model = new LocalEmbeddingModel("http://unused", "audit", Duration.ofSeconds(5), 2, 1, store,
                com.stocksage.config.ModelTokenBudgetProperties.disabled());
        var calls = new AtomicInteger();
        var cancellations = new AtomicInteger();
        ReflectionTestUtils.setField(model, "webClient", WebClient.builder().exchangeFunction(request -> {
            int attempt = calls.incrementAndGet();
            if (attempt == 2) return Mono.<ClientResponse>never().doOnCancel(cancellations::incrementAndGet);
            return Mono.just(ClientResponse.create(HttpStatus.OK).header("Content-Type", "application/json")
                    .body(attempt == 1 ? "{\"embeddings\":[[1]]}" : "{\"embeddings\":[[1,2]]}").build());
        }).build());
        var deadline = new ToolCallContext.RunDeadline(71L, System.currentTimeMillis() + 1000L);
        var execution = new ToolCallContext.RunExecution(71L, 1, "owner", "trace", deadline.deadlineEpochMs());
        assertThatThrownBy(() -> ToolCallContext.withRunExecution(execution,
                () -> model.embed(java.util.List.of("first", "second"))))
                .isInstanceOf(ResearchBudgetExceededException.class);
        assertThat(calls).hasValue(2);
        assertThat(cancellations).hasValue(1);
        assertThat(model.embed("after timeout")).containsExactly(1f, 2f);
        assertThat(calls).hasValue(3);
        assertThatThrownBy(() -> ToolCallContext.withRunDeadline(deadline, () -> model.embed("already expired")))
                .isInstanceOf(ResearchBudgetExceededException.class);
        assertThat(calls).hasValue(3);
    }

    @Test
    @SuppressWarnings("unchecked")
    void actualBatchAndFallbackRequestsHaveIndependentUsageAndAdmission() throws Exception {
        var mapper = new com.fasterxml.jackson.databind.ObjectMapper();
        var bodies = new java.util.concurrent.CopyOnWriteArrayList<com.fasterxml.jackson.databind.JsonNode>();
        var server = com.sun.net.httpserver.HttpServer.create(new java.net.InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/api/embed", exchange -> {
            bodies.add(mapper.readTree(exchange.getRequestBody()));
            int call = bodies.size();
            String response = switch (call) {
                case 1 -> "{\"model\":\"served-embedding\",\"embeddings\":[[1]],\"prompt_eval_count\":10}";
                case 2 -> "{\"embeddings\":[[1,2]],\"prompt_eval_count\":3}";
                case 3 -> "{\"embeddings\":[[1,2]],\"prompt_eval_count\":4}";
                default -> "{\"embeddings\":[[1,2]]}";
            };
            byte[] bytes = response.getBytes(java.nio.charset.StandardCharsets.UTF_8);
            exchange.getResponseHeaders().set("Content-Type", "application/json");
            exchange.sendResponseHeaders(200, bytes.length);
            try (var output = exchange.getResponseBody()) { output.write(bytes); }
        });
        server.start();
        try {
            String base = "http://127.0.0.1:" + server.getAddress().getPort();
            var store = org.mockito.Mockito.mock(com.stocksage.research.ModelInvocationStore.class);
            var sequence = new AtomicInteger();
            org.mockito.Mockito.when(store.begin(org.mockito.ArgumentMatchers.any(), org.mockito.ArgumentMatchers.anyString(),
                    org.mockito.ArgumentMatchers.anyMap())).thenAnswer(call -> "embedding-" + sequence.incrementAndGet());
            var identity = new LocalEmbeddingModel(base, "audit", Duration.ofSeconds(5), 2).runtimeConfiguration().get("providerIdentity");
            String fingerprint = com.stocksage.config.ModelPricingProperties.providerFingerprint(mapper.valueToTree(identity));
            var from = java.time.Instant.parse("2020-01-01T00:00:00Z");
            var until = java.time.Instant.parse("2100-01-01T00:00:00Z");
            var budget = new com.stocksage.config.ModelTokenBudgetProperties(1000L, "a".repeat(64), "fixture", "fixture", from, until,
                    java.util.Map.of("chat", new com.stocksage.config.ModelTokenBudgetProperties.Limit(1, 1, 0)),
                    new com.stocksage.config.ModelTokenBudgetProperties.Embedding(fingerprint, "audit", 64, "test-runtime-and-model",
                            "local-http-fixture", from, until));
            var model = new LocalEmbeddingModel(base, "audit", Duration.ofSeconds(5), 2, 1, store, budget);
            var execution = new ToolCallContext.RunExecution(73L, 2, "owner", "trace", System.currentTimeMillis() + 60_000);
            assertThat(ToolCallContext.withRunExecution(execution, () -> model.embed(java.util.List.of(" a ", "b")))).hasSize(2);
            var requests = org.mockito.ArgumentCaptor.forClass(java.util.Map.class);
            var inputs = org.mockito.ArgumentCaptor.forClass(com.stocksage.model.dto.ModelInvocationContext.class);
            org.mockito.Mockito.verify(store, org.mockito.Mockito.times(3)).begin(inputs.capture(),
                    org.mockito.ArgumentMatchers.eq("ollama-embedding"), requests.capture());
            assertThat(requests.getAllValues()).extracting(value -> value.get("inputCount")).containsExactly(2, 1, 1);
            for (int i = 0; i < 3; i++) {
                assertThat(bodies.get(i).path("options").path("num_ctx").asInt()).isEqualTo(64);
                assertThat(inputs.getAllValues().get(i).inputSha256())
                        .isEqualTo(com.stocksage.knowledge.KnowledgeIngestionService.sha256(bodies.get(i).path("input").toString()));
                assertThat(inputs.getAllValues().get(i).runId()).isEqualTo(73L);
                assertThat(inputs.getAllValues().get(i).evidenceSnapshotId()).isNull();
            }
            var usages = org.mockito.ArgumentCaptor.forClass(java.util.Map.class);
            org.mockito.Mockito.verify(store, org.mockito.Mockito.times(3)).finish(org.mockito.ArgumentMatchers.anyString(),
                    org.mockito.ArgumentMatchers.eq("SUCCEEDED"), usages.capture());
            assertThat(usages.getAllValues()).extracting(value -> value.get("totalTokens")).containsExactly(10L, 3L, 4L);
            assertThat(usages.getAllValues().get(0)).containsEntry("actualModel", "served-embedding")
                    .containsEntry("completionScope", "PROVIDER_HTTP_RESPONSE");
            ToolCallContext.withRunExecution(execution, () -> model.embed("missing usage"));
            org.mockito.Mockito.verify(store).finish(org.mockito.ArgumentMatchers.eq("embedding-4"),
                    org.mockito.ArgumentMatchers.eq("SUCCEEDED"), org.mockito.ArgumentMatchers.argThat(value ->
                            "NO_DATA".equals(value.get("usageSource")) && !value.containsKey("totalTokens")));
            org.mockito.Mockito.doThrow(new ResearchBudgetExceededException(73L, ResearchBudgetExceededException.Reason.TOKEN_LIMIT))
                    .when(store).begin(org.mockito.ArgumentMatchers.any(), org.mockito.ArgumentMatchers.anyString(), org.mockito.ArgumentMatchers.anyMap());
            assertThatThrownBy(() -> ToolCallContext.withRunExecution(execution, () -> model.embed("rejected")))
                    .isInstanceOf(ResearchBudgetExceededException.class);
            assertThat(bodies).hasSize(4);
            assertThat(model.embed("permit restored")).containsExactly(1f, 2f);
        } finally { server.stop(0); }
    }
    @Test
    void invalidProviderResponseFailsAndTheSameTextCanBeRetriedAfterRecovery() {
        LocalEmbeddingModel model = new LocalEmbeddingModel("http://unused", "audit", Duration.ofSeconds(1), 2, 1);
        AtomicInteger attempts = new AtomicInteger();
        ReflectionTestUtils.setField(model, "webClient", WebClient.builder().exchangeFunction(request -> {
            boolean invalid = attempts.incrementAndGet() <= 2;
            return Mono.just(ClientResponse.create(invalid ? HttpStatus.INTERNAL_SERVER_ERROR : HttpStatus.OK)
                    .header("Content-Type", "application/json")
                    .body(invalid ? "{\"error\":\"unsupported value NaN\"}" : "{\"embeddings\":[[1,2]]}")
                    .build());
        }).build());

        assertThatThrownBy(() -> model.embed("same full document")).isInstanceOf(RuntimeException.class);
        assertThat(attempts).hasValue(2);
        assertThat(model.embed("same full document")).containsExactly(1f, 2f);
    }

    @Test
    void zeroWrongDimensionAndNonnumericVectorsCannotBecomeSuccess() {
        for (String vector : new String[]{"[0,0]", "[1]", "[]", "[1,\"NaN\"]"}) {
            LocalEmbeddingModel model = new LocalEmbeddingModel("http://unused", "audit", Duration.ofSeconds(1), 2);
            ReflectionTestUtils.setField(model, "webClient", WebClient.builder().exchangeFunction(request ->
                    Mono.just(ClientResponse.create(HttpStatus.OK).header("Content-Type", "application/json")
                            .body("{\"embeddings\":[" + vector + "]}").build())).build());
            assertThatThrownBy(() -> model.embed("document")).isInstanceOf(RuntimeException.class);
        }
    }
}
