package com.stocksage.agent.intent;

import com.stocksage.agent.PlanRoute;
import org.junit.jupiter.api.Test;
import org.springframework.ai.embedding.EmbeddingModel;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.core.task.support.TaskExecutorAdapter;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class EmbeddingIntentMatcherTest {

    @Test
    void selectsAPrototypeOnlyWhenSimilarityAndMarginAreValid() {
        EmbeddingModel model = mock(EmbeddingModel.class);
        when(model.embed(anyList())).thenReturn(List.of(
                new float[]{1f, 0f},
                new float[]{0f, 1f},
                new float[]{-1f, 0f},
                new float[]{0f, -1f},
                new float[]{0.7f, 0.7f}
        ));
        when(model.embed(anyString())).thenReturn(new float[]{0f, 1f});
        EmbeddingIntentMatcher matcher = matcher(model);

        IntentSignal signal = matcher.match(new IntentRecognitionRequest(
                "NVDA 当前股价", List.of(), 0, false, List.of("NVDA"))).orElseThrow();

        assertThat(signal.source()).isEqualTo(IntentSignalSource.EMBEDDING);
        assertThat(signal.targetRoute()).isEqualTo(PlanRoute.MARKET);
        assertThat(signal.entities()).containsEntry("ticker", "NVDA");
        verify(model).embed(anyList());
    }

    @Test
    void zeroOrNonFiniteQueryVectorsAbstain() {
        EmbeddingModel model = mock(EmbeddingModel.class);
        when(model.embed(anyList())).thenReturn(List.of(
                new float[]{1f, 0f}, new float[]{0f, 1f}, new float[]{-1f, 0f},
                new float[]{0f, -1f}, new float[]{0.7f, 0.7f}));
        when(model.embed(anyString())).thenReturn(new float[]{0f, 0f});

        assertThat(matcher(model).match(new IntentRecognitionRequest(
                "query", List.of(), 0, false, List.of()))).isEmpty();
    }

    @SuppressWarnings("unchecked")
    private EmbeddingIntentMatcher matcher(EmbeddingModel model) {
        ObjectProvider<EmbeddingModel> provider = mock(ObjectProvider.class);
        when(provider.getIfAvailable()).thenReturn(model);
        return new EmbeddingIntentMatcher(
                provider, new TaskExecutorAdapter(Runnable::run), true, 200, 0.55, 0.03);
    }
}
