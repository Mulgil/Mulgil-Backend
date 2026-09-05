package com.mulgil.embedding;

import com.google.api.gax.rpc.ApiException;
import com.google.api.gax.rpc.InvalidArgumentException;
import com.google.cloud.vertexai.api.PredictResponse;
import com.google.cloud.vertexai.api.PredictionServiceClient;
import com.google.cloud.vertexai.api.PredictionServiceSettings;
import com.google.protobuf.ListValue;
import com.google.protobuf.Struct;
import com.google.protobuf.Value;
import com.mulgil.common.config.MulgilProperties;
import com.mulgil.indexing.ChunkEmbeddingPort;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Timer;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.util.ArrayList;
import java.util.List;

@Component
@Profile("!test & !smoke")
final class VertexChunkEmbeddingAdapter implements ChunkEmbeddingPort {
    private static final int PROVIDER_MAX_BATCH_SIZE = 5;
    private static final Logger log = LoggerFactory.getLogger(VertexChunkEmbeddingAdapter.class);
    private static final String FALLBACK_REASON = "multi_instance_failed";

    private final MulgilProperties properties;
    private final MeterRegistry metrics;
    private final ClientFactory clients;

    @Autowired
    VertexChunkEmbeddingAdapter(MulgilProperties properties, MeterRegistry metrics) {
        this(properties, metrics, () -> PredictionServiceClient.create(PredictionServiceSettings.newBuilder()
                .setEndpoint(apiEndpoint(properties.vertex().embeddingLocation())).build()));
    }

    VertexChunkEmbeddingAdapter(MulgilProperties properties, MeterRegistry metrics, ClientFactory clients) {
        this.properties = properties;
        this.metrics = metrics;
        this.clients = clients;
    }

    @Override
    public Embedding embed(String text) {
        return embedAll(List.of(text)).getFirst();
    }

    @Override
    public List<Embedding> embedAll(List<String> texts, ProviderCallObserver observer) {
        if (texts.isEmpty()) return List.of();
        String location = properties.vertex().embeddingLocation();
        String model = properties.vertex().embeddingModel();
        String endpoint = "projects/%s/locations/%s/publishers/google/models/%s".formatted(
                properties.google().cloudProject(), location, model);
        Value parameters = Value.newBuilder().setStructValue(Struct.newBuilder()
                .putFields("outputDimensionality", Value.newBuilder().setNumberValue(768).build()).build()).build();
        List<Embedding> results = new ArrayList<>(texts.size());
        try (PredictionServiceClient client = clients.create()) {
            int batchSize = Math.min(properties.vertex().embeddingBatchSize(), PROVIDER_MAX_BATCH_SIZE);
            CallSequence calls = new CallSequence(observer);
            for (int start = 0; start < texts.size(); start += batchSize) {
                List<String> batch = texts.subList(start, Math.min(start + batchSize, texts.size()));
                results.addAll(predict(client, endpoint, parameters, model, start, batch, calls));
            }
        } catch (IOException exception) {
            throw new EmbeddingProviderException("PROVIDER_UNAVAILABLE",
                    "Could not create Vertex embedding client.", true);
        }
        return List.copyOf(results);
    }

    private List<Embedding> predict(PredictionServiceClient client, String endpoint, Value parameters,
                                    String model, int startIndex, List<String> texts, CallSequence calls) {
        List<Value> instances = texts.stream().map(VertexChunkEmbeddingAdapter::instance).toList();
        try {
            return calls.call(startIndex, texts,
                    () -> embeddings(observePredict(client, endpoint, instances, parameters, model), texts.size(), model));
        } catch (InvalidArgumentException exception) {
            if (texts.size() == 1) throw EmbeddingProviderException.from(exception);
            metrics.counter("mulgil.embedding.batch.fallback", "reason", FALLBACK_REASON).increment();
            log.atWarn().addKeyValue("event", "embedding.batch.fallback")
                    .addKeyValue("reason", FALLBACK_REASON).addKeyValue("batchSize", texts.size())
                    .log("Vertex embedding batch fell back to sequential calls");
            List<Embedding> fallback = new ArrayList<>(texts.size());
            for (int index = 0; index < instances.size(); index++) {
                Value instance = instances.get(index);
                try {
                    fallback.addAll(calls.call(startIndex + index, List.of(texts.get(index)),
                            () -> embeddings(observePredict(
                                    client, endpoint, List.of(instance), parameters, model), 1, model)));
                } catch (ApiException fallbackFailure) {
                    throw EmbeddingProviderException.from(fallbackFailure);
                }
            }
            return List.copyOf(fallback);
        } catch (ApiException exception) {
            throw EmbeddingProviderException.from(exception);
        }
    }

    private PredictResponse observePredict(PredictionServiceClient client, String endpoint, List<Value> instances,
                                            Value parameters, String model) {
        Timer.Sample sample = Timer.start(metrics);
        String code = "OK";
        try {
            return client.predict(endpoint, instances, parameters);
        } catch (ApiException exception) {
            code = exception.getStatusCode() == null
                    ? "PROVIDER_FAILED" : EmbeddingProviderException.from(exception).code();
            throw exception;
        } finally {
            sample.stop(metrics.timer("mulgil.embedding.provider.request",
                    "provider", "vertex", "model", model,
                    "batchSize", Integer.toString(instances.size()), "code", code));
        }
    }

    private static Value instance(String text) {
        return Value.newBuilder().setStructValue(Struct.newBuilder()
                .putFields("content", Value.newBuilder().setStringValue(text).build())
                .putFields("task_type", Value.newBuilder().setStringValue("RETRIEVAL_DOCUMENT").build())
                .build()).build();
    }

    static String apiEndpoint(String location) {
        return ("global".equals(location) ? "" : location + "-") + "aiplatform.googleapis.com:443";
    }

    private static List<Embedding> embeddings(PredictResponse response, int expectedCount, String model) {
        if (response.getPredictionsCount() != expectedCount) {
            throw new IllegalStateException("Vertex embedding count mismatch: expected %d but received %d."
                    .formatted(expectedCount, response.getPredictionsCount()));
        }
        List<Embedding> embeddings = new ArrayList<>(expectedCount);
        for (int index = 0; index < expectedCount; index++) {
            ListValue values = response.getPredictions(index).getStructValue().getFieldsOrThrow("embeddings")
                    .getStructValue().getFieldsOrThrow("values").getListValue();
            if (values.getValuesCount() != 768) {
                throw new IllegalStateException("Vertex embedding at index %d must have 768 values.".formatted(index));
            }
            embeddings.add(new Embedding(values.getValuesList().stream()
                    .map(value -> (float) value.getNumberValue()).toList(), model));
        }
        return embeddings;
    }

    @FunctionalInterface
    interface ClientFactory {
        PredictionServiceClient create() throws IOException;
    }

    private static final class CallSequence {
        private final ProviderCallObserver observer;
        private int pendingIndex;
        private List<Embedding> pending = List.of();

        private CallSequence(ProviderCallObserver observer) {
            this.observer = observer;
        }

        private List<Embedding> call(int startIndex, List<String> texts,
                                     java.util.function.Supplier<List<Embedding>> providerCall) {
            if (!pending.isEmpty()) {
                observer.checkpoint(pendingIndex, pending);
                pending = List.of();
            }
            List<Embedding> embeddings = observer.observe(startIndex, texts, providerCall);
            pendingIndex = startIndex;
            pending = embeddings;
            return embeddings;
        }
    }
}
