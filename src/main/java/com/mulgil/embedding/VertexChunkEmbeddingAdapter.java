package com.mulgil.embedding;

import com.google.cloud.vertexai.api.PredictionServiceClient;
import com.google.cloud.vertexai.api.PredictionServiceSettings;
import com.google.protobuf.ListValue;
import com.google.protobuf.Struct;
import com.google.protobuf.Value;
import com.mulgil.common.config.MulgilProperties;
import com.mulgil.indexing.ChunkEmbeddingPort;
import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.util.List;

@Component
@Profile("!test & !smoke")
final class VertexChunkEmbeddingAdapter implements ChunkEmbeddingPort {
    private final MulgilProperties properties;

    VertexChunkEmbeddingAdapter(MulgilProperties properties) {
        this.properties = properties;
    }

    @Override
    public Embedding embed(String text) {
        String location = properties.google().cloudLocation();
        String model = properties.vertex().embeddingModel();
        String endpoint = "projects/%s/locations/%s/publishers/google/models/%s".formatted(
                properties.google().cloudProject(), location, model);
        Value instance = Value.newBuilder().setStructValue(Struct.newBuilder()
                .putFields("content", Value.newBuilder().setStringValue(text).build())
                .putFields("task_type", Value.newBuilder().setStringValue("RETRIEVAL_DOCUMENT").build())
                .build()).build();
        Value parameters = Value.newBuilder().setStructValue(Struct.newBuilder()
                .putFields("outputDimensionality", Value.newBuilder().setNumberValue(768).build()).build()).build();
        try (PredictionServiceClient client = PredictionServiceClient.create(PredictionServiceSettings.newBuilder()
                .setEndpoint(location + "-aiplatform.googleapis.com:443").build())) {
            Value prediction = client.predict(endpoint, List.of(instance), parameters).getPredictions(0);
            ListValue values = prediction.getStructValue().getFieldsOrThrow("embeddings")
                    .getStructValue().getFieldsOrThrow("values").getListValue();
            return new Embedding(values.getValuesList().stream()
                    .map(value -> (float) value.getNumberValue()).toList(), model);
        } catch (IOException exception) {
            throw new IllegalStateException("Could not create Vertex embedding client.", exception);
        }
    }
}
