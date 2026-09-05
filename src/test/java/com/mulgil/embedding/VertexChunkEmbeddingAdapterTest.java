package com.mulgil.embedding;

import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import com.google.api.gax.rpc.ApiException;
import com.google.api.gax.rpc.InvalidArgumentException;
import com.google.api.gax.rpc.StatusCode;
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
import org.springframework.boot.env.YamlPropertySourceLoader;
import org.springframework.core.io.ClassPathResource;
import org.springframework.mock.env.MockEnvironment;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.function.Supplier;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class VertexChunkEmbeddingAdapterTest {
    @Test
    void usesUsCentral1EmbeddingEndpointWhenCloudLocationIsGlobal() {
        PredictionServiceClient client = mock(PredictionServiceClient.class);
        List<String> endpoints = new ArrayList<>();
        when(client.predict(anyString(), anyList(), any())).thenAnswer(invocation -> {
            endpoints.add(invocation.getArgument(0));
            return response(List.of(1f), 768);
        });
        MulgilProperties properties = mock(MulgilProperties.class);
        when(properties.google()).thenReturn(new MulgilProperties.Google("oauth", "project", "global"));
        when(properties.vertex()).thenReturn(new MulgilProperties.Vertex(
                "generation", "text-multilingual-embedding-002", "us-central1", 5));
        VertexChunkEmbeddingAdapter adapter = new VertexChunkEmbeddingAdapter(
                properties, new SimpleMeterRegistry(), () -> client);

        adapter.embed("1");

        assertThat(endpoints).containsExactly(
                "projects/project/locations/us-central1/publishers/google/models/text-multilingual-embedding-002");
    }

    @Test
    void usesGlobalApiHostForGlobalEmbeddingLocation() {
        assertThat(VertexChunkEmbeddingAdapter.apiEndpoint("global"))
                .isEqualTo("aiplatform.googleapis.com:443");
    }

    @Test
    void capsRequestsAtFive_mapsVertexRequest_andPreservesOrderWithConfigTwenty() {
        PredictionServiceClient client = mock(PredictionServiceClient.class);
        List<String> endpoints = new ArrayList<>();
        List<Integer> batchSizes = new ArrayList<>();
        List<Value> parameters = new ArrayList<>();
        List<Value> allInstances = new ArrayList<>();
        when(client.predict(anyString(), anyList(), any())).thenAnswer(invocation -> {
            endpoints.add(invocation.getArgument(0));
            List<Value> instances = invocation.getArgument(1);
            batchSizes.add(instances.size());
            allInstances.addAll(instances);
            parameters.add(invocation.getArgument(2));
            return response(instances.stream().map(VertexChunkEmbeddingAdapterTest::marker).toList(), 768);
        });
        VertexChunkEmbeddingAdapter adapter = adapter(20, client, new SimpleMeterRegistry());

        List<ChunkEmbeddingPort.Embedding> embeddings = adapter.embedAll(
                java.util.stream.IntStream.range(0, 12).mapToObj(String::valueOf).toList());

        assertThat(batchSizes).containsExactly(5, 5, 2);
        assertThat(endpoints).allMatch(endpoint -> endpoint.equals(
                "projects/project/locations/location/publishers/google/models/text-multilingual-embedding-002"));
        assertThat(allInstances).allMatch(instance -> instance.getStructValue().getFieldsOrThrow("task_type")
                .getStringValue().equals("RETRIEVAL_DOCUMENT"));
        assertThat(parameters).allMatch(parameter -> parameter.getStructValue()
                .getFieldsOrThrow("outputDimensionality").getNumberValue() == 768d);
        assertThat(embeddings).extracting(embedding -> embedding.values().getFirst())
                .containsExactlyElementsOf(java.util.stream.IntStream.range(0, 12)
                        .mapToObj(value -> (float) value).toList());
        verify(client).close();
    }

    @Test
    void observesOnePhysicalRequestForFiveTextsWithOnlyStableTags() {
        PredictionServiceClient client = mock(PredictionServiceClient.class);
        when(client.predict(anyString(), anyList(), any())).thenReturn(response(List.of(0f, 1f, 2f, 3f, 4f), 768));
        SimpleMeterRegistry metrics = new SimpleMeterRegistry();

        adapter(5, client, metrics).embedAll(List.of("0", "1", "2", "3", "4"));

        var timer = metrics.get("mulgil.embedding.provider.request")
                .tags("provider", "vertex", "model", "text-multilingual-embedding-002",
                        "batchSize", "5", "code", "OK").timer();
        assertThat(timer.count()).isEqualTo(1L);
        assertThat(timer.getId().getTags()).extracting(io.micrometer.core.instrument.Tag::getKey)
                .containsExactlyInAnyOrder("provider", "model", "batchSize", "code");
    }

    @Test
    void checkpointsSuccessfulSingletonBeforeLaterFallbackFailure_andDoesNotAttemptRemainingInput() {
        PredictionServiceClient client = mock(PredictionServiceClient.class);
        InvalidArgumentException batchFailure = mock(InvalidArgumentException.class);
        ApiException singletonFailure = gax(StatusCode.Code.UNAVAILABLE, true);
        when(client.predict(anyString(), anyList(), any()))
                .thenThrow(batchFailure)
                .thenReturn(response(List.of(0f), 768))
                .thenThrow(singletonFailure);
        List<String> usage = new ArrayList<>();
        List<Float> checkpointed = new ArrayList<>();
        ChunkEmbeddingPort.ProviderCallObserver observer = new ChunkEmbeddingPort.ProviderCallObserver() {
            @Override
            public List<ChunkEmbeddingPort.Embedding> observe(int startIndex, List<String> texts,
                                                               Supplier<List<ChunkEmbeddingPort.Embedding>> call) {
                usage.add("started:" + startIndex + ":" + texts.size());
                try {
                    List<ChunkEmbeddingPort.Embedding> result = call.get();
                    usage.add("succeeded:" + startIndex + ":" + texts.size());
                    return result;
                } catch (RuntimeException exception) {
                    usage.add("failed:" + startIndex + ":" + texts.size());
                    throw exception;
                }
            }

            @Override
            public void checkpoint(int startIndex, List<ChunkEmbeddingPort.Embedding> embeddings) {
                checkpointed.add(embeddings.getFirst().values().getFirst());
            }
        };

        assertThatThrownBy(() -> adapter(5, client, new SimpleMeterRegistry())
                .embedAll(List.of("0", "1", "2"), observer))
                .isInstanceOfSatisfying(EmbeddingProviderException.class, exception -> {
                    assertThat(exception.code()).isEqualTo("PROVIDER_UNAVAILABLE");
                    assertThat(exception.retryable()).isTrue();
                });

        assertThat(usage).containsExactly("started:0:3", "failed:0:3",
                "started:0:1", "succeeded:0:1", "started:1:1", "failed:1:1");
        assertThat(checkpointed).containsExactly(0f);
        verify(client, times(3)).predict(anyString(), anyList(), any());
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
    void mapsGaxStatusesToSafeRetryTaxonomy() {
        assertFailure(StatusCode.Code.DEADLINE_EXCEEDED, false, "PROVIDER_TIMEOUT", true);
        assertFailure(StatusCode.Code.RESOURCE_EXHAUSTED, false, "PROVIDER_RATE_LIMIT", true);
        assertFailure(StatusCode.Code.UNAVAILABLE, false, "PROVIDER_UNAVAILABLE", true);
        assertFailure(StatusCode.Code.INTERNAL, false, "PROVIDER_UNAVAILABLE", true);
        assertFailure(StatusCode.Code.ABORTED, false, "PROVIDER_UNAVAILABLE", true);
        assertFailure(StatusCode.Code.UNKNOWN, true, "PROVIDER_UNAVAILABLE", true);
        assertFailure(StatusCode.Code.INVALID_ARGUMENT, false, "PROVIDER_FAILED", false);
        assertFailure(StatusCode.Code.PERMISSION_DENIED, false, "PROVIDER_FAILED", false);
    }

    @Test
    void mapsClientCreationIoToRetryableUnavailable() {
        MulgilProperties properties = mock(MulgilProperties.class);
        when(properties.google()).thenReturn(new MulgilProperties.Google("oauth", "project", "location"));
        when(properties.vertex()).thenReturn(new MulgilProperties.Vertex(
                "generation", "text-multilingual-embedding-002", "location", 5));
        VertexChunkEmbeddingAdapter adapter = new VertexChunkEmbeddingAdapter(
                properties, new SimpleMeterRegistry(), () -> { throw new IOException("credential=secret"); });

        assertThatThrownBy(() -> adapter.embed("text"))
                .isInstanceOfSatisfying(EmbeddingProviderException.class, exception -> {
                    assertThat(exception.code()).isEqualTo("PROVIDER_UNAVAILABLE");
                    assertThat(exception.retryable()).isTrue();
                    assertThat(exception.getMessage()).doesNotContain("credential=secret");
                });
    }

    @Test
    void fallsBackSequentiallyOnlyAfterMultiInstanceProviderFailure_andRedactsReason() {
        PredictionServiceClient client = mock(PredictionServiceClient.class);
        InvalidArgumentException providerFailure = mock(InvalidArgumentException.class);
        when(providerFailure.getMessage()).thenReturn("credential=secret");
        when(client.predict(anyString(), anyList(), any()))
                .thenThrow(providerFailure)
                .thenReturn(response(List.of(3f), 768))
                .thenReturn(response(List.of(4f), 768));
        SimpleMeterRegistry metrics = new SimpleMeterRegistry();
        Logger logger = (Logger) LoggerFactory.getLogger(VertexChunkEmbeddingAdapter.class);
        ListAppender<ILoggingEvent> appender = new ListAppender<>();
        appender.start();
        logger.addAppender(appender);

        try {
            List<ChunkEmbeddingPort.Embedding> embeddings =
                    adapter(5, client, metrics).embedAll(List.of("3", "4"));

            assertThat(embeddings).extracting(embedding -> embedding.values().getFirst()).containsExactly(3f, 4f);
            verify(client, times(3)).predict(anyString(), anyList(), any());
            assertThat(metrics.counter("mulgil.embedding.batch.fallback", "reason", "multi_instance_failed").count())
                    .isEqualTo(1d);
            assertThat(appender.list).singleElement().satisfies(event -> {
                assertThat(event.getFormattedMessage())
                        .isEqualTo("Vertex embedding batch fell back to sequential calls")
                        .doesNotContain("credential=secret");
                assertThat(event.getKeyValuePairs()).extracting(pair -> pair.key, pair -> pair.value)
                        .containsExactly(
                                org.assertj.core.groups.Tuple.tuple("event", "embedding.batch.fallback"),
                                org.assertj.core.groups.Tuple.tuple("reason", "multi_instance_failed"),
                                org.assertj.core.groups.Tuple.tuple("batchSize", 2));
                assertThat(event.getKeyValuePairs()).extracting(pair -> String.valueOf(pair.value))
                        .noneMatch(value -> value.contains("credential=secret"));
                assertThat(event.getThrowableProxy()).isNull();
            });
        } finally {
            logger.detachAppender(appender);
            appender.stop();
        }
    }

    @Test
    void doesNotFallbackForSingleInstanceFailure() {
        PredictionServiceClient client = mock(PredictionServiceClient.class);
        InvalidArgumentException providerFailure = mock(InvalidArgumentException.class);
        StatusCode status = mock(StatusCode.class);
        when(status.getCode()).thenReturn(StatusCode.Code.INVALID_ARGUMENT);
        when(providerFailure.getStatusCode()).thenReturn(status);
        when(client.predict(anyString(), anyList(), any())).thenThrow(providerFailure);
        SimpleMeterRegistry metrics = new SimpleMeterRegistry();

        assertThatThrownBy(() -> adapter(5, client, metrics).embed("1"))
                .isInstanceOfSatisfying(EmbeddingProviderException.class, exception -> {
                    assertThat(exception.code()).isEqualTo("PROVIDER_FAILED");
                    assertThat(exception.retryable()).isFalse();
                });

        verify(client).predict(anyString(), anyList(), any());
        assertThat(metrics.find("mulgil.embedding.batch.fallback").counter()).isNull();
    }

    @Test
    void validatesBatchSizeFromOneThroughTwenty() {
        try (var factory = Validation.buildDefaultValidatorFactory()) {
            var validator = factory.getValidator();

            assertThat(validator.validate(new MulgilProperties.Vertex("generation", "embedding", "location", 1))).isEmpty();
            assertThat(validator.validate(new MulgilProperties.Vertex("generation", "embedding", "location", 20))).isEmpty();
            assertThat(validator.validate(new MulgilProperties.Vertex("generation", "embedding", "location", 0))).hasSize(1);
            assertThat(validator.validate(new MulgilProperties.Vertex("generation", "embedding", "location", 21))).hasSize(1);
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
    void defaultsEmbeddingLocationToUsCentral1() throws Exception {
        var environment = new MockEnvironment();
        environment.getPropertySources().addFirst(new YamlPropertySourceLoader()
                .load("application", new ClassPathResource("application.yml")).getFirst());

        assertThat(environment.getRequiredProperty("mulgil.vertex.embedding-location"))
                .isEqualTo("us-central1");
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
        when(properties.vertex()).thenReturn(new MulgilProperties.Vertex(
                "generation", "text-multilingual-embedding-002", "location", batchSize));
        return new VertexChunkEmbeddingAdapter(properties, metrics, () -> client);
    }

    private static void assertFailure(StatusCode.Code status, boolean retryable,
                                      String expectedCode, boolean expectedRetryable) {
        PredictionServiceClient client = mock(PredictionServiceClient.class);
        ApiException failure = gax(status, retryable);
        when(client.predict(anyString(), anyList(), any())).thenThrow(failure);

        assertThatThrownBy(() -> adapter(5, client, new SimpleMeterRegistry()).embed("1"))
                .isInstanceOfSatisfying(EmbeddingProviderException.class, exception -> {
                    assertThat(exception.code()).isEqualTo(expectedCode);
                    assertThat(exception.retryable()).isEqualTo(expectedRetryable);
                });
    }

    private static ApiException gax(StatusCode.Code code, boolean retryable) {
        ApiException exception = mock(ApiException.class);
        StatusCode status = mock(StatusCode.class);
        when(status.getCode()).thenReturn(code);
        when(exception.getStatusCode()).thenReturn(status);
        when(exception.isRetryable()).thenReturn(retryable);
        return exception;
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
