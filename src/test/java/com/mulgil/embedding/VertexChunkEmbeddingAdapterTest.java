package com.mulgil.embedding;

import com.google.api.gax.rpc.InvalidArgumentException;
import com.google.cloud.vertexai.api.PredictResponse;
import com.google.cloud.vertexai.api.PredictionServiceClient;
import com.google.protobuf.ListValue;
import com.google.protobuf.Struct;
import com.google.protobuf.Value;
import com.mulgil.common.config.MulgilProperties;
import com.mulgil.indexing.ChunkEmbeddingPort;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import jakarta.validation.Validation;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.springframework.boot.test.system.CapturedOutput;
import org.springframework.boot.test.system.OutputCaptureExtension;
import org.springframework.boot.env.YamlPropertySourceLoader;
import org.springframework.core.io.ClassPathResource;
import org.springframework.mock.env.MockEnvironment;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(OutputCaptureExtension.class)
class VertexChunkEmbeddingAdapterTest {
    @Test
    void splitsTwelveInputsIntoFiveFiveTwo_preservesOrder_andUsesOneClientLifecycle() {
        PredictionServiceClient client = mock(PredictionServiceClient.class);
        List<Integer> batchSizes = new ArrayList<>();
        when(client.predict(anyString(), anyList(), any())).thenAnswer(invocation -> {
            List<Value> instances = invocation.getArgument(1);
            batchSizes.add(instances.size());
            return response(instances.stream().map(VertexChunkEmbeddingAdapterTest::marker).toList(), 768);
        });
        VertexChunkEmbeddingAdapter adapter = adapter(5, client, new SimpleMeterRegistry());

        List<ChunkEmbeddingPort.Embedding> embeddings = adapter.embedAll(
                java.util.stream.IntStream.range(0, 12).mapToObj(String::valueOf).toList());

        assertThat(batchSizes).containsExactly(5, 5, 2);
        assertThat(embeddings).extracting(embedding -> embedding.values().getFirst())
                .containsExactlyElementsOf(java.util.stream.IntStream.range(0, 12)
                        .mapToObj(value -> (float) value).toList());
        verify(client).close();
    }

    @Test
    void rejectsPredictionCountMismatch() {
        PredictionServiceClient client = mock(PredictionServiceClient.class);
        when(client.predict(anyString(), anyList(), any())).thenReturn(response(List.of(1f), 768));

        assertThatThrownBy(() -> adapter(5, client, new SimpleMeterRegistry()).embedAll(List.of("1", "2")))
                .isInstanceOf(IllegalStateException.class)
                .hasMessage("Vertex embedding count mismatch: expected 2 but received 1.");
    }

    @Test
    void rejectsNon768Prediction() {
        PredictionServiceClient client = mock(PredictionServiceClient.class);
        when(client.predict(anyString(), anyList(), any())).thenReturn(response(List.of(1f), 767));

        assertThatThrownBy(() -> adapter(5, client, new SimpleMeterRegistry()).embedAll(List.of("1")))
                .isInstanceOf(IllegalStateException.class)
                .hasMessage("Vertex embedding at index 0 must have 768 values.");
    }

    @Test
    void fallsBackSequentiallyOnlyAfterMultiInstanceProviderFailure_andRedactsReason(CapturedOutput output) {
        PredictionServiceClient client = mock(PredictionServiceClient.class);
        InvalidArgumentException providerFailure = mock(InvalidArgumentException.class);
        when(providerFailure.getMessage()).thenReturn("credential=secret");
        when(client.predict(anyString(), anyList(), any()))
                .thenThrow(providerFailure)
                .thenReturn(response(List.of(3f), 768))
                .thenReturn(response(List.of(4f), 768));
        SimpleMeterRegistry metrics = new SimpleMeterRegistry();

        List<ChunkEmbeddingPort.Embedding> embeddings = adapter(5, client, metrics).embedAll(List.of("3", "4"));

        assertThat(embeddings).extracting(embedding -> embedding.values().getFirst()).containsExactly(3f, 4f);
        verify(client, times(3)).predict(anyString(), anyList(), any());
        assertThat(metrics.counter("mulgil.embedding.batch.fallback", "reason", "multi_instance_failed").count())
                .isEqualTo(1d);
        assertThat(output).contains("embedding.batch.fallback", "reason=\"multi_instance_failed\"", "batchSize=\"2\"")
                .doesNotContain("credential=secret");
    }

    @Test
    void doesNotFallbackForSingleInstanceFailure() {
        PredictionServiceClient client = mock(PredictionServiceClient.class);
        InvalidArgumentException providerFailure = mock(InvalidArgumentException.class);
        when(client.predict(anyString(), anyList(), any())).thenThrow(providerFailure);
        SimpleMeterRegistry metrics = new SimpleMeterRegistry();

        assertThatThrownBy(() -> adapter(5, client, metrics).embed("1")).isSameAs(providerFailure);

        verify(client).predict(anyString(), anyList(), any());
        assertThat(metrics.find("mulgil.embedding.batch.fallback").counter()).isNull();
    }

    @Test
    void validatesBatchSizeFromOneThroughTwenty() {
        try (var factory = Validation.buildDefaultValidatorFactory()) {
            var validator = factory.getValidator();

            assertThat(validator.validate(new MulgilProperties.Vertex("generation", "embedding", 1))).isEmpty();
            assertThat(validator.validate(new MulgilProperties.Vertex("generation", "embedding", 20))).isEmpty();
            assertThat(validator.validate(new MulgilProperties.Vertex("generation", "embedding", 0))).hasSize(1);
            assertThat(validator.validate(new MulgilProperties.Vertex("generation", "embedding", 21))).hasSize(1);
        }
    }

    @Test
    void defaultsBatchSizeToFive() throws Exception {
        var environment = new MockEnvironment();
        environment.getPropertySources().addFirst(new YamlPropertySourceLoader()
                .load("application", new ClassPathResource("application.yml")).getFirst());

        assertThat(environment.getRequiredProperty("mulgil.vertex.embedding-batch-size", Integer.class)).isEqualTo(5);
    }

    @Test
    void keepsSingleQueryEmbedContract() {
        PredictionServiceClient client = mock(PredictionServiceClient.class);
        when(client.predict(anyString(), anyList(), any())).thenReturn(response(List.of(7f), 768));

        ChunkEmbeddingPort.Embedding embedding = adapter(5, client, new SimpleMeterRegistry()).embed("7");

        assertThat(embedding.values()).hasSize(768).allMatch(value -> value == 7f);
        verify(client).predict(anyString(), org.mockito.ArgumentMatchers.argThat(values -> values.size() == 1), any());
    }

    private static VertexChunkEmbeddingAdapter adapter(int batchSize, PredictionServiceClient client,
                                                       SimpleMeterRegistry metrics) {
        MulgilProperties properties = mock(MulgilProperties.class);
        when(properties.google()).thenReturn(new MulgilProperties.Google("oauth", "project", "location"));
        when(properties.vertex()).thenReturn(new MulgilProperties.Vertex("generation", "embedding", batchSize));
        return new VertexChunkEmbeddingAdapter(properties, metrics, () -> client);
    }

    private static float marker(Value instance) {
        return Float.parseFloat(instance.getStructValue().getFieldsOrThrow("content").getStringValue());
    }

    private static PredictResponse response(List<Float> markers, int dimensions) {
        PredictResponse.Builder response = PredictResponse.newBuilder();
        for (float marker : markers) {
            ListValue values = ListValue.newBuilder().addAllValues(Collections.nCopies(dimensions,
                    Value.newBuilder().setNumberValue(marker).build())).build();
            response.addPredictions(Value.newBuilder().setStructValue(Struct.newBuilder()
                    .putFields("embeddings", Value.newBuilder().setStructValue(Struct.newBuilder()
                            .putFields("values", Value.newBuilder().setListValue(values).build())).build()).build())
                    .build());
        }
        return response.build();
    }
}
