package com.stocksage.rag;

import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.web.reactive.function.client.ClientResponse;
import org.springframework.web.reactive.function.client.WebClient;
import reactor.core.publisher.Mono;

import java.time.Duration;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class LocalEmbeddingModelTest {
    @Test
    void invalidProviderResponseFailsAndTheSameTextCanBeRetriedAfterRecovery() {
        LocalEmbeddingModel model = new LocalEmbeddingModel("http://unused", "audit", Duration.ofSeconds(1), 2);
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
